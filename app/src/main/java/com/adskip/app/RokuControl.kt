package com.adskip.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Controls a Roku (or a Roku TV) over Wi-Fi using Roku's built-in control
 * service on port 8060. No IR codes needed. Keys used: Fwd, Play, VolumeMute.
 */
class RokuControl {

    @Volatile var host: String = ""

    fun valid(h: String): Boolean = Regex("^[0-9A-Za-z.-]{3,64}\$").matches(h)

    fun keypress(key: String, onDone: (Boolean, String) -> Unit) {
        val h = host
        Thread {
            try {
                val c = URL("http://$h:8060/keypress/$key").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 2500
                c.readTimeout = 2500
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
                val code = c.responseCode
                c.disconnect()
                onDone(code in 200..299, "HTTP $code")
            } catch (e: Exception) {
                onDone(false, e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    /** Looks for a Roku on the local network. Calls back with its IP address, or null. */
    fun discover(context: Context, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        Thread {
            var found: String? = null
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("adskip-ssdp")
            try {
                lock?.setReferenceCounted(false)
                lock?.acquire()
                val sock = DatagramSocket()
                sock.soTimeout = 1500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: roku:ecp\r\n\r\n").toByteArray()
                sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048)
                val deadline = SystemClock.elapsedRealtime() + 4000
                while (found == null && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        val m = Regex("(?i)location:\\s*http://([0-9.]+):8060").find(text)
                        if (m != null) found = m.groupValues[1]
                    } catch (e: SocketTimeoutException) {
                        // keep waiting until the deadline
                    }
                }
                sock.close()
            } catch (e: Exception) {
                // fall through with whatever was found
            } finally {
                try {
                    lock?.release()
                } catch (e: Exception) {
                    // not held
                }
            }
            onResult(found)
        }.start()
    }
}
