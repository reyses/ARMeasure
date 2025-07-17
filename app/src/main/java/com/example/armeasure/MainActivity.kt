package com.example.armeasure

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.armeasure.databinding.ActivityMainBinding
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.TransformableNode
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var arFragment: CustomArFragment

    private val anchors = mutableListOf<Anchor>()
    private var sphereRenderable: ModelRenderable? = null
    private var lineNode: Node? = null

    // New node for our aiming reticle
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
    }

    private fun setupRenderables() {
        // Create the magenta sphere for placed points
        LineMeasurementModule.createSphere(this, 0.02f, com.google.ar.sceneform.rendering.Color(Color.MAGENTA)) { renderable ->
            sphereRenderable = renderable
        }

        // Create the white circle for the aiming reticle
        LineMeasurementModule.createSphere(this, 0.015f, com.google.ar.sceneform.rendering.Color(Color.WHITE)) { renderable ->
            // Flatten the sphere to make it a flat circle
            renderable.isShadowCaster = false
            renderable.isShadowReceiver = false
            reticleRenderable = renderable
        }
    }

    private fun setupListeners() {
        binding.btnReset.setOnClickListener { resetScene() }
        binding.btnPlacePoint.setOnClickListener { placePointFromCenter() }

        arFragment.arSceneView.scene.addOnUpdateListener {
            // This is called on every frame
            updateTracking()
        }
    }

    private fun updateTracking() {
        // Check if ARCore is tracking and a plane is detected
        val isTracking = arFragment.arSceneView.arFrame?.camera?.trackingState == com.google.ar.core.TrackingState.TRACKING
        val hit = performHitTest()
        val isPlaneDetected = hit != null

        if (isTracking && isPlaneDetected) {
            // If the reticle node doesn't exist, create it
            if (reticleNode == null) {
                reticleNode = Node().apply {
                    renderable = reticleRenderable
                    setParent(arFragment.arSceneView.scene)
                }
            }
            // Move the reticle to the hit location
            reticleNode?.worldPosition = hit!!.hitPose.translation.let { Vector3(it[0], it[1], it[2]) }
            binding.btnPlacePoint.isEnabled = true
        } else {
            // Hide the reticle if no plane is detected
            reticleNode?.setParent(null)
            reticleNode = null
            binding.btnPlacePoint.isEnabled = false
        }

        // Update the temporary line if we are in the middle of a measurement
        if (anchors.size == 1) {
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
        if (anchors.size >= 2) return

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

        if (anchors.size == 2) {
            val distanceMeters = LineMeasurementModule.calculateLineDistance(anchors[0].pose, anchors[1].pose)
            displayDistance(distanceMeters)
            drawFinalLine()
        }
    }

    private fun drawTemporaryLine() {
        val startAnchor = anchors.firstOrNull() ?: return

        // Use the reticle's position as the temporary end point for a smoother line
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
        binding.tvDistance.text = String.format(Locale.US, "%.2f cm / %.2f in", centimeters, inches)
    }

    private fun resetScene() {
        anchors.forEach { it.detach() }
        anchors.clear()

        lineNode?.setParent(null)
        lineNode = null

        val children = ArrayList(arFragment.arSceneView.scene.children)
        children.forEach { node ->
            if (node is AnchorNode) {
                node.anchor?.detach()
                node.setParent(null)
            }
        }
        binding.tvDistance.text = "Point your camera at a surface"
    }
}