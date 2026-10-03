package com.example.arruler.ar

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.arruler.measure.MeasurePoint
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneScope
import kotlin.math.sqrt

/**
 * Draws points, segments and polygon outlines at world positions (meters).
 * The only class that knows about SceneView nodes.
 *
 * SceneView 4 builds nodes declaratively, so [render] / [renderExtra] only publish what should be
 * on screen as Compose state and [Nodes] (called from the `ARSceneView` content block) emits the
 * matching node composables, which SceneView creates, updates and destroys with the composition.
 */
class ArRenderer {

    /** One cylinder: centre, orientation (+Y onto the segment) and the unit-cylinder scale. */
    internal class Segment(val center: Float3, val rotation: Quaternion, val length: Float, val radius: Float)

    private var points by mutableStateOf<List<MeasurePoint>>(emptyList())
    private var segments by mutableStateOf<List<Segment>>(emptyList())
    private var segmentsAreFinal by mutableStateOf(false)
    private var extras by mutableStateOf<List<Segment>>(emptyList())
    private var cloud by mutableStateOf<List<MeasurePoint>>(emptyList())
    private var surfaces by mutableStateOf<List<SurfacePatch>>(emptyList())
    private var dome by mutableStateOf<List<DomeMarker>>(emptyList())

    /** One coverage-dome marker: world position and whether its viewing direction was observed. */
    internal class DomeMarker(val position: Float3, val observed: Boolean)

    /** One plane polygon ready to emit: its node frame, alpha bucket and world outline. */
    private class PatchNodeData(
        val id: Int, val kind: SurfaceKind, val bucket: Int, val frame: SurfaceMath.Frame,
        val outline: List<Float3>,
    )

    /**
     * Shows exactly [points] as spheres joined by segments; [closed] adds the last-to-first
     * edge (polygon outline). [final] picks the thick red style instead of the thin live one.
     */
    fun render(points: List<MeasurePoint>, closed: Boolean = false, final: Boolean = false) {
        this.points = points.toList()
        val count = when {
            points.size < 2 -> 0
            closed && points.size >= 3 -> points.size
            else -> points.size - 1
        }
        val radius = if (final) FINAL_RADIUS else LIVE_RADIUS
        segments = List(count) { i -> segment(points[i], points[(i + 1) % points.size], radius) }
        segmentsAreFinal = final
    }

    /** Extra thin segments (e.g. the height line), independent of [render]. Empty list removes them. */
    fun renderExtra(segments: List<Pair<MeasurePoint, MeasurePoint>>) {
        extras = segments.map { (a, b) -> segment(a, b, LIVE_RADIUS) }
    }

    /** Scan preview: [points] as tiny spheres (5 mm), separate from [render]; empty list removes them. */
    fun renderCloud(points: List<MeasurePoint>) {
        cloud = points.take(MAX_CLOUD_POINTS)
    }

    /**
     * OBJECT mode coverage dome: one small sphere per viewing-direction bin at [radiusM] around [centre]
     * (green = observed, grey = not yet). Empty list removes them.
     */
    fun renderDome(centre: MeasurePoint, bins: List<Pair<MeasurePoint, Boolean>>, radiusM: Float) {
        dome = bins.map { (dir, observed) ->
            DomeMarker(Float3(centre.x + dir.x * radiusM, centre.y + dir.y * radiusM, centre.z + dir.z * radiusM), observed)
        }
    }

    /**
     * SURFACES overlay: every [patches] entry as a translucent filled polygon with an outline, nodes
     * reused per [SurfacePatch.id]; patches missing from the list lose their nodes. Empty removes all.
     */
    fun renderSurfaces(patches: List<SurfacePatch>) {
        surfaces = patches
    }

    /** Clears the measurement drawing; the SURFACES overlay is a view setting and stays. */
    fun clear() {
        render(emptyList())
        renderExtra(emptyList())
        renderCloud(emptyList())
        dome = emptyList()
    }

    fun release() {
        clear()
    }

    /** Emits the nodes for the current state; call it from the `ARSceneView` content block. */
    @Composable
    internal fun ARSceneScope.Nodes() {
        // Declared before the nodes so they are disposed after them (a material instance must
        // outlive the renderables that use it).
        val pointMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.RED, 0f, 0.4f, 0.5f)
        }
        val liveMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.YELLOW, 0f, 0.4f, 0.5f)
        }
        val finalMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.RED, 0f, 0.4f, 0.5f)
        }
        val cloudMaterial = remember(materialLoader) {
            materialLoader.createColorInstance(Color.CYAN, 0f, 0.4f, 0.5f)
        }
        val domeSeen = remember(materialLoader) {
            materialLoader.createColorInstance(Color.rgb(52, 199, 89), 0f, 0.4f, 0.5f)
        }
        val domeIdle = remember(materialLoader) {
            materialLoader.createColorInstance(Color.GRAY, 0f, 0.4f, 0.5f)
        }
        // Unlit translucent fills per kind x alpha bucket, opaque outlines per kind.
        val fillMaterials = remember(materialLoader) {
            SurfaceKind.values().associateWith { kind ->
                List(SurfaceMath.ALPHA_BUCKETS) { b ->
                    materialLoader.createUnlitColorInstance(kindColor(kind, SurfaceMath.bucketAlpha(b)))
                }
            }
        }
        val outlineMaterials = remember(materialLoader) {
            SurfaceKind.values().associateWith { materialLoader.createUnlitColorInstance(kindColor(it, 1f)) }
        }
        DisposableEffect(materialLoader) {
            onDispose {
                fillMaterials.values.forEach { l -> l.forEach(materialLoader::destroyMaterialInstance) }
                outlineMaterials.values.forEach(materialLoader::destroyMaterialInstance)
                materialLoader.destroyMaterialInstance(pointMaterial)
                materialLoader.destroyMaterialInstance(liveMaterial)
                materialLoader.destroyMaterialInstance(finalMaterial)
                materialLoader.destroyMaterialInstance(cloudMaterial)
                materialLoader.destroyMaterialInstance(domeSeen)
                materialLoader.destroyMaterialInstance(domeIdle)
            }
        }

        points.forEachIndexed { i, p ->
            key("p$i") {
                SphereNode(
                    radius = POINT_RADIUS,
                    materialInstance = pointMaterial,
                    position = Float3(p.x, p.y, p.z),
                )
            }
        }
        val segmentMaterial = if (segmentsAreFinal) finalMaterial else liveMaterial
        segments.forEachIndexed { i, s ->
            key("s$i") { SegmentNode(s, segmentMaterial) }
        }
        extras.forEachIndexed { i, s ->
            key("e$i") { SegmentNode(s, liveMaterial) }
        }
        val patchData = remember(surfaces) {
            surfaces.mapNotNull { p ->
                val frame = SurfaceMath.frameOf(p.worldPolygon) ?: return@mapNotNull null
                PatchNodeData(
                    p.id, p.kind, SurfaceMath.alphaBucket(p.alpha), frame,
                    p.worldPolygon.map { Float3(it.x, it.y, it.z) },
                )
            }
        }
        patchData.forEach { d ->
            key("surf${d.id}") {
                val fill = fillMaterials.getValue(d.kind)[d.bucket]
                val c = d.frame.centroid
                val pos = Float3(c.x, c.y, c.z)
                // Back face: mirrored path on a node turned 180 degrees about local X, so it sits where
                // the front polygon does but faces the other way (visible from behind, whatever the culling).
                val backRotation = d.frame.rotation * Quaternion(1f, 0f, 0f, 0f)
                ShapeNode(
                    polygonPath = d.frame.local.map { (x, y) -> Float2(x, y) },
                    materialInstance = fill,
                    position = pos,
                    rotation = d.frame.rotation.toEulerAngles(),
                )
                ShapeNode(
                    polygonPath = d.frame.local.map { (x, y) -> Float2(x, -y) },
                    materialInstance = fill,
                    position = pos,
                    rotation = backRotation.toEulerAngles(),
                )
                PathNode(
                    points = d.outline,
                    closed = true,
                    materialInstance = outlineMaterials.getValue(d.kind),
                )
            }
        }
        dome.forEachIndexed { i, m ->
            key("d$i") {
                SphereNode(
                    radius = DOME_RADIUS,
                    materialInstance = if (m.observed) domeSeen else domeIdle,
                    position = m.position,
                )
            }
        }
        cloud.forEachIndexed { i, p ->
            key("c$i") {
                SphereNode(
                    radius = CLOUD_RADIUS,
                    materialInstance = cloudMaterial,
                    position = Float3(p.x, p.y, p.z),
                )
            }
        }
    }

    @Composable
    private fun ARSceneScope.SegmentNode(s: Segment, material: MaterialInstance) {
        // Unit-height, unit-radius cylinder centred on its node; scaled per segment.
        CylinderNode(
            radius = 1f,
            height = 1f,
            materialInstance = material,
            position = s.center,
            rotation = s.rotation.toEulerAngles(),
            scale = Float3(s.radius, s.length, s.radius),
        )
    }

    internal companion object {
        const val POINT_RADIUS = 0.015f
        const val LIVE_RADIUS = 0.003f
        const val FINAL_RADIUS = 0.005f
        const val CLOUD_RADIUS = 0.005f
        const val DOME_RADIUS = 0.012f

        /** Sphere-node budget of the scan preview (500 rather than 2,000: each is a Filament entity). */
        const val MAX_CLOUD_POINTS = 500

        /** Floor green, wall blue, ceiling purple, other grey. */
        fun kindColor(kind: SurfaceKind, alpha: Float): Float4 = when (kind) {
            SurfaceKind.FLOOR -> Float4(0.20f, 0.78f, 0.35f, alpha)
            SurfaceKind.WALL -> Float4(0.00f, 0.48f, 1.00f, alpha)
            SurfaceKind.CEILING -> Float4(0.69f, 0.32f, 0.87f, alpha)
            SurfaceKind.OTHER -> Float4(0.60f, 0.60f, 0.62f, alpha)
        }

        fun segment(a: MeasurePoint, b: MeasurePoint, radius: Float): Segment {
            val length = a.distanceTo(b)
            val rotation = if (length > 1e-6f) {
                rotationFromUpTo((b.x - a.x) / length, (b.y - a.y) / length, (b.z - a.z) / length)
            } else {
                Quaternion(0f, 0f, 0f, 1f)
            }
            return Segment(
                center = Float3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f),
                rotation = rotation,
                length = length,
                radius = radius,
            )
        }

        /** Shortest rotation taking +Y onto the unit vector (dx, dy, dz). */
        fun rotationFromUpTo(dx: Float, dy: Float, dz: Float): Quaternion {
            if (dy < -0.99999f) return Quaternion(1f, 0f, 0f, 0f)
            // axis = up x dir = (dz, 0, -dx); w = 1 + up.dir
            val w = 1f + dy
            val len = sqrt(dz * dz + dx * dx + w * w)
            return Quaternion(dz / len, 0f, -dx / len, w / len)
        }
    }
}
