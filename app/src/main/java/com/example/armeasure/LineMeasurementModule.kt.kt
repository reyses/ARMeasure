package com.example.armeasure

import android.content.Context
import com.google.ar.core.Pose
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import kotlin.math.sqrt

object LineMeasurementModule {

    fun calculateLineDistance(startPose: Pose, endPose: Pose): Float {
        val dx = startPose.tx() - endPose.tx()
        val dy = startPose.ty() - endPose.ty()
        val dz = startPose.tz() - endPose.tz()
        return calculateLineDistanceComponents(dx, dy, dz)
    }

    fun calculateLineDistanceComponents(dx: Float, dy: Float, dz: Float): Float {
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    fun createSphere(context: Context, radius: Float, color: Color, callback: (ModelRenderable) -> Unit) {
        MaterialFactory.makeOpaqueWithColor(context, color)
            .thenAccept { material ->
                val sphere = ShapeFactory.makeSphere(radius, Vector3.zero(), material)
                callback(sphere)
            }
    }

    fun createLineNode(context: Context, start: Vector3, end: Vector3, color: Color, callback: (Node) -> Unit) {
        val difference = Vector3.subtract(start, end)
        val lineNode = Node().apply {
            worldPosition = Vector3.add(start, end).scaled(0.5f)
            worldRotation = Quaternion.lookRotation(difference.normalized(), Vector3.up())
        }

        MaterialFactory.makeOpaqueWithColor(context, color)
            .thenAccept { material ->
                val lineRenderable = ShapeFactory.makeCube(
                    Vector3(0.01f, 0.01f, difference.length()),
                    Vector3.zero(),
                    material
                )
                lineNode.renderable = lineRenderable
                callback(lineNode)
            }
    }
}