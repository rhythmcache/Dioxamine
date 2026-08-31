package io.github.rhythmcache.dioxamine.adb.builtin.processmanager

data class ProcessItem(
    val pid: Int,
    val uid: Int,
    val processName: String,
    val packageName: String,
    val appLabel: String,
    val rssKb: Long,
    val threadCount: Int,
    val cpuPercent: Double,
    val isSystemApp: Boolean
) {
    val displayName: String
        get() = if (appLabel.isNotBlank()) appLabel else processName

    val formattedRam: String
        get() = when {
            rssKb >= 1024 * 1024 -> String.format("%.1f GB", rssKb / (1024.0 * 1024.0))
            rssKb >= 1024 -> String.format("%.1f MB", rssKb / 1024.0)
            else -> "$rssKb KB"
        }

    val formattedCpu: String
        get() = String.format("%.2f%%", cpuPercent)

    val isUserApp: Boolean
        get() = uid >= 10000 && !isSystemApp
}

data class SystemMemoryStats(
    val totalRamKb: Long,
    val availRamKb: Long,
    val cpuUsagePercent: Double = 0.0,
    val cpuCoreCount: Int = 1
) {
    val usedRamKb: Long
        get() = (totalRamKb - availRamKb).coerceAtLeast(0)

    val usedRatio: Float
        get() = if (totalRamKb > 0) (usedRamKb.toFloat() / totalRamKb.toFloat()).coerceIn(0f, 1f) else 0f

    val cpuUsageRatio: Float
        get() = (cpuUsagePercent.toFloat() / 100f).coerceIn(0f, 1f)

    val formattedCpu: String
        get() = String.format("%.1f%%", cpuUsagePercent)

    val formattedTotal: String
        get() = formatKb(totalRamKb)

    val formattedUsed: String
        get() = formatKb(usedRamKb)

    val formattedAvail: String
        get() = formatKb(availRamKb)

    private fun formatKb(kb: Long): String = when {
        kb >= 1024 * 1024 -> String.format("%.1f GB", kb / (1024.0 * 1024.0))
        kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }
}

enum class ProcessFilter {
    ALL,
    USER_APPS,
    SYSTEM
}

enum class ProcessSort {
    RAM_DESC,
    CPU_DESC,
    PID_ASC,
    NAME_ASC
}
