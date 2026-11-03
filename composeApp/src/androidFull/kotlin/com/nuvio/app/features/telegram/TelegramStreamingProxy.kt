package com.nuvio.app.features.telegram

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.net.ServerSocket

/**
 * Local loopback HTTP bridge for TDLib files.
 *
 * It deliberately serves bounded byte ranges instead of exposing TDLib's temporary
 * file path. This lets both Media3 and libmpv seek without eagerly downloading a movie.
 */
internal class TelegramStreamingProxy(
    context: Context,
    private val client: TelegramTdClient,
) {
    companion object {
        private const val TAG = "NuvioTelegramProxy"
        private const val CHUNK_BYTES = 1024 * 1024
        private const val DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val CLEANUP_GRACE_MS = 90_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val DOWNLOAD_PRIORITY = 32
    }

    private data class ByteRange(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1L
    }

    private data class ActiveRequests(
        var count: Int = 0,
        var cleanup: Job? = null,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val requestStates = mutableMapOf<Int, ActiveRequests>()
    private val mimeTypes = mutableMapOf<Int, String>()

    @Volatile private var server: ApplicationEngine? = null
    @Volatile private var port: Int = 0

    @Synchronized
    fun start() {
        if (server != null) return
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/file/{fileId}") {
                    val fileId = call.parameters["fileId"]?.toIntOrNull()
                    if (fileId == null || fileId <= 0) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    if (!client.isAuthenticated()) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                    beginRequest(fileId)
                    try {
                        val file = client.sendRequest(TdApi.GetFile(fileId), REQUEST_TIMEOUT_MS) as? TdApi.File
                        val totalSize = file?.size?.takeIf { it > 0L }
                            ?: file?.expectedSize?.takeIf { it > 0L }
                        if (totalSize == null || totalSize <= 0L) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }

                        val rangeHeader = call.request.headers[HttpHeaders.Range]
                        val byteRange = parseRange(rangeHeader, totalSize)
                        if (byteRange == null) {
                            call.response.header(HttpHeaders.AcceptRanges, "bytes")
                            call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                            call.respond(HttpStatusCode.RangeNotSatisfiable)
                            return@get
                        }

                        val isPartial = rangeHeader != null
                        call.response.header(HttpHeaders.AcceptRanges, "bytes")
                        call.response.header(HttpHeaders.ContentLength, byteRange.length.toString())
                        if (isPartial) {
                            call.response.header(
                                HttpHeaders.ContentRange,
                                "bytes ${byteRange.start}-${byteRange.end}/$totalSize",
                            )
                        }

                        val mime = mimeTypes[fileId]?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                            ?: ContentType.Application.OctetStream
                        call.respondBytesWriter(
                            contentType = mime,
                            status = if (isPartial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                        ) {
                            var offset = byteRange.start
                            while (offset <= byteRange.end) {
                                val requested = minOf(CHUNK_BYTES.toLong(), byteRange.end - offset + 1L).toInt()
                                val bytes = downloadChunk(fileId, offset, requested, totalSize)
                                    ?: throw IllegalStateException("Telegram did not provide bytes at offset $offset")
                                if (bytes.isEmpty()) {
                                    throw IllegalStateException("Telegram returned an empty media chunk at offset $offset")
                                }
                                writeFully(bytes)
                                offset += bytes.size
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Telegram range request failed for file $fileId", error)
                        if (!call.response.isCommitted) {
                            call.respond(HttpStatusCode.BadGateway)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            endRequest(fileId)
                        }
                    }
                }
            }
        }.start(wait = false)
        Log.i(TAG, "Telegram loopback server listening at 127.0.0.1:$port")
    }

    fun streamUrl(fileId: Int, mimeType: String?): String {
        start()
        if (!mimeType.isNullOrBlank()) synchronized(mimeTypes) { mimeTypes[fileId] = mimeType }
        return "http://127.0.0.1:$port/file/$fileId"
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        current.stop(250, 1_000)
        scope.launch {
            val ids = mutex.withLock {
                val keys = requestStates.keys.toList()
                requestStates.values.forEach { it.cleanup?.cancel() }
                requestStates.clear()
                keys
            }
            ids.forEach { deleteFile(it) }
        }
        synchronized(mimeTypes) { mimeTypes.clear() }
    }

    private fun parseRange(header: String?, totalSize: Long): ByteRange? {
        if (totalSize <= 0L) return null
        if (header.isNullOrBlank()) return ByteRange(0L, totalSize - 1L)
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val value = header.substringAfter("bytes=", "").trim()
        if (value.isBlank() || value.contains(',')) return null
        val separator = value.indexOf('-')
        if (separator < 0) return null

        val left = value.substring(0, separator).trim()
        val right = value.substring(separator + 1).trim()
        return when {
            left.isBlank() -> {
                val suffixLength = right.toLongOrNull()?.takeIf { it > 0L } ?: return null
                val length = minOf(suffixLength, totalSize)
                ByteRange(totalSize - length, totalSize - 1L)
            }
            else -> {
                val start = left.toLongOrNull()?.takeIf { it >= 0L && it < totalSize } ?: return null
                val requestedEnd = if (right.isBlank()) totalSize - 1L
                else right.toLongOrNull()?.takeIf { it >= start } ?: return null
                ByteRange(start, minOf(requestedEnd, totalSize - 1L))
            }
        }
    }

    private suspend fun beginRequest(fileId: Int) {
        mutex.withLock {
            val state = requestStates.getOrPut(fileId) { ActiveRequests() }
            state.cleanup?.cancel()
            state.cleanup = null
            state.count += 1
        }
    }

    private suspend fun endRequest(fileId: Int) {
        mutex.withLock {
            val state = requestStates[fileId] ?: return@withLock
            state.count = (state.count - 1).coerceAtLeast(0)
            if (state.count != 0) return@withLock
            state.cleanup = scope.launch {
                delay(CLEANUP_GRACE_MS)
                val thisJob = currentCoroutineContext()[Job]
                mutex.withLock {
                    val current = requestStates[fileId]
                    if (current != null && current.count == 0 && current.cleanup === thisJob) {
                        // Drop state before the TDLib calls so a fresh request can establish a new lease.
                        requestStates.remove(fileId)
                        deleteFile(fileId)
                    }
                }
            }
        }
    }

    private suspend fun downloadChunk(
        fileId: Int,
        offset: Long,
        length: Int,
        totalSize: Long,
    ): ByteArray? = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
        val usableSpace = runCatching { appContext.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val prefetch = when {
            usableSpace < 500L * 1024L * 1024L -> 2L * 1024L * 1024L
            else -> (length * 8L).coerceIn(4L * 1024L * 1024L, 16L * 1024L * 1024L)
        }.coerceAtMost(totalSize - offset)

        client.sendRequest(
            TdApi.DownloadFile().also { request ->
                request.fileId = fileId
                request.priority = DOWNLOAD_PRIORITY
                request.offset = offset
                request.limit = prefetch.coerceAtLeast(length.toLong())
                request.synchronous = false
            },
            REQUEST_TIMEOUT_MS,
        )

        var attempts = 0
        while (attempts < 450) {
            try {
                val data = client.sendRequest(
                    TdApi.ReadFilePart(fileId, offset, length.toLong()),
                    REQUEST_TIMEOUT_MS,
                ) as? TdApi.Data
                if (data?.data?.isNotEmpty() == true) {
                    if (data.data.size >= length || offset + data.data.size >= totalSize) {
                        return@withTimeoutOrNull data.data
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TelegramTdApiException) {
                // The requested range may still be downloading; retry the non-blocking read.
            }

            val file = client.sendRequest(TdApi.GetFile(fileId), REQUEST_TIMEOUT_MS) as? TdApi.File
            if (file?.local?.isDownloadingCompleted == true && offset >= file.size) return@withTimeoutOrNull null
            delay(100L)
            attempts += 1
        }
        null
    }

    private suspend fun deleteFile(fileId: Int) {
        runCatching {
            client.sendRequest(
                TdApi.CancelDownloadFile().also {
                    it.fileId = fileId
                    it.onlyIfPending = false
                },
                REQUEST_TIMEOUT_MS,
            )
        }
        runCatching { client.sendRequest(TdApi.DeleteFile().also { it.fileId = fileId }, REQUEST_TIMEOUT_MS) }
    }
}
