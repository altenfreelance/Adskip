package com.adskip.app

import org.junit.Assert.assertEquals
import org.junit.Test

class IrPatternsTest {

    @Test
    fun necFrameHasLeaderThirtyTwoBitsStopAndGap() {
        val p = IrPatterns.build(Proto.NEC, 0x20DF906FL)
        assertEquals(68, p.size)
        assertEquals(9000, p[0])
        assertEquals(4500, p[1])
        assertEquals(40000, p[p.size - 1])
    }

    @Test
    fun samsungFrameUsesShortLeader() {
        val p = IrPatterns.build(Proto.SAMSUNG, 0xE0E0F00FL)
        assertEquals(4500, p[0])
        assertEquals(4500, p[1])
    }

    @Test
    fun bitsAreSentMostSignificantFirst() {
        val p = IrPatterns.build(Proto.NEC, 0x80000000L)
        assertEquals(1690, p[3])  // first bit is a one
        assertEquals(560, p[5])   // second bit is a zero
    }
}
