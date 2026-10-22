package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.OpenGLContextHandler

internal class WindowsOpenGLRedrawer(
    private val layer: SkiaLayer,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.OPENGL) {
    init {
        loadOpenGLLibrary()
    }

    @Volatile
    private var isFrameThrottlingEnabled = properties.isVsyncEnabled

    @Volatile
    private var isLateFrameStartEnabled = false

    private val contextHandler = OpenGLContextHandler(layer)
    override val renderInfo: String get() = contextHandler.rendererInfo()

    private val device: Long = layer.backedLayer.useDrawingSurfacePlatformInfo {
        getDevice(it).also { devicePtr ->
            check(devicePtr != 0L) { "Can't get device" }
        }
    }

    private val context = createContext(device, layer.contentHandle, layer.transparency).also {
        if (it == 0L) {
            throw RenderException("Cannot create Windows GL context")
        }
        makeCurrent(device, it)
        adapterName.also { adapterName ->
            if (adapterName != null && !isVideoCardSupported(GraphicsApi.OPENGL, hostOs, adapterName)) {
                throw RenderException("Cannot create Windows GL context")
            }
        }
        onDeviceChosen(adapterName)
    }

    private val adapterName get() = OpenGLApi.instance.glGetString(OpenGLApi.instance.GL_RENDERER)

    init {
        makeCurrent()
        // For vsync we will use dwmFlush instead of swapInterval,
        // because it isn't reliable with DWM (Desktop Windows Manager): interval between frames isn't stable (14-19ms).
        // With dwmFlush it is stable (16.6-16.8 ms)
        // GLFW also uses dwmFlush (https://www.glfw.org/docs/3.0/window.html#window_swap)
        setSwapInterval(0)
        onContextInit()
    }

    override fun dispose() {
        check(!isDisposed) { "WindowsOpenGLRedrawer is disposed" }
        makeCurrent()
        contextHandler.dispose()
        deleteContext(context)
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        check(!isDisposed) { "WindowsOpenGLRedrawer is disposed" }
        toRedraw.add(this)
        frameDispatcher.scheduleFrame()
    }

    override fun setFrameThrottlingEnabled(enabled: Boolean) {
        isFrameThrottlingEnabled = enabled
    }

    override fun setLateFrameStartEnabled(enabled: Boolean) {
        isLateFrameStartEnabled = enabled
    }

    override fun renderImmediately() {
        check(!isDisposed) { "WindowsOpenGLRedrawer is disposed" }
        update()
        inDrawScope {
            if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
                makeCurrent()
                contextHandler.draw()
                swapBuffers()
                OpenGLApi.instance.glFinish()
                if (SkikoProperties.windowsWaitForVsyncOnRedrawImmediately) {
                    dwmFlush()
                }
            }
        }
    }

    private fun draw() {
        inDrawScope { contextHandler.draw() }
    }

    private fun makeCurrent() = makeCurrent(device, context)
    private fun swapBuffers() = swapBuffers(device)

    companion object {
        private val toRedraw = mutableSetOf<WindowsOpenGLRedrawer>()
        private val toRedrawCopy = mutableSetOf<WindowsOpenGLRedrawer>()
        private val toRedrawVisible = toRedrawCopy
            .asSequence()
            .filterNot(WindowsOpenGLRedrawer::isDisposed)
            .filter { it.layer.isShowing }

        private val lateFrameStart = LateFrameStart()

        private val frameTimes = if (System.getProperty("skiko.windows.frameTimes") == "true") FrameTimes() else null

        private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
            val lateStartEnabled = toRedraw.isNotEmpty() &&
                toRedraw.all { it.isFrameThrottlingEnabled && it.isLateFrameStartEnabled }
            val startsLate = lateStartEnabled && lateFrameStart.await()

            toRedrawCopy.addAll(toRedraw)
            toRedraw.clear()

            val nanoTime = System.nanoTime()
            for (redrawer in toRedrawVisible) {
                try {
                    redrawer.update(nanoTime)
                } catch (e: CancellationException) {
                    // continue
                }
            }
            val updated = System.nanoTime()

            for (redrawer in toRedrawVisible) {
                redrawer.makeCurrent()
                redrawer.draw()
            }
            val drawn = System.nanoTime()

            for (redrawer in toRedrawVisible) {
                redrawer.swapBuffers()
            }
            val swapped = System.nanoTime()

            for (redrawer in toRedrawVisible) {
                redrawer.makeCurrent()
                OpenGLApi.instance.glFinish()
            }
            val finished = System.nanoTime()
            frameTimes?.add(nanoTime, updated, drawn, swapped, finished, startsLate)

            // Every frame counts, a frame that fell back to vsync included, or one slow start would stick
            if (lateStartEnabled) lateFrameStart.onRendered(finished - nanoTime)

            val isVsyncEnabled = !startsLate && toRedrawVisible.all { it.isFrameThrottlingEnabled }
            if (isVsyncEnabled) {
                withContext(dispatcherToBlockOn) {
                    dwmFlush() // wait for vsync
                }
            }

            // Without clearing we will have a memory leak
            toRedrawCopy.clear()
        }
    }
}

/**
 * Starts a throttled frame as late before the next vblank as recent render times allow, so input that
 * arrives during the refresh still reaches the frame DWM composes at its end. Used on the UI thread only.
 */
private class LateFrameStart {
    private val timing = LongArray(2)
    private val renderNanos = LongArray(32)
    private var renders = 0
    // Half of MIN_VALUE: subtracting it from a nanoTime can't overflow
    private var lastVblank = Long.MIN_VALUE / 2

    /**
     * Waits for the frame's start time. Returns false, without waiting, when DWM has no vblank timing;
     * the frame then waits for vsync after it is presented instead.
     */
    suspend fun await(): Boolean {
        if (!dwmVblankTiming(timing)) return false
        val now = System.nanoTime()
        val period = timing[1]
        val render = expectedRenderNanos()
        // A frame as long as the refresh starts right after the vblank, as without a late start
        val lead = minOf(render + marginNanos, period - MIN_SLACK_NANOS)

        var vblank = now + timing[0]
        // One frame per vblank, and none for a vblank it can't be ready for
        if (vblank - lastVblank < period / 2 || vblank - now < render) vblank += period
        lastVblank = vblank

        val wait = vblank - lead - System.nanoTime()
        if (wait > MIN_WAIT_NANOS) {
            withContext(dispatcherToBlockOn) { sleepPrecisely(wait) }
        }
        return true
    }

    fun onRendered(nanos: Long) {
        renderNanos[renders % renderNanos.size] = nanos
        renders++
    }

    /** The 90th percentile of recent render times: a slow outlier shouldn't push every frame earlier. */
    private fun expectedRenderNanos(): Long {
        val count = minOf(renders, renderNanos.size)
        if (count == 0) return DEFAULT_RENDER_NANOS
        val sorted = renderNanos.copyOf(count).apply { sort() }
        return sorted[(count - 1) * 9 / 10]
    }

    private companion object {
        const val DEFAULT_RENDER_NANOS = 4_000_000L
        const val MIN_WAIT_NANOS = 200_000L
        const val MIN_SLACK_NANOS = 1_000_000L

        val marginNanos = ((System.getProperty("skiko.windows.lateFrameStart.marginMs")?.toDoubleOrNull() ?: 2.0) * 1e6).toLong()
    }
}

/** `-Dskiko.windows.frameTimes=true`: prints where frame time goes, every 240 frames. */
private class FrameTimes {
    private val stages = Array(4) { DoubleArray(240) }
    private var count = 0
    private var late = 0

    fun add(start: Long, updated: Long, drawn: Long, swapped: Long, finished: Long, startedLate: Boolean) {
        stages[0][count] = (updated - start) / 1e6
        stages[1][count] = (drawn - updated) / 1e6
        stages[2][count] = (swapped - drawn) / 1e6
        stages[3][count] = (finished - swapped) / 1e6
        if (startedLate) late++
        if (++count < stages[0].size) return
        val text = listOf("update", "draw", "swap", "glFinish").mapIndexed { i, name ->
            val sorted = stages[i].sortedArray()
            "$name p50=%.2f p90=%.2f max=%.2f".format(sorted[sorted.size / 2], sorted[sorted.size * 9 / 10], sorted.last())
        }
        println("[skiko frames] ${text.joinToString(" | ")} | late starts $late/$count")
        count = 0
        late = 0
    }
}

private external fun makeCurrent(device: Long, context: Long)
private external fun getDevice(platformInfo: Long): Long
private external fun createContext(device: Long, contentHandle:Long, transparency: Boolean): Long
private external fun deleteContext(context: Long)
private external fun setSwapInterval(interval: Int)
private external fun swapBuffers(device: Long)

// TODO according to https://bugs.chromium.org/p/chromium/issues/detail?id=467617 dwmFlush has lag 3 ms after vsync.
//  Maybe we should use D3DKMTWaitForVerticalBlankEvent? See also https://www.vsynctester.com/chromeisbroken.html
// TODO should we support Windows 7? DWM can be disabled on Windows 7.
//  it that case there will be a crash or just no frame limit (I don't know exactly).
private external fun dwmFlush()

/** Nanoseconds until the next vblank and the refresh period, into [timing]; false when DWM has no timing. */
private external fun dwmVblankTiming(timing: LongArray): Boolean

private external fun sleepPrecisely(nanos: Long)