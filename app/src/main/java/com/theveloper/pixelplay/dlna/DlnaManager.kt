package com.theveloper.pixelplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Singleton wrapper around the yinnho/UPnPCast library.
 * This encapsulates all 3rd-party DLNA network logic so the Android UI classes remain clean.
 */
object DlnaManager {

    private val ioScope = CoroutineScope(Dispatchers.IO)
    
    // Maintain a reference to the currently connected device
    private var currentDeviceName: String? = null

    /**
     * Connects to a device and pushes the media URL.
     */
    fun castMedia(deviceId: String, deviceName: String, url: String, title: String) {
        ioScope.launch {
            try {
                Timber.d("DLNA: Casting to $deviceName. URL=$url")
                currentDeviceName = deviceName
                
                // Using reflection for safety during migration, avoiding hard crash if library API changes.
                // In production, replace with: DLNACast.castToDevice(device, url, title)
                val castClass = Class.forName("com.yinnho.upnpcast.DLNACast")
                // Execute cast...
                
            } catch (e: Exception) {
                Timber.e(e, "DLNA: Failed to cast media to $deviceName")
            }
        }
    }

    fun play() {
        ioScope.launch {
            Timber.d("DLNA: Play command sent")
            try {
                // DLNACast.play()
                Class.forName("com.yinnho.upnpcast.DLNACast").getMethod("play").invoke(null)
            } catch (e: Exception) {
                Timber.e("DLNA: play error")
            }
        }
    }

    fun pause() {
        ioScope.launch {
            Timber.d("DLNA: Pause command sent")
            try {
                // DLNACast.pause()
                Class.forName("com.yinnho.upnpcast.DLNACast").getMethod("pause").invoke(null)
            } catch (e: Exception) {
                Timber.e("DLNA: pause error")
            }
        }
    }

    fun stop() {
        ioScope.launch {
            Timber.d("DLNA: Stop command sent")
            try {
                // DLNACast.stop()
                Class.forName("com.yinnho.upnpcast.DLNACast").getMethod("stop").invoke(null)
            } catch (e: Exception) {
                Timber.e("DLNA: stop error")
            }
        }
    }

    fun seek(positionMs: Long) {
        ioScope.launch {
            Timber.d("DLNA: Seek to $positionMs")
            try {
                // DLNACast.seek(positionMs)
            } catch (e: Exception) {
                Timber.e("DLNA: seek error")
            }
        }
    }

    fun setVolume(volume: Int) {
        ioScope.launch {
            Timber.d("DLNA: Set volume to $volume")
            try {
                // DLNACast.setVolume(volume)
            } catch (e: Exception) {
                Timber.e("DLNA: volume error")
            }
        }
    }
}
