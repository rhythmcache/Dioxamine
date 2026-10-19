package io.github.rhythmcache.dioxamine.scrcpy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job

class ScrcpyViewModel : ViewModel() {
    var activeSession: ScrcpySession? by mutableStateOf(null)
    var isMirroring: Boolean by mutableStateOf(false)
    var videoWidth: Int? by mutableStateOf(null)
    var videoHeight: Int? by mutableStateOf(null)
    var isRecording: Boolean by mutableStateOf(false)
    var errorMessage: String? by mutableStateOf(null)
    var torchOn: Boolean by mutableStateOf(false)
    var showFloatingNav: Boolean by mutableStateOf(false)
    var showTouchpadOverlay: Boolean by mutableStateOf(false)
    var showKeyboard: Boolean by mutableStateOf(false)

    @Volatile private var previousTeardownJob: Job? = null

    fun stopMirroring() {
        activeSession?.stop()?.let { previousTeardownJob = it }
        activeSession = null
        isMirroring = false
        isRecording = false
        videoWidth = null
        videoHeight = null
        torchOn = false
        showKeyboard = false
    }

    suspend fun awaitPreviousTeardown() {
        val job = previousTeardownJob ?: return
        job.join()
        if (previousTeardownJob === job) {
            previousTeardownJob = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMirroring()
    }
}
