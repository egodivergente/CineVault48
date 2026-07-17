package com.vidal.cinevault.core

/** Injectable clock so retention behavior can be tested deterministically. */
fun interface Clock {
    /** Returns the current instant as Unix epoch milliseconds. */
    fun nowMillis(): Long
}

/** Production clock backed by the Android device time. */
object SystemClock : Clock {
    /** Reads the wall-clock time reported by the Android device. */
    override fun nowMillis(): Long = System.currentTimeMillis()
}
