package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.*
import kotlin.concurrent.Volatile

internal abstract class ContextHandler(
    protected val layer: SkiaLayer,
    private val drawContent: Canvas.() -> Unit
) {
    protected var context: DirectContext? = null
    protected var renderTarget: BackendRenderTarget? = null
    protected var surface: Surface? = null
    protected var canvas: Canvas? = null

    // Asked for from any thread and done after the next frame, with the context current: free the GPU resources
    // nothing has used for this many milliseconds, or nothing when negative
    @Volatile
    private var purgeNotUsedForMillis = -1L

    /** Bytes of GPU resources the context's cache held after the last frame, or -1 before one. */
    @Volatile
    var gpuResourceCacheBytes = -1L
        private set

    /** The part of [gpuResourceCacheBytes] nothing used after the last frame, which a purge can free. */
    @Volatile
    var gpuResourceCachePurgeableBytes = -1L
        private set

    /** Frees the cached GPU resources nothing has used for [notUsedForMillis] after the next frame. */
    fun requestPurge(notUsedForMillis: Long) {
        purgeNotUsedForMillis = notUsedForMillis
    }

    protected abstract fun initContext(): Boolean
    protected abstract fun LayerDrawScope.initCanvas()

    protected open fun flush(scope: LayerDrawScope) {
        context?.flush()
    }

    open fun dispose() {
        disposeCanvas()
        context?.close()
    }

    protected open fun disposeCanvas() {
        surface?.close()
        renderTarget?.close()
    }

    open fun rendererInfo(): String {
        return "GraphicsApi: ${layer.renderApi}\n" +
                "OS: ${hostOs.id} ${hostArch.id}\n"
    }

    // throws RenderException if initialization of graphic context was not successful
    fun LayerDrawScope.draw() {
        if (!initContext()) {
            throw RenderException("Cannot init graphic context")
        }
        initCanvas()
        canvas?.runRestoringState {
            clear(Color.TRANSPARENT)
            drawContent()
        }
        flush(this)
        context?.let { context ->
            val purge = purgeNotUsedForMillis
            if (purge >= 0) {
                purgeNotUsedForMillis = -1
                context.performDeferredCleanup(purge)
            }
            gpuResourceCacheBytes = context.resourceCacheUsage
            gpuResourceCachePurgeableBytes = context.resourceCachePurgeableBytes
        }
    }
}

