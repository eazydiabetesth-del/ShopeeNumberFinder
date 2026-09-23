package com.example.shopeenumberfinder

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager

class CaptureService : Service() {

    private val channel = "capture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private var frameCount = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            sendStatus("Projection stopped")
            cleanupCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channel,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channel)
            } else {
                Notification.Builder(this)
            }
                .setContentTitle("Number Finder")
                .setContentText("Phase 3A pixel analysis")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

        startForeground(1001, notification)

        try {
            val resultCode =
                intent?.getIntExtra(
                    "resultCode",
                    Activity.RESULT_CANCELED
                ) ?: Activity.RESULT_CANCELED

            val data =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra<Intent>("data")
                }

            if (resultCode != Activity.RESULT_OK || data == null) {
                sendStatus("ERROR: capture permission missing")
                return START_NOT_STICKY
            }

            val mgr =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            projection = mgr.getMediaProjection(resultCode, data)

            projection?.registerCallback(
                projectionCallback,
                handler
            )

            startCapture()

        } catch (e: Exception) {
            sendStatus(
                "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            )
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {

        try {
            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .defaultDisplay
                .getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->

                    val image =
                        try {
                            reader.acquireLatestImage()
                        } catch (e: Exception) {
                            null
                        }

                    if (image != null) {

                        frameCount++

                        // วิเคราะห์ทุก 30 frames
                        if (frameCount == 1L || frameCount % 30L == 0L) {

                            try {
                                val plane = image.planes[0]

                                val buffer = plane.buffer
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride

                                val rowPadding =
                                    rowStride - pixelStride * width

                                val bitmapWidth =
                                    width + rowPadding / pixelStride

                                val bitmap =
                                    Bitmap.createBitmap(
                                        bitmapWidth,
                                        height,
                                        Bitmap.Config.ARGB_8888
                                    )

                                bitmap.copyPixelsFromBuffer(buffer)

                                // Sample pixels เพื่อลดภาระ CPU
                                var totalBrightness = 0L
                                var samples = 0

                                val stepX = maxOf(1, width / 40)
                                val stepY = maxOf(1, height / 40)

                                var y = 0

                                while (y < height) {

                                    var x = 0

                                    while (x < width) {

                                        val color = bitmap.getPixel(x, y)

                                        val r = (color shr 16) and 0xff
                                        val g = (color shr 8) and 0xff
                                        val b = color and 0xff

                                        totalBrightness +=
                                            (r + g + b) / 3

                                        samples++

                                        x += stepX
                                    }

                                    y += stepY
                                }

                                val brightness =
                                    if (samples > 0)
                                        totalBrightness / samples
                                    else
                                        0

                                sendStatus(
                                    "P3A • F:$frameCount • Pixel:$brightness"
                                )

                                bitmap.recycle()

                            } catch (e: Exception) {

                                sendStatus(
                                    "P3A ERROR: ${e.javaClass.simpleName}"
                                )
                            }
                        }

                        image.close()
                    }
                },
                handler
            )

            virtualDisplay =
                projection?.createVirtualDisplay(
                    "NumberFinderCapture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    handler
                )

            sendStatus("Phase 3A ready")

        } catch (e: Exception) {
            sendStatus(
                "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            )
        }
    }

    private fun sendStatus(message: String) {
        val i = Intent("NUMBER_FINDER_STATUS")
        i.setPackage(packageName)
        i.putExtra("status", message)
        sendBroadcast(i)
    }

    private fun cleanupCapture() {

        imageReader?.setOnImageAvailableListener(null, null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null
    }

    override fun onDestroy() {

        cleanupCapture()

        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
