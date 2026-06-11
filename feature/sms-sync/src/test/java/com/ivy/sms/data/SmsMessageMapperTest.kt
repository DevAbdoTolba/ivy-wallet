package com.ivy.sms.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.security.MessageDigest

class SmsMessageMapperTest {

    private val mapper = SmsMessageMapper()

    // Body with the exact junk the normalizer exists for: an LRM bidi mark
    // glued to "Total" and an NBSP between amount and currency.
    private val rawBody = "Total‎ 1,500 EGP"

    private fun row(body: String, date: Long = 1_000L) = SmsRow(
        id = 1L,
        address = "Bank-A",
        dateEpochMillis = date,
        body = body,
    )

    @Test
    fun `dedupKey hashes the RAW body - normalization does not leak into identity`() {
        val msg = with(mapper) { row(rawBody).toDomain() }

        // sha256(address:date:sha256(RAW body)) — the formula every key
        // already persisted on-device (pending unique index, transaction
        // smsSourceDedupKey) was computed with.
        msg.dedupKey shouldBe rawKey("Bank-A", 1_000L, rawBody)
    }

    @Test
    fun `body is normalized while the key stays raw`() {
        val msg = with(mapper) { row(rawBody).toDomain() }

        msg.body shouldBe "Total 1,500 EGP"
        msg.dedupKey shouldNotBe rawKey("Bank-A", 1_000L, msg.body)
    }

    @Test
    fun `raw and pre-normalized variants of the same text get different keys`() {
        // If normalization leaked into identity these two would collide —
        // they must not: identity is the raw bytes.
        val cleanBody = SmsBodyNormalizer.normalize(rawBody)
        val rawVariantKey = with(mapper) { row(rawBody).toDomain() }.dedupKey
        val cleanVariantKey = with(mapper) { row(cleanBody).toDomain() }.dedupKey

        rawVariantKey shouldNotBe cleanVariantKey
    }

    @Test
    fun `senderId and timestamp pass through unchanged`() {
        val msg = with(mapper) { row(rawBody, date = 42_000L).toDomain() }

        msg.senderId shouldBe "Bank-A"
        msg.timestamp.toEpochMilli() shouldBe 42_000L
    }

    private fun rawKey(address: String, date: Long, body: String): String =
        sha256Hex("$address:$date:${sha256Hex(body)}")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
