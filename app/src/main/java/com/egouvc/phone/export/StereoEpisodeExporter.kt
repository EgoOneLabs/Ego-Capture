package com.egouvc.phone.export

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.ZoneId
import java.util.UUID

/** New target files only. Never rewrites or deletes the source Episode. Must run after writer joins. */
class StereoEpisodeExporter(private val context: Context) {
    data class Result(val dir: File, val imuReady: Boolean, val skippedVideoFrames: Int,
                      val imuWritten: Long = 0, val audioStatus: String = "NOT_RECORDED")
    private data class Frame(val offset: Long, val size: Int, val bootNs: Long, val sourceIndex: Long, val uvcSequence: Long)

    fun export(source: File, taskName: String, deviceId: String, progress: (String) -> Unit): Result {
        val original = JSONObject(File(source,"metadata.json").readText())
        check(original.getBoolean("complete")) { "原始录制未完整保存，未启动格式导出" }
        val frames = readFrames(source)
        check(frames.size.toLong() == original.getLong("video_frames_saved")) { "视频索引行数不匹配" }
        val w=original.getInt("requested_width");val h=original.getInt("requested_height")
        check(w>0 && w%2==0 && h>0) { "双目输入尺寸无效" }
        val anchorBoot=original.getLong("start_boot_ns");val anchorUnix=original.getLong("started_unix_ms")
        val root=context.getExternalFilesDir("data") ?: error("没有导出目录")
        val task=File(root,EgoRecordFormat.component(taskName))
        check(task.isDirectory || task.mkdirs()) { "无法创建任务目录" }
        val name=EgoRecordFormat.sessionName(deviceId,anchorUnix,ZoneId.systemDefault())
        val finalDir=File(task,name);val stage=File(task,"$name.partial")
        check(!finalDir.exists() && !stage.exists()) { "该会话导出目录已存在，停止以防覆盖：$name" }
        val duration=(original.getLong("end_boot_ns")-anchorBoot)/1e9
        val estimated=File(source,"video.mjpg").length()*2 + 128L*1024*1024
        check(StatFs(task.absolutePath).availableBytes > estimated) { "导出空间不足，需预留约${estimated/1024/1024}MB" }
        check(stage.mkdirs()) { "无法创建临时导出目录" }
        for (child in listOf("videos","timestamps","imu")) check(File(stage,child).mkdir())
        val exportStarted=android.os.SystemClock.elapsedRealtime()
        var exportPhase="metadata_initialization"
        var meta=makeMeta(source,finalDir,original,taskName,deviceId,frames.size,duration)
        var skippedVideoFrames=0
        try {
            writeMeta(stage,meta)
            exportPhase="initialize_lossless_jpeg_and_mkv"
            File(stage,"export_skipped_frames.csv").bufferedWriter().use { skipped ->
                skipped.write("source_list_position,source_frame_index,frame_receive_boottime_ns,uvc_sequence,reason\n")
                File(stage,"export_video_index.csv").bufferedWriter().use { mapping ->
                    mapping.write("output_frame_index,source_list_position,source_frame_index,frame_receive_boottime_ns,uvc_sequence\n")
                    File(stage,"timestamps/left_timestamps.txt").bufferedWriter().use { leftTime ->
                        File(stage,"timestamps/right_timestamps.txt").bufferedWriter().use { rightTime ->
                            MjpegMatroskaWriter(File(stage,"videos/left.mkv"),w/2,h).use { left ->
                                MjpegMatroskaWriter(File(stage,"videos/right.mkv"),w/2,h).use { right ->
                                    RandomAccessFile(File(source,"video.mjpg"),"r").use { input ->
                                        for ((i,frame) in frames.withIndex()) {
                                            exportPhase="lossless_split_frame_$i"
                                            input.seek(frame.offset)
                                            val raw=ByteArray(frame.size);input.readFully(raw)
                                            val eyes=try {
                                                LosslessJpegSplit.split(raw,w,h)
                                            } catch(bad:LosslessJpegSplit.CorruptFrameException) {
                                                // Do not catch geometry, file I/O or native-loading errors here.
                                                skippedVideoFrames++
                                                val reason=bad.message.orEmpty().replace('\n',' ').replace('\r',' ')
                                                skipped.write("$i,${frame.sourceIndex},${frame.bootNs},${frame.uvcSequence}," +
                                                    "\""+reason.replace("\"","\"\"")+"\"\n")
                                                skipped.flush()
                                                meta.put("video_frames_skipped_corrupt",skippedVideoFrames)
                                                progress("已成对跳过 $skippedVideoFrames 个异常视频帧，继续导出 ${i+1}/${frames.size}")
                                                continue
                                            }
                                            check(eyes.size==2) { "无损拆分没有返回左右两帧" }
                                            val pts=(frame.bootNs-frames.first().bootNs)/1000
                                            val outputIndex=left.frames()
                                            val line=EgoRecordFormat.timestampLine(outputIndex,
                                                EgoRecordFormat.hostUnixNs(frame.bootNs,anchorBoot,anchorUnix))
                                            exportPhase="write_left_frame_$i"
                                            left.write(eyes[0],pts);leftTime.write(line)
                                            meta.getJSONObject("topics").getJSONObject("left").put("written_frames",left.frames())
                                            exportPhase="write_right_frame_$i"
                                            right.write(eyes[1],pts);rightTime.write(line)
                                            meta.getJSONObject("topics").getJSONObject("right").put("written_frames",right.frames())
                                            mapping.write("$outputIndex,$i,${frame.sourceIndex},${frame.bootNs},${frame.uvcSequence}\n")
                                            updateCounts(meta,duration)
                                            if(i%30==0)progress("正在无损拆分左右 MKV：${i+1}/${frames.size} 帧，请保持应用在前台")
                                        }
                                    }
                                    check(left.frames()>0) { "所有输入帧均被JPEG检查拒绝，未生成可用视频" }
                                    check(left.frames()==right.frames() &&
                                        left.frames()+skippedVideoFrames==frames.size.toLong()) {
                                        "左右视频帧数与导出记录不一致"
                                    }
                                    exportPhase="close_video_and_timestamp_files"
                                }
                            }
                        }
                    }
                }
            }
            meta.put("video_export_status",if(skippedVideoFrames==0)"COMPLETE" else "COMPLETE_WITH_SKIPPED_FRAMES")
                .put("video_frames_skipped_corrupt",skippedVideoFrames)
            writeMeta(stage,meta)
            exportPhase="imu_profile_and_export"
            // Nominal physical units only. Keep raw axes, packet timestamps and zero orientation.
            val scale=NominalImuProfile.scale()
            val rawCounts=File(source,"imu_samples.csv")
            rawCounts.copyTo(File(stage,"imu/imu_raw_counts.csv"),overwrite=false)
            var imuWritten=0L
            File(stage,"imu/imu0.csv").bufferedWriter().use { out ->
                out.write(EgoRecordFormat.IMU_HEADER+"\n")
                rawCounts.bufferedReader().use { input ->
                    check(input.readLine()=="packet_index,sample_index,host_before_boottime_ns,host_after_boottime_ns,host_unix_ns,counter_raw,ax_raw,ay_raw,az_raw,gx_raw,gy_raw,gz_raw")
                    input.lineSequence().forEach { row ->
                        val c=row.split(',');check(c.size==12) { "原始IMU列数不符" }
                        val before=c[2].toLong();val after=c[3].toLong()
                        check(after>=before)
                        val mid=before+(after-before)/2
                        out.write(EgoRecordFormat.imuLine(EgoRecordFormat.hostUnixNs(mid,anchorBoot,anchorUnix),
                            c[6].toInt(),c[7].toInt(),c[8].toInt(),c[9].toInt(),c[10].toInt(),c[11].toInt(),scale))
                        imuWritten++
                    }
                }
            }
            check(imuWritten==original.getLong("imu_packets_saved")*14) { "IMU导出行数不一致" }
            meta.getJSONObject("imu").put("written_samples",imuWritten)
                .put("scale_source",scale.source)
                .put("status","NOMINAL_SI_UNVERIFIED_TIMING_NOT_CALIBRATED")
                .put("physical_units_available",true)
                .put("physical_unit_conversion_applied",true)
                .put("scale_calibration_verified",false)
                .put("nominal_profile_id",NominalImuProfile.ID)
                .put("accel_counts_per_g",NominalImuProfile.ACCEL_COUNTS_PER_G)
                .put("gyro_counts_per_degree_per_second",NominalImuProfile.GYRO_COUNTS_PER_DEGREE_PER_SECOND)
                .put("standard_gravity_m_s2",NominalImuProfile.STANDARD_GRAVITY)
                .put("angular_velocity_unit","rad/s")
                .put("linear_acceleration_unit","m/s^2")
                .put("raw_counts_file","imu/imu_raw_counts.csv")
                .put("raw_binary_source",File(source,"imu_packets.bin").absolutePath)
                .put("axis_transform_applied",false)
                .put("acceleration_includes_static_gravity_response",true)
                .put("ready_for_kalibr_camera_imu",false)
                .put("csv_field_semantics","Nominal rad/s and m/s^2 from vendor SDK coefficients; per-unit scale/bias calibration not applied; raw sensor XYZ preserved")
                .put("axis_order","as_raw; sensor-to-camera rotation not verified")
                .put("bias_correction_applied",false)
                .put("orientation_policy","four zeros; unavailable; not a valid quaternion")
                .put("hardware_sample_time_available",false)
                .put("counter_missing_samples_estimated",JSONObject(File(source,"quality.json").readText())
                    .getLong("imu_missing_samples_estimated"))
            meta.put("export_status",if(skippedVideoFrames>0)
                "NOMINAL_SI_UNVERIFIED_TIMING_WITH_SKIPPED_VIDEO_FRAMES" else "NOMINAL_SI_UNVERIFIED_TIMING")
            // Units are converted, but sample timing, orientation and calibration remain incomplete.
            meta.put("data_status","INCOMPLETE")
            meta.put("conversion_duration_sec",(android.os.SystemClock.elapsedRealtime()-exportStarted)/1000.0)
            meta.put("calibration_status","NOT_PROVIDED")
            exportPhase="copy_usb_audio"
            val audioDir = File(stage, "audio")
            check(audioDir.mkdir()) { "Cannot create audio export directory" }
            val audioInfoFile = File(source, "audio_metadata.json")
            val audioInfo = if (audioInfoFile.isFile) JSONObject(audioInfoFile.readText())
                else JSONObject().put("status", "FAILED").put("error", "Audio metadata missing")
            val audioStatus = audioInfo.optString("status", "FAILED")
            val audioBytes = audioInfo.optLong("pcm_bytes", 0)
            val hasAudio = audioBytes > 0 && audioInfo.optBoolean("route_verified", false) && audioInfo.optBoolean("wav_header_finalized", false)
            if (hasAudio) {
                val wav = File(source, "audio.wav")
                check(wav.length() == audioBytes + 44) { "WAV byte count differs from audio metadata" }
                wav.copyTo(File(audioDir, "audio.wav"), overwrite=false)
            }
            for (filename in listOf("audio_metadata.json", "audio_index.csv")) {
                val file = File(source, filename)
                if (file.isFile) file.copyTo(File(audioDir, filename), overwrite=false)
            }
            if (!audioInfoFile.isFile) File(audioDir, "audio_metadata.json").writeText(audioInfo.toString(2)+"\n")
            audioInfo.put("wav_file", if (hasAudio) "audio/audio.wav" else JSONObject.NULL)
                .put("metadata_file", "audio/audio_metadata.json")
                .put("index_file", if (File(audioDir, "audio_index.csv").exists()) "audio/audio_index.csv" else JSONObject.NULL)
                .put("episode_anchor_boottime_ns", anchorBoot)
                .put("episode_anchor_unix_ms", anchorUnix)
                .put("sample_zero_unix_ns_verified", false)
            meta.put("audio_requested", true).put("audio_enabled", hasAudio).put("audio", audioInfo)
            exportPhase="copy_calibration"
            val calibrationSource=File(context.getExternalFilesDir(null),"calibrations")
            if(calibrationSource.isDirectory && !calibrationSource.listFiles().isNullOrEmpty()) {
                check(calibrationSource.copyRecursively(File(stage,"calibrations"),overwrite=false))
                meta.put("calibration_status","COPIED_FROM_APP_CALIBRATIONS")
            }
            writeMeta(stage,meta)
            exportPhase="rename_completed_session"
            check(stage.renameTo(finalDir)) { "导出完成但临时目录重命名失败：$stage" }
            return Result(finalDir,false,skippedVideoFrames,imuWritten,audioStatus)
        } catch(e:Throwable) {
            if(e !is Exception && e !is LinkageError) throw e
            updateCounts(meta,duration)
            meta.put("export_status","FAILED").put("data_status","INCOMPLETE")
                .put("export_failure_stage",exportPhase)
                .put("export_error",e.message ?: e.javaClass.simpleName)
            try {
                val report=failureReport(e,exportPhase,source)
                File(stage,"export_error.json").writeText(report.toString(2)+"\n")
                val diagnostics=context.getExternalFilesDir("export_diagnostics") ?: error("无诊断目录")
                File(diagnostics,"${source.name}.json").writeText(report.toString(2)+"\n")
                meta.put("export_diagnostic_file",File(diagnostics,"${source.name}.json").absolutePath)
            } catch (diagnosticError:Exception) {
                meta.put("diagnostic_write_error",diagnosticError.message ?: diagnosticError.javaClass.simpleName)
            }
            try {writeMeta(stage,meta)}catch(_:Exception){}
            throw IllegalStateException("阶段$exportPhase：${e.message}；原始数据保留；未完成目录：$stage",e)
        }
    }

    private fun readFrames(source:File):List<Frame> {
        val frames=ArrayList<Frame>();var offset=0L;var previous=-1L
        File(source,"video_index.csv").bufferedReader().use { input ->
            check(input.readLine()=="frame_index,byte_offset,jpeg_bytes,frame_receive_boottime_ns,uvc_sequence")
            input.lineSequence().forEach { row ->
                val c=row.split(',');check(c.size==5)
                val frame=Frame(c[1].toLong(),c[2].toInt(),c[3].toLong(),c[0].toLong(),c[4].toLong())
                check(frame.offset==offset && frame.size in 4..8*1024*1024 && frame.bootNs>previous)
                if(previous>=0)check((frame.bootNs-previous)/1000>0) { "视频时间间隔过小" }
                offset+=frame.size;previous=frame.bootNs;frames.add(frame)
            }
        }
        check(frames.isNotEmpty() && offset==File(source,"video.mjpg").length()) { "视频文件与索引不一致" }
        return frames
    }
    private fun makeMeta(source:File,dest:File,m:JSONObject,task:String,device:String,n:Int,duration:Double):JSONObject {
        val q=JSONObject(File(source,"quality.json").readText())
        val topics=JSONObject()
        for(side in listOf("left","right"))topics.put(side,JSONObject()
            .put("received_frames",q.getLong("raw_mjpeg_callback_frames"))
            .put("written_frames",0).put("queue_dropped",q.getLong("video_backpressure_dropped"))
            .put("bad_frames",q.getLong("video_invalid_frames"))
            .put("timestamps_file","${side}_timestamps.txt").put("format","MJPEG in Matroska").put("width",m.getInt("requested_width")/2)
            .put("height",m.getInt("requested_height")))
        return JSONObject().put("device_id",device).put("device_sn","").put("hardware_model","2UQ2")
            .put("data_id",UUID.randomUUID().toString()).put("version",com.egouvc.phone.BuildConfig.VERSION_NAME)
            .put("build_id","android_nominal_si_v1").put("task",task).put("session_dir",dest.absolutePath)
            .put("start_time",java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.US)
                .format(java.util.Date(m.getLong("started_unix_ms"))))
            .put("duration_sec",duration).put("fps",m.optInt("target_saved_fps",30)).put("sync_mode","stereo_same_source_frame")
            .put("synced_frames",0).put("total_written_frames",0).put("span_sec",duration)
            .put("source_saved_frames",n).put("source_saved_fps",n/duration)
            .put("avg_fps_per_cam",0.0).put("data_status","COLLECTING").put("topics",topics)
            .put("imu",JSONObject().put("topic","").put("received_samples",q.getLong("imu_samples"))
                .put("written_samples",0).put("csv_file",File(dest,"imu/imu0.csv").absolutePath))
            .put("export_status","EXPORTING_VIDEO").put("source_episode",source.absolutePath)
            .put("video_frames_skipped_corrupt",0).put("video_export_status","EXPORTING")
            .put("export_skipped_frames_file","export_skipped_frames.csv")
            .put("export_video_index_file","export_video_index.csv")
            .put("jpeg_transform","lossless DCT coefficient crop; MCU-aligned midpoint; no resizing or requantization")
            .put("audio_enabled",false).put("orientation_available",false)
            .put("video_timestamp_source","host callback CLOCK_BOOTTIME mapped once to Unix at recording start; not exposure")
            .put("imu_timestamp_source","host USB request midpoint; all 14 samples share packet time; not hardware sample time")
            .put("mkv_timecode_precision_ns",1_000_000)
    }
    private fun updateCounts(meta:JSONObject,duration:Double) {
        val topics=meta.getJSONObject("topics")
        val left=topics.getJSONObject("left").getLong("written_frames")
        val right=topics.getJSONObject("right").getLong("written_frames")
        meta.put("total_written_frames",left+right).put("synced_frames",minOf(left,right))
            .put("avg_fps_per_cam",minOf(left,right)/duration)
    }
    private fun failureReport(error:Throwable,phase:String,source:File):JSONObject {
        val report=JSONObject().put("stage",phase).put("source_episode",source.absolutePath)
            .put("exception",error.javaClass.name).put("message",error.message ?: "")
            .put("stacktrace",error.stackTraceToString())
            .put("phone_model",android.os.Build.MODEL).put("android_sdk",android.os.Build.VERSION.SDK_INT)
            .put("android_release",android.os.Build.VERSION.RELEASE)
            .put("requested_format","V_MJPEG in Matroska; JPEG coefficient-domain half-width crop")
        return report
    }

    private fun writeMeta(dir:File,meta:JSONObject) {
        val temp=File(dir,"meta.json.tmp");temp.writeText(meta.toString(2)+"\n")
        check(temp.renameTo(File(dir,"meta.json"))) { "meta.json原子替换失败" }
    }
}
