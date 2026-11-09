package com.adskip.app

import android.content.Context
import android.hardware.ConsumerIrManager

class IrSender(context: Context) {
    private val mgr: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean
        get() = try {
            mgr?.hasIrEmitter() == true
        } catch (e: Exception) {
            false
        }

    fun sendRaw(freq: Int, timings: IntArray): Boolean {
        val m = mgr ?: return false
        return try {
            m.transmit(freq, timings)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun send(proto: Proto, value: Long): Boolean {
        val m = mgr ?: return false
        return try {
            m.transmit(IrPatterns.CARRIER_HZ, IrPatterns.build(proto, value))
            true
        } catch (e: Exception) {
            false
        }
    }
}
