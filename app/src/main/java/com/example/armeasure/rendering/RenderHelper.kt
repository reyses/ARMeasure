package com.example.armeasure.rendering

import android.content.Context
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory

object RenderHelper {

    fun createSphere(
        context: Context,
        radius: Float,
        color: Color,
        callback: (ModelRenderable) -> Unit
    ) {
        MaterialFactory.makeOpaqueWithColor(context, color)
            .thenAccept { material ->
                val sphere = ShapeFactory.makeSphere(radius, Vector3.zero(), material)
                callback(sphere)
            }
    }

    fun createLineNode(
        context: Context,
        start: Vector3,
        end: Vector3,
        color: Color,
        callback: (Node) -> Unit
    ) {
        val difference = Vector3.subtract(start, end)
        val lineNode = Node().apply {
            // Position the node at the midpoint of the line
            worldPosition = Vector3.add(start, end).scaled(0.5f)
            // Rotate the node to face the end point
            worldRotation = Quaternion.lookRotation(difference.normalized(), Vector3.up())
        }

        MaterialFactory.makeOpaqueWithColor(context, color)
            .thenAccept { material ->
                // Create a long, thin cube to represent the line
                val lineRenderable = ShapeFactory.makeCube(
                    Vector3(0.01f, 0.01f, difference.length()),
                    Vector3.zero(), // Center the cube on the node's origin
                    material
                )
                lineNode.renderable = lineRenderable
                callback(lineNode)
            }
    }
}