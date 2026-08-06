package io.github.rhythmcache.dioxamine.scrcpy

import org.junit.Assert.*
import org.junit.Test

class ScrcpyCameraParserTest {

    @Test
    fun testParseScrcpyCameraOutput() {
        val sampleStdout = """
            [server] INFO: Device: [Xiaomi] POCO 2201116PI (Android 16)
            [server] INFO: List of cameras:
                --camera-id=0    (back, 4640x3472, fps={10, 12, 15, 24, 30}, zoom-range=[1, 10])
                    - 3840x2160
                    - 1920x1080
                    - 1280x720
                  High speed capture (--camera-high-speed):
                    - 1280x720 (fps={120, 240})
                    - 1920x1080 (fps={120, 240})
                --camera-id=1    (front, 2304x1728, fps={10, 12, 15, 24, 30}, zoom-range=[1, 10])
                    - 2304x1728
                    - 1920x1080
                  High speed capture (--camera-high-speed):
                    - 1280x720 (fps={120})
        """.trimIndent()

        val cameras = ScrcpyCameraParser.parse(sampleStdout)

        assertEquals(2, cameras.size)

        // Camera 0
        val cam0 = cameras[0]
        assertEquals("0", cam0.id)
        assertEquals("back", cam0.facing)
        assertEquals("4640x3472", cam0.sensorRes)
        assertEquals(listOf(10, 12, 15, 24, 30), cam0.fpsRange)
        assertEquals(listOf("3840x2160", "1920x1080", "1280x720"), cam0.standardSizes)
        assertEquals(2, cam0.highSpeedOptions.size)
        assertEquals("1280x720", cam0.highSpeedOptions[0].resolution)
        assertEquals(listOf(120, 240), cam0.highSpeedOptions[0].fpsList)

        // Camera 1
        val cam1 = cameras[1]
        assertEquals("1", cam1.id)
        assertEquals("front", cam1.facing)
        assertEquals("2304x1728", cam1.sensorRes)
        assertEquals(listOf("2304x1728", "1920x1080"), cam1.standardSizes)
        assertEquals(1, cam1.highSpeedOptions.size)
        assertEquals("1280x720", cam1.highSpeedOptions[0].resolution)
        assertEquals(listOf(120), cam1.highSpeedOptions[0].fpsList)
    }
}
