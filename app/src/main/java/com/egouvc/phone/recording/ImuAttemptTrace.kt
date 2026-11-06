package com.egouvc.phone.recording

import java.io.Writer

/** Single IMU-thread producer; export only after join. Bounded primitive storage, no hot-path I/O. */
internal class ImuAttemptTrace(val capacity: Int = 30000) {
    init { require(capacity in 1..30000) }
    private val data = LongArray(capacity * 14)
    var size = 0
        private set
    var omitted = 0L
        private set

    fun record(attempt: Long, pollStart: Long, before: Long, after: Long, returned: Long,
               outcome: Long, first: Long, last: Long, writeStart: Long, writeEnd: Long,
               flushed: Long, sleepMs: Long, sleepStart: Long, sleepEnd: Long) {
        if (size == capacity) { omitted++; return }
        val i = size * 14
        data[i] = attempt; data[i+1] = pollStart; data[i+2] = before; data[i+3] = after
        data[i+4] = returned; data[i+5] = outcome; data[i+6] = first; data[i+7] = last
        data[i+8] = writeStart; data[i+9] = writeEnd; data[i+10] = flushed
        data[i+11] = sleepMs; data[i+12] = sleepStart; data[i+13] = sleepEnd
        size++
    }

    fun writeTo(writer: Writer) {
        writer.write("attempt,poll_start_boot_ns,usb_before_boot_ns,usb_after_boot_ns,returned_bytes,outcome,first_counter,last_counter,write_start_boot_ns,write_end_boot_ns,flush_attempted,sleep_requested_ms,sleep_start_boot_ns,sleep_end_boot_ns\n")
        for (row in 0 until size) {
            for (column in 0 until 14) {
                if (column > 0) writer.write(",")
                writer.write(data[row * 14 + column].toString())
            }
            writer.write("\n")
        }
    }
}
