package com.example.armeasure

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.armeasure.databinding.ActivityMainBinding
import com.example.armeasure.modules.CircleMeasurementModule
import com.example.armeasure.modules.LineMeasurementModule
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.TransformableNode
import java.util.Locale

// Enum to manage the current measurement mode
private enum class MeasureMode { LINE, CIRCLE }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var arFragment: CustomArFragment

    private var measureMode = MeasureMode.LINE
    private val anchors = mutableListOf<Anchor>()
    private var sphereRenderable: ModelRenderable? = null

    // Nodes for temporary and final visuals
    private var lineNode: Node? = null
    private val circleVisuals = mutableListOf<Node>()
    private var reticleNode: Node? = null
    private var reticleRenderable: ModelRenderable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as CustomArFragment

        binding.root.post {
            setupListeners()
            setupRenderables()
        }
        updateUiForMode()
    }

    private fun setupRenderables() {
        // Magenta sphere for placed points
        LineMeasurementModule.createSphere(this, 0.02f, com.google.ar.sceneform.rendering.Color(Color.MAGENTA)) { renderable ->
            sphereRenderable = renderable
        }

        // White circle for the aiming reticle
        LineMeasurementModule.createSphere(this, 0.015f, com.google.ar.sceneform.rendering.Color(Color.WHITE)) { renderable ->
            renderable.isShadowCaster = false
            renderable.isShadowReceiver = false
            reticleRenderable = renderable
        }
    }

    private fun setupListeners() {
        binding.btnReset.setOnClickListener { resetScene() }
        binding.btnPlacePoint.setOnClickListener { placePointFromCenter() }
        binding.btnMode.setOnClickListener { switchMode() }

        arFragment.arSceneView.scene.addOnUpdateListener {
            updateTracking()
        }
    }

    private fun switchMode() {
        measureMode = if (measureMode == MeasureMode.LINE) MeasureMode.CIRCLE else MeasureMode.LINE
        resetScene()
    }

    private fun updateUiForMode() {
        binding.tvDistance.text = "Mode: ${measureMode.name}"
    }

    private fun updateTracking() {
        val hit = performHitTest()
        val isPlaneDetected = hit != null

        if (isPlaneDetected) {
            if (reticleNode == null) {
                reticleNode = Node().apply {
                    renderable = reticleRenderable
                    setParent(arFragment.arSceneView.scene)
                }
            }
            reticleNode?.worldPosition = hit!!.hitPose.translation.let { Vector3(it[0], it[1], it[2]) }
            binding.btnPlacePoint.isEnabled = true
        } else {
            reticleNode?.setParent(null)
            reticleNode = null
            binding.btnPlacePoint.isEnabled = false
        }

        if (measureMode == MeasureMode.LINE && anchors.size == 1) {
            drawTemporaryLine()
        }
    }

    private fun performHitTest(): com.google.ar.core.HitResult? {
        val frame = arFragment.arSceneView.arFrame ?: return null
        val view = arFragment.view ?: return null
        val hits = frame.hitTest(view.width / 2f, view.height / 2f)
        return hits.firstOrNull {
            val trackable = it.trackable
            trackable is Plane && trackable.isPoseInPolygon(it.hitPose)
        }
    }

    private fun placePointFromCenter() {
        val maxPoints = if (measureMode == MeasureMode.LINE) 2 else 3
        if (anchors.size >= maxPoints) {
            Toast.makeText(this, "Max points reached for this mode.", Toast.LENGTH_SHORT).show()
            return
        }

        val hit = performHitTest()
        if (hit != null) {
            placeAnchor(hit.createAnchor())
        } else {
            Toast.makeText(this, "No surface found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun placeAnchor(anchor: Anchor) {
        sphereRenderable ?: return

        anchors.add(anchor)

        val anchorNode = AnchorNode(anchor).apply { setParent(arFragment.arSceneView.scene) }
        TransformableNode(arFragment.transformationSystem).apply {
            renderable = sphereRenderable
            setParent(anchorNode)
            arFragment.transformationSystem.selectNode(this)
        }

        when (measureMode) {
            MeasureMode.LINE -> if (anchors.size == 2) {
                drawFinalLine()
                val distanceMeters = LineMeasurementModule.calculateLineDistance(anchors[0].pose, anchors[1].pose)
                displayDistance(distanceMeters)
            }
            MeasureMode.CIRCLE -> if (anchors.size == 3) {
                calculateAndDrawCircle()
            }
        }
    }

    private fun calculateAndDrawCircle() {
        val p1 = anchors[0].pose.translation.let { Vector3(it[0], it[1], it[2]) }
        val p2 = anchors[1].pose.translation.let { Vector3(it[0], it[1], it[2]) }
        val p3 = anchors[2].pose.translation.let { Vector3(it[0], it[1], it[2]) }

        val result = CircleMeasurementModule.calculateCircleFromThreePoints(p1, p2, p3)
        if (result != null) {
            val diameterCm = (result.radius * 2) * 100
            binding.tvDistance.text = String.format(Locale.US, "Diameter: %.1f cm", diameterCm)
            drawCircleResult(result.center, result.radius, p1)
        } else {
            binding.tvDistance.text = "Error: Points are in a line"
        }
    }

    private fun drawCircleResult(center: Vector3, radius: Float, pointOnCircle: Vector3) {
        val cyanColor = com.google.ar.sceneform.rendering.Color(Color.CYAN)
        // Draw a sphere at the center
        LineMeasurementModule.createSphere(this, 0.025f, cyanColor) { renderable ->
            val centerNode = Node().apply {
                this.renderable = renderable
                worldPosition = center
                setParent(arFragment.arSceneView.scene)
            }
            circleVisuals.add(centerNode)
        }
        // Draw a line for the radius
        LineMeasurementModule.createLineNode(this, center, pointOnCircle, cyanColor) { node ->
            node.setParent(arFragment.arSceneView.scene)
            circleVisuals.add(node)
        }
    }

    private fun drawTemporaryLine() {
        val startAnchor = anchors.firstOrNull() ?: return
        val endPosition = reticleNode?.worldPosition ?: return

        lineNode?.setParent(null)

        val startPos = startAnchor.pose.translation
        val startVector = Vector3(startPos[0], startPos[1], startPos[2])

        LineMeasurementModule.createLineNode(this, startVector, endPosition, com.google.ar.sceneform.rendering.Color(Color.WHITE)) { node ->
            node.setParent(arFragment.arSceneView.scene)
            this.lineNode = node
        }

        val tempDistance = Vector3.subtract(startVector, endPosition).length()
        displayDistance(tempDistance)
    }

    private fun drawFinalLine() {
        val startPos = anchors[0].pose.translation
        val endPos = anchors[1].pose.translation
        val startVector = Vector3(startPos[0], startPos[1], startPos[2])
        val endVector = Vector3(endPos[0], endPos[1], endPos[2])

        lineNode?.setParent(null)

        LineMeasurementModule.createLineNode(this, startVector, endVector, com.google.ar.sceneform.rendering.Color(Color.MAGENTA)) { node ->
            node.setParent(arFragment.arSceneView.scene)
            this.lineNode = node
        }
    }

    private fun displayDistance(meters: Float) {
        val centimeters = meters * 100
        val inches = meters * 39.37f
        binding.tvDistance.text = String.format(Locale.US, "%.1f cm / %.1f in", centimeters, inches)
    }

    private fun resetScene() {
        // Clear all anchors from the AR session
        anchors.forEach { it.detach() }
        anchors.clear()

        // Remove temporary line and circle visuals
        lineNode?.setParent(null)
        lineNode = null
        circleVisuals.forEach { it.setParent(null) }
        circleVisuals.clear()

        // Remove all nodes added to the scene
        val children = ArrayList(arFragment.arSceneView.scene.children)
        for (node in children) {
            if (node is AnchorNode) {
                node.anchor?.detach()
                node.setParent(null)
            }
        }
        updateUiForMode()
    }
}
