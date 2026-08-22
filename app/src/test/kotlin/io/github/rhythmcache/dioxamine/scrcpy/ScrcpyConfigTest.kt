package io.github.rhythmcache.dioxamine.scrcpy

import org.junit.Assert.*
import org.junit.Test

class ScrcpyConfigTest {

    @Test
    fun testDefaultConfig_videoEnabled() {
        val config = ScrcpyConfig()
        assertTrue(config.videoEnabled)
        assertFalse(config.audioEnabled)

        val args = config.toServerArgs()
        assertTrue(args.contains("video=true"))
        assertTrue(args.contains("video_codec=h264"))
        assertTrue(args.contains("audio=false"))
    }

    @Test
    fun testAudioOnlyConfig_toServerArgs() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true,
            audioCodec = "opus",
            audioBitRateKbps = 128
        )

        val args = config.toServerArgs()
        assertTrue(args.contains("video=false"))
        assertFalse(args.contains("video_codec="))
        assertFalse(args.contains("video_bit_rate="))
        assertFalse(args.contains("max_size="))
        assertTrue(args.contains("audio=true"))
        assertTrue(args.contains("audio_codec=opus"))
        assertTrue(args.contains("audio_bit_rate=128000"))
    }

    @Test
    fun testValidation_bothDisabled() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = false
        )
        val errors = config.validate(apiLevel = 33)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("At least one stream"))
    }

    @Test
    fun testValidation_audioOnly_unsupportedApi() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true
        )
        val errors = config.validate(apiLevel = 29)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("requires at least Android 11 (API 30)"))
    }

    @Test
    fun testValidation_audioOnly_supportedApi() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true
        )
        val errors = config.validate(apiLevel = 30)
        assertTrue(errors.isEmpty())
    }
}
