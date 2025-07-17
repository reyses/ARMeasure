package com.example.armeasure

import android.os.Bundle
import android.view.View
import com.google.ar.sceneform.ux.ArFragment

class CustomArFragment : ArFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // This disables the hand-raise prompt
        this.planeDiscoveryController.setInstructionView(null)
        // This disables the default white dots
        this.arSceneView.planeRenderer.isEnabled = false
    }
}