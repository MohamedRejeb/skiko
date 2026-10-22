package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.hostOs
import kotlin.time.TimeSource

private val initialTime = TimeSource.Monotonic.markNow()

internal interface Redrawer {
    fun dispose()
    fun needRender(throttledToVsync: Boolean)
    fun renderImmediately()
    fun syncBounds() = Unit
    fun update(nanoTime: Long = initialTime.elapsedNow().inWholeNanoseconds)
    fun setVisible(isVisible: Boolean) = Unit

    /**
     * Turns waiting for vsync between frames on or off, which limits rendering to the display refresh rate.
     */
    fun setFrameThrottlingEnabled(enabled: Boolean) = Unit

    /**
     * Turns syncing the presentation of a frame with the display refresh on or off.
     * Only redrawers whose presentation can be switched separately from throttling (Metal) use it.
     */
    fun setPresentSyncEnabled(enabled: Boolean) = Unit
    val renderInfo: String
    fun isTransparentBackgroundSupported(): Boolean
}

internal fun defaultIsTransparentBackgroundSupported(layer: SkiaLayer): Boolean {
    if (hostOs == OS.MacOS) {
        // macOS transparency is always supported
        return true
    }

    // for non-macOS in fullscreen transparency is not supported
    return !layer.fullscreen
}