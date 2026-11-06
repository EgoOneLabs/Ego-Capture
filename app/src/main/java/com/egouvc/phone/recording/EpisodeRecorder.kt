package com.egouvc.phone.recording

import android.content.Context
import com.egouvc.phone.export.EgoRecordFormat
import com.egouvc.phone.export.StereoEpisodeExporter
import com.egouvc.phone.BuildConfig
import com.egouvc.phone.recording.RecordingJobState.Stage
import org.json.JSONObject
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android V0.2 raw episode:
 * - saved video bytes are copied from the libuvc MJPEG callback before decoding
 * - raw 252B UVC XU IMU packets AND decoded axes, subject to Android EP0 access
 * - host callback and USB control-transfer bounds on CLOCK_BOOTTIME
 *
 * No UI work, USB reads or disk writes occur on the libuvc frame callback thread.
 */
class EpisodeRecorder(
    private val context: Context,
    private val device: UsbDevice,
    private val width: Int,
    private val height: Int,
    private val streamingConnection: UsbDeviceConnection?,
    private val monitorConnection: UsbDeviceConnection?,
    private val onRecordingStatus: (Stage, String) -> Unit,
    private val onMetrics: (RecordingMetrics) -> Unit = {},
    private val onAudioStatus: (String) -> Unit = {},
) {
    private val controller = Executors.newSingleThreadExecutor { Thread(it, "EgoUVC-Episode-Control") }
    private val closed = AtomicBoolean(false)
    @Volatile private var active: Episode? = null
    init { require(width > 0 && height > 0) }

    fun start() {
        val accepted = enqueue {
            if (active != null) return@enqueue
            if (closed.get()) {
                onRecordingStatus(Stage.FAILED, "无法开始采集：相机会话已关闭。")
                return@enqueue
            }
            var ep: Episode? = null
            try {
                val root = context.getExternalFilesDir("episodes") ?: error("没有应用外部文件目录")
                val dir = File(root, "episode_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}")
                check(dir.mkdirs()) { "创建 Episode 目录失败：$dir" }
                val prefs = context.getSharedPreferences("export_format", Context.MODE_PRIVATE)
                val taskName = EgoRecordFormat.component(prefs.getString("task_name", "task001"))
                val deviceId = EgoRecordFormat.component(prefs.getString("device_id", "2UQ2"))
                val targetFps = CaptureRates.checked(prefs.getInt("target_saved_fps", 30))
                val started = Episode(dir, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), taskName, deviceId, targetFps)
                ep = started
                // Explicitly start the video writer before announcing RECORDING.
                started.videoThread = Thread({ writeVideo(started) }, "EgoUVC-Episode-Video").apply { start() }
                started.imuThread = Thread({ pollImu(started) }, "EgoUVC-Episode-IMU").apply { start() }
                active = started
                started.audio = UsbAudioRecorder(context, dir, onAudioStatus).also { it.start() }
                onRecordingStatus(Stage.RECORDING, "采集中：原始 MJPEG 目标 ${started.targetFps} FPS，IMU XU 读取中… ${dir.absolutePath}")
            } catch (t: Exception) {
                Log.e(TAG, "Episode start failed", t)
                active = null
                ep?.let { it.running.set(false); stopEpisode(it, "启动失败") }
                onRecordingStatus(Stage.FAILED, "无法开始采集：${t.message}")
            }
        }
        if (!accepted) onRecordingStatus(Stage.FAILED, "无法开始采集：相机会话已关闭。")
    }

    fun stop() = enqueue {
        val ep = active ?: return@enqueue
        active = null
        stopEpisode(ep, "用户停止")
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        enqueue {
            val ep = active
            active = null
            if (ep != null) stopEpisode(ep, "相机关闭")
        }
        controller.shutdown()
    }

    /** Native libuvc MJPEG callback: select, copy and enqueue before the buffer expires. */
    fun onRawFrame(jpeg: ByteBuffer, callbackBootNs: Long, uvcSequence: Long,
                   frameWidth: Int, frameHeight: Int,
                   devicePtsRaw: Long = 0, deviceScrRaw: Long = 0, deviceSof: Int = 0,
                   timingFlags: Int = 0, reportedClockHz: Long = 0,
                   firstPtsRaw: Long = 0, payloadCount: Long = 0,
                   ptsCount: Long = 0, boundaryFlags: Long = 0) {
        val ep = active ?: return
        if (!ep.running.get()) return
        reportMetrics(ep, callbackBootNs)
        val frameIndex = ep.callbackCount.incrementAndGet()
        if (!ep.sampler.select(callbackBootNs)) return
        ep.selectedFrames.incrementAndGet()
        try {
            val dup = jpeg.duplicate().apply { rewind() }
            val size = dup.remaining()
            if (frameWidth != width || frameHeight != height || size < 4 || size > MAX_JPEG_BYTES ||
                (dup.get(0).toInt() and 255) != 0xff || (dup.get(1).toInt() and 255) != 0xd8 ||
                (dup.get(size - 2).toInt() and 255) != 0xff || (dup.get(size - 1).toInt() and 255) != 0xd9) {
                ep.invalidFrames.incrementAndGet()
                return
            }
            if (ep.frameQueue.remainingCapacity() == 0) {
                ep.backpressureDropped.incrementAndGet()
                return
            }
            val raw = ByteArray(size)
            dup.get(raw)
            if (!ep.running.get()) return
            val data = Frame(raw, frameIndex, uvcSequence, callbackBootNs,
                SystemClock.elapsedRealtimeNanos(), devicePtsRaw, deviceScrRaw,
                deviceSof, timingFlags, reportedClockHz, firstPtsRaw, payloadCount, ptsCount, boundaryFlags)
            if (!ep.frameQueue.offer(data)) ep.backpressureDropped.incrementAndGet()
        } catch (t: Exception) {
            ep.invalidFrames.incrementAndGet()
            Log.w(TAG, "Failed to copy MJPEG frame", t)
        }
    }

    private fun reportMetrics(ep: Episode, now: Long) {
        if (now - ep.metricsBootNs < 1_000_000_000L) return
        val bytes = ep.savedBytes.get()
        val elapsed = now - ep.startedBootNs
        onMetrics(RecordingMetrics(ep.targetFps,
            ep.savedFrames.get() * 1e9 / elapsed,
            CaptureRates.mbPerSec(bytes - ep.metricsBytes, now - ep.metricsBootNs),
            CaptureRates.mbPerSec(bytes, elapsed), bytes / 1e6))
        ep.metricsBootNs = now
        ep.metricsBytes = bytes
    }

    private fun writeVideo(ep: Episode) {
        try {
            BufferedOutputStream(FileOutputStream(File(ep.dir, "video.mjpg")), 1 shl 20).use { video ->
                File(ep.dir, "video_uvc_timing.csv").bufferedWriter().use { timing ->
                timing.write("frame_index,uvc_sequence,receive_boottime_ns,device_pts_raw_u32,device_scr_stc_raw_u32,device_scr_sof_u11,timing_flags,reported_clock_hz,first_pts_raw_u32,payload_header_count,pts_header_count,boundary_flags\n")
                File(ep.dir, "video_index.csv").bufferedWriter().use { csv ->
                    csv.write("frame_index,byte_offset,jpeg_bytes,frame_receive_boottime_ns,uvc_sequence\n")
                    var offset = 0L
                    while (ep.running.get() || ep.frameQueue.isNotEmpty()) {
                        val frame = ep.frameQueue.poll()
                        if (frame == null) { Thread.sleep(5); continue }
                        video.write(frame.raw)
                        csv.write("${frame.index},$offset,${frame.raw.size},${frame.bootNs},${frame.uvcSequence}\n")
                        timing.write("${frame.index},${frame.uvcSequence},${frame.bootNs},${frame.devicePtsRaw},${frame.deviceScrRaw},${frame.deviceSof},${frame.timingFlags},${frame.reportedClockHz},${frame.firstPtsRaw},${frame.payloadCount},${frame.ptsCount},${frame.boundaryFlags}\n")
                        offset += frame.raw.size
                        ep.savedBytes.set(offset)
                        ep.savedFrames.incrementAndGet()
                        val writeLatency = SystemClock.elapsedRealtimeNanos() - frame.queuedBootNs
                        ep.maxWriteLatencyNs.accumulateAndGet(writeLatency, ::maxOf)
                        if (ep.savedFrames.get() % 30L == 0L) { video.flush(); csv.flush(); timing.flush() }
                    }
                    video.flush(); csv.flush(); timing.flush()
                }
                }
            }
        } catch (t: Exception) {
            ep.videoError = t.message ?: t.javaClass.simpleName
            ep.running.set(false)
            Log.e(TAG, "Episode video writer failed", t)
        }
    }

    private fun pollImu(ep: Episode) {
        var stage = "initialization"
        val d = ep.imuDiagnostics
        try {
            XuImuReader(context, device, streamingConnection, monitorConnection).use { xu ->
                d.transport = xu.transportPath
                stage = "file_write"
                File(ep.dir, "imu_packets.bin").outputStream().buffered().use { raw ->
                    File(ep.dir, "imu_packets.csv").bufferedWriter().use { packets ->
                        File(ep.dir, "imu_samples.csv").bufferedWriter().use { samples ->
                            packets.write("packet_index,host_before_boottime_ns,host_after_boottime_ns,host_unix_ns,raw_offset_bytes,raw_bytes,first_counter,last_counter\n")
                            samples.write("packet_index,sample_index,host_before_boottime_ns,host_after_boottime_ns,host_unix_ns,counter_raw,ax_raw,ay_raw,az_raw,gx_raw,gy_raw,gz_raw\n")
                            var previous: ByteArray? = null
                            var lastCounter: Long? = null
                            var lastRequestStart: Long? = null
                            val streak = ImuFailureStreak()
                            while (ep.running.get()) {
                                val pollStart = SystemClock.elapsedRealtimeNanos()
                                lastRequestStart?.let { d.maxPollGapNs = maxOf(d.maxPollGapNs, pollStart - it) }
                                lastRequestStart = pollStart
                                var readSuccess = false
                                var before = 0L; var after = 0L; var returned = -999L
                                var outcome = 0L; var firstCounter = -1L; var endCounter = -1L
                                var writeStartNs = 0L; var writeEndNs = 0L; var flushed = 0L
                                var sleepMs = 0L; var sleepStartNs = 0L; var sleepEndNs = 0L
                                try {
                                // 只重试 USB/解析失败；磁盘写入失败直接退出，避免被误判为读包错误。
                                stage = "usb_transfer"
                                val packet = try {
                                    d.readAttempts++
                                    xu.readPacket().also {
                                        before = it.beforeBootNs; after = it.afterBootNs; returned = it.raw.size.toLong()
                                        firstCounter = it.samples.first().counterRaw
                                        endCounter = it.samples.last().counterRaw
                                        outcome = 1L
                                        streak.success()
                                        readSuccess = true
                                        d.maxTransferNs = maxOf(d.maxTransferNs, it.afterBootNs - it.beforeBootNs)
                                    }
                                } catch (e: XuImuReader.ReadFailure) {
                                    before = e.beforeBootNs; after = e.afterBootNs; returned = e.returnedBytes.toLong()
                                    outcome = if (e.stage == "packet_parse") 4L else 3L
                                    ep.imuReadErrors.incrementAndGet()
                                    val count = streak.failure()
                                    d.maxConsecutiveErrors = maxOf(d.maxConsecutiveErrors, count)
                                    d.recordError(e.stage, e.message.orEmpty())
                                    if (ep.imuReadErrors.get() == 1L || ep.imuReadErrors.get() % 100L == 0L || count >= MAX_CONSECUTIVE_IMU_ERRORS) Log.w(TAG, "IMU ${e.stage} 连续 $count 次：${e.message}")
                                    if (count >= MAX_CONSECUTIVE_IMU_ERRORS) {
                                        ep.imuError = "IMU ${e.stage} 连续失败：${e.message}"
                                        break
                                    }
                                    null
                                }
                                if (packet != null) {
                                    stage = "file_write"
                                    if (packet.raw.contentEquals(previous)) {
                                        outcome = 2L
                                        ep.imuDuplicates.incrementAndGet()
                                    } else {
                                        previous = packet.raw
                                        val first = packet.samples.first().counterRaw
                                        lastCounter?.let { last ->
                                            val advance = (first - last) and 0xffff_ffffL
                                            if (advance > 32L && advance % 32L == 0L) {
                                                d.counterGapEvents++
                                                ep.imuMissingSamples.addAndGet(advance / 32L - 1L)
                                            }
                                        }
                                        lastCounter = packet.samples.last().counterRaw
                                        val number = ep.imuPackets.get() + 1L
                                        val offset = (number - 1L) * ImuPacketParser.PACKET_BYTES
                                        val writeStart = SystemClock.elapsedRealtimeNanos()
                                        writeStartNs = writeStart
                                        raw.write(packet.raw)
                                        packets.write("$number,${packet.beforeBootNs},${packet.afterBootNs},${packet.hostUnixNs},$offset,252,$first,${packet.samples.last().counterRaw}\n")
                                        packet.samples.forEachIndexed { i, sample ->
                                            samples.write("$number,$i,${packet.beforeBootNs},${packet.afterBootNs},${packet.hostUnixNs},${sample.counterRaw},${sample.ax},${sample.ay},${sample.az},${sample.gx},${sample.gy},${sample.gz}\n")
                                        }
                                        ep.imuPackets.incrementAndGet()
                                        ep.imuSamples.addAndGet(packet.samples.size.toLong())
                                        if (number % 10L == 0L) { flushed = 1L; raw.flush(); packets.flush(); samples.flush() }
                                        writeEndNs = SystemClock.elapsedRealtimeNanos()
                                        d.maxWriteNs = maxOf(d.maxWriteNs, writeEndNs - writeStart)
                                    }
                                }
                                stage = "poll_wait"
                                // Guard from USB completion; elapsed parsing/writing time counts toward the 10 ms gap.
                                val waitAnchor = if (readSuccess && after > 0L) after else pollStart
                                sleepMs = ImuPollPolicy.delayMs(waitAnchor, SystemClock.elapsedRealtimeNanos(),
                                    BuildConfig.IMU_POLL_START_BASED, readSuccess)
                                sleepStartNs = SystemClock.elapsedRealtimeNanos()
                                Thread.sleep(sleepMs)
                                sleepEndNs = SystemClock.elapsedRealtimeNanos()
                                } catch (failure: Exception) {
                                    outcome = if (stage == "file_write") 5L else 6L
                                    throw failure
                                } finally {
                                    if (writeStartNs != 0L && writeEndNs == 0L) writeEndNs = SystemClock.elapsedRealtimeNanos()
                                    if (sleepStartNs != 0L && sleepEndNs == 0L) sleepEndNs = SystemClock.elapsedRealtimeNanos()
                                    d.trace.record(d.readAttempts, pollStart, before, after, returned, outcome,
                                        firstCounter, endCounter, writeStartNs, writeEndNs, flushed,
                                        sleepMs, sleepStartNs, sleepEndNs)
                                }
                            }
                            stage = "file_write"
                            raw.flush(); packets.flush(); samples.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            d.recordError(stage, e.message ?: e.javaClass.simpleName)
            ep.imuError = "IMU $stage 失败：${e.message ?: e.javaClass.simpleName}"
            Log.w(TAG, "Episode IMU failed at $stage", e)
        } finally {
            if (ep.imuError != null && ep.running.get()) {
                onRecordingStatus(Stage.RECORDING, "IMU 已停止：${ep.imuError}。视频仍在采集，请停止并保存后检查。")
            }
        }
    }

    private class ImuDiagnostics {
        val trace = ImuAttemptTrace()
        var traceWriteError: String? = null
        var transport = "not_verified"
        var readAttempts = 0L
        var maxPollGapNs = 0L
        var maxTransferNs = 0L
        var maxWriteNs = 0L
        var maxConsecutiveErrors = 0
        var counterGapEvents = 0L
        private val errors = mutableMapOf<String, Long>()
        private var firstError: String? = null
        private var lastError: String? = null
        fun recordError(stage: String, message: String) {
            errors[stage] = (errors[stage] ?: 0L) + 1L
            val text = "$stage: $message"
            if (firstError == null) firstError = text
            lastError = text
        }
        fun toJson(): String = JSONObject().apply {
            put("diagnostic_version", "imu_step4_usb_end_guard")
            put("trace_rows", trace.size)
            put("trace_capacity", trace.capacity)
            put("trace_omitted_attempts", trace.omitted)
            put("trace_write_error", traceWriteError ?: JSONObject.NULL)
            put("trace_scope", "poll loop only; initial connection verification excluded")
            put("read_error_log_policy", "first, every 100th, and terminal consecutive threshold")
            put("poll_mode", if (BuildConfig.IMU_POLL_START_BASED) "usb_end_gap_10ms_min_sleep_1ms" else "read_then_sleep_10ms")
            put("transport_path", transport)
            put("read_attempts", readAttempts)
            put("max_poll_start_gap_ns", maxPollGapNs)
            put("max_successful_usb_transfer_ns", maxTransferNs)
            put("max_imu_write_and_flush_ns", maxWriteNs)
            put("max_consecutive_read_errors", maxConsecutiveErrors)
            put("counter_gap_events", counterGapEvents)
            put("counter_missing_is_estimate", true)
            put("errors_by_stage", JSONObject(errors.toMap()))
            put("first_error", firstError ?: JSONObject.NULL)
            put("last_error", lastError ?: JSONObject.NULL)
        }.toString(2)
    }

    /** Only runs on the coordinator thread, never on UI or libuvc callback. */
    private fun stopEpisode(ep: Episode, reason: String) {
        onRecordingStatus(Stage.FINALIZING, "正在停止采集并保存原始数据…")
        ep.running.set(false)
        ep.audio?.stopAndJoin()
        try { ep.videoThread?.join() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        try { ep.imuThread?.join() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        val endBoot = SystemClock.elapsedRealtimeNanos()
        val endUnix = System.currentTimeMillis()
        try {
            File(ep.dir, "video_uvc_timing_diagnostics.json").writeText(JSONObject().apply {
                put("diagnostic_version", "uvc_frame_guard_probe_v3")
                put("scope", "saved raw MJPEG frames only; not all USB payloads")
                put("timing_file", "video_uvc_timing.csv")
                put("boundary_flags", "1=EOF with data; 2=FID change; 4=buffer full; 8=JPEG SOI; 16=JPEG EOI; 32=header-only EOF observed; 64=prior partial discarded at new SOI; 128=complete old JPEG closed at new SOI; 256=header-only EOF closed frame")
                put("boundary_scope", "existing frame assembly; rejected error headers and USB transport failures are not counted")
                put("flags", "1=PTS seen; 2=SCR seen; 4=PTS changed within frame; 8=truncated timing field")
                put("clock_frequency_source", "UVC negotiated dwClockFrequency; zero means not reported")
                put("manual_clock_hz_candidate", 48000000)
                put("exposure_timestamp_verified", false)
                put("official_timestamp_changed", false)
                put("note", "Raw PTS/SCR only. Neither device-to-host mapping nor exposure semantics verified. Zero PTS may be valid when presence flag is set.")
            }.toString(2)+"\n")
            try {
                File(ep.dir, "imu_attempts.csv").bufferedWriter().use { ep.imuDiagnostics.trace.writeTo(it) }
            } catch (traceFailure: Exception) {
                ep.imuDiagnostics.traceWriteError = traceFailure.message ?: traceFailure.javaClass.simpleName
                Log.w(TAG, "IMU diagnostic trace export failed", traceFailure)
            }
            File(ep.dir, "imu_diagnostics.json").writeText(ep.imuDiagnostics.toJson())
            val videoFileBytes = File(ep.dir, "video.mjpg").length()
            val imuFileBytes = File(ep.dir, "imu_packets.bin").length()
            if (videoFileBytes != ep.savedBytes.get()) ep.videoError = "video.mjpg 长度与索引不一致"
            if (imuFileBytes != ep.imuPackets.get() * ImuPacketParser.PACKET_BYTES)
                ep.imuError = "imu_packets.bin 长度与索引不一致"
            val complete = ep.videoError == null && ep.imuError == null &&
                ep.savedFrames.get() > 0 && ep.imuPackets.get() > 0
            val durationSec = (endBoot - ep.startedBootNs) / 1_000_000_000.0
            val savedFps = ep.savedFrames.get() / durationSec
            File(ep.dir, "metadata.json").writeText("""{
  "schema_version": "egouvc_phone_episode_v0_2_3",
  "status": "${if (complete) "COMPLETE" else "INCOMPLETE"}",
  "video_source": "libuvc MJPEG callback before preview decode; original compressed bytes",
  "video_format": "ORIGINAL_MJPEG",
  "requested_width": $width,
  "requested_height": $height,
  "requested_uvc_fps": 60,
  "target_saved_fps": ${ep.targetFps},
  "observed_saved_fps": ${String.format(Locale.US, "%.3f", savedFps)},
  "video_timestamp": "native libuvc callback CLOCK_BOOTTIME, not camera exposure",
  "timebase_video": "ANDROID_CLOCK_BOOTTIME_NS",
  "imu_timestamp": "host USB GET_CUR start/end CLOCK_BOOTTIME, not IMU sample capture time",
  "timebase_imu": "ANDROID_CLOCK_BOOTTIME_NS",
  "imu_unit": 3,
  "imu_selector": 1,
  "imu_record_format": "14 x [DE C8,u32BE counter,6xs16BE] per 252B packet",
  "imu_counter_unit": "unknown; no camera-IMU calibration or hardware sync",
  "start_boot_ns": ${ep.startedBootNs},
  "end_boot_ns": $endBoot,
  "started_unix_ms": ${ep.startedUnixMs},
  "ended_unix_ms": $endUnix,
  "stop_reason": ${toJsonString(reason)},
  "audio_requested": true,
  "audio_metadata_file": "audio_metadata.json",
  "video_frames_saved": ${ep.savedFrames.get()},
  "imu_packets_saved": ${ep.imuPackets.get()},
  "complete": $complete
}
""")
            File(ep.dir, "quality.json").writeText("""{
  "raw_mjpeg_callback_frames": ${ep.callbackCount.get()},
  "video_frames_selected": ${ep.selectedFrames.get()},
  "audio_requested": true,
  "audio_metadata_file": "audio_metadata.json",
  "video_frames_saved": ${ep.savedFrames.get()},
  "video_bytes_saved": ${ep.savedBytes.get()},
  "video_saved_fps": ${String.format(Locale.US, "%.3f", savedFps)},
  "max_video_queue_to_write_ns": ${ep.maxWriteLatencyNs.get()},
  "video_backpressure_dropped": ${ep.backpressureDropped.get()},
  "video_invalid_frames": ${ep.invalidFrames.get()},
  "video_error": ${toJsonString(ep.videoError)},
  "imu_packets": ${ep.imuPackets.get()},
  "imu_samples": ${ep.imuSamples.get()},
  "imu_duplicates": ${ep.imuDuplicates.get()},
  "imu_missing_samples_estimated": ${ep.imuMissingSamples.get()},
  "imu_read_errors": ${ep.imuReadErrors.get()},
  "imu_error": ${toJsonString(ep.imuError)}
}
""")
            val imuMsg = if (ep.imuError == null) "IMU ${ep.imuSamples.get()} 样本" else "IMU 失败：${ep.imuError}"
            if (complete) {
                onRecordingStatus(Stage.EXPORTING, "原始数据已保存，正在导出左右 MKV；请保持应用在前台，完成前不能开始下一次采集。")
                try {
                    val result = StereoEpisodeExporter(context).export(ep.dir, ep.taskName, ep.deviceId) {
                        onRecordingStatus(Stage.EXPORTING, it)
                    }
                    val rawBytes = ep.dir.walkTopDown().filter { it.isFile && it.name != "storage_stats.json" }.sumOf { it.length() }
                    val exportedBytes = result.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val eyesBytes = listOf("left", "right").sumOf { File(result.dir, "videos/$it.mkv").length() }
                    try { File(ep.dir, "storage_stats.json").writeText(JSONObject().apply {
                        put("unit", "MB=1000000 bytes")
                        put("duration_sec", durationSec)
                        put("target_saved_fps", ep.targetFps)
                        put("actual_saved_fps", savedFps)
                        put("raw_video_bytes", videoFileBytes)
                        put("raw_video_mb_per_sec", videoFileBytes / 1e6 / durationSec)
                        put("stereo_video_bytes", eyesBytes)
                        put("stereo_video_mb_per_sec", eyesBytes / 1e6 / durationSec)
                        put("raw_episode_bytes_excluding_storage_stats", rawBytes)
                        put("exported_session_bytes", exportedBytes)
                        put("retained_total_bytes_excluding_storage_stats", rawBytes + exportedBytes)
                        put("retained_total_mb_per_recorded_sec", (rawBytes + exportedBytes) / 1e6 / durationSec)
                        put("note", "Logical file sizes after export; conversion time excluded; not physical disk throughput")
                    }.toString(2) + "\n") } catch (statsError: Exception) {
                        Log.w(TAG, "Storage summary sidecar failed; exported video remains valid", statsError)
                    }
                    val storageSummary = String.format(Locale.US,
                        "原始视频 %.2f MB/s；左右视频合计 %.2f MB/s；保留全部数据 %.2f MB/s（按录制时长折算），共 %.1f MB。",
                        videoFileBytes / 1e6 / durationSec, eyesBytes / 1e6 / durationSec,
                        (rawBytes + exportedBytes) / 1e6 / durationSec, (rawBytes + exportedBytes) / 1e6)
                    val audioInfo = result.audioStatus
                    val audioSummary = if (audioInfo == "COMPLETE") "头环声音已导出：48 kHz 双声道 WAV。"
                        else "头环声音未完整保存：$audioInfo，请查看 audio/audio_metadata.json。"
                    onAudioStatus(audioSummary)
                    val imuExport = "IMU 已换算导出 ${result.imuWritten} 样本（11列；加速度 m/s²、角速度 rad/s；名义系数，包级时间；未提供姿态）"
                    val videoWarning = if (result.skippedVideoFrames>0)
                        "注意：左右各跳过 ${result.skippedVideoFrames} 个异常帧，已记录，原始数据保留。" else ""
                    onRecordingStatus(Stage.COMPLETE, "左右 MKV 与时间戳已导出，" + videoWarning +
                        String.format(Locale.US, "采集保存 %.1f FPS", savedFps) +
                        "。$storageSummary$audioSummary$imuExport。目标：${result.dir.absolutePath}；原始数据：${ep.dir.absolutePath}")
                } catch (exportError: Exception) {
                    Log.e(TAG, "Stereo format export failed", exportError)
                    onRecordingStatus(Stage.FAILED, "原始数据已保存，但格式导出失败：${exportError.message}；原始目录：${ep.dir.absolutePath}")
                }
            } else {
                onRecordingStatus(Stage.FAILED, "未完整保存，未执行格式导出。$imuMsg；原始目录：${ep.dir.absolutePath}")
            }
        } catch (t: Exception) {
            Log.e(TAG, "Episode finalization failed", t)
            onRecordingStatus(Stage.FAILED, "Episode 保存结束出错：${t.message}（目录：${ep.dir}）")
        }
    }

    private fun enqueue(work: () -> Unit): Boolean = try {
        controller.execute(work)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private class Episode(val dir: File, val startedUnixMs: Long, val startedBootNs: Long,
                          val taskName: String, val deviceId: String, val targetFps: Int) {
        var audio: UsbAudioRecorder? = null
        val running = AtomicBoolean(true)
        val sampler = RawFrameSampler(targetFps)
        var metricsBootNs = startedBootNs
        var metricsBytes = 0L
        val callbackCount = AtomicLong()
        val selectedFrames = AtomicLong()
        val frameQueue = ArrayBlockingQueue<Frame>(FRAME_QUEUE_CAPACITY)
        val savedFrames = AtomicLong()
        val savedBytes = AtomicLong()
        val maxWriteLatencyNs = AtomicLong()
        val backpressureDropped = AtomicLong()
        val invalidFrames = AtomicLong()
        val imuPackets = AtomicLong()
        val imuSamples = AtomicLong()
        val imuDuplicates = AtomicLong()
        val imuMissingSamples = AtomicLong()
        val imuReadErrors = AtomicLong()
        val imuDiagnostics = ImuDiagnostics()
        @Volatile var videoError: String? = null
        @Volatile var imuError: String? = null
        var videoThread: Thread? = null
        var imuThread: Thread? = null
    }

    private data class Frame(val raw: ByteArray, val index: Long, val uvcSequence: Long,
                             val bootNs: Long, val queuedBootNs: Long,
                             val devicePtsRaw: Long, val deviceScrRaw: Long,
                             val deviceSof: Int, val timingFlags: Int, val reportedClockHz: Long,
                             val firstPtsRaw: Long, val payloadCount: Long,
                             val ptsCount: Long, val boundaryFlags: Long)

    private fun toJsonString(text: String?): String =
        if (text == null) "null" else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    companion object {
        private const val TAG = "EgoUVCPhone"
        private const val MAX_JPEG_BYTES = 8 * 1024 * 1024
        private const val FRAME_QUEUE_CAPACITY = 8
        private const val MAX_CONSECUTIVE_IMU_ERRORS = 100
    }
}
