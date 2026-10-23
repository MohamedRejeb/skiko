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
    override val gpuResourceCacheBytes: Long get() = contextHandler.gpuResourceCacheBytes
    override val gpuResourceCachePurgeableBytes: Long get() = contextHandler.gpuResourceCachePurgeableBytes

    override fun purgeGpuResources(notUsedForMillis: Long) = contextHandler.requestPurge(notUsedForMillis)

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

    override fun skipLateFrameStart() {
        lateFrameStart.skipNext()
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
            // Requested while the previous frame waited for its late start, which drew it: waiting for vsync
            // here would only delay the next late start
            if (toRedraw.isEmpty()) return@FrameDispatcher
            val lateStartEnabled = toRedraw.all { it.isFrameThrottlingEnabled && it.isLateFrameStartEnabled }
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
            if (lateStartEnabled) lateFrameStart.onRendered(nanoTime, finished)

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
    private var period = DEFAULT_PERIOD_NANOS
    // Half of MIN_VALUE: subtracting it from a nanoTime can't overflow
    private var lastVblank = Long.MIN_VALUE / 2

    // The vblank the frame being rendered started late for, 0 when it started at once
    private var target = 0L
    private val missed = BooleanArray(8)
    private var lateStarts = 0

    @Volatile
    private var skipRequested = false

    /** Ends the current wait, or the next one, at once: for input that shouldn't wait, like a pen press. Any thread. */
    fun skipNext() {
        skipRequested = true
    }

    /**
     * Waits for the frame's start time. Returns false, without waiting, when DWM has no vblank timing;
     * the frame then waits for vsync after it is presented instead.
     */
    suspend fun await(): Boolean {
        target = 0L
        if (!dwmVblankTiming(timing)) return false
        if (skipRequested) {
            skipRequested = false
            return true
        }
        val now = System.nanoTime()
        val period = timing[1]
        this.period = period
        val sorted = sortedRenderNanos()
        // A high percentile: one slow frame shouldn't start every frame early. After a miss, the slowest
        // recent frame, until frames make their vblank again.
        val slow = when {
            sorted.isEmpty() -> DEFAULT_RENDER_NANOS
            missed.any { it } -> sorted.last()
            else -> sorted[(sorted.size - 1) * percentile / 100]
        }
        val typical = if (sorted.isEmpty()) DEFAULT_RENDER_NANOS else sorted[(sorted.size - 1) / 2]
        // A frame as long as the refresh starts right after the vblank, as without a late start
        val lead = minOf(slow + marginNanos, period - MIN_SLACK_NANOS)

        var vblank = now + timing[0]
        // One frame per vblank, and none for a vblank a typical frame can't be ready for
        if (vblank - lastVblank < period / 2 || vblank - now < typical) vblank += period
        val previousVblank = lastVblank
        lastVblank = vblank

        val start = vblank - lead
        if (start - System.nanoTime() > MIN_WAIT_NANOS) {
            val skipped = withContext(dispatcherToBlockOn) {
                // In slices, so a skipped wait ends within one
                while (!skipRequested) {
                    val left = start - System.nanoTime()
                    if (left <= 0) return@withContext false
                    sleepPrecisely(minOf(left, SKIP_CHECK_NANOS))
                }
                true
            }
            if (skipped) {
                skipRequested = false
                // Started early, so a later frame may still make this vblank
                lastVblank = previousVblank
                return true
            }
        }
        target = vblank
        return true
    }

    fun onRendered(started: Long, finished: Long) {
        // A frame longer than the refresh (a GC pause, say) tells nothing more about when to start the next
        renderNanos[renders % renderNanos.size] = minOf(finished - started, period)
        renders++
        if (target != 0L) missed[lateStarts++ % missed.size] = finished > target
        target = 0L
    }

    private fun sortedRenderNanos(): LongArray = renderNanos.copyOf(minOf(renders, renderNanos.size)).apply { sort() }

    private companion object {
        const val DEFAULT_RENDER_NANOS = 4_000_000L
        const val DEFAULT_PERIOD_NANOS = 16_666_667L
        const val MIN_WAIT_NANOS = 200_000L
        const val MIN_SLACK_NANOS = 1_000_000L
        const val SKIP_CHECK_NANOS = 1_000_000L

        val marginNanos = ((System.getProperty("skiko.windows.lateFrameStart.marginMs")?.toDoubleOrNull() ?: 2.0) * 1e6).toLong()
        val percentile = (System.getProperty("skiko.windows.lateFrameStart.percentile")?.toIntOrNull() ?: 90).coerceIn(50, 100)
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