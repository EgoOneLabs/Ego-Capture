package com.egouvc.phone

import android.Manifest
import com.egouvc.phone.export.EgoRecordFormat
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.egouvc.phone.recording.CaptureRates
import android.util.Log
import android.view.View
import android.view.SurfaceHolder
import com.egouvc.phone.camera.CameraPhase
import com.egouvc.phone.camera.CameraStatus
import com.egouvc.phone.camera.UvcCameraSource
import com.egouvc.phone.databinding.ActivityMainBinding
import com.egouvc.phone.usb.UsbConnectionState
import com.egouvc.phone.usb.UsbDeviceManager
import com.egouvc.phone.usb.UsbStatus
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private val deviceManager: UsbDeviceManager
        get() = (application as EgoUvcApplication).usbDeviceManager
    private val statusObserver: (UsbStatus) -> Unit = ::renderStatus
    private var cameraRequestPending = false
    private lateinit var cameraSource: UvcCameraSource
    private var previewSurfaceReady = false
    private var cameraPhase = CameraPhase.IDLE
    private var recording = false
    private var recordingDetailsExpanded = false
    private var cameraSourceIsProbing = false

    private var lastCameraStatus: CameraStatus? = null
    private var lastUsbStatus: UsbStatus? = null
    private var previewFps = 0.0
    private lateinit var languageContext: Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languageContext = UiLanguage.localized(this, UiLanguage.isEnglish(this))
        binding = ActivityMainBinding.inflate(layoutInflater.cloneInContext(languageContext))
        setContentView(binding.root)
        initRecordingDetails()
        val exportPrefs = getSharedPreferences("export_format", MODE_PRIVATE)
        binding.taskNameInput.setText(exportPrefs.getString("task_name", "task001"))
        binding.exportDeviceInput.setText(exportPrefs.getString("device_id", "2UQ2"))
        binding.languageButton.setOnClickListener {
            val english = !UiLanguage.isEnglish(this)
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("english", english).apply()
            languageContext = UiLanguage.localized(this, english)
            reloadStaticText(binding.root)
            refreshLanguageLabels()
            lastUsbStatus?.let(::renderStatus)
            lastCameraStatus?.let(::renderCamera)
        }
        val initialFps = exportPrefs.getInt("target_saved_fps", 30).let { if (it in CaptureRates.choices) it else 30 }
        binding.savedFpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            CaptureRates.choices.map { "$it FPS" })
        binding.savedFpsSpinner.setSelection(CaptureRates.choices.indexOf(initialFps))
        binding.savedFpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!recording) {
                    exportPrefs.edit().putInt("target_saved_fps", CaptureRates.choices[position]).apply()
                    refreshLanguageLabels()
                }
            }
        }
        refreshLanguageLabels()
        binding.diagnosticsToggle.setOnClickListener {
            val expanded = binding.diagnosticsPanel.visibility != View.VISIBLE
            binding.diagnosticsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            refreshLanguageLabels()
        }
        binding.refreshButton.setOnClickListener { deviceManager.refresh() }
        binding.requestPermissionButton.setOnClickListener { requestNeededPermission() }
        cameraSource = UvcCameraSource(applicationContext, ::renderCamera, ::showDecodedPreview)
        binding.probeImuButton.setOnClickListener { cameraSource.probeImu() }
        binding.decodedPreviewImage.onRenderedFps = { fps ->
            previewFps = fps
            renderPreviewFps()
        }
        binding.startCaptureButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST_CODE)
                return@setOnClickListener
            }
            try {
                val task = EgoRecordFormat.component(binding.taskNameInput.text.toString())
                val device = EgoRecordFormat.component(binding.exportDeviceInput.text.toString())
                exportPrefs.edit().putString("task_name", task).putString("device_id", device).apply()
                cameraSource.startEpisodeRecording()
            } catch (_: IllegalArgumentException) {
                binding.recordingStatusValue.text = tr("请填写任务名与设备编号，不可包含斜杠、换行或 .. 路径。")
            }
        }
        binding.stopCaptureButton.setOnClickListener { cameraSource.stopEpisodeRecording() }
        binding.openCameraButton.setOnClickListener {
            deviceManager.refresh()
            val device = deviceManager.readyDevice()
            if (device == null) {
                binding.cameraMessageValue.text = uiString(R.string.video_requires_ready)
            } else if (previewSurfaceReady) {
                cameraSource.open(device, binding.previewSurface.holder.surface)
            } else {
                binding.cameraMessageValue.text = uiString(R.string.preview_surface_unavailable)
            }
        }
        binding.closeCameraButton.setOnClickListener { cameraSource.close() }
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurfaceReady = true
                updateCameraButtons()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewSurfaceReady = false
                cameraSource.close()
                updateCameraButtons()
            }
        })
        Log.i(TAG, "MainActivity created")
    }

    private fun initRecordingDetails() {
        binding.recordingDetailsToggle.setOnClickListener {
            recordingDetailsExpanded = !recordingDetailsExpanded
            updateRecordingDetails()
        }
        updateRecordingDetails()
    }

    private fun updateRecordingDetails() {
        binding.recordingStatusValue.maxLines = if (recordingDetailsExpanded) Int.MAX_VALUE else 4
        binding.recordingStatusValue.ellipsize = if (recordingDetailsExpanded) null else android.text.TextUtils.TruncateAt.END
        binding.recordingDetailsToggle.text = uiString(
            if (recordingDetailsExpanded) R.string.ui_collapse_status else R.string.ui_expand_status,
        )
    }

    override fun onStart() {
        super.onStart()
        deviceManager.observe(statusObserver)
    }

    override fun onResume() {
        super.onResume()
        deviceManager.refresh()
    }

    override fun onStop() {
        cameraSource.close()
        deviceManager.removeObserver(statusObserver)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.i(TAG, "Target attach intent received")
            deviceManager.refresh()
        }
    }

    private fun requestNeededPermission() {
        when (deviceManager.status.state) {
            UsbConnectionState.CAMERA_PERMISSION_REQUIRED -> {
                if (cameraRequestPending) return
                cameraRequestPending = true
                binding.requestPermissionButton.isEnabled = false
                Log.i(TAG, "Requesting CAMERA permission for USB video device")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
            }
            UsbConnectionState.USB_PERMISSION_REQUIRED -> deviceManager.requestUsbPermission()
            else -> Unit
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_REQUEST_CODE) {
            binding.audioStatusValue.text = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                tr("麦克风权限已授予，请再次点击开始采集") else tr("麦克风权限未授予，无法开始带声音采集")
            return
        }
        if (requestCode != CAMERA_REQUEST_CODE) return
        cameraRequestPending = false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(TAG, "CAMERA permission granted")
            deviceManager.refresh()
            deviceManager.requestUsbPermission()
        } else {
            deviceManager.onCameraPermissionDenied()
        }
    }

    private fun renderCamera(status: CameraStatus) {
        lastCameraStatus = status
        cameraPhase = status.phase
        recording = status.recording
        cameraSourceIsProbing = status.imuProbing
        val localSaveWithoutCamera = status.recording &&
            (status.phase == CameraPhase.IDLE || status.phase == CameraPhase.ERROR)
        val recordingMessage = status.recordingMessage ?: uiString(R.string.capture_text_12)
        binding.recordingStatusValue.text = tr(if (localSaveWithoutCamera)
            "相机连接已关闭；本地保存或导出继续，请保持应用在前台。\n$recordingMessage"
            else recordingMessage)
        if (status.phase == CameraPhase.IDLE || status.phase == CameraPhase.ERROR) {
            binding.decodedPreviewImage.clearFrame()
            binding.decodedPreviewImage.visibility = View.VISIBLE
        }
        binding.cameraStateValue.text = when (status.phase) {
            CameraPhase.IDLE -> uiString(R.string.video_idle)
            CameraPhase.OPENING -> uiString(R.string.video_opening)
            CameraPhase.STREAMING -> uiString(R.string.video_streaming)
            CameraPhase.ERROR -> uiString(R.string.state_error)
        }
        com.egouvc.phone.ui.CaptureUi.status(binding.cameraStateValue, when (status.phase) {
            CameraPhase.STREAMING -> com.egouvc.phone.ui.CaptureUi.READY
            CameraPhase.OPENING -> com.egouvc.phone.ui.CaptureUi.WAITING
            CameraPhase.ERROR -> com.egouvc.phone.ui.CaptureUi.ERROR
            else -> com.egouvc.phone.ui.CaptureUi.NEUTRAL
        })
        binding.cameraModeValue.text = status.configuredMode?.let(::tr) ?: uiString(R.string.not_available)
        binding.cameraFpsValue.text = if (status.rawCallbackFps != null || status.callbackFps != null) {
            tr(java.lang.String.format(Locale.US,
                "原始接收：%.1f FPS / %d 帧；解码回调：%.1f FPS / %d 帧（均非保存帧率）",
                status.rawCallbackFps ?: 0.0, status.rawFramesObserved,
                status.callbackFps ?: 0.0, status.framesObserved))
        } else uiString(R.string.video_fps_waiting)
        binding.cameraModesValue.text = status.supportedModes ?: uiString(R.string.not_available)
        binding.cameraMessageValue.text = tr(status.message.orEmpty())
        binding.imuProbeValue.text = tr(status.imuProbeMessage ?: "IMU：尚未探测")
        binding.audioStatusValue.text = tr(status.audioMessage ?: "头环声音：开始采集后检查 USB 麦克风")
        if (status.metrics == null) binding.storageRateValue.text = uiString(R.string.capture_text_11)
        status.metrics?.let { m ->
            binding.storageRateValue.text = if (UiLanguage.isEnglish(this)) {
                String.format(Locale.US, "Raw video: %.2f MB/s now · %.2f MB/s average · %.1f MB saved\nSaved: %.1f / %d FPS · MB = 1,000,000 bytes", m.videoWindowMbPerSec, m.videoAverageMbPerSec, m.videoMb, m.savedFps, m.targetFps)
            } else {
                String.format(Locale.US, "原始视频：当前 %.2f MB/s · 平均 %.2f MB/s · 已存 %.1f MB\n实际保存 %.1f / 目标 %d FPS · MB = 1,000,000 字节", m.videoWindowMbPerSec, m.videoAverageMbPerSec, m.videoMb, m.savedFps, m.targetFps)
            }
        }
        updateCameraButtons()
    }

    private fun showDecodedPreview(bitmap: Bitmap) {
        if (cameraPhase != CameraPhase.STREAMING) {
            bitmap.recycle()
            return
        }
        binding.decodedPreviewImage.showFrame(bitmap)
        binding.decodedPreviewImage.visibility = View.VISIBLE
    }

    private fun updateCameraButtons() {
        if (!::binding.isInitialized) return
        binding.openCameraButton.isEnabled = previewSurfaceReady &&
            deviceManager.status.state == UsbConnectionState.READY &&
            (cameraPhase == CameraPhase.IDLE || cameraPhase == CameraPhase.ERROR)
        binding.closeCameraButton.isEnabled = cameraPhase == CameraPhase.OPENING ||
            cameraPhase == CameraPhase.STREAMING
        binding.startCaptureButton.isEnabled = cameraPhase == CameraPhase.STREAMING && !recording
        binding.taskNameInput.isEnabled = !recording
        binding.exportDeviceInput.isEnabled = !recording
        binding.savedFpsSpinner.isEnabled = !recording
        binding.stopCaptureButton.isEnabled = cameraPhase == CameraPhase.STREAMING &&
            lastCameraStatus?.captureCanStop == true
        binding.probeImuButton.isEnabled = cameraPhase == CameraPhase.STREAMING && !recording &&
            !cameraSourceIsProbing
    }

    private fun renderStatus(status: UsbStatus) {
        lastUsbStatus = status
        if (status.state != UsbConnectionState.READY && cameraPhase != CameraPhase.IDLE &&
            cameraPhase != CameraPhase.ERROR
        ) cameraSource.close()
        binding.deviceNameValue.text = when {
            status.productName != null && status.deviceName != null ->
                "${status.productName}\n${status.deviceName}"
            else -> status.deviceName ?: uiString(R.string.not_available)
        }
        binding.vidPidValue.text = if (status.vendorId != null && status.productId != null) {
            String.format(Locale.US, "%04x:%04x", status.vendorId, status.productId)
        } else {
            uiString(R.string.not_available)
        }
        binding.hostValue.text = uiString(if (status.hostSupported) R.string.supported else R.string.unsupported)
        binding.connectionValue.text = uiString(
            when (status.state) {
                UsbConnectionState.DISCONNECTED -> R.string.state_disconnected
                UsbConnectionState.CAMERA_PERMISSION_REQUIRED -> R.string.state_camera_required
                UsbConnectionState.USB_PERMISSION_REQUIRED -> R.string.state_usb_required
                UsbConnectionState.USB_PERMISSION_PENDING -> R.string.state_usb_pending
                UsbConnectionState.READY -> R.string.state_ready
                UsbConnectionState.ERROR -> R.string.state_error
            },
        )
        com.egouvc.phone.ui.CaptureUi.status(binding.connectionValue, when (status.state) {
            UsbConnectionState.READY -> com.egouvc.phone.ui.CaptureUi.READY
            UsbConnectionState.ERROR -> com.egouvc.phone.ui.CaptureUi.ERROR
            UsbConnectionState.DISCONNECTED -> com.egouvc.phone.ui.CaptureUi.NEUTRAL
            else -> com.egouvc.phone.ui.CaptureUi.WAITING
        })
        binding.cameraPermissionValue.text = uiString(
            if (status.cameraPermissionGranted) R.string.granted else R.string.not_granted,
        )
        binding.usbPermissionValue.text = uiString(
            when {
                status.deviceName == null -> R.string.no_target_device
                status.usbPermissionGranted -> R.string.granted
                else -> R.string.not_granted
            },
        )
        binding.requestPermissionButton.isEnabled = !cameraRequestPending &&
            (status.state == UsbConnectionState.CAMERA_PERMISSION_REQUIRED ||
                status.state == UsbConnectionState.USB_PERMISSION_REQUIRED)
        binding.messageValue.text = tr(status.message.orEmpty())
        binding.messageValue.visibility = if (status.message.isNullOrBlank()) View.GONE else View.VISIBLE
        updateCameraButtons()
    }

    private fun tr(text: String) = UiLanguage.display(this, text)
    private fun uiString(id: Int) = languageContext.getString(id)

    private fun reloadStaticText(view: View) {
        if (view is TextView) {
            val tag = view.tag as? String
            if (tag != null && ':' in tag) {
                val kind = tag.substringBefore(':')
                val id = resources.getIdentifier(tag.substringAfter(':'), "string", packageName)
                if (id != 0) {
                    if (kind == "hint") view.hint = uiString(id) else view.text = uiString(id)
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) reloadStaticText(view.getChildAt(i))
    }

    private fun refreshLanguageLabels() {
        updateRecordingDetails()
        val english = UiLanguage.isEnglish(this)
        binding.languageButton.text = if (english) "中文" else "English"
        val expanded = binding.diagnosticsPanel.visibility == View.VISIBLE
        binding.diagnosticsToggle.text = tr(if (expanded) "收起设备与诊断详情" else "展开设备与诊断详情")
        val fps = CaptureRates.choices[binding.savedFpsSpinner.selectedItemPosition.coerceAtLeast(0)]
        binding.savedFpsHint.text = if (english) "Target: $fps FPS; actual rate is measured during capture" else "保存目标：$fps FPS；实际帧率在采集时显示"
        renderPreviewFps()
    }

    private fun renderPreviewFps() {
        binding.previewFpsValue.text = String.format(Locale.US,
            if (UiLanguage.isEnglish(this)) "Stereo preview: %.1f FPS · left / right"
            else "双目预览：%.1f FPS · 左 / 右", previewFps)
    }

    companion object {
        private const val TAG = "EgoUVCPhone"
        private const val CAMERA_REQUEST_CODE = 1001
        private const val AUDIO_REQUEST_CODE = 1002
    }
}
