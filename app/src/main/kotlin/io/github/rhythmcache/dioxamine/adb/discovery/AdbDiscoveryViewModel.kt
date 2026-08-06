package io.github.rhythmcache.dioxamine.adb.discovery

import android.content.Context
import androidx.lifecycle.ViewModel

class AdbDiscoveryViewModel(context: Context) : ViewModel() {
    private val nsd = NsdAdbDiscovery(context)

    val devices get() = nsd.discovered

    fun startDiscovery() = nsd.start()
    fun stopDiscovery() = nsd.stop()

    override fun onCleared() {
        super.onCleared()
        nsd.stop()
    }
}
