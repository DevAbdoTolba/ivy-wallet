package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject

class SmsMessageMapper @Inject constructor() {
    fun SmsRow.toDomain(): SmsMessage {
        // Single normalize chokepoint — every downstream PARSING stage (Drain
        // tokenize, alignment, amount/date regexes) sees the canonical body,
        // so invisible bidi marks and Arabic-Indic digits don't fragment
        // clusters or produce false wildcards.
        //
        // The dedup key deliberately hashes the RAW body: identity must never
        // change when normalization rules evolve, or every key persisted by
        // earlier builds (transaction smsSourceDedupKey, the pending_review_item
        // unique index) stops matching and re-reads duplicate everything.
        // Bumping NORMALIZER_VERSION re-normalizes persisted TEXT
        // (RenormalizePersistedSmsDataUseCase) but keys stay stable.
        val bodyHash = sha256Hex(body)
        return SmsMessage(
            dedupKey = dedupKey(address, dateEpochMillis, bodyHash),
            senderId = address,
            body = SmsBodyNormalizer.normalize(body),
            timestamp = Instant.ofEpochMilli(dateEpochMillis),
        )
    }

    private fun dedupKey(address: String, date: Long, bodyHash: String): String =
        sha256Hex("$address:$date:$bodyHash")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
