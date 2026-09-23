package com.example.shopeenumberfinder

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs

class CaptureService : Service() {

    private val channel = "capture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private var frameCount = 0L
    private var stableCount = 0
    private var lastBrightness = -1L

    private enum class GameState {
        WAITING,
        COUNTDOWN,
        BOARD_READY
    }

    private var gameState = GameState.WAITING

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                sendStatus("Projection stopped")
                cleanupCapture()
            }
        }

    override fun onCreate() {
        super.onCreate()

        val nm =
            getSystemService(NOTIFICATION_SERVICE)
                    as NotificationManager

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
                .setContentText("Phase 3B board detector")
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
                    intent?.getParcelableExtra(
                        "data",
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra<Intent>("data")
                }

            if (resultCode != Activity.RESULT_OK || data == null) {
                sendStatus("P3B • ERROR permission")
                return START_NOT_STICKY
            }

            val mgr =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            projection =
                mgr.getMediaProjection(resultCode, data)

            projection?.registerCallback(
                projectionCallback,
                handler
            )

            startCapture()

        } catch (e: Exception) {

            sendStatus(
                "P3B ERROR: ${e.javaClass.simpleName}"
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

            imageReader =
                ImageReader.newInstance(
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

                        // วิเคราะห์ประมาณทุก 15 frames
                        if (frameCount == 1L ||
                            frameCount % 15L == 0L
                        ) {

                            try {

                                val plane = image.planes[0]

                                val buffer = plane.buffer
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride

                                val rowPadding =
                                    rowStride -
                                        pixelStride * width

                                val bitmapWidth =
                                    width +
                                        rowPadding / pixelStride

                                val bitmap =
                                    Bitmap.createBitmap(
                                        bitmapWidth,
                                        height,
                                        Bitmap.Config.ARGB_8888
                                    )

                                bitmap.copyPixelsFromBuffer(buffer)

                                analyzeFrame(
                                    bitmap,
                                    width,
                                    height
                                )

                                bitmap.recycle()

                            } catch (e: Exception) {

                                sendStatus(
                                    "P3B ERROR: " +
                                        e.javaClass.simpleName
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
                    DisplayManager
                        .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    handler
                )

            sendStatus("P3B • WAITING")

        } catch (e: Exception) {

            sendStatus(
                "P3B ERROR: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun analyzeFrame(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {

        /*
         * วิเคราะห์เฉพาะบริเวณเกม
         *
         * จากภาพตัวอย่าง:
         * ส่วนตารางอยู่ประมาณ
         * Y = 28% ถึง 88% ของจอ
         *
         * เราไม่สนใจ status bar,
         * header Shopee และ navigation bar
         */

        val startX = (width * 0.04).toInt()
        val endX = (width * 0.96).toInt()

        val startY = (height * 0.28).toInt()
        val endY = (height * 0.88).toInt()

        var brightnessTotal = 0L
        var samples = 0

        var lightPixels = 0
        var darkPixels = 0

        val stepX =
            maxOf(1, (endX - startX) / 40)

        val stepY =
            maxOf(1, (endY - startY) / 50)

        var y = startY

        while (y < endY) {

            var x = startX

            while (x < endX) {

                val color =
                    bitmap.getPixel(x, y)

                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                val brightness =
                    (r + g + b) / 3

                brightnessTotal += brightness
                samples++

                if (brightness > 205)
                    lightPixels++

                if (brightness < 100)
                    darkPixels++

                x += stepX
            }

            y += stepY
        }

        if (samples == 0)
            return

        val brightness =
            brightnessTotal / samples

        val lightRatio =
            lightPixels.toFloat() / samples

        val darkRatio =
            darkPixels.toFloat() / samples

        /*
         * COUNTDOWN detection
         *
         * ตอน 3/2/1 หน้าจอจะถูก dark overlay
         * ทำให้ darkRatio สูงกว่าหน้าเกมปกติ
         */

        if (
            brightness < 150 ||
            darkRatio > 0.45f
        ) {

            gameState = GameState.COUNTDOWN
            stableCount = 0
            lastBrightness = brightness

            sendStatus(
                "P3B • COUNTDOWN • B:$brightness"
            )

            return
        }

        /*
         * ตรวจความนิ่งหลัง countdown
         */

        if (lastBrightness >= 0) {

            val difference =
                abs(brightness - lastBrightness)

            if (difference < 12)
                stableCount++
            else
                stableCount = 0
        }

        lastBrightness = brightness

        /*
         * หน้า board มี background ขาว/เทา
         * จำนวน light pixel จะค่อนข้างสูง
         */

        val looksLikeBoard =
            lightRatio > 0.50f &&
            brightness > 175

        if (
            looksLikeBoard &&
            stableCount >= 2
        ) {

            gameState = GameState.BOARD_READY

            sendStatus(
                "P3B • BOARD READY ✓"
            )

        } else {

            gameState = GameState.WAITING

            sendStatus(
                "P3B • WAITING • " +
                    "B:$brightness • " +
                    "L:${(lightRatio * 100).toInt()}%"
            )
        }
    }

    private fun sendStatus(message: String) {

        val i =
            Intent("NUMBER_FINDER_STATUS")

        i.setPackage(packageName)
        i.putExtra("status", message)

        sendBroadcast(i)
    }

    private fun cleanupCapture() {

        imageReader
            ?.setOnImageAvailableListener(
                null,
                null
            )

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null
    }

    override fun onDestroy() {

        cleanupCapture()

        projection
            ?.unregisterCallback(
                projectionCallback
            )

        projection?.stop()
        projection = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
