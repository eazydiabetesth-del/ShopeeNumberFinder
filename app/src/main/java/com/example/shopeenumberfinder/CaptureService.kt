package com.example.shopeenumberfinder

import android.app.*
import android.content.Intent
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
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

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
                .setContentText("Phase 2C diagnostic")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

        startForeground(1001, notification)

        sendStatus("Capture service started")

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
                sendStatus("ERROR: capture permission missing")
                return START_NOT_STICKY
            }

            sendStatus("Permission OK")

            val mgr =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            projection = mgr.getMediaProjection(resultCode, data)

            sendStatus("Projection OK")

            projection?.registerCallback(
                projectionCallback,
                handler
            )

            sendStatus("Callback OK")

            startCapture()

        } catch (e: Exception) {

            sendStatus(
                "ERROR: ${e.javaClass.simpleName}: " +
                    (e.message ?: "unknown")
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

            sendStatus("Display ${width}x${height}")

            imageReader =
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
                )

            sendStatus("ImageReader OK")

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
                        image.close()

                        if (
                            frameCount == 1L ||
                            frameCount % 30L == 0L
                        ) {
                            sendStatus("Frames: $frameCount")
                        }
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

            if (virtualDisplay != null) {
                sendStatus("VirtualDisplay OK")
            } else {
                sendStatus("ERROR: VirtualDisplay null")
            }

        } catch (e: Exception) {

            sendStatus(
                "ERROR: ${e.javaClass.simpleName}: " +
                    (e.message ?: "unknown")
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
