package com.egouvc.phone.recording

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Stereo/mono PCM16 RIFF. Header is finalized even after a partial recording. */
class PcmWaveWriter(file: File, private val rate: Int, private val channels: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    var dataBytes = 0L
        private set
    private var closed = false
    init {
        require(rate > 0 && channels in 1..2)
        out.setLength(0)
        out.write(ByteArray(44))
    }
    fun write(bytes: ByteArray, size: Int) {
        check(!closed)
        require(size in 0..bytes.size && size % (channels * 2) == 0)
        check(dataBytes + size <= 0xffff_ffffL - 36L) { "PCM WAV exceeds RIFF size limit" }
        out.write(bytes, 0, size)
        dataBytes += size
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            out.seek(0)
            out.writeBytes("RIFF"); u32(dataBytes + 36); out.writeBytes("WAVEfmt ")
            u32(16); u16(1); u16(channels); u32(rate.toLong())
            u32(rate.toLong() * channels * 2); u16(channels * 2); u16(16)
            out.writeBytes("data"); u32(dataBytes)
        } finally { out.close() }
    }
    private fun u16(n: Int) { repeat(2) { out.write(n ushr (it * 8)) } }
    private fun u32(n: Long) { repeat(4) { out.write((n ushr (it * 8)).toInt()) } }
}
