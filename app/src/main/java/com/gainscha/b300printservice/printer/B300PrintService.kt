package com.gainscha.b300printservice.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import kotlin.math.roundToInt

class B300PrintService : PrintService() {

    companion object {
        private const val TAG = "B300PrintService"

        /** Floyd-Steinberg error-diffusion dithering for best thermal print quality */
        fun floydSteinbergDither(source: Bitmap, threshold: Int): Bitmap {
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            // Convert to grayscale values
            val gray = FloatArray(w * h) { i ->
                val c = pixels[i]
                0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            }

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val oldVal = gray[idx].coerceIn(0f, 255f)
                    val newVal = if (oldVal < threshold) 0f else 255f
                    val err = oldVal - newVal
                    gray[idx] = newVal

                    // Distribute error to neighbors
                    if (x + 1 < w)               gray[idx + 1]     += err * 7f / 16f
                    if (y + 1 < h && x - 1 >= 0) gray[idx + w - 1] += err * 3f / 16f
                    if (y + 1 < h)               gray[idx + w]     += err * 5f / 16f
                    if (y + 1 < h && x + 1 < w)  gray[idx + w + 1] += err * 1f / 16f

                    val v = newVal.roundToInt()
                    out.setPixel(x, y, Color.argb(255, v, v, v))
                }
            }
            return out
        }

        /** Atkinson dithering — preserves more detail in highlights */
        fun atkinsonDither(source: Bitmap, threshold: Int): Bitmap {
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            val gray = FloatArray(w * h) { i ->
                val c = pixels[i]
                0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            }

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val oldVal = gray[idx].coerceIn(0f, 255f)
                    val newVal = if (oldVal < threshold) 0f else 255f
                    val err = (oldVal - newVal) / 8f

                    if (x + 1 < w)                gray[idx + 1]         += err
                    if (x + 2 < w)                gray[idx + 2]         += err
                    if (y + 1 < h && x - 1 >= 0) gray[idx + w - 1]     += err
                    if (y + 1 < h)                gray[idx + w]         += err
                    if (y + 1 < h && x + 1 < w)  gray[idx + w + 1]     += err
                    if (y + 2 < h)                gray[idx + w * 2]     += err

                    val v = newVal.roundToInt()
                    out.setPixel(x, y, Color.argb(255, v, v, v))
                }
            }
            return out
        }

        /** Ordered (Bayer 4x4) dithering */
        private val BAYER_4X4 = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5)
        )

        fun orderedDither(source: Bitmap, threshold: Int): Bitmap {
            val w = source.width
            val h = source.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = source.getPixel(x, y)
                    val g = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).roundToInt()
                    val bayer = BAYER_4X4[y % 4][x % 4] * 16
                    val v = if (g + bayer < threshold + 128) 0 else 255
                    out.setPixel(x, y, Color.argb(255, v, v, v))
                }
            }
            return out
        }

        /** Apply contrast and brightness adjustments */
        fun adjustContrastBrightness(src: Bitmap, contrast: Float, brightness: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, src.config)
            val scale = contrast        // 1.0 = normal
            val translate = brightness  // 0 = normal

            val cm = ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))

            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            return out
        }

        /** Simple grayscale conversion */
        fun toGrayscale(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix().apply { setSaturation(0f) }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            return out
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var isJobCancelled = false

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
        try { printJob?.cancel() } catch (e: Exception) { }
    }

    override fun onPrintJobQueued(printJob: PrintJob?) {
        Log.d(TAG, "onPrintJobQueued called")
        val job = printJob ?: return
        val printerId = job.info?.printerId ?: run {
            try { job.fail("No printer selected") } catch (e: Exception) { }
            return
        }

        isJobCancelled = false

        // MUST be called on main thread
        val descriptor = try {
            job.document?.data ?: run {
                try { job.fail("PDF document is empty") } catch (e: Exception) { }
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get document data: ${e.message}")
            try { job.fail("Cannot read document") } catch (ex: Exception) { }
            return
        }

        try { job.start() } catch (e: Exception) {
            Log.e(TAG, "Failed to start job: ${e.message}"); return
        }

        // Load settings on main thread too
        val prefs = getSharedPreferences("B300_SETTINGS", android.content.Context.MODE_PRIVATE)
        val paperType  = prefs.getString("paper_type", "Gap") ?: "Gap"
        val darkIndex  = prefs.getInt("darkness_index", 5)
        val speedIndex = prefs.getInt("speed_index", 2)
        val ditherMode = prefs.getInt("dithering_index", 1) // 0=Threshold,1=Floyd,2=Atkinson,3=Ordered
        val userWidthMm  = prefs.getFloat("paper_width", 78f).toInt()
        val threshold  = prefs.getInt("threshold", 128)
        // contrast: 0-200 → 0.0-2.0, center=1.0 at 100
        val contrast   = prefs.getInt("contrast", 100) / 100f
        // brightness: 0-200 → -128..+128 shift, center=0 at 100
        val brightness = (prefs.getInt("brightness", 100) - 100) * 1.28f

        // Darkness values for the SDK (1-15)
        val darknessValues = intArrayOf(1, 3, 5, 7, 8, 10, 12, 14, 15)
        val darknessVal = darknessValues.getOrElse(darkIndex) { 10 }
        // Speed values (1-5)
        val speedVal = (speedIndex + 1).coerceIn(1, 5)

        executor.execute {
            var pdfRenderer: PdfRenderer? = null
            var bluetoothPort: BluetoothPort? = null
            var tempFile: java.io.File? = null
            var seekableDescriptor: ParcelFileDescriptor? = null

            try {
                val macAddress = printerId.localId
                Log.d(TAG, "Connecting to: $macAddress")

                // Copy to seekable temp file
                tempFile = java.io.File.createTempFile("print_job", ".pdf", cacheDir)
                java.io.FileInputStream(descriptor.fileDescriptor).use { i ->
                    java.io.FileOutputStream(tempFile).use { o -> i.copyTo(o) }
                }
                try { descriptor.close() } catch (e: Exception) { }

                seekableDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(seekableDescriptor)

                bluetoothPort = BluetoothPort(macAddress)
                if (!bluetoothPort.openPort()) throw Exception("Cannot connect to printer: $macAddress")

                // 203 DPI → 8 dots/mm
                val targetPixelWidth = userWidthMm * 8
                val pageCount = pdfRenderer.pageCount
                Log.d(TAG, "Pages=$pageCount width=${userWidthMm}mm dither=$ditherMode threshold=$threshold")

                for (i in 0 until pageCount) {
                    if (isJobCancelled) break

                    val page = pdfRenderer.openPage(i)
                    val scale = targetPixelWidth.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt()

                    // Render at 2x for better quality then downsample
                    val renderScale = 2
                    val highResBitmap = Bitmap.createBitmap(
                        targetPixelWidth * renderScale,
                        height * renderScale,
                        Bitmap.Config.ARGB_8888
                    )
                    highResBitmap.eraseColor(Color.WHITE)
                    page.render(highResBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    // Downsample to target size
                    var bitmap = Bitmap.createScaledBitmap(highResBitmap, targetPixelWidth, height, true)
                    highResBitmap.recycle()

                    // 1. Grayscale
                    val gray = toGrayscale(bitmap)
                    bitmap.recycle()

                    // 2. Contrast / Brightness
                    val adjusted = adjustContrastBrightness(gray, contrast, brightness)
                    gray.recycle()

                    // 3. Dithering → 1-bit black/white
                    val dithered = when (ditherMode) {
                        1 -> floydSteinbergDither(adjusted, threshold)
                        2 -> atkinsonDither(adjusted, threshold)
                        3 -> orderedDither(adjusted, threshold)
                        else -> {
                            // Simple threshold
                            val t = Bitmap.createBitmap(adjusted.width, adjusted.height, Bitmap.Config.ARGB_8888)
                            for (y in 0 until adjusted.height) {
                                for (x in 0 until adjusted.width) {
                                    val g = Color.red(adjusted.getPixel(x, y))
                                    t.setPixel(x, y, if (g < threshold) Color.BLACK else Color.WHITE)
                                }
                            }
                            t
                        }
                    }
                    adjusted.recycle()

                    val command = LabelCommand()
                    command.addSize(userWidthMm, height / 8)

                    // Speed
                    try {
                        val speedEnum = LabelCommand.SPEED::class.java.enumConstants
                            ?.find { it.name.endsWith(speedVal.toString()) }
                        if (speedEnum != null) {
                            command.javaClass.getMethod("addSpeed", LabelCommand.SPEED::class.java)
                                .invoke(command, speedEnum)
                        }
                    } catch (e: Exception) { Log.w(TAG, "Speed not set: ${e.message}") }

                    // Darkness
                    try {
                        val densityEnum = LabelCommand.DENSITY::class.java.enumConstants
                            ?.find { it.name.endsWith(darknessVal.toString()) }
                        if (densityEnum != null) {
                            command.javaClass.getMethod("addDensity", LabelCommand.DENSITY::class.java)
                                .invoke(command, densityEnum)
                        }
                    } catch (e: Exception) { Log.w(TAG, "Darkness not set: ${e.message}") }

                    when (paperType) {
                        "Continuous" -> command.addReference(0, 0)
                        "Black Mark" -> {
                            try {
                                command.javaClass.getMethod("addBline", Int::class.java, Int::class.java)
                                    .invoke(command, 2, 0)
                            } catch (e: Exception) { command.addGap(0) }
                        }
                        else -> command.addGap(2)
                    }

                    command.addCls()

                    try {
                        command.addBitmap(0, 0, LabelCommand.BITMAP_MODE.OVERWRITE, targetPixelWidth, dithered)
                    } catch (e: NoSuchMethodError) {
                        command.addBitmap(0, 0, targetPixelWidth, dithered)
                    }

                    command.addPrint(1)
                    bluetoothPort.writeDataImmediately(command.command)
                    dithered.recycle()

                    Thread.sleep(500)
                }

                handler.post {
                    try {
                        if (isJobCancelled) job.cancel() else job.complete()
                    } catch (e: Exception) { Log.e(TAG, "Error completing job: ${e.message}") }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Print error: ${e.message}", e)
                handler.post { try { job.fail(e.message ?: "Unknown error") } catch (ex: Exception) { } }
            } finally {
                try { bluetoothPort?.closePort() } catch (e: Exception) { }
                try { pdfRenderer?.close() } catch (e: Exception) { }
                try { seekableDescriptor?.close() } catch (e: Exception) { }
                try { tempFile?.delete() } catch (e: Exception) { }
            }
        }
    }
}
