package com.theveloper.pixelplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero-dependency native DLNA AVTransport client.
 * Discovers the exact controlURL from the TV's location XML and sends standard SOAP requests.
 */
object DlnaManager {

    private val ioScope = CoroutineScope(Dispatchers.IO)
    
    private var currentControlUrl: String? = null
    private var currentDeviceName: String? = null

    fun castMedia(deviceId: String, deviceName: String, mediaUrl: String, title: String) {
        ioScope.launch {
            try {
                Timber.d("DLNA: Casting to $deviceName. URL=$mediaUrl location=$deviceId") // deviceId stores location URL
                currentDeviceName = deviceName
                
                val locationUrl = deviceId
                val controlUrl = fetchAvTransportControlUrl(locationUrl)
                if (controlUrl == null) {
                    Timber.e("DLNA: Could not find AVTransport control URL for $deviceName")
                    return@launch
                }
                
                currentControlUrl = controlUrl
                
                // 1. SetAVTransportURI
                val setUriSuccess = sendSoapAction(controlUrl, "SetAVTransportURI", """
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>$mediaUrl</CurrentURI>
                        <CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="1" parentID="0" restricted="1"&gt;&lt;dc:title&gt;$title&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                """.trimIndent())
                
                if (setUriSuccess) {
                    // 2. Play
                    sendSoapAction(controlUrl, "Play", """
                        <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            <InstanceID>0</InstanceID>
                            <Speed>1</Speed>
                        </u:Play>
                    """.trimIndent())
                }
            } catch (e: Exception) {
                Timber.e(e, "DLNA: Failed to cast media to $deviceName")
            }
        }
    }

    fun play() {
        currentControlUrl?.let { url ->
            ioScope.launch {
                sendSoapAction(url, "Play", """
                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Speed>1</Speed>
                    </u:Play>
                """.trimIndent())
            }
        }
    }

    fun pause() {
        currentControlUrl?.let { url ->
            ioScope.launch {
                sendSoapAction(url, "Pause", """
                    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Pause>
                """.trimIndent())
            }
        }
    }

    fun stop() {
        currentControlUrl?.let { url ->
            ioScope.launch {
                sendSoapAction(url, "Stop", """
                    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Stop>
                """.trimIndent())
            }
        }
    }

    fun seek(positionMs: Long) {
        val seconds = positionMs / 1000
        val target = String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        currentControlUrl?.let { url ->
            ioScope.launch {
                sendSoapAction(url, "Seek", """
                    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Unit>REL_TIME</Unit>
                        <Target>$target</Target>
                    </u:Seek>
                """.trimIndent())
            }
        }
    }

    fun setVolume(volume: Int) {} // RenderingControl service needed for volume

    private suspend fun fetchAvTransportControlUrl(location: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(location)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            
            // Extract baseUrl
            var baseUrl = "${url.protocol}://${url.host}:${url.port}"
            val urlBaseMatch = "<URLBase>(.*?)</URLBase>".toRegex().find(xml)
            if (urlBaseMatch != null) {
                baseUrl = urlBaseMatch.groupValues[1].removeSuffix("/")
            }
            
            // Extract AVTransport controlURL
            // Since regex on XML is brittle, we'll do a simple substring extraction looking for the service type
            val serviceIdx = xml.indexOf("urn:schemas-upnp-org:service:AVTransport:1")
            if (serviceIdx != -1) {
                val controlUrlStartIdx = xml.indexOf("<controlURL>", serviceIdx)
                if (controlUrlStartIdx != -1) {
                    val controlUrlEndIdx = xml.indexOf("</controlURL>", controlUrlStartIdx)
                    var controlPath = xml.substring(controlUrlStartIdx + 12, controlUrlEndIdx).trim()
                    if (!controlPath.startsWith("/")) {
                        controlPath = "/$controlPath"
                    }
                    return@withContext baseUrl + controlPath
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch device description from $location")
        }
        return@withContext null
    }

    private suspend fun sendSoapAction(url: String, action: String, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            
            val soapEnvelope = """
                <?xml version="1.0" encoding="utf-8" standalone="yes"?>
                <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                    <s:Body>
                        $body
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
            
            OutputStreamWriter(connection.outputStream).use { it.write(soapEnvelope) }
            
            val code = connection.responseCode
            if (code == 200) {
                return@withContext true
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Timber.e("SOAP Action $action failed: HTTP $code $error")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send SOAP action $action to $url")
        }
        return@withContext false
    }
}
