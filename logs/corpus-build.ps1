param(
    [Parameter(Mandatory = $true)]
    [string]$DumpFile
)

$ErrorActionPreference = 'Stop'

# UTF-8 throughout — bodies are Arabic-heavy; any other code page would
# silently mojibake the corpus.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8

# Tree-descent prefix depth for cluster signatures. MUST match
# DrainParser.PREFIX_DEPTH (feature/sms-sync/src/main/java/com/ivy/sms/data/
# DrainParserState.kt) or the offline auto-clusters stop approximating
# production Drain clusters. Bumped 3 -> 5 (2026-05-14) alongside the
# production change. Written into the corpus header as `prefixDepth` so a
# stale corpus is detectable on the next rebuild.
$PrefixDepth = 5

if (-not (Test-Path $DumpFile)) {
    Write-Error "Dump file not found: $DumpFile"
    exit 1
}

$repoRoot   = Split-Path -Parent $PSScriptRoot
$resources  = Join-Path $repoRoot "feature\sms-sync\src\test\resources"
$outFile    = Join-Path $resources "sms-corpus.json"
$gitIgnore  = Join-Path $resources ".gitignore"

if (-not (Test-Path $resources)) {
    New-Item -ItemType Directory -Path $resources -Force | Out-Null
}
if (-not (Test-Path $gitIgnore)) {
    "sms-corpus.json" | Out-File -FilePath $gitIgnore -Encoding utf8
}

Write-Host "Reading dump:  $DumpFile"
Write-Host "Writing corpus: $outFile"
Write-Host ""

# Depth guard: a corpus generated at a different PREFIX_DEPTH has cluster
# ids/signatures that no longer line up with what this run produces, and the
# rebuild below OVERWRITES the file. Warn loudly before clobbering it.
if (Test-Path $outFile) {
    $existingDepth = $null
    if ((Get-Content -LiteralPath $outFile -Raw -Encoding utf8) -match '"prefixDepth":\s*(\d+)') {
        $existingDepth = [int]$matches[1]
    }
    if ($existingDepth -ne $PrefixDepth) {
        $depthLabel = if ($null -eq $existingDepth) { 'UNKNOWN (no prefixDepth field; predates the depth-5 bump)' } else { "$existingDepth" }
        Write-Host "!!! WARNING: existing sms-corpus.json was generated at prefix depth $depthLabel" -ForegroundColor Red
        Write-Host "!!! while this script clusters at depth $PrefixDepth - cluster ids/signatures will shift," -ForegroundColor Red
        Write-Host "!!! and regenerating OVERWRITES every annotation in the old file." -ForegroundColor Red
        Write-Host "!!! Copy it aside now if you need to port annotations forward." -ForegroundColor Red
        Write-Host ""
    }
}

# ----- 1. Parse rows out of the dump file. -----
# Each record is `Row: <id> address=<sender>, date=<ms>, body=<text>`. Bodies
# can contain commas and (rarely) newlines; we split on the start of every
# Row: line, then the body is everything after `body=` to the next chunk.
$content   = Get-Content -LiteralPath $DumpFile -Raw -Encoding utf8
$rowChunks = $content -split '(?m)(?=^Row: \d+ address=)'

$records = New-Object System.Collections.Generic.List[Hashtable]
foreach ($chunk in $rowChunks) {
    if ($chunk -notmatch '^Row: ') { continue }
    if ($chunk -match '(?s)^Row: (\d+) address=(.+?), date=(\d+), body=(.*)$') {
        $records.Add(@{
            rowId     = [int]$matches[1]
            sender    = $matches[2].Trim()
            timestamp = [long]$matches[3]
            body      = $matches[4].TrimEnd()
        })
    }
}
Write-Host "Parsed $($records.Count) records."

# ----- 2. Cluster signature: sender | tokenCount | first-$PrefixDepth-stable-tokens. -----
# Mirrors what DrainParser does for descent (digit-collapse for clustering
# only) so a corpus cluster ~ a Drain cluster.
function Get-ClusterSignature($body, $sender) {
    # Light NFKC + bidi/zero-width strip so signatures don't fork on invisible
    # characters. Mirrors SmsBodyNormalizer's behaviour at a glance. Use the
    # String.Normalize() instance method — PowerShell 5.1 does not surface
    # the static `System.Text.Normalizer` type, but every .NET String exposes
    # `Normalize(NormalizationForm)`.
    $normalized = $body.Normalize([System.Text.NormalizationForm]::FormKC)
    # Combined pass: drop bidi / zero-width marks AND fold Arabic-Indic
    # digits to ASCII. Pure ASCII source — no Unicode literals embedded —
    # so PS 5.1's Windows-1252-default file decoding can't mangle it.
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $normalized.ToCharArray()) {
        $code = [int][char]$ch
        # Bidi / zero-width / BOM: skip entirely.
        if (($code -ge 0x200B -and $code -le 0x200F) -or
            ($code -ge 0x202A -and $code -le 0x202E) -or
            ($code -ge 0x2066 -and $code -le 0x2069) -or
            ($code -eq 0xFEFF)) {
            continue
        }
        # Arabic-Indic and Eastern Arabic-Indic digits → ASCII 0-9.
        if ($code -ge 0x0660 -and $code -le 0x0669) {
            [void]$sb.Append([char](48 + ($code - 0x0660)))
        } elseif ($code -ge 0x06F0 -and $code -le 0x06F9) {
            [void]$sb.Append([char](48 + ($code - 0x06F0)))
        } else {
            [void]$sb.Append($ch)
        }
    }
    $normalized = $sb.ToString() -replace '\s+', ' '
    $normalized = $normalized.Trim()

    $tokens = $normalized -split '\s+' | Where-Object { $_.Length -gt 0 }
    if ($tokens.Count -eq 0) {
        return @{ key = "$sender|0|"; tokens = $tokens }
    }
    # First $PrefixDepth stable tokens; keep $PrefixDepth in sync with
    # DrainParser.PREFIX_DEPTH so the corpus auto-cluster mirrors how
    # production Drain would group these bodies. Bumped 3 -> 5 (2026-05-14)
    # alongside the production change to stop sibling SMS structures from
    # collapsing into one signature.
    $stable = $tokens | Where-Object { $_ -notmatch '\d' } | Select-Object -First $PrefixDepth
    return @{
        key    = "$sender|$($tokens.Count)|" + ($stable -join '|')
        tokens = $tokens
    }
}

$bySig = @{}
foreach ($r in $records) {
    $sig = Get-ClusterSignature -body $r.body -sender $r.sender
    $key = $sig.key
    if (-not $bySig.ContainsKey($key)) {
        $bySig[$key] = New-Object System.Collections.Generic.List[Hashtable]
    }
    $bySig[$key].Add($r)
}

Write-Host "Auto-clusters: $($bySig.Count)"

# ----- 3. Build the corpus JSON. -----
$clusters = New-Object System.Collections.Generic.List[Hashtable]
$clusterIdx = 0
$senderTallies = @{}
foreach ($key in $bySig.Keys | Sort-Object) {
    $clusterIdx += 1
    $msgs = $bySig[$key]
    $sender = $msgs[0].sender
    if (-not $senderTallies.ContainsKey($sender)) { $senderTallies[$sender] = 0 }
    $senderTallies[$sender] += 1
    $clusterId = "$($sender.ToLowerInvariant() -replace '[^a-z0-9]', '-')-$($senderTallies[$sender])"

    $messages = $msgs | ForEach-Object {
        @{
            rowId     = $_.rowId
            timestamp = $_.timestamp
            body      = $_.body
        }
    }

    $clusters.Add([ordered]@{
        id         = $clusterId
        sender     = $sender
        signature  = $key
        tokenCount = ($key -split '\|')[1]
        sampleSize = $msgs.Count
        annotation = $null
        messages   = @($messages)
    })
}

$nowIso = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$corpus = [ordered]@{
    version        = 1
    prefixDepth    = $PrefixDepth
    generatedAt    = $nowIso
    sourceDump     = (Resolve-Path $DumpFile).Path
    totalMessages  = $records.Count
    clusterCount   = $clusters.Count
    clusters       = @($clusters)
}

# ConvertTo-Json on PS 5.1 \u-escapes non-ASCII; PS 7 keeps literal UTF-8.
# Either form is valid JSON and both deserialize fine in kotlinx.serialization,
# so accept whatever the host PS gives us.
$json = $corpus | ConvertTo-Json -Depth 8 -Compress:$false
$json | Out-File -FilePath $outFile -Encoding utf8

Write-Host ""
Write-Host "Done."
Write-Host "Corpus:        $outFile"
Write-Host "Total records: $($records.Count)"
Write-Host "Cluster count: $($clusters.Count)"
Write-Host ""
Write-Host "Top clusters by message count:"
$clusters | Sort-Object { -$_.sampleSize } | Select-Object -First 10 | ForEach-Object {
    Write-Host ("  {0,3}  {1,-30}  {2}" -f $_.sampleSize, $_.id, ($_.signature -replace '^[^|]+\|\d+\|', ''))
}
