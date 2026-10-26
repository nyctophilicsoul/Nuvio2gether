package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals

class TraktAuthRepositoryTest {

    @Test
    fun `missing or unknown authentication method defaults to device code`() {
        assertEquals(TraktAuthenticationMethod.DEVICE_CODE, TraktAuthenticationMethod.fromStorageValue(null))
        assertEquals(TraktAuthenticationMethod.DEVICE_CODE, TraktAuthenticationMethod.fromStorageValue("legacy-or-unknown"))
    }

    @Test
    fun `HTTP 400 permanently invalidates a rejected refresh token`() {
        assertEquals(
            TraktTokenRefreshResponseAction.INVALIDATE,
            traktTokenRefreshResponseAction(400),
        )
    }

    @Test
    fun `successful token responses are accepted`() {
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(200),
        )
        assertEquals(
            TraktTokenRefreshResponseAction.ACCEPT,
            traktTokenRefreshResponseAction(201),
        )
    }

    @Test
    fun `non-400 failures preserve credentials for a later attempt`() {
        listOf(401, 429, 500, 503).forEach { status ->
            assertEquals(
                TraktTokenRefreshResponseAction.TRANSIENT_FAILURE,
                traktTokenRefreshResponseAction(status),
            )
        }
    }
}
