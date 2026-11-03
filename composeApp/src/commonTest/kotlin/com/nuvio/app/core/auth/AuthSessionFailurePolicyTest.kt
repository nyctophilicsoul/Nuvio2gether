package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSessionFailurePolicyTest {
    @Test
    fun `401 response is treated as an invalid remote session`() {
        assertTrue(isInvalidRemoteSessionFailure(401, "Unauthorized"))
    }

    @Test
    fun `403 permission denial must not clear local account credentials`() {
        assertFalse(isInvalidRemoteSessionFailure(403, "permission denied by row-level security"))
    }

    @Test
    fun `403 remains non terminal even when message mentions an expired jwt`() {
        assertFalse(isInvalidRemoteSessionFailure(403, "JWT expired"))
    }

    @Test
    fun `explicit invalid jwt message remains detectable without status code`() {
        assertTrue(isInvalidRemoteSessionFailure(null, "Invalid JWT: token expired"))
    }

    @Test
    fun `remote user deletion is detectable without status code`() {
        assertTrue(isInvalidRemoteSessionFailure(null, "User does not exist"))
    }
}
