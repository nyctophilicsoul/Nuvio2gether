package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals

class TraktAuthRepositoryTest {

    @Test
    fun `missing or legacy authentication method defaults to browser PKCE`() {
        assertEquals(TraktAuthenticationMethod.BROWSER_REDIRECT, TraktAuthenticationMethod.fromStorageValue(null))
        assertEquals(TraktAuthenticationMethod.BROWSER_REDIRECT, TraktAuthenticationMethod.fromStorageValue("device_code"))
        assertEquals(TraktAuthenticationMethod.BROWSER_REDIRECT, TraktAuthenticationMethod.fromStorageValue("legacy-or-unknown"))
    }

    @Test
    fun `PKCE challenge matches RFC 7636 S256 example`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            TraktPkce.challengeForVerifier(verifier),
        )
    }

    @Test
    fun `Trakt redirect URI must use HTTPS`() {
        assertEquals(true, isValidTraktRedirectUri("https://auth.example.com/trakt/callback"))
        assertEquals(false, isValidTraktRedirectUri("nuvio://auth/trakt"))
        assertEquals(false, isValidTraktRedirectUri("http://auth.example.com/trakt/callback"))
        assertEquals(false, isValidTraktRedirectUri("not a URI"))
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
