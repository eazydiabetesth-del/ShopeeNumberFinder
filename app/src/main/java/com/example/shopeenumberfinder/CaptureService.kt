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

class CaptureService : Service() {

    private val channel = "capture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private var frameCount = 0L
    private var consecutiveGridFrames = 0

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
                .setContentText("Phase 3C 5x5 grid detector")
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
                sendStatus("P3C • ERROR permission")
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
                "P3C ERROR: ${e.javaClass.simpleName}"
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

                        /*
                         * วิเคราะห์ทุก 10 frames
                         * เร็วพอสำหรับตรวจหน้าเกม
                         * แต่ไม่กิน CPU โดยไม่จำเป็น
                         */
                        if (
                            frameCount == 1L ||
                            frameCount % 10L == 0L
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

                                analyzeGrid(
                                    bitmap,
                                    width,
                                    height
                                )

                                bitmap.recycle()

                            } catch (e: Exception) {

                                sendStatus(
                                    "P3C ERROR: " +
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

            sendStatus("P3C • SEARCHING")

        } catch (e: Exception) {

            sendStatus(
                "P3C ERROR: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun analyzeGrid(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {

        /*
         * Geometry จากหน้าเกมที่ทดสอบ
         *
         * ศูนย์กลาง column:
         * ~12%, 31%, 50%, 69%, 88%
         *
         * ศูนย์กลาง row:
         * ~34%, 46%, 58%, 70%, 82%
         *
         * ใช้ normalized coordinates
         * จึงไม่ผูกกับ pixel resolution ตรง ๆ
         */

        val columnCenters =
            floatArrayOf(
                0.12f,
                0.31f,
                0.50f,
                0.69f,
                0.88f
            )

        val rowCenters =
            floatArrayOf(
                0.34f,
                0.46f,
                0.58f,
                0.70f,
                0.82f
            )

        var validCells = 0

        for (row in 0 until 5) {

            for (column in 0 until 5) {

                val centerX =
                    (width *
                        columnCenters[column])
                        .toInt()

                val centerY =
                    (height *
                        rowCenters[row])
                        .toInt()

                if (
                    looksLikeCell(
                        bitmap,
                        centerX,
                        centerY,
                        width,
                        height
                    )
                ) {
                    validCells++
                }
            }
        }

        /*
         * ต้องพบอย่างน้อย 23 จาก 25 ช่อง
         *
         * ยอมให้คลาดเคลื่อนเล็กน้อยจาก
         * animation / highlight / overlay
         */

        if (validCells >= 23) {

            consecutiveGridFrames++

        } else {

            consecutiveGridFrames = 0
        }

        /*
         * ต้องผ่าน 3 analysis ติดต่อกัน
         * ป้องกัน transition frame
         * และช่วง countdown 3-2-1
         */

        if (consecutiveGridFrames >= 3) {

            sendStatus(
                "P3C • GRID READY ✓ • $validCells/25"
            )

        } else {

            sendStatus(
                "P3C • SEARCHING • $validCells/25"
            )
        }
    }

    private fun looksLikeCell(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int
    ): Boolean {

        /*
         * อย่า sample ตรงกลางเพียงจุดเดียว
         * เพราะตรงนั้นมีตัวเลขสีเข้ม
         *
         * sample รอบ ๆ ตัวเลขแทน
         */

        val dx =
            maxOf(
                6,
                (width * 0.025f).toInt()
            )

        val dy =
            maxOf(
                6,
                (height * 0.012f).toInt()
            )

        val offsets =
            arrayOf(
                intArrayOf(-dx, -dy),
                intArrayOf(dx, -dy),
                intArrayOf(-dx, dy),
                intArrayOf(dx, dy),
                intArrayOf(0, -dy * 2),
                intArrayOf(0, dy * 2)
            )

        var lightSamples = 0
        var neutralSamples = 0
        var totalSamples = 0

        for (offset in offsets) {

            val x =
                (centerX + offset[0])
                    .coerceIn(
                        0,
                        bitmap.width - 1
                    )

            val y =
                (centerY + offset[1])
                    .coerceIn(
                        0,
                        bitmap.height - 1
                    )

            val color =
                bitmap.getPixel(x, y)

            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            val brightness =
                (r + g + b) / 3

            /*
             * ช่องเกมเป็นขาว/เทาอ่อน
             */

            if (brightness >= 185) {
                lightSamples++
            }

            /*
             * สีพื้นช่องควรค่อนข้าง neutral
             * R/G/B ไม่ต่างกันมาก
             *
             * ช่วยแยกจาก background
             * หรือ element สีต่าง ๆ
             */

            val maxChannel =
                maxOf(r, g, b)

            val minChannel =
                minOf(r, g, b)

            if (
                maxChannel -
                minChannel <= 35
            ) {
                neutralSamples++
            }

            totalSamples++
        }

        /*
         * อย่างน้อย 4/6 จุดต้องสว่าง
         * และอย่างน้อย 4/6 จุดต้องเป็น
         * สีขาว/เทาค่อนข้าง neutral
         */

        return (
            lightSamples >= 4 &&
            neutralSamples >= 4 &&
            totalSamples == offsets.size
        )
    }

    private fun sendStatus(
        message: String
    ) {

        val i =
            Intent(
                "NUMBER_FINDER_STATUS"
            )

        i.setPackage(packageName)
        i.putExtra(
            "status",
            message
        )

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
