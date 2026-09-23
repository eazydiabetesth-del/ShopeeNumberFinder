package com.example.shopeenumberfinder

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

class CaptureService : Service() {

    private val channel = "capture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())
    private var frameCount = 0L

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
                .setContentText("Phase 2 • Screen capture running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()

        startForeground(1001, notification)

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

        if (resultCode == Activity.RESULT_OK && data != null) {

            val mgr =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            projection = mgr.getMediaProjection(resultCode, data)

            startCapture()
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {

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

                val image = reader.acquireLatestImage()

                if (image != null) {

                    frameCount++

                    image.close()

                    if (frameCount % 30L == 0L) {

                        val i = Intent("NUMBER_FINDER_FRAME")
                        i.setPackage(packageName)
                        i.putExtra("frames", frameCount)

                        sendBroadcast(i)
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
    }

    override fun onDestroy() {

        imageReader?.setOnImageAvailableListener(null, null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
