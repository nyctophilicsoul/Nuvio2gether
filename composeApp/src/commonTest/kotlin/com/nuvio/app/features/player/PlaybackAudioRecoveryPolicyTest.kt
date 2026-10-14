package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackAudioRecoveryPolicyTest {
    @Test
    fun selectsSafeDecoderBeforeForcingPcm() {
        assertEquals(
            PlaybackAudioRecoveryPolicy.Step.SAFE_DECODER,
            PlaybackAudioRecoveryPolicy.nextStep(
                safeDecoderActive = false,
                pcmForced = false,
                audioDisabled = false,
            ),
        )
    }

    @Test
    fun forcesPcmAfterSafeDecoderHasFailed() {
        assertEquals(
            PlaybackAudioRecoveryPolicy.Step.FORCE_PCM,
            PlaybackAudioRecoveryPolicy.nextStep(
                safeDecoderActive = true,
                pcmForced = false,
                audioDisabled = false,
            ),
        )
    }

    @Test
    fun disablesAudioOnlyAfterEarlierStepsHaveFailed() {
        assertEquals(
            PlaybackAudioRecoveryPolicy.Step.DISABLE_AUDIO,
            PlaybackAudioRecoveryPolicy.nextStep(
                safeDecoderActive = true,
                pcmForced = true,
                audioDisabled = false,
            ),
        )
    }

    @Test
    fun stopsRetryingAfterEveryRecoveryStep() {
        assertNull(
            PlaybackAudioRecoveryPolicy.nextStep(
                safeDecoderActive = true,
                pcmForced = true,
                audioDisabled = true,
            ),
        )
    }
}
