package com.example.arruler

import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.arruler.databinding.ActivityMainBinding
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import kotlin.math.sqrt
import kotlin.math.abs

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
    private var cylinderRenderable: ModelRenderable? = null
    private var currentDistanceMeters: Float = 0f

    private val tempStart = Vector3()
    private val tempEnd = Vector3()
    private val tempDiff = Vector3()
    private val tempScale = Vector3()
    private val vectorUp = Vector3.up()
    private val tempRotation = Quaternion()

    private var isMeasuring = false
    private var unit = MeasurementUnit.CM

    private val distanceFormatter = DistanceFormatter()

    // Cache a reusable HitResult pair to avoid allocation if we wanted to optimization further,
    // but performHitTest returns a new pair anyway.

    enum class MeasurementUnit {
        CM, INCH, M, FT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        // Sceneform 1.15+ specific: disable plane renderer by default
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
                // Create a unit cylinder with height 1.0, centered at Y=0.5
                cylinderRenderable = ShapeFactory.makeCylinder(
                    0.003f,
                    1.0f,
                    Vector3(0f, 0.5f, 0f),
                    material
                )
            }
    }

    private fun setupListeners() {
        binding.btnMeasure.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (!isMeasuring) {
                if (startAnchor == null) {
                    startMeasurement()
                } else {
                    // Reset to start new measurement
                     clearMeasurement()
                     startMeasurement()
                }
            } else {
                stopMeasurement()
            }
        }

        binding.btnClear.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            clearMeasurement()
        }

        binding.btnUnit.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            switchUnit()
        }

        arFragment.arSceneView.scene.addOnUpdateListener { frameTime ->
            updateReticle()
            if (isMeasuring && startAnchor != null) {
                updateLiveMeasurement()
            }
        }

        arFragment.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
            // Use screen center instead of tap if possible for consistency,
            // but for now, tap anywhere to place is also fine.
            // However, the UX is moving towards "Tap button to place at reticle".
            // So we might ignore scene taps or redirect them.
            // For now, let's keep tap-to-place as an alternative if measuring.
            if (isMeasuring && startAnchor != null) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                placeEndPoint(hitResult)
            }
        }
    }

    private fun updateReticle() {
        // Continuous hit test from center
        val hitPair = performHitTest()
        if (hitPair != null) {
            // Hit a plane
            binding.centerCrosshair.setColorFilter(Color.parseColor("#34C759")) // Green
            binding.centerCrosshair.alpha = 1.0f

            if (!isMeasuring) {
                if (startAnchor == null) {
                    binding.tvReticleInfo.text = "Tap to Start"
                } else {
                    binding.tvReticleInfo.text = "Tap to Measure Again"
                }
            }
            // If measuring, text is handled by updateLiveMeasurement or kept as is
        } else {
            // No plane
            binding.centerCrosshair.setColorFilter(Color.WHITE)
            binding.centerCrosshair.alpha = 0.5f

            if (!isMeasuring && startAnchor == null) {
                binding.tvReticleInfo.text = "Find a surface"
            }
        }
    }

    private fun startMeasurement() {
        val hitPair = performHitTest() ?: return
        val (hitResult, _) = hitPair

        binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

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

        binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun stopMeasurement() {
        isMeasuring = false
        updateUI()
    }

    private fun updateLiveMeasurement() {
        val pair = performHitTest() ?: return
        val (_, hitPose) = pair

        val startPos = startAnchor?.pose?.translation ?: return
        val endPos = hitPose.translation

        // Manual distance calculation
        val dx = endPos[0] - startPos[0]
        val dy = endPos[1] - startPos[1]
        val dz = endPos[2] - startPos[2]

        currentDistanceMeters = sqrt(dx * dx + dy * dy + dz * dz)

        // Set temp vectors for drawing
        tempStart.set(startPos[0], startPos[1], startPos[2])
        tempEnd.set(endPos[0], endPos[1], endPos[2])

        drawTemporaryLine(tempStart, tempEnd, currentDistanceMeters)

        updateDistanceDisplay()

        // Update Reticle Info with live distance
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
        binding.tvReticleInfo.text = distanceFormatter.format(value, unitText)
    }

    private fun drawTemporaryLine(start: Vector3, end: Vector3, distance: Float) {
        val renderable = cylinderRenderable ?: return

        if (lineNode == null || lineNode?.renderable != renderable) {
            lineNode?.setParent(null)
            lineNode = Node().apply {
                setParent(arFragment.arSceneView.scene)
                this.renderable = renderable
            }
        }

        // Calculate rotation
        tempDiff.set(end.x - start.x, end.y - start.y, end.z - start.z)

        if (distance > 0) {
            val invDistance = 1.0f / distance
            tempDiff.set(tempDiff.x * invDistance, tempDiff.y * invDistance, tempDiff.z * invDistance)
        } else {
            tempDiff.set(0f, 0f, 0f)
        }

        setLookRotation(tempRotation, tempDiff, vectorUp)

        lineNode?.apply {
            if (parent == null) {
                setParent(arFragment.arSceneView.scene)
            }
            worldPosition = start
            worldRotation = tempRotation
            localScale = tempScale.apply { x = 1f; y = distance; z = 1f }
        }
    }

    private fun setLookRotation(dest: Quaternion, forward: Vector3, up: Vector3) {
        var fx = forward.x
        var fy = forward.y
        var fz = forward.z

        val lenSq = fx * fx + fy * fy + fz * fz
        if (lenSq < 1e-6f) {
            dest.set(0f, 0f, 0f, 1f)
            return
        }

        if (abs(lenSq - 1.0f) > 1e-6f) {
            val invLen = 1.0f / sqrt(lenSq)
            fx *= invLen
            fy *= invLen
            fz *= invLen
        }

        val zx = -fx
        val zy = -fy
        val zz = -fz

        var ux = up.x
        var uy = up.y
        var uz = up.z

        var xx = uy * zz - uz * zy
        var xy = uz * zx - ux * zz
        var xz = ux * zy - uy * zx

        var xLenSq = xx * xx + xy * xy + xz * xz

        if (xLenSq < 1e-6f) {
            if (abs(uz) < 0.999f) {
                ux = 0f; uy = 0f; uz = 1f
            } else {
                ux = 1f; uy = 0f; uz = 0f
            }
            xx = uy * zz - uz * zy
            xy = uz * zx - ux * zz
            xz = ux * zy - uy * zx
            xLenSq = xx * xx + xy * xy + xz * xz
        }

        val xInvLen = 1.0f / sqrt(xLenSq)
        xx *= xInvLen
        xy *= xInvLen
        xz *= xInvLen

        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx

        val trace = xx + yy + zz
        if (trace > 0) {
            val s = 0.5f / sqrt(trace + 1.0f)
            dest.w = 0.25f / s
            dest.x = (yz - zy) * s
            dest.y = (zx - xz) * s
            dest.z = (xy - yx) * s
        } else {
            if (xx > yy && xx > zz) {
                val s = 2.0f * sqrt(1.0f + xx - yy - zz)
                val invS = 1.0f / s
                dest.w = (yz - zy) * invS
                dest.x = 0.25f * s
                dest.y = (xy + yx) * invS
                dest.z = (zx + xz) * invS
            } else if (yy > zz) {
                val s = 2.0f * sqrt(1.0f + yy - xx - zz)
                val invS = 1.0f / s
                dest.w = (zx - xz) * invS
                dest.x = (xy + yx) * invS
                dest.y = 0.25f * s
                dest.z = (yz + zy) * invS
            } else {
                val s = 2.0f * sqrt(1.0f + zz - xx - yy)
                val invS = 1.0f / s
                dest.w = (xy - yx) * invS
                dest.x = (zx + xz) * invS
                dest.y = (yz + zy) * invS
                dest.z = 0.25f * s
            }
        }
    }

    private fun drawFinalLine() {
        val startPos = startAnchor?.pose?.translation ?: return
        val endPos = endAnchor?.pose?.translation ?: return

        val start = Vector3(startPos[0], startPos[1], startPos[2])
        val end = Vector3(endPos[0], endPos[1], endPos[2])

        val difference = Vector3.subtract(end, start)
        val directionFromTopToBottom = difference.normalized()
        val rotationFromAToB = Quaternion.lookRotation(
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

    private fun performHitTest(): Pair<HitResult, Pose>? {
        val frame = arFragment.arSceneView.arFrame ?: return null
        val view = arFragment.view ?: return null

        if (view.width == 0 || view.height == 0) return null

        val hits = frame.hitTest(view.width / 2f, view.height / 2f)
        for (hitResult in hits) {
            val trackable = hitResult.trackable
            val pose = hitResult.hitPose
            if (trackable is Plane && trackable.isPoseInPolygon(pose)) {
                return Pair(hitResult, pose)
            }
        }
        return null
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
        } else {
            binding.tvDistance.text = "—"
        }
    }

    private fun updateUI() {
        if (isMeasuring) {
            // State: Measuring
            // Button: Stop / Place
            binding.btnMeasure.iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9500")) // Orange
            binding.btnMeasure.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } else if (startAnchor != null) {
            // State: Finished
            // Button: New
             binding.btnMeasure.iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#007AFF")) // Blue
             binding.btnMeasure.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } else {
            // State: Idle
            // Button: Start
            binding.btnMeasure.iconTint = android.content.res.ColorStateList.valueOf(Color.BLACK)
            binding.btnMeasure.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
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
