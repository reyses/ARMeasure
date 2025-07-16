package com.example.armeasure

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.armeasure.databinding.ActivityMainBinding
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as CustomArFragment

        // This is the fix: We post the setup to the view's message queue.
        // This makes it run after the layout is fully created, avoiding the crash.
        binding.root.post {
            setupListeners()
            setupRenderables()
        }
    }

    private fun setupRenderables() {
        val sphereColor = com.google.ar.sceneform.rendering.Color(Color.MAGENTA)
        LineMeasurementModule.createSphere(this, 0.02f, sphereColor) { renderable ->
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
        val currentCameraPose = arFragment.arSceneView.arFrame?.camera?.pose ?: return

        lineNode?.setParent(null)

        val startPos = startAnchor.pose.translation
        val currentPos = currentCameraPose.translation
        val startVector = Vector3(startPos[0], startPos[1], startPos[2])
        val currentVector = Vector3(currentPos[0], currentPos[1], currentPos[2])

        val whiteColor = com.google.ar.sceneform.rendering.Color(Color.WHITE)
        LineMeasurementModule.createLineNode(this, startVector, currentVector, whiteColor) { node ->
            node.setParent(arFragment.arSceneView.scene)
            this.lineNode = node
        }

        val distanceMeters = LineMeasurementModule.calculateLineDistance(startAnchor.pose, currentCameraPose)
        displayDistance(distanceMeters)
    }

    private fun drawFinalLine() {
        val startPos = anchors[0].pose.translation
        val endPos = anchors[1].pose.translation
        val startVector = Vector3(startPos[0], startPos[1], startPos[2])
        val endVector = Vector3(endPos[0], endPos[1], endPos[2])

        lineNode?.setParent(null)

        val magentaColor = com.google.ar.sceneform.rendering.Color(Color.MAGENTA)
        LineMeasurementModule.createLineNode(this, startVector, endVector, magentaColor) { node ->
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
        binding.tvDistance.text = ""
    }
}