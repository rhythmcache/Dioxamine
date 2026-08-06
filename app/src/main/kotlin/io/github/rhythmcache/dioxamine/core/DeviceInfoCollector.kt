package io.github.rhythmcache.dioxamine.core

import android.os.Build
import io.github.rhythmcache.dioxamine.scrcpy.ScrcpyConfig
import org.json.JSONObject

/**
 * Collects device info relevant to the host and active connected target device
 * for bug-report exports and diagnostic logs.
 */
object DeviceInfoCollector {

    fun collect(
        activeConn: DeviceConnection? = null,
        config: ScrcpyConfig? = null,
        includeTargetDevice: Boolean = activeConn != null
    ): String {
        val json = JSONObject()

        json.put("exportedAt", System.currentTimeMillis())

        val clientInfo = JSONObject()
        clientInfo.put("manufacturer", Build.MANUFACTURER)
        clientInfo.put("model", Build.MODEL)
        clientInfo.put("device", Build.DEVICE)
        clientInfo.put("brand", Build.BRAND)
        clientInfo.put("board", Build.BOARD)
        clientInfo.put("hardware", Build.HARDWARE)
        clientInfo.put("sdkInt", Build.VERSION.SDK_INT)
        clientInfo.put("androidRelease", Build.VERSION.RELEASE)
        clientInfo.put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(","))
        clientInfo.put("fingerprint", Build.FINGERPRINT)
        json.put("clientDevice", clientInfo)

        if (includeTargetDevice && activeConn != null) {
            val targetInfo = JSONObject()
            targetInfo.put("id", activeConn.id)
            targetInfo.put("label", activeConn.label)
            targetInfo.put("transport", activeConn.transport.name)
            targetInfo.put("mode", activeConn.mode.name)
            targetInfo.put("apiLevel", activeConn.apiLevel ?: JSONObject.NULL)
            targetInfo.put("androidVersion", activeConn.androidVersion ?: JSONObject.NULL)
            targetInfo.put("model", activeConn.model ?: JSONObject.NULL)
            json.put("targetDevice", targetInfo)
        }

        if (config != null) {
            val configInfo = JSONObject()
            configInfo.put("videoCodec", config.videoCodec)
            configInfo.put("maxSize", config.maxSize)
            configInfo.put("maxFps", config.maxFps)
            configInfo.put("bitRateMbps", config.bitRateMbps)
            configInfo.put("audioEnabled", config.audioEnabled)
            configInfo.put("audioCodec", config.audioCodec)
            configInfo.put("audioSource", config.effectiveAudioSource())
            configInfo.put("controlEnabled", config.controlEnabled)
            configInfo.put("videoSource", config.videoSource)
            configInfo.put("turnScreenOff", config.turnScreenOff)
            configInfo.put("captureOrientation", config.captureOrientation ?: JSONObject.NULL)
            configInfo.put("serverArgs", config.toServerArgs())
            json.put("sessionConfig", configInfo)
        }

        return json.toString(2)
    }
}
