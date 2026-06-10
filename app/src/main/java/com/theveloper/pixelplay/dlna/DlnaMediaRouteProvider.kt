package com.theveloper.pixelplay.dlna

import android.content.Context
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A custom MediaRouteProvider that discovers DLNA/UPnP devices on the local network
 * and exposes them as routes to the Android MediaRouter ecosystem.
 */
class DlnaMediaRouteProvider(context: Context) : MediaRouteProvider(context) {

    private val providerScope = CoroutineScope(Dispatchers.IO)
    private var discoveryJob: Job? = null
    
    // In-memory cache of discovered devices
    private val discoveredDevices = mutableMapOf<String, DlnaDevice>()

    init {
        Timber.d("DlnaMediaRouteProvider initialized")
    }

    override fun onCreateRouteController(routeId: String): RouteController? {
        Timber.d("Creating DLNA RouteController for routeId: $routeId")
        return DlnaRouteController(routeId)
    }

    override fun onDiscoveryRequestChanged(request: androidx.mediarouter.media.MediaRouteDiscoveryRequest?) {
        super.onDiscoveryRequestChanged(request)
        if (request != null && request.isActiveScan) {
            Timber.d("Active DLNA scan requested")
            startDiscovery()
        } else {
            Timber.d("DLNA scan stopped")
            discoveryJob?.cancel()
        }
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        
        // Acquire MulticastLock to ensure UDP broadcast packets for SSDP aren't dropped by Android
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val multicastLock = wifiManager?.createMulticastLock("PixelPlayerDLNALock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()

        discoveryJob = providerScope.launch {
            // Continuously scan every 5 seconds while active
            while (isActive) {
                try {
                    val ssdpRequest = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                    val socket = java.net.DatagramSocket()
                    socket.soTimeout = 3000
                    val address = java.net.InetAddress.getByName("239.255.255.250")
                    val sendData = ssdpRequest.toByteArray()
                    val packet = java.net.DatagramPacket(sendData, sendData.size, address, 1900)
                    socket.send(packet)

                    val receiveData = ByteArray(1024)
                    while (isActive) {
                        val receivePacket = java.net.DatagramPacket(receiveData, receiveData.size)
                        try {
                            socket.receive(receivePacket)
                            val response = String(receivePacket.data, 0, receivePacket.length)
                            
                            var usn = ""
                            var location = ""
                            var serverName = "DLNA Render Device"
                            
                            response.lines().forEach { line ->
                                if (line.startsWith("USN:", ignoreCase = true)) usn = line.substringAfter(":").trim()
                                if (line.startsWith("LOCATION:", ignoreCase = true)) location = line.substringAfter(":").trim()
                                if (line.startsWith("SERVER:", ignoreCase = true)) {
                                    val s = line.substringAfter(":").trim()
                                    if (s.isNotEmpty()) serverName = s
                                }
                            }
                            
                            if (usn.isNotEmpty() && location.isNotEmpty()) {
                                // Extracting a cleaner name if possible, or fallback to Server header
                                val device = DlnaDevice(usn, serverName, location)
                                discoveredDevices[device.id] = device
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            break // Scan timeout for this interval
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    Timber.e(e, "SSDP Discovery error")
                }
                
                publishRoutes()
                delay(5000)
            }
        }
        
        discoveryJob?.invokeOnCompletion { 
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        }
    }

    private fun publishRoutes() {
        // MediaRouter enforces strict main thread access for route publication
        CoroutineScope(Dispatchers.Main).launch {
            val builder = MediaRouteProviderDescriptor.Builder()

            for (device in discoveredDevices.values) {
                val controlFilter = android.content.IntentFilter().apply {
                    addCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                }
                val routeDescriptor = MediaRouteDescriptor.Builder(device.id, device.name)
                    .setDescription(device.manufacturer)
                    .addControlFilter(controlFilter)
                    .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_TV)
                    .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                    .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                    .setVolumeMax(100)
                    .setVolume(50) 
                    .build()
                
                builder.addRoute(routeDescriptor)
            }

            descriptor = builder.build()
        }
    }
}

data class DlnaDevice(
    val id: String,
    val name: String,
    val manufacturer: String
)
