param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Senders
)

$ErrorActionPreference = 'Stop'

# adb prints UTF-8 bytes; without these overrides PowerShell decodes them
# through the console's OEM code page (CP437/850 on Windows), turning every
# Arabic character into garbage like `╪¬┘à`. Force UTF-8 in/out for the rest
# of this session so the captured stream stays accurate end-to-end.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$OutputEncoding           = [System.Text.Encoding]::UTF8

$stamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$out = Join-Path $PSScriptRoot "${stamp}_sms-dump.txt"

# Header so the file documents what was requested.
"# SMS inbox dump @ $stamp"                   | Out-File -FilePath $out -Encoding utf8
"# Senders requested: $($Senders -join ', ')" | Add-Content -Path $out -Encoding utf8

Write-Host "Pulling full inbox once, then filtering by sender on the host."
Write-Host "Senders: $($Senders -join ', ')"
Write-Host "Writing to: $out"
Write-Host ""

# Pulling on-device with --where ran into Android's content-cmd quoting:
# senders with spaces broke the WHERE tokenisation, and senders without
# spaces only worked with brittle backslash escapes that produce a SQL
# literal-error on some Android builds. Filter host-side instead — only
# requested senders are written to disk; unrequested rows exist briefly in
# PowerShell memory while parsing and are never persisted.
Write-Host "  fetching ..." -ForegroundColor DarkGray
$allRows = & adb shell content query `
    --uri content://sms/inbox `
    --projection address:date:body 2>&1

if ($LASTEXITCODE -ne 0) {
    "! adb exit ${LASTEXITCODE}: $allRows" | Add-Content -Path $out -Encoding utf8
    Write-Host "  ! adb error (see file)" -ForegroundColor Red
    exit 1
}

# Each match line looks like:
#   Row: 0 address=VF-Cash, date=1715000000000, body=...
# So we anchor on `address=<sender>,` (the comma terminates the address
# field). regex-escape the sender to be safe with hyphens, dots, etc.
foreach ($sender in $Senders) {
    Write-Host "  > $sender" -ForegroundColor Cyan
    "" | Add-Content -Path $out -Encoding utf8
    "=== SENDER: $sender ===" | Add-Content -Path $out -Encoding utf8

    $escaped = [regex]::Escape($sender)
    $matched = $allRows | Where-Object { $_ -match "address=$escaped," }

    if (-not $matched -or $matched.Count -eq 0) {
        "(no rows)" | Add-Content -Path $out -Encoding utf8
        Write-Host "    (no rows)" -ForegroundColor Yellow
    } else {
        $matched | Add-Content -Path $out -Encoding utf8
        Write-Host "    $($matched.Count) rows" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Done."
Write-Host "File: $out"
