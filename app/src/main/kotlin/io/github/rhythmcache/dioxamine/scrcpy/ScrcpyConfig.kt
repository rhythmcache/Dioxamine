package io.github.rhythmcache.dioxamine.scrcpy

data class ScrcpyConfig(
    val videoEnabled: Boolean = true,  // Stream screen/camera video
    val maxSize: Int = 1080,         // 0 = Original, 1080, 720, 480
    val maxFps: Int = 60,            // 60, 30, 15
    val bitRateMbps: Int = 8,        // 8, 4, 2 Mbps
    val audioEnabled: Boolean = false, // Requires Android 11 (API 30+)
    val audioSource: String = "output", // playback, output, mic, mic-unprocessed, etc.
    val audioSourceExplicit: Boolean = false, // true once the user has manually picked a source
    val audioDup: Boolean = false,
    val turnScreenOff: Boolean = false,
    val bindVolumeKeys: Boolean = false,
    val controlEnabled: Boolean = true, // Enabled for touch control

    // --- Video source: display (default) or camera ---
    val videoSource: String = "display", // "display" or "camera"
    val cameraId: String? = null,
    val cameraFacing: String? = null,     // front, back, external
    val cameraSize: String? = null,       // e.g. "1920x1080"
    val cameraFps: Int? = null,
    val cameraHighSpeed: Boolean = false,
    val cameraAr: String? = null,         // "16:9", "1.6", or "sensor"
    val cameraTorch: Boolean = false,     // torch on at startup

    val videoCodec: String = "h264",      // h264, h265, av1
    val audioCodec: String = "opus",      // opus, aac, flac, raw
    val audioBitRateKbps: Int = 128,      // 64, 128, 192, 256, 320 Kbps
    val captureOrientation: String? = null // 0, 90, 180, 270, flip0, flip90, flip180, flip270
) {
    /**
     * Validates the configuration parameters, returning a list of configuration warnings
     * or validation error strings.
     */
    fun validate(apiLevel: Int): List<String> {
        val errors = mutableListOf<String>()

        if (!videoEnabled && !audioEnabled) {
            errors.add("At least one stream (video or audio) must be enabled.")
        }

        if (audioEnabled) {
            if (apiLevel < 30) {
                errors.add("Audio forwarding requires at least Android 11 (API 30). Device is API $apiLevel.")
            }
            if (audioDup && apiLevel < 33) {
                errors.add("Don't Mute (audio duplication) requires at least Android 13 (API 33). Device is API $apiLevel.")
            }
        }

        if (videoEnabled && videoSource == "camera") {
            if (apiLevel < 31) {
                errors.add("Camera mirroring requires at least Android 12 (API 31). Target device is API $apiLevel.")
            }
            if (cameraHighSpeed && (cameraFps == null || cameraFps < 120)) {
                errors.add("High speed camera mode is enabled, but camera FPS is not set to high-speed (>=120 FPS).")
            }
            if (cameraHighSpeed && cameraSize == null) {
                errors.add("High speed camera mode requires a specific capture resolution size (e.g. 720p).")
            }
        }

        return errors
    }

    /**
     * Returns the effective audio source, applying scrcpy's documented default:
     * switching video_source to "camera" implicitly switches default audio_source
     * to "mic" (unless the user explicitly chose one), and vice versa for "display" -> "output".
     * See camera.md: "By default, it automatically switches audio source to microphone."
     */
    fun effectiveAudioSource(): String {
        if (audioSourceExplicit) return audioSource
        return if (videoSource == "camera") "mic" else "output"
    }

    fun toServerArgs(): String {
        val parts = mutableListOf<String>()
        parts.add("log_level=info")
        parts.add("tunnel_forward=true")
        parts.add("send_device_meta=false") // Skip device name header
        parts.add("send_dummy_byte=false")  // Skip 1-byte dummy header byte on forward tunnel

        parts.add("video=$videoEnabled")
        if (videoEnabled) {
            parts.add("video_codec=$videoCodec")
            if (captureOrientation != null && captureOrientation != "0") {
                parts.add("capture_orientation=$captureOrientation")
            }

            // --- Video source ---
            if (videoSource == "camera") {
                parts.add("video_source=camera")
                cameraId?.let { parts.add("camera_id=$it") }
                // camera_facing is forbidden if camera_id is set (mirrors scrcpy's own validation)
                if (cameraId == null) {
                    cameraFacing?.let { parts.add("camera_facing=$it") }
                }
                cameraSize?.let { parts.add("camera_size=$it") }
                // camera_ar / max_size(-m) are forbidden if camera_size is set
                if (cameraSize == null) {
                    cameraAr?.let { parts.add("camera_ar=$it") }
                    val effectiveMaxSize = if (maxSize > 0) maxSize else 1920 // camera can't do true "original"
                    parts.add("max_size=$effectiveMaxSize")
                }
                cameraFps?.let { parts.add("camera_fps=$it") }
                if (cameraHighSpeed) parts.add("camera_high_speed=true")
                if (cameraTorch) parts.add("camera_torch=true")
            } else {
                if (maxSize > 0) parts.add("max_size=$maxSize")
            }

            if (maxFps > 0) parts.add("max_fps=$maxFps")
            if (bitRateMbps > 0) parts.add("video_bit_rate=${bitRateMbps * 1_000_000}")
        }

        parts.add("audio=$audioEnabled")
        if (audioEnabled) {
            parts.add("audio_codec=$audioCodec")
            if ((audioCodec == "opus" || audioCodec == "aac") && audioBitRateKbps > 0) {
                parts.add("audio_bit_rate=${audioBitRateKbps * 1_000}")
            }
            // audio_dup requires audio_source=playback; if the user chose "Device Audio"
            // (output) with Don't Mute enabled, silently switch to playback for the server.
            val serverAudioSource = if (audioDup && effectiveAudioSource() == "output") "playback" else effectiveAudioSource()
            parts.add("audio_source=$serverAudioSource")
            if (audioDup) {
                parts.add("audio_dup=true")
            }
        }
        parts.add("control=$controlEnabled")
        if (turnScreenOff) parts.add("turn_screen_off=true")
        return parts.joinToString(" ")
    }
}
