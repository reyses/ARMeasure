package com.example.arruler

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arruler.ar.ArRenderer
import com.example.arruler.ar.ArSessionController
import com.example.arruler.databinding.ActivityMainBinding
import com.example.arruler.measure.MeasureMode
import com.example.arruler.measure.MeasureState
import com.example.arruler.measure.MeasurementSession
import com.example.arruler.measure.Phase
import com.example.arruler.ui.AreaControls
import com.example.arruler.ui.ControlsBar
import com.example.arruler.ui.MeasureOverlay
import kotlinx.coroutines.launch

/** Thin wiring: AR session + measurement state + renderer + Compose UI. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ar: ArSessionController
    private lateinit var renderer: ArRenderer
    private val session = MeasurementSession()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ar = ArSessionController(binding.arSceneView)
        renderer = ArRenderer(binding.arSceneView)
        ar.onFrame = ::onArFrame
        ar.onTap = ::onArTap

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { s ->
                    renderMeasureState(s)
                }
            }
        }

        binding.composeView.setContent {
            val state by session.state.collectAsState()
            val hasSurface by ar.hasSurface.collectAsState()
            MaterialTheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    MeasureOverlay(state, hasSurface)
                    ControlsBar(
                        state = state,
                        onToggleUnit = session::nextUnit,
                        onMainButton = ::onMainButton,
                        onClear = ::clearAll,
                    )
                    AreaControls(state, ::onSetMode, ::onAreaClose, ::onAreaUndo, ::onAreaHeight)
                }
            }
        }
    }

    override fun onDestroy() {
        renderer.release()
        ar.releaseAnchors()
        super.onDestroy()
    }

    private fun onArFrame() {
        areaFrame()
        if (session.state.value.phase == Phase.MEASURING) {
            ar.hitTestCenter()?.let { session.setLive(it.point) }
        }
    }

    private fun onArTap(x: Float, y: Float) {
        val s = session.state.value
        if (s.mode == MeasureMode.AREA) return
        if (s.phase != Phase.MEASURING || s.points.isEmpty()) return
        val hit = ar.hitTest(x, y) ?: return
        haptic()
        session.addPoint(ar.createAnchor(hit))
    }

    private fun onMainButton() {
        if (session.state.value.mode == MeasureMode.AREA) return onAreaShutter()
        if (session.state.value.phase == Phase.MEASURING) {
            session.stop()
            return
        }
        if (session.state.value.points.isNotEmpty()) clearAll()
        val hit = ar.hitTestCenter() ?: return
        haptic()
        session.start(ar.createAnchor(hit))
    }

    private fun clearAll() {
        ar.releaseAnchors()
        session.clear()
    }

    // ---- AREA mode (additive) ----

    private fun renderMeasureState(s: MeasureState) {
        if (s.mode == MeasureMode.AREA) {
            renderer.render(s.displayPoints, closed = s.closed, final = s.closed)
            val hp = s.heightPoint
            val area = s.areaMeasurement
            renderer.renderExtra(if (s.closed && hp != null && area != null) listOf(area.centroid() to hp) else emptyList())
        } else {
            renderer.render(s.displayPoints, final = s.phase == Phase.FINISHED)
            renderer.renderExtra(emptyList())
        }
    }

    private fun areaFrame() {
        val s = session.state.value
        if (s.mode == MeasureMode.AREA && s.heightActive) {
            ar.hitTestCenter()?.let { session.setHeightLive(it.point) }
        }
    }

    private fun onSetMode(mode: MeasureMode) {
        if (session.state.value.mode == mode) return
        clearAll()
        session.setMode(mode)
    }

    private fun onAreaShutter() {
        val s = session.state.value
        val hit = ar.hitTestCenter() ?: return
        when {
            s.heightActive -> { haptic(); session.commitHeight(ar.createAnchor(hit)) }
            s.closed -> { clearAll(); haptic(); session.start(ar.createAnchor(hit)) }
            s.phase == Phase.MEASURING && s.points.isNotEmpty() -> { haptic(); session.addPoint(ar.createAnchor(hit)) }
            else -> { clearAll(); haptic(); session.start(ar.createAnchor(hit)) }
        }
    }

    private fun onAreaClose() = session.closePolygon()

    private fun onAreaUndo() = session.undo()

    private fun onAreaHeight() = session.startHeight()

    private fun haptic() {
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}
