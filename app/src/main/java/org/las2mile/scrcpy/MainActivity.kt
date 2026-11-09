package org.las2mile.scrcpy

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.las2mile.scrcpy.adb.AdbPairingHelper
import org.las2mile.scrcpy.history.ConnectionHistoryManager
import org.las2mile.scrcpy.usb.UsbAdbManager
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_KEY = "default"

        // Preference keys
        private const val PREF_SERVER_ADDRESS = "Server Address"
        private const val PREF_NO_CONTROL = "No Control"
        private const val PREF_NAV = "Nav Switch"
        private const val PREF_AUDIO = "switch_audio"
        private const val PREF_STAY_AWAKE = "switch_stay_awake"
        private const val PREF_SCREEN_OFF = "switch_screen_off"
        private const val PREF_SYNC_ROTATION = "switch_sync_rotation"
        private const val PREF_CONNECTION_MODE = "connection_mode"
        private const val PREF_RES_IDX = "spinner_resolution"
        private const val PREF_BITRATE_IDX = "spinner_bitrate"
        private const val PREF_CODEC_IDX = "spinner_codec"
        private const val PREF_FPS_IDX = "spinner_fps"
        private const val PREF_AUDIO_CODEC_IDX = "spinner_audio_codec"
        private const val PREF_UHID = "switch_uhid"
    }

    // UI references - Mode & Connection
    private var toggleGroupMode: MaterialButtonToggleGroup? = null
    private var layoutWifiConnection: View? = null
    private var layoutUsbConnection: View? = null
    private var tvUsbStatus: TextView? = null
    private var btnUsbConnect: MaterialButton? = null
    private var etServerHost: MaterialAutoCompleteTextView? = null
    private var btnThemeToggle: MaterialButton? = null
    private var cardHistory: View? = null
    private var btnClearHistory: MaterialButton? = null
    private var layoutHistoryItems: LinearLayout? = null
    private var tvEmptyHistory: TextView? = null
    private var historyManager: ConnectionHistoryManager? = null

    // UI references - Video & Audio & Advanced
    private var acResolution: AutoCompleteTextView? = null
    private var acBitrate: AutoCompleteTextView? = null
    private var acCodec: AutoCompleteTextView? = null
    private var acFps: AutoCompleteTextView? = null
    private var acAudioCodec: AutoCompleteTextView? = null
    private var switchAudio: MaterialSwitch? = null
    private var switchStayAwake: MaterialSwitch? = null
    private var switchScreenOff: MaterialSwitch? = null
    private var switchSyncRotation: MaterialSwitch? = null
    private var switch0: MaterialSwitch? = null // no control
    private var switch1: MaterialSwitch? = null // nav bar
    private var switchUhid: MaterialSwitch? = null // mouse & keyboard UHID
    private var tilAudioCodec: TextInputLayout? = null
    private var btnStart: MaterialButton? = null

    private var sendCommands = SendCommands()
    private var fileBase64: ByteArray? = null

    private var usbManager: UsbAdbManager? = null
    private var isUsbMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedNightMode = getSharedPreferences(PREF_KEY, MODE_PRIVATE)
            .getInt("pref_night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedNightMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyManager = ConnectionHistoryManager.getInstance(this)

        loadServerJar()
        sendCommands = SendCommands()
        usbManager = UsbAdbManager.getInstance(this)

        bindViews()
        setupDropdowns()
        setupUsbListener()
        restorePreferences()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshHistoryCard()
        refreshHostDropdown()
    }

    override fun onStart() {
        super.onStart()
        usbManager?.let { mgr ->
            mgr.register()
            if (isUsbMode) {
                updateUsbDeviceUI()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager?.unregister()
    }

    private fun loadServerJar() {
        val am = assets
        try {
            am.open("scrcpy-server.jar").use { `is` ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var r: Int
                while (`is`.read(buf).also { r = it } != -1) {
                    baos.write(buf, 0, r)
                }
                val raw = baos.toByteArray()
                fileBase64 = Base64.encode(raw, Base64.NO_WRAP)
                Log.d(TAG, "Loaded scrcpy-server.jar: " + raw.size + " bytes")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load scrcpy-server.jar: " + e.message)
        }
    }

    private fun bindViews() {
        toggleGroupMode = findViewById(R.id.toggle_group_connection_mode)
        layoutWifiConnection = findViewById(R.id.layout_wifi_connection)
        layoutUsbConnection = findViewById(R.id.layout_usb_connection)
        tvUsbStatus = findViewById(R.id.tv_usb_status)
        btnUsbConnect = findViewById(R.id.button_usb_connect)
        etServerHost = findViewById(R.id.editText_server_host)
        btnThemeToggle = findViewById(R.id.btn_theme_toggle)
        cardHistory = findViewById(R.id.card_history)
        btnClearHistory = findViewById(R.id.btn_clear_history)
        layoutHistoryItems = findViewById(R.id.layout_history_items)
        tvEmptyHistory = findViewById(R.id.tv_empty_history)

        acResolution = findViewById(R.id.spinner_video_resolution)
        acBitrate = findViewById(R.id.spinner_video_bitrate)
        acCodec = findViewById(R.id.spinner_video_codec)
        acFps = findViewById(R.id.spinner_max_fps)
        acAudioCodec = findViewById(R.id.spinner_audio_codec)
        switchAudio = findViewById(R.id.switch_audio)
        switchStayAwake = findViewById(R.id.switch_stay_awake)
        switchScreenOff = findViewById(R.id.switch_screen_off)
        switchSyncRotation = findViewById(R.id.switch_sync_rotation)
        switch0 = findViewById(R.id.switch0)
        switch1 = findViewById(R.id.switch1)
        switchUhid = findViewById(R.id.switch_uhid)
        tilAudioCodec = findViewById(R.id.til_audio_codec)
        btnStart = findViewById(R.id.button_start)
    }

    private fun setupDropdowns() {
        acResolution?.let { setupDropdown(it, R.array.options_resolution_keys, PREF_RES_IDX, 1) }
        acBitrate?.let { setupDropdown(it, R.array.options_bitrate_keys, PREF_BITRATE_IDX, 1) }
        acCodec?.let { setupDropdown(it, R.array.options_codec_keys, PREF_CODEC_IDX, 0) }
        acFps?.let { setupDropdown(it, R.array.options_fps_keys, PREF_FPS_IDX, 0) }
        acAudioCodec?.let { setupDropdown(it, R.array.options_audio_codec_keys, PREF_AUDIO_CODEC_IDX, 0) }
    }

    private fun setupDropdown(view: AutoCompleteTextView, arrayRes: Int, prefKey: String, defaultIdx: Int) {
        val items = resources.getStringArray(arrayRes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        view.setAdapter(adapter)
        val savedIdx = prefs().getInt(prefKey, defaultIdx)
        if (savedIdx in items.indices) {
            view.setText(items[savedIdx], false)
        }
        view.setOnItemClickListener { _, _, pos, _ ->
            prefs().edit().putInt(prefKey, pos).apply()
        }
    }

    private fun restorePreferences() {
        val p = prefs()
        etServerHost?.setText(p.getString(PREF_SERVER_ADDRESS, ""))
        switchAudio?.isChecked = p.getBoolean(PREF_AUDIO, true)
        switchStayAwake?.isChecked = p.getBoolean(PREF_STAY_AWAKE, true)
        switchScreenOff?.isChecked = p.getBoolean(PREF_SCREEN_OFF, false)
        switchSyncRotation?.isChecked = p.getBoolean(PREF_SYNC_ROTATION, true)
        switch0?.isChecked = p.getBoolean(PREF_NO_CONTROL, false)
        if (!p.contains("pref_nav_v4_migrated")) {
            p.edit().putBoolean(PREF_NAV, true).putBoolean("pref_nav_v4_migrated", true).apply()
        }
        switch1?.isChecked = p.getBoolean(PREF_NAV, true)
        switchUhid?.isChecked = p.getBoolean(PREF_UHID, false)

        tilAudioCodec?.visibility = if (switchAudio?.isChecked == true) View.VISIBLE else View.GONE
        switch1?.visibility = if (switch0?.isChecked == true) View.GONE else View.VISIBLE

        val savedMode = p.getInt(PREF_CONNECTION_MODE, 0)
        if (savedMode == 1) {
            toggleGroupMode?.check(R.id.btn_mode_usb)
            setConnectionMode(true)
        } else {
            toggleGroupMode?.check(R.id.btn_mode_wifi)
            setConnectionMode(false)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        toggleGroupMode?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val usb = (checkedId == R.id.btn_mode_usb)
                setConnectionMode(usb)
                prefs().edit().putInt(PREF_CONNECTION_MODE, if (usb) 1 else 0).apply()
            }
        }

        btnUsbConnect?.setOnClickListener {
            val mgr = usbManager ?: return@setOnClickListener
            if (mgr.isConnected) {
                mgr.disconnect()
            } else {
                val dev = mgr.findAdbDevice()
                if (dev != null) {
                    tvUsbStatus?.setText(R.string.usb_status_connecting)
                    btnUsbConnect?.isEnabled = false
                    mgr.connect(dev)
                } else {
                    Toast.makeText(this, getString(R.string.usb_status_no_device), Toast.LENGTH_SHORT).show()
                }
            }
        }

        switchAudio?.setOnCheckedChangeListener { _, checked ->
            tilAudioCodec?.visibility = if (checked) View.VISIBLE else View.GONE
        }

        switch0?.setOnCheckedChangeListener { _, checked ->
            switch1?.visibility = if (checked) View.GONE else View.VISIBLE
        }

        findViewById<View>(R.id.button_adb_pair)?.setOnClickListener { showPairDialog() }
        btnStart?.setOnClickListener { onStartFullscreen() }
        findViewById<View>(R.id.button_start_float)?.setOnClickListener { onStartFloat() }

        setupThemeToggle()
        setupHostDropdown()
        setupHistoryCard()
    }

    private fun setupUsbListener() {
        usbManager?.setListener(object : UsbAdbManager.UsbListener {
            override fun onDeviceDiscovered(device: UsbDevice) {
                runOnUiThread {
                    tvUsbStatus?.text = getString(R.string.usb_status_detected, usbManager?.getDeviceDisplayName(device))
                    btnUsbConnect?.apply {
                        setText(R.string.usb_btn_scan_connect)
                        isEnabled = true
                    }
                    if (isUsbMode && usbManager?.isConnected == false) {
                        usbManager?.connect(device)
                    }
                }
            }

            override fun onDeviceConnected(device: UsbDevice, bridgePort: Int) {
                runOnUiThread {
                    tvUsbStatus?.text = getString(R.string.usb_status_connected, usbManager?.getDeviceDisplayName(device))
                    btnUsbConnect?.apply {
                        setText(R.string.usb_btn_disconnect)
                        isEnabled = true
                    }
                    if (isUsbMode) {
                        btnStart?.isEnabled = true
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.usb_status_connected, usbManager?.getDeviceDisplayName(device)), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDeviceDisconnected(device: UsbDevice) {
                runOnUiThread {
                    tvUsbStatus?.setText(R.string.usb_status_no_device)
                    btnUsbConnect?.apply {
                        setText(R.string.usb_btn_scan_connect)
                        isEnabled = true
                    }
                    if (isUsbMode) {
                        btnStart?.isEnabled = false
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    btnUsbConnect?.isEnabled = true
                }
            }
        })
    }

    private fun setConnectionMode(usb: Boolean) {
        isUsbMode = usb
        if (usb) {
            layoutWifiConnection?.visibility = View.GONE
            layoutUsbConnection?.visibility = View.VISIBLE
            btnStart?.isEnabled = usbManager?.isConnected == true
            updateUsbDeviceUI()
        } else {
            layoutWifiConnection?.visibility = View.VISIBLE
            layoutUsbConnection?.visibility = View.GONE
            btnStart?.isEnabled = true
        }
    }

    private fun updateUsbDeviceUI() {
        val mgr = usbManager ?: return
        if (mgr.isConnected) {
            tvUsbStatus?.text = getString(R.string.usb_status_connected, mgr.getDeviceDisplayName(mgr.connectedDevice))
            btnUsbConnect?.apply {
                setText(R.string.usb_btn_disconnect)
                isEnabled = true
            }
            btnStart?.isEnabled = true
        } else {
            val dev = mgr.findAdbDevice()
            if (dev != null) {
                tvUsbStatus?.text = getString(R.string.usb_status_detected, mgr.getDeviceDisplayName(dev))
                btnUsbConnect?.apply {
                    setText(R.string.usb_btn_scan_connect)
                    isEnabled = true
                }
                mgr.connect(dev)
            } else {
                tvUsbStatus?.setText(R.string.usb_status_no_device)
                btnUsbConnect?.apply {
                    setText(R.string.usb_btn_scan_connect)
                    isEnabled = true
                }
                if (isUsbMode) {
                    btnStart?.isEnabled = false
                }
            }
        }
    }

    private fun onStartFullscreen() {
        val addr = collectAndSaveParams() ?: return

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_connecting))
            .setMessage(getString(R.string.msg_deploying_server))
            .setCancelable(false)
            .create()
        dialog.show()

        btnStart?.isEnabled = false

        val fAddr = addr
        val fBitrate = getSelectedBitrate()
        val fMaxFps = getSelectedFps()
        val fVideoCodec = getSelectedVideoCodec()
        val fAudio = switchAudio?.isChecked == true
        val fAudioCodec = getSelectedAudioCodec()
        val fControl = switch0?.isChecked != true
        val fStayAwake = switchStayAwake?.isChecked == true
        val fSyncRotation = switchSyncRotation?.isChecked == true
        val fResolution = getSelectedResolution()
        val fMaxSize = Math.max(fResolution[0], fResolution[1])

        Thread({
            val res = sendCommands.SendAdbCommands(
                this, fileBase64, fAddr, 7007,
                fBitrate, fMaxSize, fMaxFps, fVideoCodec, fAudio, fAudioCodec, fControl, fStayAwake
            )
            runOnUiThread {
                dialog.dismiss()
                btnStart?.isEnabled = !isUsbMode || (usbManager?.isConnected == true)
                if (res == 0) {
                    if (isUsbMode) {
                        val dev = usbManager?.connectedDevice
                        val name = if (dev != null) usbManager?.getDeviceDisplayName(dev) ?: "USB Device" else "USB Device"
                        historyManager?.addHistory("USB", "usb", name)
                    } else {
                        historyManager?.addHistory(fAddr, "wifi", fAddr)
                    }
                    refreshHistoryCard()
                    refreshHostDropdown()

                    val i = Intent(this, ScreenMirrorActivity::class.java).apply {
                        putExtra(ScreenMirrorActivity.EXTRA_SERVER_ADR, if (isUsbMode) "127.0.0.1" else fAddr)
                        putExtra(ScreenMirrorActivity.EXTRA_SERVER_PORT, 7007)
                        putExtra(ScreenMirrorActivity.EXTRA_SCREEN_WIDTH, fResolution[0])
                        putExtra(ScreenMirrorActivity.EXTRA_SCREEN_HEIGHT, fResolution[1])
                        putExtra(ScreenMirrorActivity.EXTRA_AUDIO_ENABLED, fAudio)
                        putExtra(ScreenMirrorActivity.EXTRA_NAV, switch1?.isChecked == true)
                        putExtra(ScreenMirrorActivity.EXTRA_NO_CONTROL, !fControl)
                        putExtra(ScreenMirrorActivity.EXTRA_SCREEN_OFF, switchScreenOff?.isChecked == true)
                        putExtra(ScreenMirrorActivity.EXTRA_SYNC_ROTATION, fSyncRotation)
                        putExtra(ScreenMirrorActivity.EXTRA_UHID, switchUhid?.isChecked == true)
                    }
                    startActivity(i)
                } else {
                    val err = sendCommands.lastError
                    val msg = if (!err.isNullOrEmpty()) err else getString(R.string.msg_connection_failed)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }, "AdbConnectThread").start()
    }

    private fun onStartFloat() {
        val addr = collectAndSaveParams() ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_grant_overlay_permission, Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.msg_grant_notification_permission, Toast.LENGTH_SHORT).show()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, R.string.msg_grant_background_permission, Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                        return
                    } catch (ignored: Exception) {}
                }
            }
        }

        val fAddr = addr
        val fBitrate = getSelectedBitrate()
        val fMaxFps = getSelectedFps()
        val fVideoCodec = getSelectedVideoCodec()
        val fAudio = switchAudio?.isChecked == true
        val fAudioCodec = getSelectedAudioCodec()
        val fControl = switch0?.isChecked != true
        val fStayAwake = switchStayAwake?.isChecked == true
        val fResolution = getSelectedResolution()
        val fMaxSize = Math.max(fResolution[0], fResolution[1])

        Thread({
            val res = sendCommands.SendAdbCommands(
                this, fileBase64, fAddr, 7007,
                fBitrate, fMaxSize, fMaxFps, fVideoCodec, fAudio, fAudioCodec, fControl, fStayAwake
            )
            runOnUiThread {
                if (res == 0) {
                    if (isUsbMode) {
                        val dev = usbManager?.connectedDevice
                        val name = if (dev != null) usbManager?.getDeviceDisplayName(dev) ?: "USB Device" else "USB Device"
                        historyManager?.addHistory("USB", "usb", name)
                    } else {
                        historyManager?.addHistory(fAddr, "wifi", fAddr)
                    }
                    refreshHistoryCard()
                    refreshHostDropdown()

                    val serviceIntent = Intent(this, FloatService::class.java).apply {
                        putExtra("ip", if (isUsbMode) "127.0.0.1" else fAddr)
                        putExtra("port", 7007)
                        putExtra("width", fResolution[0])
                        putExtra("height", fResolution[1])
                        putExtra("audio", fAudio)
                        putExtra("no_control", !fControl)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    finish()
                } else {
                    val err = sendCommands.lastError
                    val msg = if (!err.isNullOrEmpty()) err else getString(R.string.msg_connection_failed)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }, "AdbFloatThread").start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onStartFloat()
            } else {
                Toast.makeText(this, R.string.msg_grant_notification_permission, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPairDialog() {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_adb_pair, null)

        val etHost = dialogView.findViewById<android.widget.EditText>(R.id.et_pair_host)
        val etPort = dialogView.findViewById<android.widget.EditText>(R.id.et_pair_port)
        val etCode = dialogView.findViewById<android.widget.EditText>(R.id.et_pair_code)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.pb_pair_progress)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_pair_status)

        val currentHost = etServerHost?.text?.toString()?.trim() ?: ""
        if (currentHost.isNotEmpty()) {
            val colon = currentHost.indexOf(':')
            etHost.setText(if (colon != -1) currentHost.substring(0, colon) else currentHost)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pair_dialog_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.pair_dialog_btn_pair), null)
            .setNegativeButton(getString(R.string.pair_dialog_btn_cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val host = etHost.text?.toString()?.trim() ?: ""
            val portStr = etPort.text?.toString()?.trim() ?: ""
            val code = etCode.text?.toString()?.trim() ?: ""

            if (host.isEmpty() || portStr.isEmpty() || code.isEmpty()) {
                tvStatus.setText(R.string.pair_err_fill_all)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val port = try {
                portStr.toInt()
            } catch (e: NumberFormatException) {
                tvStatus.setText(R.string.pair_err_port_format)
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            tvStatus.visibility = View.GONE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

            AdbPairingHelper.pair(this, host, port, code, object : AdbPairingHelper.PairingCallback {
                override fun onSuccess() {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, getString(R.string.pair_success), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    etServerHost?.let { et ->
                        et.setText("$host:")
                        et.setSelection(et.text?.length ?: 0)
                        et.requestFocus()
                    }
                }

                override fun onFailure(error: String) {
                    progressBar.visibility = View.GONE
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    tvStatus.text = error
                    tvStatus.visibility = View.VISIBLE
                }
            })
        }
    }

    private fun collectAndSaveParams(): String? {
        prefs().edit()
            .putBoolean(PREF_NO_CONTROL, switch0?.isChecked == true)
            .putBoolean(PREF_NAV, switch1?.isChecked == true)
            .putBoolean(PREF_AUDIO, switchAudio?.isChecked == true)
            .putBoolean(PREF_STAY_AWAKE, switchStayAwake?.isChecked == true)
            .putBoolean(PREF_SCREEN_OFF, switchScreenOff?.isChecked == true)
            .putBoolean(PREF_SYNC_ROTATION, switchSyncRotation?.isChecked == true)
            .putBoolean(PREF_UHID, switchUhid?.isChecked == true)
            .apply()

        if (isUsbMode) {
            val mgr = usbManager
            if (mgr == null || !mgr.isConnected) {
                Toast.makeText(this, getString(R.string.usb_status_no_device), Toast.LENGTH_SHORT).show()
                return null
            }
            return "127.0.0.1:" + mgr.bridgePort
        } else {
            val et = etServerHost ?: return null
            val addr = et.text?.toString()?.trim() ?: ""
            if (addr.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_address_empty), Toast.LENGTH_SHORT).show()
                return null
            }
            prefs().edit().putString(PREF_SERVER_ADDRESS, addr).apply()
            return addr
        }
    }

    private fun getSelectedResolution(): IntArray {
        val values = resources.getStringArray(R.array.options_resolution_values)
        var idx = prefs().getInt(PREF_RES_IDX, 1)
        if (idx !in values.indices) idx = 1
        val valStr = values[idx]
        return try {
            val parts = valStr.split("x")
            val w = parts[0].toInt()
            val h = parts[1].toInt()
            intArrayOf(w, h)
        } catch (e: Exception) {
            intArrayOf(1280, 720)
        }
    }

    private fun getSelectedBitrate(): Int {
        val values = resources.getIntArray(R.array.options_bitrate_values)
        var idx = prefs().getInt(PREF_BITRATE_IDX, 1)
        if (idx !in values.indices) idx = 1
        return values[idx]
    }

    private fun getSelectedFps(): Int {
        val values = resources.getIntArray(R.array.options_fps_values)
        var idx = prefs().getInt(PREF_FPS_IDX, 0)
        if (idx !in values.indices) idx = 0
        return values[idx]
    }

    private fun getSelectedVideoCodec(): String {
        val values = resources.getStringArray(R.array.options_codec_values)
        var idx = prefs().getInt(PREF_CODEC_IDX, 0)
        if (idx !in values.indices) idx = 0
        return values[idx]
    }

    private fun getSelectedAudioCodec(): String {
        val values = resources.getStringArray(R.array.options_audio_codec_values)
        var idx = prefs().getInt(PREF_AUDIO_CODEC_IDX, 0)
        if (idx !in values.indices) idx = 0
        return values[idx]
    }

    private fun prefs(): SharedPreferences {
        return getSharedPreferences(PREF_KEY, MODE_PRIVATE)
    }

    private fun setupThemeToggle() {
        val btn = btnThemeToggle ?: return
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        btn.setIconResource(if (isNight) R.drawable.ic_theme_toggle_day else R.drawable.ic_theme_toggle)

        btn.setOnClickListener {
            val cur = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val newMode = if (cur == Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
            prefs().edit().putInt("pref_night_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
            recreate()
        }
    }

    private fun refreshHostDropdown() {
        val et = etServerHost ?: return
        val mgr = historyManager ?: return
        val recents = mgr.getRecentAddresses(5)
        if (recents.isNullOrEmpty()) {
            et.setAdapter(null)
            return
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, recents) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()
                        results.values = recents
                        results.count = recents.size
                        return results
                    }

                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        notifyDataSetChanged()
                    }
                }
            }
        }
        et.setAdapter(adapter)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHostDropdown() {
        val et = etServerHost ?: return
        et.threshold = 0
        refreshHostDropdown()

        et.setOnClickListener {
            refreshHostDropdown()
            if (et.adapter != null && et.adapter.count > 0) {
                et.showDropDown()
            }
        }

        et.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                refreshHostDropdown()
                if (et.adapter != null && et.adapter.count > 0) {
                    et.post {
                        if (et.windowToken != null) {
                            et.showDropDown()
                        }
                    }
                }
            }
            false
        }

        et.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                refreshHostDropdown()
                if (et.adapter != null && et.adapter.count > 0) {
                    et.post {
                        if (et.windowToken != null) {
                            et.showDropDown()
                        }
                    }
                }
            }
        }

        et.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as? String
            if (selected != null) {
                et.setText(selected, false)
                et.setSelection(selected.length)
                prefs().edit().putString(PREF_SERVER_ADDRESS, selected).apply()
            }
        }
    }

    private fun setupHistoryCard() {
        btnClearHistory?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_history_title)
                .setMessage(R.string.dialog_clear_history_msg)
                .setPositiveButton(R.string.dialog_btn_clear) { _, _ ->
                    historyManager?.let { mgr ->
                        mgr.clearHistory()
                        refreshHistoryCard()
                        refreshHostDropdown()
                        Toast.makeText(this, R.string.msg_history_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.pair_dialog_btn_cancel, null)
                .show()
        }
        refreshHistoryCard()
    }

    private fun refreshHistoryCard() {
        val layout = layoutHistoryItems ?: return
        val mgr = historyManager ?: return
        val list = mgr.historyList
        if (list.isNullOrEmpty()) {
            tvEmptyHistory?.visibility = View.VISIBLE
            btnClearHistory?.visibility = View.GONE
            layout.removeAllViews()
            return
        }

        tvEmptyHistory?.visibility = View.GONE
        btnClearHistory?.visibility = View.VISIBLE
        layout.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val maxItems = Math.min(list.size, 5)
        for (i in 0 until maxItems) {
            val item = list[i]
            val row = inflater.inflate(R.layout.item_connection_history, layout, false)
            val ivType = row.findViewById<ImageView>(R.id.iv_history_type)
            val tvAddress = row.findViewById<TextView>(R.id.tv_history_address)
            val tvTime = row.findViewById<TextView>(R.id.tv_history_time)
            val btnDelete = row.findViewById<View>(R.id.btn_delete_history_item)

            val isWifi = "wifi".equals(item.type, ignoreCase = true)
            ivType?.setImageResource(if (isWifi) R.drawable.ic_wifi_network else R.drawable.ic_usb_cable)
            tvAddress?.text = if (!TextUtils.isEmpty(item.displayName)) item.displayName else item.address
            if (item.timestamp > 0) {
                val rel = DateUtils.getRelativeTimeSpanString(item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                tvTime?.text = rel
            } else {
                tvTime?.text = ""
            }

            row.setOnClickListener {
                if (isWifi) {
                    toggleGroupMode?.check(R.id.btn_mode_wifi)
                    setConnectionMode(false)
                    etServerHost?.let { et ->
                        et.setText(item.address)
                        et.setSelection(item.address.length)
                        prefs().edit().putString(PREF_SERVER_ADDRESS, item.address).apply()
                        et.requestFocus()
                    }
                } else {
                    toggleGroupMode?.check(R.id.btn_mode_usb)
                    setConnectionMode(true)
                }
            }

            btnDelete?.setOnClickListener {
                mgr.deleteHistory(item.address)
                refreshHistoryCard()
                refreshHostDropdown()
            }

            layout.addView(row)
        }
    }
}

