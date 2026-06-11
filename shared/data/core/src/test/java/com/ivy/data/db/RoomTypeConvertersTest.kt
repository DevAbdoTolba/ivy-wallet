package com.ivy.data.db

import com.ivy.domain.db.RoomTypeConverters
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.UUID

class RoomTypeConvertersTest {
    private val converters = RoomTypeConverters()

    @Test
    fun `parse uuid - dashed input`() {
        // given
        val id = UUID.randomUUID()

        // when
        val parsed = converters.parseUUID(id.toString())

        // then
        parsed shouldBe id
    }

    @Test
    fun `parse uuid - dash-less 32-char hex input is repaired`() {
        // given: the format the original Migration130to131 seeded —
        // lower(hex(randomblob(16)))
        val dashless = "0123456789abcdef0123456789abcdef"

        // when
        val parsed = converters.parseUUID(dashless)

        // then
        parsed shouldBe UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")
    }

    @Test
    fun `parse uuid - null input`() {
        converters.parseUUID(null) shouldBe null
    }

    @Test
    fun `parse uuid - roundtrip with saveUUID`() {
        // given
        val id = UUID.randomUUID()

        // when
        val parsed = converters.parseUUID(converters.saveUUID(id))

        // then
        parsed shouldBe id
    }
}
