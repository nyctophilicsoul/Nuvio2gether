package com.nuvio.app.core.network

import android.os.SystemClock
import android.util.Base64
import co.touchlab.kermit.Logger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object NuvioDns : Dns {
    private val log = Logger.withTag("NuvioDns")

    @Volatile
    var provider: DnsProvider = DnsProvider.SYSTEM
        set(value) {
            field = value
            cache.clear()
        }

    private class CachedLookup(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedLookup>()

    override fun lookup(hostname: String): List<InetAddress> {
        val current = provider
        val endpoint = DohEndpoint.forProvider(current)
        if (endpoint == null || hostname.isIpLiteral() || hostname.equals("localhost", ignoreCase = true)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        val normalizedHost = hostname.trimEnd('.').lowercase()
        val key = "${current.storageId}|$normalizedHost"
        val now = SystemClock.elapsedRealtime()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.addresses }

        val answer = try {
            DohClient.resolve(endpoint, normalizedHost)
        } catch (error: IOException) {
            log.w { "DoH lookup via ${current.displayName} failed for $hostname, using system DNS: ${error.message}" }
            return Dns.SYSTEM.lookup(hostname)
        }
        if (answer.addresses.isEmpty()) {
            // A valid NXDOMAIN/NODATA response must not silently bypass the selected resolver.
            throw UnknownHostException("$hostname has no address (DNS over HTTPS via ${current.displayName})")
        }
        val ttlMs = answer.ttlSeconds.coerceIn(0L, 3_600L) * 1_000L
        if (ttlMs > 0L) cache[key] = CachedLookup(answer.addresses, now + ttlMs)
        return answer.addresses
    }

    private val ipv4Literal = Regex("""^\\d{1,3}(\\.\\d{1,3}){3}$""")

    private fun String.isIpLiteral(): Boolean = contains(':') || ipv4Literal.matches(this)
}

internal class DohEndpoint private constructor(val host: String, val bootstrapAddresses: List<String>) {
    val url: String get() = "https://$host/dns-query"

    companion object {
        private val cloudflare = DohEndpoint("cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1"))
        private val google = DohEndpoint("dns.google", listOf("8.8.8.8", "8.8.4.4"))
        private val adguard = DohEndpoint("dns.adguard-dns.com", listOf("94.140.14.14", "94.140.15.15"))
        private val quad9 = DohEndpoint("dns.quad9.net", listOf("9.9.9.9", "149.112.112.112"))
        private val all = listOf(cloudflare, google, adguard, quad9)

        fun forProvider(provider: DnsProvider): DohEndpoint? = when (provider) {
            DnsProvider.SYSTEM -> null
            DnsProvider.CLOUDFLARE -> cloudflare
            DnsProvider.GOOGLE -> google
            DnsProvider.ADGUARD -> adguard
            DnsProvider.QUAD9 -> quad9
        }

        fun forHost(host: String): DohEndpoint? = all.firstOrNull { it.host.equals(host, ignoreCase = true) }
    }
}

internal class DohAnswer(val addresses: List<InetAddress>, val ttlSeconds: Long)

internal object DohClient {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28

    private val queryExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "NuvioDns-query").apply { isDaemon = true }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns { host ->
                DohEndpoint.forHost(host)?.bootstrapAddresses?.map { InetAddress.getByName(it) }
                    ?: Dns.SYSTEM.lookup(host)
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun resolve(endpoint: DohEndpoint, hostname: String): DohAnswer {
        // Run A and AAAA concurrently so a slow IPv6 response cannot hold up IPv4 playback.
        val aTask = java.util.concurrent.FutureTask { query(endpoint, hostname, TYPE_A) }
        val aaaaTask = java.util.concurrent.FutureTask { query(endpoint, hostname, TYPE_AAAA) }
        queryExecutor.execute(aTask)
        queryExecutor.execute(aaaaTask)

        val v4 = try {
            aTask.get(8, TimeUnit.SECONDS)
        } catch (error: Exception) {
            aTask.cancel(true)
            aaaaTask.cancel(true)
            val cause = error.cause
            if (cause is IOException) throw cause
            throw IOException("A record lookup failed for $hostname", cause ?: error)
        }
        val v6 = try {
            aaaaTask.get(8, TimeUnit.SECONDS)
        } catch (error: Exception) {
            aaaaTask.cancel(true)
            null
        }
        val addresses = v4.addresses + v6?.addresses.orEmpty()
        val ttls = listOfNotNull(
            v4.ttlSeconds.takeIf { v4.addresses.isNotEmpty() },
            v6?.ttlSeconds?.takeIf { v6.addresses.isNotEmpty() },
        )
        return DohAnswer(addresses, ttls.minOrNull() ?: 0L)
    }

    private fun query(endpoint: DohEndpoint, hostname: String, type: Int): DohAnswer {
        val id = DnsMessage.nextId()
        val message = Base64.encodeToString(
            DnsMessage.query(hostname, type, id),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val request = Request.Builder()
            .url("${endpoint.url}?dns=$message")
            .header("Accept", "application/dns-message")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${endpoint.host}")
            val body = response.body?.bytes() ?: throw IOException("Empty response from ${endpoint.host}")
            return DnsMessage.parseAnswer(hostname, body, type, id)
        }
    }
}

internal object DnsMessage {
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1
    private const val RCODE_NXDOMAIN = 3
    private val random = SecureRandom()

    fun nextId(): Int = random.nextInt(0x1_0000)

    fun query(hostname: String, type: Int, id: Int = 0): ByteArray {
        val normalized = hostname.trimEnd('.')
        require(normalized.isNotEmpty() && normalized.length <= 253) { "Invalid host name: $hostname" }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        normalized.split('.').forEach { label ->
            require(label.all { it.code in 0x21..0x7E }) { "Invalid host name: $hostname" }
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) {
                "Invalid host name: $hostname"
            }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, CLASS_IN.toByte()))
        return out.toByteArray()
    }

    fun parseAnswer(hostname: String, data: ByteArray, type: Int, expectedId: Int = 0): DohAnswer {
        fun u8(at: Int): Int {
            if (at < 0 || at >= data.size) throw IOException("Truncated DNS answer")
            return data[at].toInt() and 0xFF
        }
        fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        fun readName(start: Int): Pair<String, Int> {
            var at = start
            var next = -1
            var jumps = 0
            val labels = mutableListOf<String>()
            while (true) {
                val length = u8(at)
                if (length == 0) {
                    if (next < 0) next = at + 1
                    return labels.joinToString(".") to next
                }
                when {
                    length and 0xC0 == 0xC0 -> {
                        val pointer = ((length and 0x3F) shl 8) or u8(at + 1)
                        if (pointer >= data.size || ++jumps > 32) throw IOException("Invalid DNS compression pointer")
                        if (next < 0) next = at + 2
                        at = pointer
                    }
                    length and 0xC0 != 0 -> throw IOException("Invalid DNS label encoding")
                    else -> {
                        if (length > 63 || at + 1 + length > data.size) throw IOException("Truncated DNS name")
                        labels += String(data, at + 1, length, Charsets.US_ASCII)
                        at += length + 1
                        if (labels.sumOf { it.length + 1 } > 254) throw IOException("DNS name is too long")
                    }
                }
            }
        }

        if (data.size < 12) throw IOException("DNS response header is truncated")
        if (u16(0) != expectedId) throw IOException("DNS transaction ID mismatch")
        val flags = u16(2)
        if (flags and 0x8000 == 0) throw IOException("DNS packet is not a response")
        if (flags and 0x7800 != 0) throw IOException("Unexpected DNS opcode")
        if (flags and 0x0200 != 0) throw IOException("Truncated DNS response")
        val rcode = flags and 0x000F
        if (u16(4) != 1) throw IOException("Unexpected DNS question count")
        val answerCount = u16(6)
        val authorityCount = u16(8)
        val additionalCount = u16(10)
        val (questionName, questionEnd) = readName(12)
        if (!questionName.equals(hostname.trimEnd('.'), ignoreCase = true)) throw IOException("DNS question name mismatch")
        if (u16(questionEnd) != type || u16(questionEnd + 2) != CLASS_IN) throw IOException("DNS question type/class mismatch")
        var at = questionEnd + 4
        if (rcode == RCODE_NXDOMAIN) return DohAnswer(emptyList(), 0L)
        if (rcode != 0) throw IOException("DNS error code $rcode")

        val addresses = mutableListOf<InetAddress>()
        var ttl = Long.MAX_VALUE
        repeat(answerCount + authorityCount + additionalCount) { index ->
            val (_, ownerEnd) = readName(at)
            at = ownerEnd
            val recordType = u16(at)
            val recordClass = u16(at + 2)
            val recordTtl = u32(at + 4)
            val length = u16(at + 8)
            val dataStart = at + 10
            if (dataStart < at || dataStart + length > data.size) throw IOException("Truncated DNS resource record")
            if (index < answerCount && recordClass == CLASS_IN && recordType == type) {
                val expectedLength = if (type == TYPE_A) 4 else if (type == TYPE_AAAA) 16 else -1
                if (length == expectedLength) {
                    addresses += InetAddress.getByAddress(hostname, data.copyOfRange(dataStart, dataStart + length))
                    ttl = minOf(ttl, recordTtl)
                }
            }
            at = dataStart + length
        }
        return DohAnswer(addresses, if (ttl == Long.MAX_VALUE) 0L else ttl)
    }
}
