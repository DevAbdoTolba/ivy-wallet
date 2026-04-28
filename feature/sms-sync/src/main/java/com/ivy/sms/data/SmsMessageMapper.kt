package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject

class SmsMessageMapper @Inject constructor() {
    fun SmsRow.toDomain(): SmsMessage {
        val raw = "$address:$dateEpochMillis:$body"
        val bodyHash = sha256Hex(body)
        return SmsMessage(
            dedupKey = dedupKey(address, dateEpochMillis, bodyHash),
            senderId = address,
            body = body,
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
