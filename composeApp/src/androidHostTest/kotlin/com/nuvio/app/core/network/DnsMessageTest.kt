package com.nuvio.app.core.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DnsMessageTest {
    @Test
    fun parsesAddressAndPreservesZeroTtl() {
        val answer = DnsMessage.parseAnswer("example.com", response(ttl = 0), TYPE_A, ID)

        assertEquals(listOf("1.2.3.4"), answer.addresses.map { it.hostAddress })
        assertEquals(0L, answer.ttlSeconds)
    }

    @Test
    fun rejectsMismatchedTransactionId() {
        assertFailsWith<IOException> {
            DnsMessage.parseAnswer("example.com", response(), TYPE_A, ID + 1)
        }
    }

    @Test
    fun rejectsTruncatedResponses() {
        assertFailsWith<IOException> {
            DnsMessage.parseAnswer("example.com", response(flags = 0x8380), TYPE_A, ID)
        }
    }

    @Test
    fun rejectsQuestionNameMismatch() {
        assertFailsWith<IOException> {
            DnsMessage.parseAnswer("other.example", response(), TYPE_A, ID)
        }
    }

    @Test
    fun rejectsMalformedCompressionPointer() {
        val packet = response().also {
            // The answer owner name points outside the DNS packet.
            it[29] = 0xFF.toByte()
            it[30] = 0xFF.toByte()
        }
        assertFailsWith<IOException> {
            DnsMessage.parseAnswer("example.com", packet, TYPE_A, ID)
        }
    }

    @Test
    fun queryEncodesTransactionIdAndQuestionType() {
        val query = DnsMessage.query("example.com", TYPE_AAAA, ID)
        assertEquals(ID, ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF))
        assertEquals(TYPE_AAAA, ((query[query.size - 4].toInt() and 0xFF) shl 8) or (query[query.size - 3].toInt() and 0xFF))
        assertTrue(query.isNotEmpty())
    }

    private fun response(
        ttl: Int = 60,
        flags: Int = 0x8180,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        u16(ID)
        u16(flags)
        u16(1) // questions
        u16(1) // answers
        u16(0)
        u16(0)
        out.write(byteArrayOf(7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0))
        u16(TYPE_A)
        u16(1)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C)) // owner name -> question name
        u16(TYPE_A)
        u16(1)
        out.write(byteArrayOf(0, 0, 0, ttl.toByte()))
        u16(4)
        out.write(byteArrayOf(1, 2, 3, 4))
        return out.toByteArray()
    }

    private companion object {
        const val ID = 0x1234
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
    }
}
