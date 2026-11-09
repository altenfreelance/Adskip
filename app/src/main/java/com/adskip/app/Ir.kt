package com.adskip.app

enum class Proto { NEC, SAMSUNG }

data class Remote(
    val name: String,
    val proto: Proto,
    val mute: Long,
    val play: Long,
    val ff: Long
)

/**
 * Starting codes for common TVs, written as the 32-bit value sent most
 * significant bit first. They are best guesses. Use the Test buttons in the
 * app and edit the hex if a button does nothing on your TV.
 */
object Remotes {
    val all = listOf(
        Remote("Samsung (guess)", Proto.SAMSUNG, 0xE0E0F00FL, 0xE0E0E01FL, 0xE0E0E21DL),
        Remote("LG (guess)", Proto.NEC, 0x20DF906FL, 0x20DF0DF2L, 0x20DF8D72L),
        Remote("Custom NEC", Proto.NEC, 0L, 0L, 0L),
        Remote("Custom Samsung", Proto.SAMSUNG, 0L, 0L, 0L)
    )
}

object IrPatterns {
    const val CARRIER_HZ = 38000

    /** On/off durations in microseconds, starting with an "on" burst. */
    fun build(proto: Proto, value: Long): IntArray {
        val out = ArrayList<Int>()
        when (proto) {
            Proto.NEC -> {
                out.add(9000)
                out.add(4500)
            }
            Proto.SAMSUNG -> {
                out.add(4500)
                out.add(4500)
            }
        }
        for (i in 31 downTo 0) {
            val bit = (value shr i) and 1L
            out.add(560)
            out.add(if (bit == 1L) 1690 else 560)
        }
        out.add(560)    // stop burst
        out.add(40000)  // gap after the frame
        return out.toIntArray()
    }
}
