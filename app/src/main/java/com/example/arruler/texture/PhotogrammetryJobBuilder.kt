package com.example.arruler.texture

import com.example.arruler.processing.JobPackage
import com.example.arruler.processing.PackageMeta
import java.io.File

/** Turns the keyframes saved by [KeyframeCapture] into the PHOTOGRAMMETRY job ZIP (the format is [JobPackage]'s). */
object PhotogrammetryJobBuilder {
    /** @return the number of images packed. Throws if [keyframeDir] holds no keyframes. */
    fun build(keyframeDir: File, dest: File, meta: PackageMeta): Int {
        val records = KeyframeStore.read(keyframeDir).filter { File(keyframeDir, it.file).isFile }
        require(records.isNotEmpty()) { "no keyframes in $keyframeDir" }
        JobPackage.writePhotoJob(dest, KeyframeStore.toPhotoFrames(keyframeDir, records), meta)
        return records.size
    }
}
