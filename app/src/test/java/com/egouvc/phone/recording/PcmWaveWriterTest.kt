package com.egouvc.phone.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWaveWriterTest {
    @Test fun stereoPcmHasCorrectHeaderAndUnchangedPayload() {
        val file = File.createTempFile("usb-audio", ".wav")
        try {
            val payload = ByteArray(48000 * 4) { (it % 251).toByte() }
            PcmWaveWriter(file, 48000, 2).use { it.write(payload, payload.size) }
            val bytes = file.readBytes()
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals(payload.size + 36, b.getInt(4))
            assertEquals(2, b.getShort(22).toInt())
            assertEquals(48000, b.getInt(24))
            assertEquals(192000, b.getInt(28))
            assertEquals(16, b.getShort(34).toInt())
            assertEquals(payload.size, b.getInt(40))
            assertArrayEquals(payload, bytes.copyOfRange(44, bytes.size))
        } finally { file.delete() }
    }
    @Test fun partialCaptureIsFinalizedWhenScopeThrows() {
        val file = File.createTempFile("usb-audio", ".wav")
        try {
            try { PcmWaveWriter(file,48000,2).use { it.write(ByteArray(400),400); error("simulated read failure") } }
            catch (_: IllegalStateException) { }
            val b=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(444L,file.length()); assertEquals(400,b.getInt(40))
        } finally { file.delete() }
    }
    @Test fun rejectPartialStereoFrameWithoutAddingBytes() {
        val file=File.createTempFile("usb-audio", ".wav")
        try {
            PcmWaveWriter(file,48000,2).use {
                try { it.write(ByteArray(3),3); fail("must reject") } catch (_:IllegalArgumentException) { }
                assertEquals(0L,it.dataBytes)
            }
            assertEquals(44L,file.length())
        } finally { file.delete() }
    }
}
