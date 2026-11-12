package com.egouvc.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import com.egouvc.phone.camera.CameraPhase
import com.egouvc.phone.camera.CameraStatus
import com.egouvc.phone.camera.UvcCameraSource
import com.egouvc.phone.databinding.ActivityMainBinding
import com.egouvc.phone.export.EgoRecordFormat
import com.egouvc.phone.recording.CaptureRates
import com.egouvc.phone.recording.RecordingJobState.Stage
import com.egouvc.phone.ui.AutoConnectPolicy
import com.egouvc.phone.usb.UsbConnectionState
import com.egouvc.phone.usb.UsbStatus

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var languageContext: Context
    private lateinit var cameraSource: UvcCameraSource
    private val manager get() = (application as EgoUvcApplication).usbDeviceManager
    private val main = Handler(Looper.getMainLooper())
    private val autoConnect = AutoConnectPolicy()
    private val observer: (UsbStatus) -> Unit = ::onUsbStatus
    private var foreground = false
    private var surfaceReady = false
    private var permissionInFlight = false
    private var status = CameraStatus(CameraPhase.IDLE)
    private var lastError: String? = null
    private var reconcilePosted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languageContext = UiLanguage.localized(this, UiLanguage.isEnglish(this))
        binding = ActivityMainBinding.inflate(layoutInflater.cloneInContext(languageContext))
        setContentView(binding.root)
        cameraSource = UvcCameraSource(applicationContext, ::renderCamera, ::showPreview)
        window.setDecorFitsSystemWindows(false)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val unit = resources.displayMetrics.density
            val keyboard = insets.isVisible(WindowInsets.Type.ime())
            val available = resources.displayMetrics.heightPixels - bars.top - maxOf(bars.bottom, ime.bottom)
            binding.header.visibility = if (keyboard && available / unit < 360) View.GONE else View.VISIBLE
            binding.outputGroup.visibility = if (keyboard) View.GONE else View.VISIBLE
            // Shrink only while the keyboard needs space; normal preview stays 16:9.
            val reservedDp = 310f + (if (binding.header.visibility == View.VISIBLE) 44f else 0f) +
                (if (keyboard) 0f else 116f)
            binding.decodedPreviewImage.maximumHeightPx = (available - reservedDp * unit).toInt()
            view.setPadding(bars.left+(12*unit).toInt(), bars.top+(8*unit).toInt(),
                bars.right+(12*unit).toInt(), maxOf(bars.bottom,ime.bottom)+(8*unit).toInt())
            insets
        }
        binding.root.requestApplyInsets()
        val prefs = getSharedPreferences("export_format", MODE_PRIVATE)
        binding.taskNameInput.setText(prefs.getString("task_name", "task001"))
        binding.exportDeviceInput.setText(prefs.getString("device_id", "2UQ2"))
        binding.savedFpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            CaptureRates.choices.map { "$it FPS" })
        val fps = prefs.getInt("target_saved_fps",30).let { if (it in CaptureRates.choices) it else 30 }
        binding.savedFpsSpinner.setSelection(CaptureRates.choices.indexOf(fps))
        binding.savedFpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!status.recording) prefs.edit().putInt("target_saved_fps", CaptureRates.choices[position]).apply()
            }
        }
        binding.languageButton.setOnClickListener {
            val english = !UiLanguage.isEnglish(this)
            getSharedPreferences("ui",MODE_PRIVATE).edit().putBoolean("english",english).apply()
            languageContext = UiLanguage.localized(this,english)
            reloadText(binding.root)
            renderCamera(status)
        }
        binding.startCaptureButton.setOnClickListener {
            hideKeyboard()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                if (!permissionInFlight) {
                    permissionInFlight=true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),AUDIO_REQUEST)
                }
                return@setOnClickListener
            }
            try {
                val task = EgoRecordFormat.component(binding.taskNameInput.text.toString())
                val device = EgoRecordFormat.component(binding.exportDeviceInput.text.toString())
                prefs.edit().putString("task_name",task).putString("device_id",device).apply()
                lastError=null
                cameraSource.startEpisodeRecording()
            } catch (_: IllegalArgumentException) { toast(R.string.onepage_invalid_name) }
        }
        binding.stopCaptureButton.setOnClickListener { hideKeyboard(); cameraSource.stopEpisodeRecording() }
        val retryConnection = View.OnClickListener {
            if (status.phase == CameraPhase.IDLE || status.phase == CameraPhase.ERROR) {
                autoConnect.reset(); manager.refresh(); scheduleConnect()
            }
        }
        binding.decodedPreviewImage.setOnClickListener(retryConnection)
        binding.previewHint.setOnClickListener(retryConnection)
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady=true; scheduleConnect() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady=false; cameraSource.close() }
        })
        renderCamera(status)
    }

    override fun onStart() { super.onStart(); foreground=true; manager.observe(observer) }
    override fun onResume() { super.onResume(); manager.refresh(); scheduleConnect() }
    override fun onStop() {
        foreground=false; autoConnect.reset(); cameraSource.close(); manager.removeObserver(observer); super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) manager.refresh()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,results)
        permissionInFlight=false
        if (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED) toast(R.string.onepage_permission_needed)
        manager.refresh(); scheduleConnect()
    }
    private fun onUsbStatus(value: UsbStatus) {
        if (value.state != UsbConnectionState.READY && status.phase in listOf(CameraPhase.OPENING,CameraPhase.STREAMING)) cameraSource.close()
        if (value.state == UsbConnectionState.DISCONNECTED) autoConnect.reset()
        scheduleConnect()
    }
    private fun scheduleConnect() {
        if (reconcilePosted) return
        reconcilePosted=true
        main.post {
            reconcilePosted=false
            if (!foreground || permissionInFlight || !surfaceReady) return@post
            val usb=manager.status
            when (autoConnect.next(usb.deviceName,foreground,surfaceReady,
                checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,
                usb.usbPermissionGranted,usb.state==UsbConnectionState.USB_PERMISSION_PENDING,
                status.phase==CameraPhase.IDLE || status.phase==CameraPhase.ERROR,status.recording)) {
                AutoConnectPolicy.Action.CAMERA_PERMISSION -> {
                    permissionInFlight=true; requestPermissions(arrayOf(Manifest.permission.CAMERA),CAMERA_REQUEST)
                }
                AutoConnectPolicy.Action.AUDIO_PERMISSION -> {
                    permissionInFlight=true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),AUDIO_REQUEST)
                }
                AutoConnectPolicy.Action.USB_PERMISSION -> manager.requestUsbPermission()
                AutoConnectPolicy.Action.OPEN -> manager.readyDevice()?.let { cameraSource.open(it,binding.previewSurface.holder.surface) }
                else -> Unit
            }
        }
    }
    private fun showPreview(bitmap: Bitmap) {
        if (status.phase==CameraPhase.STREAMING) binding.decodedPreviewImage.showFrame(bitmap) else bitmap.recycle()
    }
    private fun renderCamera(value: CameraStatus) {
        status=value
        val streaming=value.phase==CameraPhase.STREAMING
        if (!streaming && value.phase!=CameraPhase.OPENING) binding.decodedPreviewImage.clearFrame()
        binding.previewHint.visibility=if (streaming) View.GONE else View.VISIBLE
        binding.previewHint.text=getStringUi(if (value.phase==CameraPhase.OPENING) R.string.onepage_connecting else R.string.onepage_connect_hint)
        binding.decodedPreviewImage.setLabels(getStringUi(R.string.onepage_left),getStringUi(R.string.onepage_right))
        binding.decodedPreviewImage.contentDescription=getStringUi(R.string.onepage_preview_description)
        binding.languageButton.text=if (UiLanguage.isEnglish(this)) "中文" else "English"
        binding.startCaptureButton.isEnabled=streaming && !value.recording
        binding.stopCaptureButton.isEnabled=streaming && value.captureCanStop
        binding.taskNameInput.isEnabled=!value.recording
        binding.exportDeviceInput.isEnabled=!value.recording
        binding.savedFpsSpinner.isEnabled=!value.recording
        binding.startCaptureButton.text=getStringUi(if (value.captureCanStop) R.string.onepage_recording else R.string.capture_text_7)
        binding.stopCaptureButton.text=getStringUi(if (value.recording && !value.captureCanStop) R.string.onepage_saving else R.string.capture_text_8)
        binding.outputPath.text=if (value.recordingStage==Stage.COMPLETE) value.outputDirectory.orEmpty() else ""
        val error=when {
            value.recordingStage==Stage.FAILED -> value.recordingMessage
            value.phase==CameraPhase.ERROR -> value.message
            else -> null
        }
        if (error!=null && error!=lastError) {
            lastError=error; Log.e("EgoUVCPhone",error); toast(R.string.onepage_error)
        }
        scheduleConnect()
    }
    private fun reloadText(view: View) {
        if (view is TextView) {
            val tag=view.tag as? String
            if (tag!=null && ':' in tag) {
                val id=resources.getIdentifier(tag.substringAfter(':'),"string",packageName)
                if (id!=0) { if (tag.startsWith("hint:")) view.hint=getStringUi(id) else view.text=getStringUi(id) }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) reloadText(view.getChildAt(i))
    }
    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.root.windowToken,0)
        binding.root.clearFocus()
    }
    private fun getStringUi(id: Int)=languageContext.getString(id)
    private fun toast(id: Int) { Toast.makeText(this,getStringUi(id),Toast.LENGTH_LONG).show() }
    companion object { private const val CAMERA_REQUEST=1001; private const val AUDIO_REQUEST=1002 }
}
