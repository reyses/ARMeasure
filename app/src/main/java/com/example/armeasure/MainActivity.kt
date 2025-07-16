package com.example.armeasure

import android.graphics.Color // Import Android's Color class
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.armeasure.databinding.ActivityMainBinding
import com.example.armeasure.rendering.RenderHelper
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import java.util.Locale // Import Locale for String formatting

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var arFragment: ArFragment

    private val anchors = mutableListOf<Anchor>()
    private var sphereRenderable: ModelRenderable? = null
    private var lineNode: Node? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The 'cast can never succeed' warning is often a linter bug and can be ignored if the app runs.
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        setupRenderables()
        setupListeners()
    }

    private fun setupRenderables() {
        // FIXED: Used a valid color constructor
        val sphereColor = com.google.ar.sceneform.rendering.Color(Color.MAGENTA)
        RenderHelper.createSphere(this, 0.02f, sphereColor) { renderable ->
            sphereRenderable = renderable
        }
    }

    private fun setupListeners() {
        binding.btnReset.setOnClickListener { resetScene() }

        arFragment.setOnTapArPlaneListener { hitResult, _, _ ->
            if (anchors.size < 2) {
                placeAnchor(hitResult)
            }
        }

        arFragment.arSceneView.scene.addOnUpdateListener {
            if (anchors.size == 1) {
                drawTemporaryLine()
            }
        }
    }

    private fun placeAnchor(hitResult: HitResult) {
        sphereRenderable ?: return

        val anchor = hitResult.createAnchor()
        anchors.add(anchor)

        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(arFragment.arSceneView.scene)

        val sphereNode = TransformableNode(arFragment.transformationSystem)
        sphereNode.renderable = sphereRenderable
        sphereNode.setParent(anchorNode)

        // FIXED: Used transformationSystem.selectNode() instead of the old selectExtension
        arFragment.transformationSystem.selectNode(sphereNode)

        if (anchors.size == 2) {
            val distanceMeters = MeasurementModule.calculateDistance(anchors[0].pose, anchors[1].pose)
            displayDistance(distanceMeters)
            drawFinalLine()
        }
    }

    private fun drawTemporaryLine() {
        val startAnchor = anchors.firstOrNull() ?: return
        val currentCameraPose = arFragment.arSceneView.arFrame?.camera?.pose ?: return

        lineNode?.setParent(null) // Remove previous line

        val startPos = startAnchor.pose.translation
        val currentPos = currentCameraPose.translation
        val startVector = Vector3(startPos[0], startPos[1], startPos[2])
        val currentVector = Vector3(currentPos[0], currentPos[1], currentPos[2])

        // FIXED: Works with the new RenderHelper to create and place a line node
        val whiteColor = com.google.ar.sceneform.rendering.Color(Color.WHITE)
        RenderHelper.createLineNode(this, startVector, currentVector, whiteColor) { node ->
            node.setParent(arFragment.arSceneView.scene)
            this.lineNode = node
        }

        val distanceMeters = MeasurementModule.calculateDistance(startAnchor.pose, currentCameraPose)
        displayDistance(distanceMeters)
    }

    private fun drawFinalLine() {
        val startPos = anchors[0].pose.translation
        val endPos = anchors[1].pose.translation
        val startVector = Vector3(startPos[0], startPos[1], startPos[2])
        val endVector = Vector3(endPos[0], endPos[1], endPos[2])

        lineNode?.setParent(null) // Clear temporary line

        // FIXED: Works with the new RenderHelper to create the final line
        val magentaColor = com.google.ar.sceneform.rendering.Color(Color.MAGENTA)
        RenderHelper.createLineNode(this, startVector, endVector, magentaColor) { node ->
            node.setParent(arFragment.arSceneView.scene)
            this.lineNode = node // Keep reference to remove it on reset
        }
    }

    private fun displayDistance(meters: Float) {
        val centimeters = meters * 100
        val inches = meters * 39.37f
        // FIXED: Added Locale.US to prevent formatting warnings
        binding.tvDistance.text = String.format(Locale.US, "%.2f cm / %.2f in", centimeters, inches)
    }

    private fun resetScene() {
        anchors.forEach { it.detach() }
        anchors.clear()

        val children = ArrayList(arFragment.arSceneView.scene.children)
        children.forEach { node ->
            if (node is AnchorNode) {
                node.anchor?.detach()
            }
            if (node.renderable != null) {
                // This removes spheres and lines
                node.setParent(null)
            }
        }

        lineNode = null
        binding.tvDistance.text = ""
    }
}