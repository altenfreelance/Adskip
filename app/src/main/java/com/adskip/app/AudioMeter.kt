package com.adskip.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/** Reports the microphone level in dB (relative to full scale) every half second. */
class AudioMeter(private val onDb: (Double) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, rate)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        running = true
        thread = Thread {
            val buf = ShortArray(rate / 2)
            rec.startRecording()
            while (running) {
                var read = 0
                while (read < buf.size && running) {
                    val r = rec.read(buf, read, buf.size - read)
                    if (r <= 0) break
                    read += r
                }
                if (read > 0) {
                    var s = 0.0
                    for (i in 0 until read) {
                        val v = buf[i] / 32768.0
                        s += v * v
                    }
                    onDb(20.0 * log10(sqrt(s / read) + 1e-5))
                }
            }
            rec.stop()
            rec.release()
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread = null
    }
}
