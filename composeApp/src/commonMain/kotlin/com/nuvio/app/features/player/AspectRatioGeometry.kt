package com.nuvio.app.features.player

import kotlin.math.max
import kotlin.math.min

/**
 * Engine-independent geometry contract for player resize modes.
 *
 * Width/height are coded dimensions. pixelAspectRatio is SAR (sample width / sample height).
 * The returned rectangle is centered in the viewport; positive overflow means intentional crop.
 */
data class AspectRatioViewport(
    val width: Float,
    val height: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val sourceDisplayAspectRatio: Float,
    val targetAspectRatio: Float,
) {
    val cropLeftPx: Float get() = max(0f, -offsetX)
    val cropTopPx: Float get() = max(0f, -offsetY)
}

object AspectRatioGeometry {
    fun calculate(
        codedWidth: Int,
        codedHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        pixelAspectRatio: Float = 1f,
        mode: PlayerResizeMode = PlayerResizeMode.BestFit,
    ): AspectRatioViewport? {
        if (codedWidth <= 0 || codedHeight <= 0 ||
            viewportWidth <= 0f || viewportHeight <= 0f ||
            !viewportWidth.isFinite() || !viewportHeight.isFinite()
        ) return null

        val sar = pixelAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val codedAspect = codedWidth.toFloat() / codedHeight.toFloat()
        val displayAspect = codedAspect * sar
        val targetAspect = when (mode) {
            PlayerResizeMode.Ratio16x9 -> 16f / 9f
            PlayerResizeMode.Fit -> codedAspect
            else -> displayAspect
        }

        val sourceWidth = when (mode) {
            PlayerResizeMode.Ratio16x9 -> codedHeight * (16f / 9f)
            PlayerResizeMode.Fit -> codedWidth.toFloat()
            else -> codedWidth * sar
        }
        val sourceHeight = codedHeight.toFloat()
        val sx = viewportWidth / sourceWidth
        val sy = viewportHeight / sourceHeight

        if (mode == PlayerResizeMode.Full) {
            return AspectRatioViewport(
                width = viewportWidth,
                height = viewportHeight,
                offsetX = 0f,
                offsetY = 0f,
                scaleX = viewportWidth / sourceWidth,
                scaleY = viewportHeight / sourceHeight,
                sourceDisplayAspectRatio = displayAspect,
                targetAspectRatio = viewportWidth / viewportHeight,
            )
        }

        val scale = when (mode) {
            PlayerResizeMode.Zoom -> max(sx, sy)
            // Fit uses the coded (square-pixel) geometry and never enlarges a
            // source beyond its native dimensions. BestFit remains the
            // metadata-corrected mode that can upscale to fill the available area.
            PlayerResizeMode.Fit -> min(1f, min(sx, sy))
            else -> min(sx, sy)
        }
        val renderedWidth = sourceWidth * scale
        val renderedHeight = sourceHeight * scale
        return AspectRatioViewport(
            width = renderedWidth,
            height = renderedHeight,
            offsetX = (viewportWidth - renderedWidth) / 2f,
            offsetY = (viewportHeight - renderedHeight) / 2f,
            scaleX = scale,
            scaleY = scale,
            sourceDisplayAspectRatio = displayAspect,
            targetAspectRatio = targetAspect,
        )
    }
}
