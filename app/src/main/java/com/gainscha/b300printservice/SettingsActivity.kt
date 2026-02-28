package com.gainscha.b300printservice

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("B300_SETTINGS", Context.MODE_PRIVATE)

        // Views
        val btSpinner: Spinner = findViewById(R.id.spinner_bt_devices)
        val btButton: Button = findViewById(R.id.btn_open_bt)
        val paperTypeSpinner: Spinner = findViewById(R.id.spinner_paper_type)
        val darknessSpinner: Spinner = findViewById(R.id.spinner_darkness)
        val speedSpinner: Spinner = findViewById(R.id.spinner_speed)
        val ditheringSpinner: Spinner = findViewById(R.id.spinner_dithering)
        val etWidth: android.widget.EditText = findViewById(R.id.et_paper_width)
        val etHeight: android.widget.EditText = findViewById(R.id.et_paper_height)
        val seekbarThreshold: SeekBar = findViewById(R.id.seekbar_threshold)
        val tvThresholdLabel: TextView = findViewById(R.id.tv_threshold_label)
        val seekbarContrast: SeekBar = findViewById(R.id.seekbar_contrast)
        val tvContrastLabel: TextView = findViewById(R.id.tv_contrast_label)
        val seekbarBrightness: SeekBar = findViewById(R.id.seekbar_brightness)
        val tvBrightnessLabel: TextView = findViewById(R.id.tv_brightness_label)
        val saveButton: Button = findViewById(R.id.btn_save_settings)

        // === Bluetooth Devices ===
        btButton.setOnClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        val btDevicesList = mutableListOf<Pair<String, String>>()
        val adapterNames = mutableListOf<String>()

        @SuppressLint("MissingPermission")
        fun loadBtDevices() {
            try {
                val bAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bAdapter != null && bAdapter.isEnabled) {
                    for (device in bAdapter.bondedDevices) {
                        val name = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
                        btDevicesList.add(Pair(name, device.address))
                        adapterNames.add("$name (${device.address})")
                    }
                }
            } catch (e: SecurityException) { /* no permission yet */ }
        }

        loadBtDevices()

        if (adapterNames.isEmpty()) {
            adapterNames.add("No Paired Devices Found")
            btDevicesList.add(Pair("", ""))
        }
        btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, adapterNames)

        // === Paper Type ===
        val paperTypes = arrayOf("Gap", "Black Mark", "Continuous")
        paperTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paperTypes)

        // === Darkness ===
        val darknessLevels = arrayOf(
            "1 - Çok Açık", "3 - Açık", "5", "7", "8 - Normal-Açık",
            "10 - Normal", "12 - Normal-Koyu", "14 - Koyu", "15 - Çok Koyu"
        )
        darknessSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, darknessLevels)

        // === Print Speed ===
        val speedLevels = arrayOf(
            "1 - Çok Yavaş (En İyi Kalite)", "2 - Yavaş", "3 - Normal",
            "4 - Hızlı", "5 - Çok Hızlı"
        )
        speedSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speedLevels)

        // === Dithering ===
        val ditheringModes = arrayOf(
            "Threshold (Hızlı)", "Floyd-Steinberg (En İyi)", "Atkinson", "Ordered (Bayer)"
        )
        ditheringSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ditheringModes)

        // === Seekbar Listeners ===
        seekbarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvThresholdLabel.text = "Threshold (Eşik): $p"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekbarContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvContrastLabel.text = "Contrast (Kontrast): $p%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekbarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvBrightnessLabel.text = "Brightness (Parlaklık): $p%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // === Load Preferences ===
        val currentPaperType = sharedPreferences.getString("paper_type", "Gap")
        val currentDark = sharedPreferences.getInt("darkness_index", 5)
        val currentSpeed = sharedPreferences.getInt("speed_index", 2)
        val currentDither = sharedPreferences.getInt("dithering_index", 1) // Floyd-Steinberg default
        val currentWidth = sharedPreferences.getFloat("paper_width", 78f)
        val currentHeight = sharedPreferences.getFloat("paper_height", 32f)
        val currentMac = sharedPreferences.getString("printer_mac", "")
        val currentThreshold = sharedPreferences.getInt("threshold", 128)
        val currentContrast = sharedPreferences.getInt("contrast", 100)
        val currentBrightness = sharedPreferences.getInt("brightness", 100)

        paperTypeSpinner.setSelection(paperTypes.indexOf(currentPaperType).coerceAtLeast(0))
        darknessSpinner.setSelection(currentDark.coerceIn(0, darknessLevels.lastIndex))
        speedSpinner.setSelection(currentSpeed.coerceIn(0, speedLevels.lastIndex))
        ditheringSpinner.setSelection(currentDither.coerceIn(0, ditheringModes.lastIndex))
        etWidth.setText(currentWidth.toString())
        etHeight.setText(currentHeight.toString())
        seekbarThreshold.progress = currentThreshold
        tvThresholdLabel.text = "Threshold (Eşik): $currentThreshold"
        seekbarContrast.progress = currentContrast
        tvContrastLabel.text = "Contrast (Kontrast): $currentContrast%"
        seekbarBrightness.progress = currentBrightness
        tvBrightnessLabel.text = "Brightness (Parlaklık): $currentBrightness%"

        val savedDeviceIndex = btDevicesList.indexOfFirst { it.second == currentMac }
        btSpinner.setSelection(if (savedDeviceIndex >= 0) savedDeviceIndex else 0)

        // === Save ===
        saveButton.setOnClickListener {
            val width = etWidth.text.toString().toFloatOrNull() ?: 78f
            val height = etHeight.text.toString().toFloatOrNull() ?: 32f

            val editor = sharedPreferences.edit()
                .putString("paper_type", paperTypeSpinner.selectedItem.toString())
                .putInt("darkness_index", darknessSpinner.selectedItemPosition)
                .putInt("speed_index", speedSpinner.selectedItemPosition)
                .putInt("dithering_index", ditheringSpinner.selectedItemPosition)
                .putFloat("paper_width", width)
                .putFloat("paper_height", height)
                .putInt("threshold", seekbarThreshold.progress)
                .putInt("contrast", seekbarContrast.progress)
                .putInt("brightness", seekbarBrightness.progress)

            if (btSpinner.selectedItemPosition >= 0 && btSpinner.selectedItemPosition < btDevicesList.size) {
                val sel = btDevicesList[btSpinner.selectedItemPosition]
                if (sel.second.isNotEmpty()) {
                    editor.putString("printer_name", sel.first)
                    editor.putString("printer_mac", sel.second)
                }
            }
            editor.apply()

            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)

        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Bluetooth permissions are required!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
