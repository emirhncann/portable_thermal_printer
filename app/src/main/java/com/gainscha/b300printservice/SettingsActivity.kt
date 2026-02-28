package com.gainscha.b300printservice

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("B300_SETTINGS", Context.MODE_PRIVATE)

        val btSpinner: Spinner = findViewById(R.id.spinner_bt_devices)
        val btButton: Button = findViewById(R.id.btn_open_bt)
        val paperTypeSpinner: Spinner = findViewById(R.id.spinner_paper_type)
        val darknessSpinner: Spinner = findViewById(R.id.spinner_darkness)
        val etWidth: android.widget.EditText = findViewById(R.id.et_paper_width)
        val etHeight: android.widget.EditText = findViewById(R.id.et_paper_height)
        val saveButton: Button = findViewById(R.id.btn_save_settings)

        // Setup Spinners
        val paperTypes = arrayOf("Gap", "Black Mark", "Continuous")
        val darknessLevels = arrayOf("8 (Light)", "10 (Normal)", "12 (Dark)", "14 (Darker)")

        paperTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paperTypes)
        darknessSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, darknessLevels)

        // Setup Bluetooth Devices
        btButton.setOnClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        val btDevicesList = mutableListOf<Pair<String, String>>()
        val adapterNames = mutableListOf<String>()

        try {
            val bAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bAdapter != null && bAdapter.isEnabled) {
                // Must ensure permissions are granted (checkAndRequestPermissions handles prompting, 
                // but if already granted, this populates immediately)
                val pairedDevices = bAdapter.bondedDevices
                if (pairedDevices.isNotEmpty()) {
                    for (device in pairedDevices) {
                        btDevicesList.add(Pair(device.name ?: "Unknown", device.address))
                        adapterNames.add("${device.name} (${device.address})")
                    }
                }
            }
        } catch (e: SecurityException) {
            // Permission not granted yet
        }

        if (adapterNames.isEmpty()) {
            adapterNames.add("No Paired Devices Found")
            btDevicesList.add(Pair("", ""))
        }

        btSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, adapterNames)

        // Load Preferences
        val currentPaperType = sharedPreferences.getString("paper_type", "Gap")
        val currentDarkness = sharedPreferences.getString("darkness", "10 (Normal)")
        val currentWidth = sharedPreferences.getFloat("paper_width", 172f)
        val currentHeight = sharedPreferences.getFloat("paper_height", 32f)
        val currentMac = sharedPreferences.getString("printer_mac", "")

        paperTypeSpinner.setSelection(paperTypes.indexOf(currentPaperType).takeIf { it >= 0 } ?: 0)
        darknessSpinner.setSelection(darknessLevels.indexOf(currentDarkness).takeIf { it >= 0 } ?: 1)
        etWidth.setText(currentWidth.toString())
        etHeight.setText(currentHeight.toString())
        
        val savedDeviceIndex = btDevicesList.indexOfFirst { it.second == currentMac }
        btSpinner.setSelection(if (savedDeviceIndex >= 0) savedDeviceIndex else 0)

        saveButton.setOnClickListener {
            val selectedPaper = paperTypeSpinner.selectedItem.toString()
            val selectedDarkness = darknessSpinner.selectedItem.toString()
            val width = etWidth.text.toString().toFloatOrNull() ?: 100f
            val height = etHeight.text.toString().toFloatOrNull() ?: 150f

            val editor = sharedPreferences.edit()
                .putString("paper_type", selectedPaper)
                .putString("darkness", selectedDarkness)
                .putFloat("paper_width", width)
                .putFloat("paper_height", height)

            if (btSpinner.selectedItemPosition >= 0 && btSpinner.selectedItemPosition < btDevicesList.size) {
                val selectedDevice = btDevicesList[btSpinner.selectedItemPosition]
                if (selectedDevice.second.isNotEmpty()) {
                    editor.putString("printer_name", selectedDevice.first)
                    editor.putString("printer_mac", selectedDevice.second)
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
        // Location is still needed for scanning even on 12+ if we don't use neverForLocation correctly,
        // but we added it to manifest. Let's ask for Location anyway just to be safe across all versions.
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)

        val missingPermissions = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                100
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Permissions are required to find the printer!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
