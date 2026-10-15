package com.nuvio.app.features.streams

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.TimeSource

/**
 * Measures time-to-response with a small Range request. It never intentionally
 * reads a full media body. Failed probes remain in the fallback chain because
 * some providers reject Range requests even when their streams are playable.
 */
object StreamSourceProbe {
    private const val MAX_CONCURRENT_PROBES = 6
    private const val REQUEST_TIMEOUT_MS = 3_500L
    private const val CONNECT_TIMEOUT_MS = 2_500L

    private val client by lazy { createApiHttpClient() }

    suspend fun orderByFastest(candidates: List<StreamItem>): List<StreamItem> =
        orderByProbeLatency(candidates, ::probeResponseTime)

    internal suspend fun orderByProbeLatency(
        candidates: List<StreamItem>,
        probe: suspend (StreamItem) -> Long?,
    ): List<StreamItem> = coroutineScope {
        if (candidates.size < 2) return@coroutineScope candidates

        val tiers = candidates.groupBy(QualityFirstAutoPlayPolicy::detectQualityHeight).values
        val ordered = mutableListOf<StreamItem>()
        var consumed = 0

        for (tier in tiers) {
            val direct = tier.filter { !it.playableDirectUrl.isNullOrBlank() }
            val withoutDirectUrl = tier.filter { it.playableDirectUrl.isNullOrBlank() }
            val results = direct.chunked(MAX_CONCURRENT_PROBES).flatMap { batch ->
                batch.map { stream ->
                    async {
                        val latency = try {
                            probe(stream)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                        stream to latency
                    }
                }.awaitAll()
            }

            val reachable = results.filter { it.second != null }
                .sortedBy { it.second }
                .map { it.first }
            val unreachable = results.filter { it.second == null }.map { it.first }

            ordered += reachable
            ordered += withoutDirectUrl
            ordered += unreachable
            consumed += tier.size

            // Keep the highest quality with at least one responding candidate.
            // Lower tiers remain in original quality order for real-player fallback.
            if (reachable.isNotEmpty()) {
                ordered += candidates.drop(consumed)
                break
            }
        }

        ordered.distinctBy { it.playableDirectUrl ?: it.p2pInfoHash ?: "${it.addonId}|${it.streamLabel}" }
    }

    private suspend fun probeResponseTime(stream: StreamItem): Long? {
        val url = stream.playableDirectUrl ?: return null
        val started = TimeSource.Monotonic.markNow()

        return try {
            client.prepareGet(url) {
                header(HttpHeaders.Range, "bytes=0-0")
                sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request).forEach { (name, value) ->
                    header(name, value)
                }
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }.execute { response ->
                if (response.status.value in 200..399) {
                    started.elapsedNow().inWholeMilliseconds
                } else {
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
