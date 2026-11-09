package com.adskip.app

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Looks at the camera's brightness plane about twice a second.
 * Reports whether the picture just cut to a different scene and whether a
 * static, high-contrast patch (a channel logo) sits in any screen corner.
 */
class FrameAnalyzer(
    private val onStep: (cut: Boolean, logo: Boolean, diff: Float) -> Unit
) : ImageAnalysis.Analyzer {

    private val gridW = 64
    private val gridH = 36
    private val patchW = 10
    private val patchH = 6
    private val corners = listOf(
        0 to 0,
        gridW - patchW to 0,
        0 to gridH - patchH,
        gridW - patchW to gridH - patchH
    )

    private var prev: FloatArray? = null
    private var lastTs = 0L
    private val recent = ArrayList<Float>()   // last minute of frame-to-frame changes

    override fun analyze(image: ImageProxy) {
        try {
            val ts = image.imageInfo.timestamp
            if (ts - lastTs < 450_000_000L) return
            lastTs = ts

            val plane = image.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val iw = image.width
            val ih = image.height

            val cur = FloatArray(gridW * gridH)
            var sum = 0f
            for (y in 0 until gridH) {
                val y0 = y * ih / gridH
                val y1 = minOf(ih - 1, y0 + ih / (2 * gridH))
                for (x in 0 until gridW) {
                    val x0 = x * iw / gridW
                    val x1 = minOf(iw - 1, x0 + iw / (2 * gridW))
                    val v = (px(buf, y0, x0, rs, ps) + px(buf, y0, x1, rs, ps) +
                        px(buf, y1, x0, rs, ps) + px(buf, y1, x1, rs, ps)) / 4f
                    cur[y * gridW + x] = v
                    sum += v
                }
            }
            // Remove overall brightness so auto-exposure changes do not look like cuts.
            val mean = sum / (gridW * gridH)
            for (i in cur.indices) cur[i] -= mean

            val p = prev
            var cut = false
            var logo = false
            var diffVal = 0f
            if (p != null) {
                var d = 0f
                for (i in cur.indices) d += abs(cur[i] - p[i])
                d /= cur.size
                // A cut must stand well above this room's usual frame-to-frame noise
                // (screen flicker, camera grain), so the bar adapts to the setup.
                val noise = median(recent)
                cut = d > maxOf(20f, 3f * noise)
                diffVal = d
                recent.add(d)
                if (recent.size > 120) recent.removeAt(0)

                val n = (patchW * patchH).toFloat()
                for ((cx, cy) in corners) {
                    var s = 0f
                    var s2 = 0f
                    var dd = 0f
                    for (yy in 0 until patchH) {
                        for (xx in 0 until patchW) {
                            val i = (cy + yy) * gridW + cx + xx
                            val v = cur[i]
                            s += v
                            s2 += v * v
                            dd += abs(v - p[i])
                        }
                    }
                    val m = s / n
                    val sd = sqrt(max(0f, s2 / n - m * m))
                    if (sd > 14f && dd / n < 5f) logo = true
                }
            }
            prev = cur
            onStep(cut, logo, diffVal)
        } finally {
            image.close()
        }
    }

    private fun median(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        val sorted = list.sorted()
        return sorted[sorted.size / 2]
    }

    private fun px(b: ByteBuffer, y: Int, x: Int, rs: Int, ps: Int): Float =
        (b.get(y * rs + x * ps).toInt() and 0xFF).toFloat()
}
