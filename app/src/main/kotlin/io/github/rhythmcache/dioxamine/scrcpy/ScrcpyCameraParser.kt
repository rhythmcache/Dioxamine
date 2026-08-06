package io.github.rhythmcache.dioxamine.scrcpy

data class HighSpeedOption(
    val resolution: String,
    val fpsList: List<Int>
)

data class CameraDevice(
    val id: String,
    val facing: String,
    val sensorRes: String?,
    val fpsRange: List<Int>,
    val standardSizes: List<String>,
    val highSpeedOptions: List<HighSpeedOption>
)

object ScrcpyCameraParser {
    private val cameraHeaderRegex = Regex("""--camera-id=(\d+)\s*\(([^,]+)(?:,\s*(\d+x\d+))?(?:,\s*fps=\{([\d,\s]+)\})?""")
    private val highSpeedRegex = Regex("""-\s*(\d+x\d+)\s*\(fps=\{([\d,\s]+)\}\)""")
    private val standardSizeRegex = Regex("""^\s*-\s*(\d+x\d+)\s*$""")

    fun parse(stdout: String): List<CameraDevice> {
        val list = mutableListOf<CameraDevice>()
        var currentId: String? = null
        var currentFacing = "back"
        var currentSensorRes: String? = null
        var currentFpsRange = listOf<Int>()
        val currentStandardSizes = mutableListOf<String>()
        val currentHighSpeed = mutableListOf<HighSpeedOption>()
        var inHighSpeed = false

        fun flush() {
            if (currentId != null) {
                list.add(
                    CameraDevice(
                        id = currentId!!,
                        facing = currentFacing,
                        sensorRes = currentSensorRes,
                        fpsRange = currentFpsRange,
                        standardSizes = currentStandardSizes.toList(),
                        highSpeedOptions = currentHighSpeed.toList()
                    )
                )
                currentStandardSizes.clear()
                currentHighSpeed.clear()
                inHighSpeed = false
            }
        }

        for (line in stdout.lines()) {
            val trimmed = line.trim()
            if (trimmed.contains("--camera-id=")) {
                flush()
                val match = cameraHeaderRegex.find(trimmed)
                if (match != null) {
                    currentId = match.groupValues[1]
                    currentFacing = match.groupValues[2].trim()
                    currentSensorRes = match.groupValues[3].ifEmpty { null }
                    val fpsStr = match.groupValues[4]
                    currentFpsRange = if (fpsStr.isNotEmpty()) {
                        fpsStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                    } else emptyList()
                }
            } else if (trimmed.contains("High speed capture")) {
                inHighSpeed = true
            } else if (inHighSpeed) {
                val match = highSpeedRegex.find(trimmed)
                if (match != null) {
                    val size = match.groupValues[1]
                    val fpsList = match.groupValues[2].split(",").mapNotNull { it.trim().toIntOrNull() }
                    currentHighSpeed.add(HighSpeedOption(size, fpsList))
                }
            } else {
                val match = standardSizeRegex.find(line)
                if (match != null) {
                    currentStandardSizes.add(match.groupValues[1])
                }
            }
        }
        flush()
        return list
    }
}
