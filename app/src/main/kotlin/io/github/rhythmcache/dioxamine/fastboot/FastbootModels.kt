package io.github.rhythmcache.dioxamine.fastboot

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FastbootDevice(
    val id: String,
    val label: String,
    val deviceName: String,
)

enum class LogLevel { COMMAND, INFO, RESULT, ERROR, SYSTEM }

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val text: String,
) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
}

sealed class OperationStatus {
    object Idle : OperationStatus()
    data class Running(val opLabel: String) : OperationStatus()
    data class Success(val opLabel: String, val message: String? = null) : OperationStatus()
    data class Failed(val opLabel: String, val message: String) : OperationStatus()
}

sealed class FastbootSubScreen {
    object TilesList : FastbootSubScreen()
    object Reboot : FastbootSubScreen()
    object FlashImage : FastbootSubScreen()
    object BootImage : FastbootSubScreen()
    object LockState : FastbootSubScreen()
    object Variables : FastbootSubScreen()
}

/** Commands that require a local file to be attached before they can be sent. */
sealed class PendingFileCommand {
    data class Flash(val partition: String) : PendingFileCommand()
    object Boot : PendingFileCommand()
    data class Fetch(val partition: String) : PendingFileCommand()
}