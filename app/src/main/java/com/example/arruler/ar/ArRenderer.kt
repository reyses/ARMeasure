package com.example.arruler.ar

import android.graphics.Color
import com.example.arruler.measure.MeasurePoint
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import kotlin.math.sqrt

/**
 * Draws points, segments and polygon outlines at world positions (meters).
 * The only class that knows about SceneView nodes.
 */
class ArRenderer(private val arView: ARSceneView) {

    private val engine = arView.engine
    private val materialLoader = arView.materialLoader

    private val pointMaterial = materialLoader.createColorInstance(Color.RED, 0f, 0.4f, 0.5f)
    private val liveMaterial = materialLoader.createColorInstance(Color.YELLOW, 0f, 0.4f, 0.5f)
    private val finalMaterial = materialLoader.createColorInstance(Color.RED, 0f, 0.4f, 0.5f)

    private val pointNodes = ArrayList<Node>()
    private val segmentNodes = ArrayList<Node>()
    private var segmentsAreFinal: Boolean? = null

    /**
     * Shows exactly [points] as spheres joined by segments; [closed] adds the last-to-first
     * edge (polygon outline). [final] picks the thick red style instead of the thin live one.
     */
    fun render(points: List<MeasurePoint>, closed: Boolean = false, final: Boolean = false) {
        syncPoints(points)
        syncSegments(points, closed, final)
    }

    fun clear() = render(emptyList())

    fun release() {
        clear()
        materialLoader.destroyMaterialInstance(pointMaterial)
        materialLoader.destroyMaterialInstance(liveMaterial)
        materialLoader.destroyMaterialInstance(finalMaterial)
    }

    private fun syncPoints(points: List<MeasurePoint>) {
        trim(pointNodes, points.size)
        while (pointNodes.size < points.size) {
            val node = SphereNode(engine, radius = POINT_RADIUS, materialInstance = pointMaterial)
            arView.addChildNode(node)
            pointNodes += node
        }
        points.forEachIndexed { i, p -> pointNodes[i].position = Float3(p.x, p.y, p.z) }
    }

    private fun syncSegments(points: List<MeasurePoint>, closed: Boolean, final: Boolean) {
        if (segmentsAreFinal != final) {
            trim(segmentNodes, 0)
            segmentsAreFinal = final
        }
        val count = when {
            points.size < 2 -> 0
            closed && points.size >= 3 -> points.size
            else -> points.size - 1
        }
        trim(segmentNodes, count)
        val material = if (final) finalMaterial else liveMaterial
        val radius = if (final) FINAL_RADIUS else LIVE_RADIUS
        while (segmentNodes.size < count) {
            // Unit-height, unit-radius cylinder centred on its node; scaled per segment.
            val node = CylinderNode(engine, radius = 1f, height = 1f, materialInstance = material)
            arView.addChildNode(node)
            segmentNodes += node
        }
        for (i in 0 until count) {
            placeSegment(segmentNodes[i], points[i], points[(i + 1) % points.size], radius)
        }
    }

    private fun placeSegment(node: Node, a: MeasurePoint, b: MeasurePoint, radius: Float) {
        val length = a.distanceTo(b)
        node.position = Float3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
        node.scale = Float3(radius, length, radius)
        if (length > 1e-6f) {
            node.quaternion = rotationFromUpTo((b.x - a.x) / length, (b.y - a.y) / length, (b.z - a.z) / length)
        }
    }

    /** Shortest rotation taking +Y onto the unit vector (dx, dy, dz). */
    private fun rotationFromUpTo(dx: Float, dy: Float, dz: Float): Quaternion {
        if (dy < -0.99999f) return Quaternion(1f, 0f, 0f, 0f)
        // axis = up x dir = (dz, 0, -dx); w = 1 + up.dir
        val w = 1f + dy
        val len = sqrt(dz * dz + dx * dx + w * w)
        return Quaternion(dz / len, 0f, -dx / len, w / len)
    }

    private fun trim(nodes: MutableList<Node>, size: Int) {
        while (nodes.size > size) {
            val node = nodes.removeAt(nodes.lastIndex)
            arView.removeChildNode(node)
            node.destroy()
        }
    }

    private companion object {
        const val POINT_RADIUS = 0.015f
        const val LIVE_RADIUS = 0.003f
        const val FINAL_RADIUS = 0.005f
    }
}
