package io.github.rhythmcache.dioxamine

import android.app.Application
import io.github.rhythmcache.dioxamine.core.AppLogger

class DioxamineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
