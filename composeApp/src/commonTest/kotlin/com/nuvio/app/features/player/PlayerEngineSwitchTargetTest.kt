package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerEngineSwitchTargetTest {
    @Test
    fun exoPlayerSwitchesToLibmpv() {
        assertEquals(AndroidPlaybackEngine.Libmpv, AndroidPlaybackEngine.ExoPlayer.switchTarget)
    }

    @Test
    fun libmpvSwitchesToExoPlayer() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, AndroidPlaybackEngine.Libmpv.switchTarget)
    }
}
