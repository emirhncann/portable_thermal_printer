package com.gainscha.b300printservice.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log

class B300PrinterDiscoverySession(private val printService: PrintService) : PrinterDiscoverySession() {

    companion object {
        private const val TAG = "B300DiscoverySession"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null

    init {
        try {
            val bm = printService.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bm?.adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Bluetooth adapter: ${e.message}")
        }
    }

    private val printers = mutableMapOf<PrinterId, PrinterInfo>()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let { tryAddPrinter(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.d(TAG, "onStartPrinterDiscovery")
        printers.clear()

        // Add bonded devices first — guard against SecurityException
        try {
            bluetoothAdapter?.bondedDevices?.forEach { tryAddPrinter(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission not granted yet: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning bonded devices: ${e.message}")
        }

        // Scan for new devices
        try {
            if (!receiverRegistered) {
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                printService.registerReceiver(receiver, filter)
                receiverRegistered = true
            }
            bluetoothAdapter?.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start discovery - no permission: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery: ${e.message}")
        }
    }

    override fun onStopPrinterDiscovery() {
        Log.d(TAG, "onStopPrinterDiscovery")
        stopReceiverAndDiscovery()
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        val toUpdate = mutableListOf<PrinterInfo>()
        for (id in printerIds) {
            printers[id]?.let { toUpdate.add(it) }
        }
        if (toUpdate.isNotEmpty()) {
            addPrinters(toUpdate)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStartPrinterStateTracking: ${printerId.localId}")
        val printer = printers[printerId]
        if (printer != null) {
            try {
                val updated = PrinterInfo.Builder(printer)
                    .setStatus(PrinterInfo.STATUS_IDLE)
                    .build()
                printers[printerId] = updated
                addPrinters(listOf(updated))
            } catch (e: Exception) {
                Log.e(TAG, "Error updating printer status: ${e.message}")
            }
        } else {
            try {
                bluetoothAdapter?.bondedDevices?.forEach { device ->
                    if (printService.generatePrinterId(device.address) == printerId) {
                        tryAddPrinter(device)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to query bonded devices: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking: ${e.message}")
            }
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStopPrinterStateTracking: ${printerId.localId}")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopReceiverAndDiscovery()
    }

    private fun stopReceiverAndDiscovery() {
        if (receiverRegistered) {
            try {
                printService.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already unregistered
            }
            receiverRegistered = false
        }
        try {
            @Suppress("MissingPermission")
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun tryAddPrinter(device: BluetoothDevice) {
        try {
            val name: String = try {
                device.name ?: return
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read device name - no permission")
                return
            }

            val sharedPrefs = printService.getSharedPreferences("B300_SETTINGS", Context.MODE_PRIVATE)
            val targetMac = sharedPrefs.getString("printer_mac", "")

            val isTarget = if (!targetMac.isNullOrEmpty()) {
                device.address == targetMac
            } else {
                name.contains("B300", ignoreCase = true)
            }

            if (!isTarget) return

            val printerId = printService.generatePrinterId(device.address)

            val widthMm = sharedPrefs.getFloat("paper_width", 100f)
            val heightMm = sharedPrefs.getFloat("paper_height", 150f)
            val widthMils = (widthMm * 39.37f).toInt()
            val heightMils = (heightMm * 39.37f).toInt()

            val customMedia = android.print.PrintAttributes.MediaSize(
                "CUSTOM_LBL", name, widthMils, heightMils
            )

            val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
                .addMediaSize(customMedia, true)
                .addResolution(
                    android.print.PrintAttributes.Resolution("R1", "203 DPI", 203, 203),
                    true
                )
                .setColorModes(
                    android.print.PrintAttributes.COLOR_MODE_MONOCHROME,
                    android.print.PrintAttributes.COLOR_MODE_MONOCHROME
                )
                .build()

            val printerInfo = PrinterInfo.Builder(printerId, name, PrinterInfo.STATUS_IDLE)
                .setCapabilities(capabilities)
                .setDescription("Gainscha B300 Thermal Printer")
                .build()

            printers[printerId] = printerInfo
            addPrinters(listOf(printerInfo))
            Log.d(TAG, "Added printer: $name [${device.address}]")

        } catch (e: Exception) {
            Log.e(TAG, "Error adding printer: ${e.message}", e)
        }
    }
}
