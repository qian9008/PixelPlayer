package com.theveloper.pixelplay.dlna

import android.content.Intent
import android.net.Uri
import androidx.mediarouter.media.MediaRouteProvider
import timber.log.Timber

/**
 * Handles media control actions (play, pause, seek, volume) for a specific DLNA device.
 * It translates Android MediaRouter commands into UPnP AVTransport and RenderingControl SOAP requests.
 */
class DlnaRouteController(private val routeId: String) : MediaRouteProvider.RouteController() {

    override fun onSelect() {
        Timber.d("DLNA Route selected: $routeId")
        // TODO: Connect to the UPnP device and prepare for AVTransport
    }

    override fun onUnselect() {
        Timber.d("DLNA Route unselected: $routeId")
        onStop()
    }

    override fun onUnselect(reason: Int) {
        Timber.d("DLNA Route unselected with reason: $reason")
        onStop()
    }

    override fun onSetVolume(volume: Int) {
        Timber.d("DLNA Route setVolume: $volume")
        DlnaManager.setVolume(volume)
    }

    override fun onUpdateVolume(delta: Int) {
        Timber.d("DLNA Route updateVolume delta: $delta")
        // Typically UPnP needs absolute volume. We'd track current volume or query it.
        // For simplicity, we just log it until current volume tracking is added.
    }

    override fun onControlRequest(intent: Intent, callback: androidx.mediarouter.media.MediaRouter.ControlRequestCallback?): Boolean {
        // Handle custom intent actions like Play/Pause/Seek
        val action = intent.action
        Timber.d("DLNA Route ControlRequest: $action")
        
        when (action) {
            "android.media.intent.action.PLAY" -> {
                val uri = intent.data
                if (uri != null) {
                    playMedia(uri)
                } else {
                    resume()
                }
                callback?.onResult(null)
                return true
            }
            "android.media.intent.action.PAUSE" -> {
                pause()
                callback?.onResult(null)
                return true
            }
            "android.media.intent.action.STOP" -> {
                onStop()
                callback?.onResult(null)
                return true
            }
            "android.media.intent.action.SEEK" -> {
                val position = intent.getLongExtra("android.media.intent.extra.ITEM_POSITION", 0)
                seek(position)
                callback?.onResult(null)
                return true
            }
        }
        
        return super.onControlRequest(intent, callback)
    }

    private fun playMedia(uri: Uri) {
        Timber.d("DLNA PlayMedia: $uri")
        DlnaManager.castMedia(routeId, "DLNA Device", uri.toString(), "PixelPlayer Audio")
    }

    private fun resume() {
        Timber.d("DLNA Resume")
        DlnaManager.play()
    }

    private fun pause() {
        Timber.d("DLNA Pause")
        DlnaManager.pause()
    }

    private fun onStop() {
        Timber.d("DLNA Stop")
        DlnaManager.stop()
    }

    private fun seek(positionMs: Long) {
        Timber.d("DLNA Seek to: $positionMs")
        DlnaManager.seek(positionMs)
    }
}
