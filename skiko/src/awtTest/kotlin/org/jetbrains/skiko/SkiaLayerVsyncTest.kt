package org.jetbrains.skiko

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skiko.redrawer.Redrawer
import org.jetbrains.skiko.util.uiTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkiaLayerVsyncTest {
    @Test
    fun `switches start from properties`() {
        onEdt {
            val vsyncOn = SkiaLayer(properties = SkiaLayerProperties(isVsyncEnabled = true))
            assertTrue(vsyncOn.isFrameThrottlingEnabled)
            assertTrue(vsyncOn.isPresentSyncEnabled)
            assertTrue(vsyncOn.isVsyncEnabled)

            val vsyncOff = SkiaLayer(properties = SkiaLayerProperties(isVsyncEnabled = false))
            assertFalse(vsyncOff.isFrameThrottlingEnabled)
            assertFalse(vsyncOff.isPresentSyncEnabled)
            assertFalse(vsyncOff.isVsyncEnabled)
        }
    }

    @Test
    fun `switches change independently and properties keep the initial value`() {
        onEdt {
            val layer = SkiaLayer(properties = SkiaLayerProperties(isVsyncEnabled = true))

            layer.isPresentSyncEnabled = false
            assertTrue(layer.isFrameThrottlingEnabled)
            assertFalse(layer.isVsyncEnabled)

            layer.isVsyncEnabled = false
            assertFalse(layer.isFrameThrottlingEnabled)
            assertFalse(layer.isPresentSyncEnabled)

            layer.isVsyncEnabled = true
            assertTrue(layer.isFrameThrottlingEnabled)
            assertTrue(layer.isPresentSyncEnabled)

            assertTrue(layer.properties.isVsyncEnabled)
        }
    }

    @Test
    fun `switches must be set on the event dispatch thread`() {
        val layer = onEdt { SkiaLayer() }
        assertFailsWith<IllegalStateException> { layer.isFrameThrottlingEnabled = false }
        assertFailsWith<IllegalStateException> { layer.isPresentSyncEnabled = false }
        assertFailsWith<IllegalStateException> { layer.isVsyncEnabled = false }
    }

    @Test(timeout = 60000)
    fun `switches are forwarded to the redrawer`() = uiTest {
        val redrawers = mutableListOf<RecordingRedrawer>()
        val window = UiTestWindow(
            properties = SkiaLayerProperties(isVsyncEnabled = true),
            renderFactory = { layer, _, _, _ -> RecordingRedrawer(layer).also(redrawers::add) }
        )
        try {
            window.setSize(400, 300)
            window.isVisible = true
            val redrawer = redrawers.single()
            assertEquals(listOf("throttling=true", "presentSync=true"), redrawer.calls)

            window.layer.isPresentSyncEnabled = false
            window.layer.isFrameThrottlingEnabled = false
            assertEquals(
                listOf("throttling=true", "presentSync=true", "presentSync=false", "throttling=false"),
                redrawer.calls
            )
        } finally {
            window.dispose()
        }
    }

    @Test(timeout = 60000)
    fun `recreated redrawer starts from the current switches`() = uiTest {
        val redrawers = mutableListOf<RecordingRedrawer>()
        val window = UiTestWindow(
            properties = SkiaLayerProperties(isVsyncEnabled = true),
            renderFactory = { layer, _, _, _ -> RecordingRedrawer(layer).also(redrawers::add) }
        )
        try {
            window.setSize(400, 300)
            window.isVisible = true
            window.layer.isPresentSyncEnabled = false

            // Re-adding the layer disposes its redrawer and creates a new one
            window.contentPane.remove(window.layer)
            window.contentPane.add(window.layer)

            assertEquals(2, redrawers.size)
            assertEquals(listOf("throttling=true", "presentSync=false"), redrawers.last().calls)
        } finally {
            window.dispose()
        }
    }

    @Test(timeout = 60000)
    fun `fallback redrawer starts from the current switches`() = uiTest(
        excludeRenderApis = listOf(GraphicsApi.SOFTWARE_COMPAT)
    ) {
        val failingApi = renderApi
        val fallbackRedrawers = mutableListOf<RecordingRedrawer>()
        val window = UiTestWindow(
            properties = SkiaLayerProperties(isVsyncEnabled = true),
            renderFactory = { layer, api, _, _ ->
                if (api == failingApi) {
                    FailingOnDrawRedrawer(layer)
                } else {
                    RecordingRedrawer(layer).also(fallbackRedrawers::add)
                }
            }
        )
        try {
            window.layer.isFrameThrottlingEnabled = false
            window.setSize(400, 300)
            window.isVisible = true
            delay(1000)

            assertEquals(listOf("throttling=false", "presentSync=true"), fallbackRedrawers.single().calls)
        } finally {
            window.dispose()
        }
    }

    @Test(timeout = 60000)
    fun `rendering continues while vsync is switched at runtime`() = uiTest {
        val framesPerStep = 30
        var drawCount = 0
        val onDrawCompleted = CompletableDeferred<Unit>()
        val window = UiTestWindow(properties = SkiaLayerProperties(isVsyncEnabled = false))
        try {
            window.setSize(400, 200)
            window.layer.renderDelegate = SkikoRenderDelegate { _, _, _, _ ->
                drawCount++
                when (drawCount) {
                    framesPerStep -> window.layer.isVsyncEnabled = true
                    2 * framesPerStep -> window.layer.isPresentSyncEnabled = false
                    3 * framesPerStep -> window.layer.isVsyncEnabled = false
                    4 * framesPerStep -> onDrawCompleted.complete(Unit)
                }
                if (!onDrawCompleted.isCompleted) window.layer.needRender()
            }
            window.isUndecorated = true
            window.isVisible = true

            onDrawCompleted.await()
            assertRenderApiFor(window.layer)
        } finally {
            window.dispose()
        }
    }

    private fun <T> onEdt(block: () -> T): T = runBlocking(MainUIDispatcher) { block() }

    private open class RecordingRedrawer(protected val layer: SkiaLayer) : Redrawer {
        val calls = mutableListOf<String>()

        override fun setFrameThrottlingEnabled(enabled: Boolean) {
            calls += "throttling=$enabled"
        }

        override fun setPresentSyncEnabled(enabled: Boolean) {
            calls += "presentSync=$enabled"
        }

        override fun dispose() = Unit
        override fun needRender(throttledToVsync: Boolean) = Unit
        override fun renderImmediately() = Unit
        override fun update(nanoTime: Long) = layer.update(nanoTime)
        override val renderInfo: String get() = ""
        override fun isTransparentBackgroundSupported() = false
    }

    private class FailingOnDrawRedrawer(layer: SkiaLayer) : RecordingRedrawer(layer) {
        private val frameDispatcher = FrameDispatcher(MainUIDispatcher) {
            layer.inDrawScope { throw RenderException() }
        }

        override fun needRender(throttledToVsync: Boolean) = frameDispatcher.scheduleFrame()
        override fun dispose() = frameDispatcher.cancel()
    }
}
