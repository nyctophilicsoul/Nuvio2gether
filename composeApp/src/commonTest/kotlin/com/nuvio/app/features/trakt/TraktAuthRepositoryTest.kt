package com.nuvio.app.features.trakt

import kotlin.test.Test
import io.ktor.http.Url
import kotlin.test.assertEquals

class TraktAuthRepositoryTest {

    @Test
    fun `Trakt authorization URL sends S256 PKCE challenge`() {
        val challenge = "base64url-challenge"
        val state = "secure-oauth-state"
        val url = Url(
            buildTraktAuthorizationUrl(
                clientId = "client-id",
                redirectUri = "nuvio://auth/trakt",
                state = state,
                codeChallenge = challenge,
            ),
        )

        assertEquals("code", url.parameters["response_type"])
        assertEquals("client-id", url.parameters["client_id"])
        assertEquals("nuvio://auth/trakt", url.parameters["redirect_uri"])
        assertEquals(state, url.parameters["state"])
        assertEquals(challenge, url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])
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
