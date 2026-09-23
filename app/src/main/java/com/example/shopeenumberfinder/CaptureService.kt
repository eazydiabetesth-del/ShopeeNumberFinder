package com.example.shopeenumberfinder

import android.app.*
import android.content.*
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class CaptureService : Service() {

    companion object {

        const val ACTION_STATUS =
            "NUMBER_FINDER_STATUS"

        const val ACTION_ROUTES_READY =
            "NUMBER_FINDER_ROUTES_READY"

        const val ACTION_CLEAR =
            "NUMBER_FINDER_CLEAR"

        const val ACTION_COMMAND =
            "NUMBER_FINDER_COMMAND"

        const val CMD_CAPTURE = "CAPTURE"
        const val CMD_END = "END"
    }

    private val channel = "capture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler =
        Handler(Looper.getMainLooper())

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private var screenWidth = 0
    private var screenHeight = 0

    private var captureRequested = false
    private var ocrBusy = false
    private var frameCount = 0L

    /*
     * OCR หลาย frame แล้วสะสมจนได้ 1..25 ครบ
     *
     * number -> (row, column)
     */
    private val captureAccumulator =
        mutableMapOf<Int, Pair<Int, Int>>()

    private val ocrEveryFrames = 4L

    private val columnCenters =
        floatArrayOf(
            0.12f,
            0.31f,
            0.50f,
            0.69f,
            0.88f
        )

    private val rowCenters =
        floatArrayOf(
            0.34f,
            0.46f,
            0.58f,
            0.70f,
            0.82f
        )

    private val commandReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action != ACTION_COMMAND
                ) {
                    return
                }

                when (
                    intent.getStringExtra("command")
                ) {

                    CMD_CAPTURE -> {
                        startManualCapture()
                    }

                    CMD_END -> {
                        resetSession()
                    }
                }
            }
        }

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                cleanupCapture()
            }
        }

    override fun onCreate() {
        super.onCreate()

        val nm =
            getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            nm.createNotificationChannel(
                NotificationChannel(
                    channel,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val filter =
            IntentFilter(
                ACTION_COMMAND
            )

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                commandReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                commandReceiver,
                filter
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val notification =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                Notification.Builder(
                    this,
                    channel
                )

            } else {

                Notification.Builder(this)
            }
                .setContentTitle(
                    "Number Finder"
                )
                .setContentText(
                    "Phase 5B precomputed routes"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .build()

        startForeground(
            1001,
            notification
        )

        /*
         * Projection เปิดอยู่แล้ว
         * ไม่ต้องเปิดซ้ำ
         */
        if (projection != null) {
            return START_NOT_STICKY
        }

        try {

            val resultCode =
                intent?.getIntExtra(
                    "resultCode",
                    Activity.RESULT_CANCELED
                ) ?: Activity.RESULT_CANCELED

            val data =
                if (
                    Build.VERSION.SDK_INT >= 33
                ) {

                    intent?.getParcelableExtra(
                        "data",
                        Intent::class.java
                    )

                } else {

                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra<Intent>(
                        "data"
                    )
                }

            if (
                resultCode != Activity.RESULT_OK ||
                data == null
            ) {

                sendStatus(
                    "P5B • ERROR permission"
                )

                return START_NOT_STICKY
            }

            val mgr =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            projection =
                mgr.getMediaProjection(
                    resultCode,
                    data
                )

            projection?.registerCallback(
                projectionCallback,
                handler
            )

            startCapture()

        } catch (e: Exception) {

            sendStatus(
                "P5B ERROR: " +
                    e.javaClass.simpleName
            )
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {

        try {

            val metrics =
                DisplayMetrics()

            @Suppress("DEPRECATION")
            (
                getSystemService(
                    WINDOW_SERVICE
                ) as WindowManager
            )
                .defaultDisplay
                .getRealMetrics(metrics)

            screenWidth =
                metrics.widthPixels

            screenHeight =
                metrics.heightPixels

            val density =
                metrics.densityDpi

            imageReader =
                ImageReader.newInstance(
                    screenWidth,
                    screenHeight,
                    PixelFormat.RGBA_8888,
                    2
                )

            imageReader
                ?.setOnImageAvailableListener(
                    { reader ->

                        val image =
                            try {
                                reader.acquireLatestImage()
                            } catch (_: Exception) {
                                null
                            }

                        if (image != null) {

                            frameCount++

                            if (
                                captureRequested &&
                                !ocrBusy &&
                                frameCount %
                                    ocrEveryFrames == 0L
                            ) {

                                try {

                                    val plane =
                                        image.planes[0]

                                    val buffer =
                                        plane.buffer

                                    val pixelStride =
                                        plane.pixelStride

                                    val rowStride =
                                        plane.rowStride

                                    val rowPadding =
                                        rowStride -
                                            pixelStride *
                                            screenWidth

                                    val bitmapWidth =
                                        screenWidth +
                                            rowPadding /
                                            pixelStride

                                    val fullBitmap =
                                        Bitmap.createBitmap(
                                            bitmapWidth,
                                            screenHeight,
                                            Bitmap.Config.ARGB_8888
                                        )

                                    fullBitmap
                                        .copyPixelsFromBuffer(
                                            buffer
                                        )

                                    val bitmap =
                                        Bitmap.createBitmap(
                                            fullBitmap,
                                            0,
                                            0,
                                            screenWidth,
                                            screenHeight
                                        )

                                    fullBitmap.recycle()

                                    runOcr(bitmap)

                                } catch (e: Exception) {

                                    sendStatus(
                                        "P5B OCR ERROR: " +
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
                    screenWidth,
                    screenHeight,
                    density,
                    DisplayManager
                        .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    handler
                )

            sendStatus(
                "P5B • กด CAPTURE"
            )

        } catch (e: Exception) {

            sendStatus(
                "P5B ERROR: " +
                    e.javaClass.simpleName
            )
        }
    }

    /*
     * =====================================================
     * CAPTURE
     * =====================================================
     */

    private fun startManualCapture() {

        captureRequested = true
        captureAccumulator.clear()

        sendClear()

        sendStatus(
            "P5B • CAPTURING 0/25"
        )
    }

    private fun runOcr(
        bitmap: Bitmap
    ) {

        if (!captureRequested) {

            bitmap.recycle()
            return
        }

        ocrBusy = true

        recognizer
            .process(
                InputImage.fromBitmap(
                    bitmap,
                    0
                )
            )
            .addOnSuccessListener { result ->

                val detected =
                    readBoard(result)

                for (
                    entry in detected
                ) {

                    if (
                        entry.key in 1..25
                    ) {

                        captureAccumulator[
                            entry.key
                        ] = entry.value
                    }
                }

                val count =
                    (1..25).count {
                        captureAccumulator
                            .containsKey(it)
                    }

                sendStatus(
                    "P5B • CAPTURING $count/25"
                )

                val complete =
                    (1..25).all {
                        captureAccumulator
                            .containsKey(it)
                    }

                if (complete) {

                    /*
                     * สำคัญ:
                     *
                     * หลังจากนี้หยุด OCR
                     */
                    captureRequested = false

                    /*
                     * คำนวณ route ทั้ง 5 ชุด
                     * เพียงครั้งเดียว
                     */
                    sendAllRoutes()

                    sendStatus(
                        "P5B • READY ✓ • 1–10"
                    )
                }
            }
            .addOnFailureListener { e ->

                sendStatus(
                    "P5B OCR ERROR: " +
                        e.javaClass.simpleName
                )
            }
            .addOnCompleteListener {

                bitmap.recycle()
                ocrBusy = false
            }
    }

    /*
     * =====================================================
     * PRECOMPUTE ALL 5 ROUTES
     * =====================================================
     */

    private fun sendAllRoutes() {

        val intent =
            Intent(
                ACTION_ROUTES_READY
            )

        intent.setPackage(
            packageName
        )

        /*
         * Marker ขยับขึ้นเล็กน้อย
         * แต่ยังอยู่ภายในปุ่ม
         */
        val markerYOffset =
            (
                screenHeight *
                    0.010f
            ).toInt()

        /*
         * group 0 = 1..10
         * group 1 = 11..20
         * ...
         * group 4 = 41..50
         */
        for (
            group in 0 until 5
        ) {

            val start =
                group * 10 + 1

            for (
                index in 0 until 10
            ) {

                val number =
                    start + index

                /*
                 * 26..50 ใช้ตำแหน่ง
                 * ของ 1..25 เดิม
                 */
                val baseNumber =
                    if (
                        number <= 25
                    ) {
                        number
                    } else {
                        number - 25
                    }

                val cell =
                    captureAccumulator[
                        baseNumber
                    ] ?: continue

                val x =
                    (
                        screenWidth *
                            columnCenters[
                                cell.second - 1
                            ]
                    ).toInt()

                val y =
                    (
                        screenHeight *
                            rowCenters[
                                cell.first - 1
                            ]
                    ).toInt() -
                        markerYOffset

                /*
                 * ส่งทั้ง 50 จุด
                 * ครั้งเดียว
                 */
                intent.putExtra(
                    "x_${group}_$index",
                    x
                )

                intent.putExtra(
                    "y_${group}_$index",
                    y
                )

                intent.putExtra(
                    "number_${group}_$index",
                    number
                )
            }
        }

        sendBroadcast(intent)
    }

    /*
     * =====================================================
     * OCR BOARD
     * =====================================================
     */

    private fun readBoard(
        result:
            com.google.mlkit.vision.text.Text
    ): Map<Int, Pair<Int, Int>> {

        val board =
            mutableMapOf<
                Int,
                Pair<Int, Int>
            >()

        val occupied =
            mutableSetOf<
                Pair<Int, Int>
            >()

        for (
            block in result.textBlocks
        ) {

            for (
                line in block.lines
            ) {

                for (
                    element in line.elements
                ) {

                    val raw =
                        element.text
                            .trim()
                            .replace(
                                Regex(
                                    "[^0-9]"
                                ),
                                ""
                            )

                    val number =
                        raw.toIntOrNull()
                            ?: continue

                    if (
                        number !in 1..25
                    ) {
                        continue
                    }

                    val box =
                        element.boundingBox
                            ?: continue

                    val cell =
                        nearestCell(
                            box.exactCenterX(),
                            box.exactCenterY()
                        ) ?: continue

                    if (
                        number !in board &&
                        cell !in occupied
                    ) {

                        board[number] =
                            cell

                        occupied.add(
                            cell
                        )
                    }
                }
            }
        }

        return board
    }

    private fun nearestCell(
        x: Float,
        y: Float
    ): Pair<Int, Int>? {

        var bestRow = -1
        var bestColumn = -1

        var bestDx =
            Float.MAX_VALUE

        var bestDy =
            Float.MAX_VALUE

        for (
            row in 0 until 5
        ) {

            val cy =
                screenHeight *
                    rowCenters[row]

            val dy =
                abs(y - cy)

            if (
                dy < bestDy
            ) {

                bestDy = dy
                bestRow = row
            }
        }

        for (
            column in 0 until 5
        ) {

            val cx =
                screenWidth *
                    columnCenters[column]

            val dx =
                abs(x - cx)

            if (
                dx < bestDx
            ) {

                bestDx = dx
                bestColumn = column
            }
        }

        if (
            bestRow < 0 ||
            bestColumn < 0
        ) {
            return null
        }

        if (
            bestDx >
                screenWidth * 0.065f ||
            bestDy >
                screenHeight * 0.045f
        ) {
            return null
        }

        return Pair(
            bestRow + 1,
            bestColumn + 1
        )
    }

    /*
     * =====================================================
     * RESET
     * =====================================================
     */

    private fun resetSession() {

        captureRequested = false
        captureAccumulator.clear()

        sendClear()

        sendStatus(
            "P5B • RESET ✓ • กด CAPTURE"
        )
    }

    private fun sendClear() {

        val intent =
            Intent(
                ACTION_CLEAR
            )

        intent.setPackage(
            packageName
        )

        sendBroadcast(intent)
    }

    private fun sendStatus(
        message: String
    ) {

        val intent =
            Intent(
                ACTION_STATUS
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "status",
            message
        )

        sendBroadcast(intent)
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

        runCatching {
            unregisterReceiver(
                commandReceiver
            )
        }

        cleanupCapture()

        projection
            ?.unregisterCallback(
                projectionCallback
            )

        projection?.stop()
        projection = null

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
