package com.egouvc.phone.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Android UAC route only. Never silently falls back to a built-in microphone. */
class UsbAudioRecorder(private val context: Context, private val dir: File,
                       private val notify: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile var error: String? = null
        private set
    private var pcmBytes = 0L
    private var route: AudioDeviceInfo? = null
    private var firstReadNs = 0L
    private var lastReadNs = 0L
    private var startRequestNs = 0L
    private var stopRequestNs = 0L
    private var routeVerified = false

    fun start() {
        check(thread == null)
        running.set(true)
        thread = Thread({ capture() }, "EgoUVC-USB-Audio").also { it.start() }
    }

    fun stopAndJoin() {
        stopRequestNs = SystemClock.elapsedRealtimeNanos()
        running.set(false)
        // Reads are nonblocking, so no stop/read deadlock or unbounded read timeout.
        thread?.join()
    }

    private fun capture() {
        val meta = JSONObject()
        var input: AudioRecord? = null
        var wav: PcmWaveWriter? = null
        var overallPeak = 0
        var waveFinalized = false
        try {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission not granted"
            }
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            meta.put("usb_inputs", JSONArray().apply { devices.forEach { put(deviceJson(it)) } })
            val named = devices.filter { it.productName.toString().contains("2UQ2", ignoreCase = true) }
            val selected = when {
                named.size == 1 -> named.single()
                named.isEmpty() && devices.size == 1 -> devices.single()
                else -> throw IllegalStateException("2UQ2 USB microphone missing or ambiguous; refusing phone microphone")
            }
            meta.put("selection_policy", if (named.size == 1) "2UQ2 product name" else "only USB input while 2UQ2 video connected")
            meta.put("selected_device", deviceJson(selected))
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "48kHz stereo PCM16 unsupported" }
            input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(maxOf(min * 4, RATE * 4 / 2)).build()
            record = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            check(input.sampleRate == RATE && input.channelCount == 2) { "AudioRecord format differs from 48kHz stereo" }
            check(input.setPreferredDevice(selected)) { "USB audio route request rejected" }
            if (!running.get()) return
            startRequestNs = SystemClock.elapsedRealtimeNanos()
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord failed to start" }
            val deadline = startRequestNs + 1_500_000_000L
            while (running.get() && input.routedDevice == null && SystemClock.elapsedRealtimeNanos() < deadline) Thread.sleep(10)
            route = input.routedDevice
            check(route?.id == selected.id) { "Actual audio input is not the selected USB microphone" }
            routeVerified = true
            meta.put("routed_device", deviceJson(route!!))
            // Route verified before any PCM is accepted. Recheck after every read.
            wav = PcmWaveWriter(File(dir, "audio.wav"), RATE, 2)
            File(dir, "audio_index.csv").bufferedWriter().use { index ->
                index.write("pcm_frame_offset,frames,read_before_boottime_ns,read_after_boottime_ns,audio_frame_position,audio_timestamp_boottime_ns,timestamp_result\n")
                val bytes = ByteArray(7680) // 40 ms of stereo PCM16
                val stamp = AudioTimestamp()
                var lastDataNs = SystemClock.elapsedRealtimeNanos()
                var lastRouteNs = lastDataNs
                var silenceCheckNs = lastDataNs
                var amplitude = 0
                var blocks = 0L
                notify("录音中：头环 USB 麦克风，48 kHz / 双声道 PCM16")
                while (running.get()) {
                    check(input.routedDevice?.id == selected.id) { "USB microphone route changed; recording stopped" }
                    val before = SystemClock.elapsedRealtimeNanos()
                    val count = input.read(bytes, 0, bytes.size, AudioRecord.READ_NON_BLOCKING)
                    val after = SystemClock.elapsedRealtimeNanos()
                    check(count >= 0) { "USB audio read failed: $count" }
                    check(input.routedDevice?.id == selected.id) { "USB microphone route changed; recording stopped" }
                    if (after - lastRouteNs >= 500_000_000L) {
                        check(manager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.id == selected.id }) {
                            "USB microphone disconnected"
                        }
                        lastRouteNs = after
                    }
                    if (count == 0) {
                        check(after - lastDataNs < 3_000_000_000L) { "USB audio produced no data for 3 seconds" }
                        Thread.sleep(5)
                        continue
                    }
                    check(count % 4 == 0) { "Unaligned stereo PCM block: $count" }
                    val frameOffset = pcmBytes / 4
                    wav.write(bytes, count)
                    pcmBytes += count
                    if (firstReadNs == 0L) firstReadNs = after
                    lastReadNs = after
                    lastDataNs = after
                    val result = input.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                    index.write("$frameOffset,${count/4},$before,$after,${if(result==AudioRecord.SUCCESS) stamp.framePosition else -1},${if(result==AudioRecord.SUCCESS) stamp.nanoTime else -1},$result\n")
                    if (++blocks % 25L == 0L) index.flush()
                    for (i in 0 until count step 2) {
                        val value = ((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()
                        amplitude = maxOf(amplitude, kotlin.math.abs(value))
                        overallPeak = maxOf(overallPeak, kotlin.math.abs(value))
                    }
                    if (after - silenceCheckNs > 5_000_000_000L) {
                        if (amplitude == 0) notify("头环音频连续 5 秒全零，请检查麦克风隐私开关或设备静音")
                        amplitude = 0
                        silenceCheckNs = after
                    }
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
            notify("头环录音失败：$error；视频与 IMU 继续采集")
        } finally {
            try { input?.stop() } catch (_: Exception) { }
            try { input?.release() } catch (_: Exception) { }
            record = null
            try { wav?.close(); waveFinalized = wav != null } catch (failure: Exception) { error = "WAV finalization failed: ${failure.message}" }
            meta.put("status", if (error == null && pcmBytes > 0L) "COMPLETE" else if (pcmBytes > 0L) "PARTIAL" else "FAILED")
                .put("error", error ?: JSONObject.NULL)
                .put("source", "USB_AUDIO_CLASS_ANDROID_AUDIORECORD")
                .put("route_verified", routeVerified)
                .put("wav_header_finalized", waveFinalized)
                .put("phone_microphone_fallback_allowed", false)
                .put("sample_rate_hz", RATE).put("channels", 2).put("sample_format", "PCM_S16LE")
                .put("bits_per_sample", 16).put("pcm_bytes", pcmBytes).put("frames", pcmBytes / 4)
                .put("duration_sec", pcmBytes.toDouble() / (RATE * 4))
                .put("peak_abs_pcm16", overallPeak)
                .put("signal_all_zero", pcmBytes > 0 && overallPeak == 0)
                .put("start_request_boottime_ns", startRequestNs)
                .put("stop_request_boottime_ns", stopRequestNs)
                .put("first_read_completion_boottime_ns", firstReadNs)
                .put("last_read_completion_boottime_ns", lastReadNs)
                .put("timestamp_file", "audio_index.csv")
                .put("timestamp_semantics", "Android AudioRecord pipeline frame-delivery timestamps plus host read bounds; not verified ADC capture or camera exposure sync")
                .put("hardware_av_sync_verified", false)
            try { File(dir, "audio_metadata.json").writeText(meta.toString(2) + "\n") }
            catch (failure: Exception) { error = "Audio metadata write failed: ${failure.message}" }
        }
    }

    private fun deviceJson(device: AudioDeviceInfo) = JSONObject().put("id", device.id)
        .put("type", device.type).put("name", device.productName.toString()).put("address", device.address)
    companion object { const val RATE = 48000 }
}
