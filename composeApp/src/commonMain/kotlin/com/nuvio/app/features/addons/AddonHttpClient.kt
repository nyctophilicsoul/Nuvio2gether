package com.nuvio.app.features.addons

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String =
    if (forceRefresh) {
        httpGetTextWithHeaders(
            url = url,
            headers = mapOf("Cache-Control" to "no-cache"),
        )
    } else {
        httpGetText(url)
    }

/**
 * Downloads a bounded binary response for verified addon/plugin packages.
 * The response is never treated as text; the raw transport preserves the original bytes.
 */
internal suspend fun httpGetBytesWithHeaders(
    url: String,
    headers: Map<String, String> = emptyMap(),
    maxResponseBodyBytes: Int = 128 * 1024 * 1024,
): ByteArray {
    require(maxResponseBodyBytes > 0) { "Response size limit must be positive" }
    val response = httpRequestRaw(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/octet-stream") + headers,
        body = "",
        maxResponseBodyBytes = maxResponseBodyBytes,
    )
    check(response.status in 200..299) {
        "Package download failed with HTTP ${response.status}"
    }
    check(!response.body.endsWith("...[truncated]")) {
        "Package download exceeded the ${maxResponseBodyBytes}-byte size limit"
    }
    return response.bodyBytes
}
