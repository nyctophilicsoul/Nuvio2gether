package com.nuvio.app.features.player

/**
 * Ordered recovery ladder adapted from Nuvio TV.
 *
 * Keep each recovery step explicit so the caller can avoid repeatedly retrying the
 * same failing configuration. This policy has no player lifecycle dependencies.
 */
internal object PlaybackAudioRecoveryPolicy {
    enum class Step {
        SAFE_DECODER,
        FORCE_PCM,
        DISABLE_AUDIO,
    }

    fun nextStep(
        safeDecoderActive: Boolean,
        pcmForced: Boolean,
        audioDisabled: Boolean,
    ): Step? = when {
        !safeDecoderActive -> Step.SAFE_DECODER
        !pcmForced -> Step.FORCE_PCM
        !audioDisabled -> Step.DISABLE_AUDIO
        else -> null
    }
}
