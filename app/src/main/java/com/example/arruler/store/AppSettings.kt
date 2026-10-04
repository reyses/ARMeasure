package com.example.arruler.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide switches kept in SharedPreferences: the Downloads copy of every save and the capture video. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _copyToDownloads = MutableStateFlow(prefs.getBoolean(KEY_COPY, true))
    /** Every Save also writes to Download/ARMeasure/<project> (default on). */
    val copyToDownloads: StateFlow<Boolean> = _copyToDownloads.asStateFlow()

    private val _recordVideo = MutableStateFlow(prefs.getBoolean(KEY_VIDEO, true))
    /** Record the AR session to MP4 during an object capture (default on, about 30-60 MB per minute). */
    val recordCaptureVideo: StateFlow<Boolean> = _recordVideo.asStateFlow()

    private val _useGpu = MutableStateFlow(prefs.getBoolean(KEY_GPU, true))
    /** Use the GPU kernels that passed the on-device self-test (default on; the gate still needs a verified self-test). */
    val useGpu: StateFlow<Boolean> = _useGpu.asStateFlow()

    fun setUseGpu(on: Boolean) {
        _useGpu.value = on
        prefs.edit().putBoolean(KEY_GPU, on).apply()
        com.example.arruler.gpu.GpuGate.setEnabled(on)
    }

    fun setCopyToDownloads(on: Boolean) {
        _copyToDownloads.value = on
        prefs.edit().putBoolean(KEY_COPY, on).apply()
    }

    fun setRecordCaptureVideo(on: Boolean) {
        _recordVideo.value = on
        prefs.edit().putBoolean(KEY_VIDEO, on).apply()
    }

    private companion object {
        const val KEY_COPY = "copy_to_downloads"
        const val KEY_VIDEO = "record_capture_video"
        const val KEY_GPU = "use_gpu_when_verified"
    }
}
