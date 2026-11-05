package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.withContext
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.Direct3DContextHandler
import javax.swing.SwingUtilities

internal class Direct3DRedrawer(
    private val layer: SkiaLayer,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.DIRECT3D) {

    private val contextHandler = Direct3DContextHandler(layer)
    override val renderInfo: String get() = contextHandler.rendererInfo()
    override val frameCount: Long get() = contextHandler.frames
    override val gpuResourceCacheBytes: Long get() = contextHandler.gpuResourceCacheBytes
    override val gpuResourceCachePurgeableBytes: Long get() = contextHandler.gpuResourceCachePurgeableBytes

    override fun purgeGpuResources(notUsedForMillis: Long) = contextHandler.requestPurge(notUsedForMillis)

    private var drawLock = Any()
    private var isSwapChainInitialized = false

    @Volatile
    private var isFrameThrottlingEnabled = properties.isVsyncEnabled

    private var device: Long = 0L
        get() {
            if (field == 0L) {
                throw RenderException("DirectX12 device is not initialized or already disposed")
            }
            return field
        }

    val adapterName: String
    val adapterMemorySize: Long

    init {
        releaseRemovedDevices()
        val adapter = chooseAdapter(properties.adapterPriority.ordinal)
        if (adapter == 0L) {
            throw RenderException("Failed to choose DirectX12 adapter.")
        }
        adapterName = getAdapterName(adapter)
        adapterMemorySize = getAdapterMemorySize(adapter)
        onDeviceChosen(adapterName)
        device = createDirectXDevice(adapter, layer.contentHandle, layer.transparency)
            .takeIf { it != 0L } ?: throw RenderException("Failed to create DirectX12 device.")
    }

    private var isLateFrameStartEnabled = false
    private val lateFrameStart = LateFrameStart()

    private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
        if (layer.isShowing) {
            val lateStart = isFrameThrottlingEnabled && isLateFrameStartEnabled
            val startsLate = lateStart && lateFrameStart.await()
            val started = System.nanoTime()
            update()
            // A frame that started late is timed for its vblank already; waiting for the next one would undo that
            draw(withVsync = isFrameThrottlingEnabled && !startsLate)
            if (lateStart) lateFrameStart.onRendered(started, System.nanoTime())
        }
    }

    init {
        onContextInit()
        redrawers += this
    }

    override fun dispose() = synchronized(drawLock) {
        redrawers -= this
        frameDispatcher.cancel()
        contextHandler.dispose()
        disposeDevice(device)
        device = 0L
        super.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        checkDisposed()
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
        checkDisposed()
        update()
        inDrawScope {
            if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
                drawAndSwap(withVsync = SkikoProperties.windowsWaitForVsyncOnRedrawImmediately)
            }
        }
    }

    private suspend fun draw(withVsync: Boolean) {
        inDrawScope {
            withContext(dispatcherToBlockOn) {
                drawAndSwap(withVsync = withVsync)
            }
        }
    }

    private fun LayerDrawScope.drawAndSwap(withVsync: Boolean) = synchronized(drawLock) {
        if (isDisposed) {
            return
        }
        contextHandler.draw()
        swap(withVsync)
    }

    fun makeContext() = DirectContext(
        makeDirectXContext(device)
    )

    fun makeSurface(context: Long, width: Int, height: Int, surfaceProps: SurfaceProps, index: Int): Surface {
        return interopScope {
            val surface = makeDirectXSurface(device, context, width, height, toInterop(surfaceProps.packToIntArray()), index)
            if (surface == 0L) throw RenderException("No Direct3D surface for buffer $index")
            Surface(surface)
        }
    }

    fun changeSize(width: Int, height: Int): Boolean {
        return if (!isSwapChainInitialized) {
            initSwapChain(device, width, height, layer.transparency)
            isSwapChainInitialized = true
            true
        } else {
            checkDevice(resizeBuffers(device, width, height))
            false
        }
    }

    private fun swap(withVsync: Boolean) {
        if (!isSwapChainInitialized) {
            return
        }
        checkDevice(swap(device, withVsync))
    }

    /** [lostReason]: the native call's answer, 0 while the device is there. */
    private fun checkDevice(lostReason: Int) {
        if (lostReason != 0) throw RenderException("Direct3D device lost: 0x${lostReason.toUInt().toString(16)}")
    }

    fun getBufferIndex() = getBufferIndex(device)
    fun initFence() = initFence(device)

    // Called from native code
    private fun isAdapterSupported(name: String) = isVideoCardSupported(GraphicsApi.DIRECT3D, hostOs, name)

    private external fun chooseAdapter(adapterPriority: Int): Long
    private external fun createDirectXDevice(adapter: Long, contentHandle: Long, transparency: Boolean): Long
    private external fun makeDirectXContext(device: Long): Long
    private external fun makeDirectXSurface(device: Long, context: Long, width: Int, height: Int, surfacePropsIntArray: InteropPointer, index: Int): Long
    private external fun resizeBuffers(device: Long, width: Int, height: Int): Int
    private external fun swap(device: Long, isVsyncEnabled: Boolean): Int
    private external fun disposeDevice(device: Long)
    private external fun isDeviceRemoved(device: Long): Boolean
    private external fun getBufferIndex(device: Long): Int
    private external fun initSwapChain(device: Long, width: Int, height: Int, transparency: Boolean)
    private external fun initFence(device: Long)
    private external fun getAdapterName(adapter: Long): String
    private external fun getAdapterMemorySize(adapter: Long): Long

    private companion object {
        // On the event dispatch thread only, where layers make and dispose their redrawers
        private val redrawers = ArrayList<Direct3DRedrawer>()

        /**
         * A process has one Direct3D 12 device per adapter, which all its windows share. Once it's removed, by a driver
         * reset say, no new one can be made until every window has let go of it, and a window only finds out when it
         * next draws. So whichever makes a new device first takes the removed one from the others, and they follow.
         */
        private fun releaseRemovedDevices() {
            val removed = redrawers.filter { it.isDeviceRemoved(it.device) }
            removed.forEach { it.layer.releaseRedrawer() }
            if (removed.isNotEmpty()) SwingUtilities.invokeLater { removed.forEach { it.layer.restoreRedrawer() } }
        }
    }
}
