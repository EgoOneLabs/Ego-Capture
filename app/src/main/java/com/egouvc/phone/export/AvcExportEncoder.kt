package com.egouvc.phone.export

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.Closeable
import java.io.File

/** Post-recording conversion only. Sequential left/right encoding avoids two simultaneous codecs. */
internal class AvcExportEncoder(
    private val file: File, private val width: Int, private val height: Int,
    private val onFrame: (Long) -> Unit,
) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var mux: AvcMatroskaWriter? = null
    private var eos = false
    private var started = false
    private var lastProgress = SystemClock.elapsedRealtime()
    private var lastInputPts = -1L
    private val rgb = IntArray(width * height)

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
        } catch (e: Exception) { codec.release(); throw e }
    }

    fun submit(bitmap: Bitmap, cropX: Int, ptsUs: Long) {
        require(ptsUs > lastInputPts) { "输入视频时间戳未递增" }
        val slot = inputSlot()
        val inputSize = codec.getInputBuffer(slot)?.capacity() ?: error("无编码输入缓冲")
        val image = codec.getInputImage(slot) ?: error("手机编码器不提供 YUV 输入图像")
        try {
            bitmap.getPixels(rgb, 0, width, cropX, 0, width, height)
            fill(image)
        } finally { image.close() }
        codec.queueInputBuffer(slot, 0, inputSize, ptsUs, 0)
        lastInputPts = ptsUs
        drain(false)
    }

    private fun inputSlot(): Int {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) return i
            drain(false); checkTimeout()
        }
    }
    fun finish() {
        val slot = inputSlot()
        codec.queueInputBuffer(slot, 0, 0, lastInputPts + 33334, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        while (!eos) { drain(true); checkTimeout() }
        check(mux != null && mux!!.frames() > 0) { "编码器没有输出视频帧" }
    }
    private fun drain(wait: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (wait) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(mux == null) { "编码器重复更改格式" }
                    val f = codec.outputFormat
                    fun bytes(key: String): ByteArray? = f.getByteBuffer(key)?.duplicate()?.let { b ->
                        ByteArray(b.remaining()).also { b.get(it) }
                    }
                    mux = AvcMatroskaWriter(file, width, height, bytes("csd-0"), bytes("csd-1"))
                    lastProgress = SystemClock.elapsedRealtime()
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val b = codec.getOutputBuffer(index) ?: error("无编码输出缓冲")
                            b.position(info.offset); b.limit(info.offset + info.size)
                            val data = ByteArray(info.size); b.get(data)
                            (mux ?: error("尚无视频编码配置")).write(data, info.presentationTimeUs,
                                info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            onFrame(info.presentationTimeUs)
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        lastProgress = SystemClock.elapsedRealtime()
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (eos) return
                }
            }
        }
    }
    private fun checkTimeout() {
        check(SystemClock.elapsedRealtime() - lastProgress < 30_000) { "视频编码器30秒无输出，导出已停止" }
    }
    private fun fill(image: Image) {
        check(image.planes.size == 3 && image.cropRect.width() == width && image.cropRect.height() == height && image.cropRect.left == 0 && image.cropRect.top == 0) {
            "编码器 YUV 尺寸不符合要求"
        }
        val planes = image.planes
        val bases = IntArray(3) { planes[it].buffer.position() }
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = rgb[y * width + x]
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            val yy = ((66*r + 129*g + 25*b + 128) shr 8) + 16
            planes[0].buffer.put(bases[0] + y * planes[0].rowStride + x * planes[0].pixelStride,
                yy.coerceIn(0,255).toByte())
            if (y % 2 == 0 && x % 2 == 0) {
                var sr=0; var sg=0; var sb=0
                for (dy in 0..1) for (dx in 0..1) {
                    val c=rgb[(y+dy)*width+x+dx]; sr+=c shr 16 and 255; sg+=c shr 8 and 255; sb+=c and 255
                }
                sr/=4; sg/=4; sb/=4
                val u=(( -38*sr - 74*sg + 112*sb + 128) shr 8)+128
                val v=((112*sr - 94*sg - 18*sb + 128) shr 8)+128
                for (i in 1..2) planes[i].buffer.put(bases[i]+(y/2)*planes[i].rowStride+(x/2)*planes[i].pixelStride,
                    (if(i==1)u else v).coerceIn(0,255).toByte())
            }
        }
    }
    override fun close() {
        try { if (started) codec.stop() } finally {
            try { codec.release() } finally { mux?.close() }
        }
    }
}
