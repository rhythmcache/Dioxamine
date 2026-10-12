package io.github.rhythmcache.dioxamine.adb.shell

/**
 * Lifecycle states for a shell session.
 */
enum class ShellSessionState {
    IDLE, STARTING, ACTIVE, CLOSED, ERROR
}
