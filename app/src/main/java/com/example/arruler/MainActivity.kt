package com.example.arruler

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.arruler.databinding.ActivityMainBinding
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var arFragment: ArFragment

    private var startAnchor: Anchor? = null
    private var endAnchor: Anchor? = null

    private var startNode: AnchorNode? = null
    private var endNode: AnchorNode? = null
    private var lineNode: Node? = null

    private var sphereRenderable: ModelRenderable? = null
    private var yellowMaterial: Material? = null
    private var currentDistanceMeters: Float = 0f

    private val decimalFormat = DecimalFormat("0.0", DecimalFormatSymbols(Locale.US)).apply {
        roundingMode = RoundingMode.HALF_UP
    }
    private val tempStart = Vector3()
    private val tempEnd = Vector3()
    private val tempDiff = Vector3()
    private val vectorUp = Vector3.up()

    private var isMeasuring = false
    private var unit = MeasurementUnit.CM

    private val distanceFormatter = DistanceFormatter()

    enum class MeasurementUnit {
        CM, INCH, M, FT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        arFragment.arSceneView.planeRenderer.isEnabled = false

        setupRenderable()
        setupListeners()
        updateUI()
    }

    private fun setupRenderable() {
        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(Color.RED))
            .thenAccept { material ->
                sphereRenderable = ShapeFactory.makeSphere(0.015f, Vector3.zero(), material)
            }

        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(Color.YELLOW))
            .thenAccept { material ->
                yellowMaterial = material
            }
    }

    private fun setupListeners() {
        binding.btnMeasure.setOnClickListener {
            if (!isMeasuring) {
                startMeasurement()
            } else {
                stopMeasurement()
            }
        }

        binding.btnClear.setOnClickListener {
            clearMeasurement()
        }

        binding.btnUnit.setOnClickListener {
            switchUnit()
        }

        arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
            if (isMeasuring && startAnchor != null) {
                updateLiveMeasurement()
            }
        }

        arFragment.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            if (isMeasuring && startAnchor != null) {
                placeEndPoint(hitResult)
            }
        }
    }

    private fun startMeasurement() {
        val hitResult = performHitTest() ?: return

        startAnchor = hitResult.createAnchor()
        startNode = AnchorNode(startAnchor).apply {
            setParent(arFragment.arSceneView.scene)
        }

        Node().apply {
            renderable = sphereRenderable
            setParent(startNode)
        }

        isMeasuring = true
        updateUI()
    }

    private fun placeEndPoint(hitResult: HitResult) {
        endAnchor = hitResult.createAnchor()
        endNode = AnchorNode(endAnchor).apply {
            setParent(arFragment.arSceneView.scene)
        }

        Node().apply {
            renderable = sphereRenderable
            setParent(endNode)
        }

        drawFinalLine()
        stopMeasurement()
    }

    private fun stopMeasurement() {
        isMeasuring = false
        updateUI()
    }

    private fun updateLiveMeasurement() {
        val hitResult = performHitTest() ?: return
        val hitPose = hitResult.hitPose

        val startPos = startAnchor?.pose?.translation ?: return
        val endPos = hitPose.translation

        val dx = endPos[0] - startPos[0]
        val dy = endPos[1] - startPos[1]
        val dz = endPos[2] - startPos[2]

        currentDistanceMeters = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

        drawTemporaryLine(Vector3(startPos[0], startPos[1], startPos[2]),
                         Vector3(endPos[0], endPos[1], endPos[2]),
                         currentDistanceMeters)
        tempStart.set(startPos[0], startPos[1], startPos[2])
        tempEnd.set(endPos[0], endPos[1], endPos[2])

        drawTemporaryLine(tempStart, tempEnd)

        updateDistanceDisplay()
    }

    private fun drawTemporaryLine(start: Vector3, end: Vector3, distance: Float) {
        lineNode?.setParent(null)

        val difference = Vector3.subtract(end, start)
        val directionFromTopToBottom = if (distance > 0) difference.scaled(1.0f / distance) else Vector3.zero()
        tempDiff.set(end.x - start.x, end.y - start.y, end.z - start.z)
        val length = tempDiff.length()

        if (length != 0.0f) {
            tempDiff.set(tempDiff.x / length, tempDiff.y / length, tempDiff.z / length)
        }

        val rotationFromAToB = com.google.ar.sceneform.math.Quaternion.lookRotation(
            tempDiff,
            vectorUp
        )

        val material = yellowMaterial ?: return
        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(Color.YELLOW))
            .thenAccept { material ->
                val lineRenderable = ShapeFactory.makeCylinder(
                    0.003f,
                    distance,
                    Vector3(0f, distance / 2, 0f),
                    length,
                    Vector3(0f, length / 2, 0f),
                    material
                )

        val lineRenderable = ShapeFactory.makeCylinder(
            0.003f,
            difference.length(),
            Vector3(0f, difference.length() / 2, 0f),
            material
        )

        lineNode = Node().apply {
            setParent(arFragment.arSceneView.scene)
            renderable = lineRenderable
            worldPosition = start
            worldRotation = rotationFromAToB
        }
    }

    private fun drawFinalLine() {
        val startPos = startAnchor?.pose?.translation ?: return
        val endPos = endAnchor?.pose?.translation ?: return

        val start = Vector3(startPos[0], startPos[1], startPos[2])
        val end = Vector3(endPos[0], endPos[1], endPos[2])

        val difference = Vector3.subtract(end, start)
        val directionFromTopToBottom = difference.normalized()
        val rotationFromAToB = com.google.ar.sceneform.math.Quaternion.lookRotation(
            directionFromTopToBottom,
            Vector3.up()
        )

        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(Color.RED))
            .thenAccept { material ->
                val lineRenderable = ShapeFactory.makeCylinder(
                    0.005f,
                    difference.length(),
                    Vector3(0f, difference.length() / 2, 0f),
                    material
                )

                lineNode?.setParent(null)
                lineNode = Node().apply {
                    setParent(arFragment.arSceneView.scene)
                    renderable = lineRenderable
                    worldPosition = start
                    worldRotation = rotationFromAToB
                }
            }

        currentDistanceMeters = difference.length()
        updateDistanceDisplay()
    }

    private fun performHitTest(): HitResult? {
        val frame = arFragment.arSceneView.arFrame ?: return null
        val view = arFragment.view ?: return null

        val hits = frame.hitTest(view.width / 2f, view.height / 2f)
        return hits.firstOrNull { hitResult ->
            val trackable = hitResult.trackable
            trackable is Plane && trackable.isPoseInPolygon(hitResult.hitPose)
        }
    }

    private fun clearMeasurement() {
        startNode?.anchor?.detach()
        endNode?.anchor?.detach()

        startNode?.setParent(null)
        endNode?.setParent(null)
        lineNode?.setParent(null)

        startAnchor = null
        endAnchor = null
        startNode = null
        endNode = null
        lineNode = null

        currentDistanceMeters = 0f
        isMeasuring = false

        updateUI()
        updateDistanceDisplay()
    }

    private fun switchUnit() {
        unit = when (unit) {
            MeasurementUnit.CM -> MeasurementUnit.INCH
            MeasurementUnit.INCH -> MeasurementUnit.M
            MeasurementUnit.M -> MeasurementUnit.FT
            MeasurementUnit.FT -> MeasurementUnit.CM
        }
        updateUI()
        updateDistanceDisplay()
    }

    private fun updateDistanceDisplay() {
        val value = when (unit) {
            MeasurementUnit.CM -> currentDistanceMeters * 100
            MeasurementUnit.INCH -> currentDistanceMeters * 39.37f
            MeasurementUnit.M -> currentDistanceMeters
            MeasurementUnit.FT -> currentDistanceMeters * 3.281f
        }

        val unitText = when (unit) {
            MeasurementUnit.CM -> "cm"
            MeasurementUnit.INCH -> "in"
            MeasurementUnit.M -> "m"
            MeasurementUnit.FT -> "ft"
        }

        if (currentDistanceMeters > 0) {
            binding.tvDistance.text = distanceFormatter.format(value, unitText)
            binding.tvDistance.text = "${decimalFormat.format(value)} $unitText"
        } else {
            binding.tvDistance.text = "—"
        }
    }

    private fun updateUI() {
        if (isMeasuring) {
            binding.btnMeasure.text = "Tap to Place End"
            binding.btnMeasure.setBackgroundColor(Color.parseColor("#FF9500"))
            binding.tvInstructions.text = "Move phone to measure, tap screen to lock"
        } else if (startAnchor != null) {
            binding.btnMeasure.text = "New Measurement"
            binding.btnMeasure.setBackgroundColor(Color.parseColor("#007AFF"))
            binding.tvInstructions.text = "Measurement complete"
        } else {
            binding.btnMeasure.text = "Start Measuring"
            binding.btnMeasure.setBackgroundColor(Color.parseColor("#34C759"))
            binding.tvInstructions.text = "Point at a surface to begin"
        }

        binding.btnUnit.text = when (unit) {
            MeasurementUnit.CM -> "CM"
            MeasurementUnit.INCH -> "IN"
            MeasurementUnit.M -> "M"
            MeasurementUnit.FT -> "FT"
        }

        updateDistanceDisplay()
    }
}