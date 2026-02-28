package com.gainscha.b300printservice.printer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.smart.command.LabelCommand
import com.smart.io.BluetoothPort
import java.util.concurrent.Executors

class B300PrintService : PrintService() {

    companion object {
        private const val TAG = "B300PrintService"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isJobCancelled = false

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession? {
        return try {
            B300PrinterDiscoverySession(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create discovery session: ${e.message}", e)
            null
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob?) {
        Log.d(TAG, "onRequestCancelPrintJob called")
        isJobCancelled = true
        try {
            printJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling job: ${e.message}")
        }
    }

    override fun onPrintJobQueued(printJob: PrintJob?) {
        Log.d(TAG, "onPrintJobQueued called")
        val job = printJob ?: run {
            Log.e(TAG, "Print job is null")
            return
        }

        val printerId = job.info?.printerId ?: run {
            Log.e(TAG, "Printer ID is null")
            try { job.fail("No printer selected") } catch (e: Exception) { }
            return
        }

        isJobCancelled = false

        // job.document.data MUST be called on the main thread — do it here before executor
        val descriptor = try {
            job.document?.data ?: run {
                job.fail("PDF document is empty")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get document data: ${e.message}")
            try { job.fail("Cannot read document") } catch (ex: Exception) { }
            return
        }

        try {
            job.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start job: ${e.message}")
            return
        }

        executor.execute {
            var pdfRenderer: PdfRenderer? = null
            var bluetoothPort: BluetoothPort? = null
            var tempFile: java.io.File? = null
            var seekableDescriptor: ParcelFileDescriptor? = null

            try {
                val macAddress = printerId.localId
                Log.d(TAG, "Connecting to: $macAddress")

                // PdfRenderer requires a seekable file descriptor.
                // The descriptor from the print framework is a non-seekable pipe,
                // so we must copy it to a temp file first.
                tempFile = java.io.File.createTempFile("print_job", ".pdf", cacheDir)
                java.io.FileInputStream(descriptor.fileDescriptor).use { inStream ->
                    java.io.FileOutputStream(tempFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                try { descriptor.close() } catch (e: Exception) { }

                seekableDescriptor = android.os.ParcelFileDescriptor.open(
                    tempFile,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                pdfRenderer = PdfRenderer(seekableDescriptor)

                bluetoothPort = BluetoothPort(macAddress)
                if (!bluetoothPort.openPort()) {
                    throw Exception("Cannot connect to printer: $macAddress")
                }

                val sharedPrefs = getSharedPreferences("B300_SETTINGS", android.content.Context.MODE_PRIVATE)
                val paperType = sharedPrefs.getString("paper_type", "Gap") ?: "Gap"
                val darknessStr = sharedPrefs.getString("darkness", "10") ?: "10"
                val darknessVal = darknessStr.split(" ").firstOrNull()?.toIntOrNull() ?: 10
                val userWidthMm = sharedPrefs.getFloat("paper_width", 100f).toInt()
                val targetPixelWidth = userWidthMm * 8 // 203 DPI ≈ 8 dots/mm

                val pageCount = pdfRenderer.pageCount
                Log.d(TAG, "Printing $pageCount pages at width ${userWidthMm}mm")

                for (i in 0 until pageCount) {
                    if (isJobCancelled) break

                    val page = pdfRenderer.openPage(i)
                    val width = targetPixelWidth
                    val scale = width.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt()

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val command = LabelCommand()
                    command.addSize(userWidthMm, height / 8)

                    // Set darkness
                    try {
                        val densityEnum = LabelCommand.DENSITY::class.java.enumConstants
                            ?.find { it.name.endsWith(darknessVal.toString()) }
                        if (densityEnum != null) {
                            command.javaClass.getMethod("addDensity", LabelCommand.DENSITY::class.java)
                                .invoke(command, densityEnum)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot set density: ${e.message}")
                    }

                    when (paperType) {
                        "Continuous" -> command.addReference(0, 0)
                        "Black Mark" -> {
                            try {
                                command.javaClass.getMethod("addBline", Int::class.java, Int::class.java)
                                    .invoke(command, 2, 0)
                            } catch (e: Exception) {
                                command.addGap(0)
                            }
                        }
                        else -> command.addGap(2)
                    }

                    command.addCls()

                    try {
                        command.addBitmap(0, 0, LabelCommand.BITMAP_MODE.OVERWRITE, width, bitmap)
                    } catch (e: NoSuchMethodError) {
                        command.addBitmap(0, 0, width, bitmap)
                    }

                    command.addPrint(1)
                    bluetoothPort.writeDataImmediately(command.command)
                    bitmap.recycle()

                    Thread.sleep(500)
                }

                handler.post {
                    try {
                        if (isJobCancelled) job.cancel() else job.complete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error completing job: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Print error: ${e.message}", e)
                handler.post {
                    try { job.fail(e.message ?: "Unknown error") } catch (ex: Exception) { }
                }
            } finally {
                try { bluetoothPort?.closePort() } catch (e: Exception) { }
                try { pdfRenderer?.close() } catch (e: Exception) { }
                try { seekableDescriptor?.close() } catch (e: Exception) { }
                try { tempFile?.delete() } catch (e: Exception) { }
            }
        }
    }
}
