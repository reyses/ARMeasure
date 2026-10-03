package com.example.arruler

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlin.math.abs
import kotlin.math.sqrt

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

    private val tempStart = Vector3()
    private val tempEnd = Vector3()
    private val tempDiff = Vector3()
    private val tempScale = Vector3()
    private val vectorUp = Vector3.up()
    private val tempRotation = Quaternion()

    enum class MeasurementUnit { CM, INCH, M, FT }
    enum class AppState { IDLE, MEASURING, FINISHED }

    // Compose State
    private var currentDistanceMeters by mutableFloatStateOf(0f)
    private var appState by mutableStateOf(AppState.IDLE)
    private var unit by mutableStateOf(MeasurementUnit.CM)
    private var hasSurface by mutableStateOf(false)

    private val distanceFormatter = DistanceFormatter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            insets // Let compose handle window insets automatically
        }

        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment
        arFragment.arSceneView.planeRenderer.isEnabled = false

        setupRenderable()
        setupListeners()
        setupComposeUI()
    }

    private fun setupComposeUI() {
        binding.composeView.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    // Center Crosshair
                    val crosshairColor by animateColorAsState(
                        if (hasSurface) Color(0xFF34C759) else Color.White,
                        label = "crosshairColor"
                    )
                    val crosshairAlpha by animateFloatAsState(
                        if (hasSurface) 1.0f else 0.5f,
                        label = "crosshairAlpha"
                    )
                    val crosshairScale by animateFloatAsState(
                        if (appState == AppState.MEASURING) 0.8f else 1.0f,
                        label = "crosshairScale"
                    )

                    Icon(
                        painter = painterResource(id = R.drawable.ic_crosshair),
                        contentDescription = "Crosshair",
                        tint = crosshairColor,
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                            .alpha(crosshairAlpha)
                            .scale(crosshairScale)
                    )

                    // Reticle Info (Floating Text with Glass background)
                    val reticleText = when {
                        appState == AppState.MEASURING -> {
                            val value = formatValue(currentDistanceMeters)
                            distanceFormatter.format(value, getUnitText())
                        }
                        appState == AppState.FINISHED -> "Tap to Measure Again"
                        hasSurface -> "Tap to Start"
                        else -> "Find a surface"
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = 48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        AnimatedContent(targetState = reticleText, label = "reticleText") { text ->
                            Text(
                                text = text,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    // Distance Display (Top)
                    if (currentDistanceMeters > 0) {
                        val value = formatValue(currentDistanceMeters)
                        val formattedDistance = distanceFormatter.format(value, getUnitText())
                        
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(Color(0xFF2C2C2E).copy(alpha = 0.85f))
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            AnimatedContent(targetState = formattedDistance, label = "distance") { text ->
                                Text(
                                    text = text,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 32.sp
                                )
                            }
                        }
                    }

                    // Bottom Controls (Modern Shutter Style)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Unit Toggle Button
                        Surface(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .clickable {
                                    binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    switchUnit()
                                },
                            color = Color.Black.copy(alpha = 0.4f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AnimatedContent(targetState = getUnitText().uppercase(), label = "unit") { text ->
                                    Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }

                        // Main Measure Shutter Button
                        val shutterColor by animateColorAsState(
                            targetValue = when (appState) {
                                AppState.MEASURING -> Color(0xFFFF9500) // Orange
                                AppState.FINISHED -> Color(0xFF007AFF) // Blue
                                AppState.IDLE -> Color.White
                            },
                            label = "shutterColor"
                        )
                        
                        val shutterIconTint by animateColorAsState(
                            targetValue = if (appState == AppState.IDLE) Color.Black else Color.White,
                            label = "shutterIconTint"
                        )

                        Surface(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .clickable {
                                    binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    onMainButtonClicked()
                                },
                            color = shutterColor,
                            shadowElevation = 8.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_crosshair),
                                    contentDescription = "Measure",
                                    tint = shutterIconTint,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // Clear Button
                        Surface(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .clickable {
                                    binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    clearMeasurement()
                                },
                            color = Color.Black.copy(alpha = 0.4f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_clear),
                                    contentDescription = "Clear",
                                    tint = Color(0xFFFF3B30),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getUnitText(): String = when (unit) {
        MeasurementUnit.CM -> "cm"
        MeasurementUnit.INCH -> "in"
        MeasurementUnit.M -> "m"
        MeasurementUnit.FT -> "ft"
    }

    private fun formatValue(meters: Float): Float = when (unit) {
        MeasurementUnit.CM -> meters * 100
        MeasurementUnit.INCH -> meters * 39.37f
        MeasurementUnit.M -> meters
        MeasurementUnit.FT -> meters * 3.281f
    }

    private fun onMainButtonClicked() {
        if (appState != AppState.MEASURING) {
            if (startAnchor == null) {
                startMeasurement()
            } else {
                clearMeasurement()
                startMeasurement()
            }
        } else {
            stopMeasurement()
        }
    }

    private fun setupRenderable() {
        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(AndroidColor.RED))
            .thenAccept { material ->
                sphereRenderable = ShapeFactory.makeSphere(0.015f, Vector3.zero(), material)
            }

        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(AndroidColor.YELLOW))
            .thenAccept { material ->
                yellowMaterial = material
                cylinderRenderable = ShapeFactory.makeCylinder(
                    0.003f,
                    1.0f,
                    Vector3(0f, 0.5f, 0f),
                    material
                )
            }
    }

    private fun setupListeners() {
        arFragment.arSceneView.scene.addOnUpdateListener {
            updateReticle()
            if (appState == AppState.MEASURING && startAnchor != null) {
                updateLiveMeasurement()
            }
        }

        arFragment.setOnTapArPlaneListener { hitResult, plane, _ ->
            if (appState == AppState.MEASURING && startAnchor != null) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                placeEndPoint(hitResult)
            }
        }
    }

    private fun updateReticle() {
        val hitPair = performHitTest()
        hasSurface = hitPair != null
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

        appState = AppState.MEASURING
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
        appState = AppState.FINISHED
    }

    private fun stopMeasurement() {
        appState = AppState.IDLE
    }

    private fun updateLiveMeasurement() {
        val pair = performHitTest() ?: return
        val (_, hitPose) = pair

        val startPos = startAnchor?.pose?.translation ?: return
        val endPos = hitPose.translation

        val dx = endPos[0] - startPos[0]
        val dy = endPos[1] - startPos[1]
        val dz = endPos[2] - startPos[2]

        currentDistanceMeters = sqrt(dx * dx + dy * dy + dz * dz)

        tempStart.set(startPos[0], startPos[1], startPos[2])
        tempEnd.set(endPos[0], endPos[1], endPos[2])

        drawTemporaryLine(tempStart, tempEnd, currentDistanceMeters)
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

        MaterialFactory.makeOpaqueWithColor(this, com.google.ar.sceneform.rendering.Color(AndroidColor.RED))
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
        appState = AppState.IDLE
    }

    private fun switchUnit() {
        unit = when (unit) {
            MeasurementUnit.CM -> MeasurementUnit.INCH
            MeasurementUnit.INCH -> MeasurementUnit.M
            MeasurementUnit.M -> MeasurementUnit.FT
            MeasurementUnit.FT -> MeasurementUnit.CM
        }
    }
}
