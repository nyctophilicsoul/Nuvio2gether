package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AspectRatioGeometryTest {
    @Test
    fun bestFitPreservesDisplayAspectAndAddsPillarbox() {
        val result = AspectRatioGeometry.calculate(720, 576, 1920f, 1080f, 16f / 15f)
        requireNotNull(result)
        assertEquals(4f / 3f, result.sourceDisplayAspectRatio, 0.001f)
        assertEquals(1440f, result.width, 0.1f)
        assertEquals(1080f, result.height, 0.1f)
        assertEquals(240f, result.offsetX, 0.1f)
        assertEquals(0f, result.offsetY, 0.1f)
    }

    @Test
    fun fitUsesSquarePixelCodedGeometry() {
        val result = AspectRatioGeometry.calculate(720, 576, 1920f, 1080f, 16f / 15f, PlayerResizeMode.Fit)
        requireNotNull(result)
        assertEquals(720f / 576f, result.width / result.height, 0.001f)
    }

    @Test
    fun fitDoesNotUpscaleLowResolutionSources() {
        val result = AspectRatioGeometry.calculate(720, 576, 1920f, 1080f, 1f, PlayerResizeMode.Fit)
        requireNotNull(result)
        assertEquals(720f, result.width, 0.1f)
        assertEquals(576f, result.height, 0.1f)
        assertEquals(600f, result.offsetX, 0.1f)
        assertEquals(252f, result.offsetY, 0.1f)
    }

    @Test
    fun fitStillDownscalesSourcesLargerThanViewport() {
        val result = AspectRatioGeometry.calculate(3840, 2160, 1920f, 1080f, 1f, PlayerResizeMode.Fit)
        requireNotNull(result)
        assertEquals(1920f, result.width, 0.1f)
        assertEquals(1080f, result.height, 0.1f)
    }

    @Test
    fun fitModeNameRestoresAsFitInsteadOfBestFit() {
        assertEquals(PlayerResizeMode.Fit, PlayerResizeMode.fromStoredName("Fit"))
    }

    @Test
    fun legacyFillNameStillRestoresAsFull() {
        assertEquals(PlayerResizeMode.Full, PlayerResizeMode.fromStoredName("Fill"))
    }

    @Test
    fun fullStretchesBothAxesWithoutBars() {
        val result = AspectRatioGeometry.calculate(720, 576, 1920f, 1080f, 1f, PlayerResizeMode.Full)
        requireNotNull(result)
        assertEquals(1920f, result.width)
        assertEquals(1080f, result.height)
        assertTrue(result.scaleX != result.scaleY)
    }

    @Test
    fun zoomFillsViewportAndCropsSymmetrically() {
        val result = AspectRatioGeometry.calculate(1920, 1080, 1080f, 2400f, 1f, PlayerResizeMode.Zoom)
        requireNotNull(result)
        assertTrue(result.width >= 1080f)
        assertTrue(result.height >= 2400f)
        assertEquals(result.offsetX, -result.offsetX - (result.width - 1080f), 0.1f)
    }

    @Test
    fun forcedSixteenNineUsesSixteenNineGeometry() {
        val result = AspectRatioGeometry.calculate(720, 576, 1920f, 1080f, 1f, PlayerResizeMode.Ratio16x9)
        requireNotNull(result)
        assertEquals(16f / 9f, result.width / result.height, 0.001f)
    }

    @Test
    fun rejectsInvalidDimensions() {
        assertNull(AspectRatioGeometry.calculate(0, 576, 1920f, 1080f))
    }
}
