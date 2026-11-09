package com.adskip.app

enum class Mode { RECORDED, LIVE }

/**
 * Decides whether the TV is showing an ad, one step every 0.5 s.
 * Pure Kotlin, no Android classes, so it can be tested on its own.
 *
 * Signals: cut rate (ads cut faster), volume jump (ads are louder than the
 * show), and the channel logo in a screen corner (it usually disappears in ads).
 */
class AdDetector {
    var sensitivity = 50
    var mode = Mode.RECORDED

    var inAd = false
        private set

    data class Step(val cut: Boolean, val logo: Boolean, val db: Double?)

    data class Result(
        val inAd: Boolean,
        val changed: Boolean,
        val score: Double,
        val threshold: Double,
        val cuts: Int,
        val logoPct: Int,
        val dbDelta: Double?
    )

    private val cutHist = ArrayDeque<Int>()
    private val logoHist = ArrayDeque<Int>()
    private val dbHist = ArrayDeque<Double>()
    private val dbLong = ArrayDeque<Double>()
    private var above = 0
    private var below = 0
    private var adSteps = 0
    private var cooldown = 0

    fun threshold(): Double = 0.7 - sensitivity * 0.004

    fun reset() {
        cutHist.clear()
        logoHist.clear()
        dbHist.clear()
        dbLong.clear()
        inAd = false
        above = 0
        below = 0
        adSteps = 0
        cooldown = 0
    }

    fun forceExit() {
        inAd = false
        above = 0
        below = 0
        cooldown = COOLDOWN_STEPS
    }

    fun step(s: Step): Result {
        if (cooldown > 0) cooldown--
        if (inAd) adSteps++
        push(cutHist, if (s.cut) 1 else 0, WIN)
        push(logoHist, if (s.logo) 1 else 0, WIN)

        // Audio only counts while we believe it is the show. Once the TV is
        // muted or fast-forwarding, the volume says nothing about the picture.
        val db = s.db
        if (!inAd && db != null && db > SILENCE_DB) {
            push(dbHist, db, WIN)
            push(dbLong, db, LONG)
        }

        val ready = cutHist.size >= WIN
        val cuts = cutHist.sum()
        val logoRatio = if (logoHist.isEmpty()) 1.0 else logoHist.sum().toDouble() / logoHist.size

        var delta: Double? = null
        if (!inAd && dbHist.size >= WIN && dbLong.size >= 40) {
            delta = dbHist.average() - median(dbLong)
        }

        // While fast-forwarding, every frame changes quickly, so cut rate is meaningless.
        val useCut = !(inAd && mode == Mode.RECORDED)

        var num = 0.0
        var den = 0.0
        if (useCut) {
            num += 0.42 * clamp((cuts - 1) / 3.0)
            den += 0.42
        }
        if (delta != null) {
            num += 0.33 * clamp((delta - 0.5) / 4.0)
            den += 0.33
        }
        num += 0.25 * (1.0 - logoRatio)
        den += 0.25
        val score = num / den

        val thr = threshold()
        var changed = false
        if (ready) {
            if (!inAd) {
                above = if (score > thr && cooldown == 0) above + 1 else 0
                if (above >= ENTER_STEPS) {
                    inAd = true
                    above = 0
                    below = 0
                    adSteps = 0
                    changed = true
                }
            } else if (adSteps >= MIN_AD_STEPS) {
                // Real ad breaks last at least ~12 s. The show must look like a show
                // (clearly below the entry bar) for 8 s before we call it back.
                below = if (score <= thr - 0.10) below + 1 else 0
                if (below >= EXIT_STEPS) {
                    inAd = false
                    above = 0
                    below = 0
                    cooldown = COOLDOWN_STEPS
                    changed = true
                }
            }
        }
        return Result(inAd, changed, score, thr, cuts, (logoRatio * 100).toInt(), delta)
    }

    private fun <T> push(q: ArrayDeque<T>, v: T, max: Int) {
        q.addLast(v)
        while (q.size > max) q.removeFirst()
    }

    private fun clamp(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

    private fun median(q: ArrayDeque<Double>): Double {
        val sorted = q.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        const val WIN = 13          // 6.5 s of history
        const val LONG = 240        // 2 min of volume history for the baseline
        const val ENTER_STEPS = 8   // 4 s above threshold to call an ad
        const val EXIT_STEPS = 16   // 8 s clearly below threshold to call the show back
        const val MIN_AD_STEPS = 24 // an ad lasts at least 12 s before it can end
        const val COOLDOWN_STEPS = 40 // 20 s after an ad ends before another can start
        const val SILENCE_DB = -60.0
    }
}
