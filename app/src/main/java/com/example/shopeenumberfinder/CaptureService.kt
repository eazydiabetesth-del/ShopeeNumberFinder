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

        const val ACTION_ROUTE =
            "NUMBER_FINDER_ROUTE"

        const val ACTION_COMMAND =
            "NUMBER_FINDER_COMMAND"

        const val CMD_CAPTURE =
            "CAPTURE"

        const val CMD_ROUTE =
            "ROUTE"

        const val CMD_END =
            "END"
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

    /*
     * CAPTURE จะเปิด flag นี้
     * OCR จะทำงานเฉพาะตอน flag เป็น true
     */
    private var captureRequested = false
    private var ocrBusy = false

    /*
     * เก็บตำแหน่ง 1..25
     *
     * number -> screen coordinate
     */
    private val board =
        mutableMapOf<Int, Pair<Int, Int>>()

    /*
     * OCR หลาย frame แล้วรวมผล
     * ทำให้ไม่จำเป็นต้องอ่านครบ 25
     * ใน frame เดียว
     */
    private val captureAccumulator =
        mutableMapOf<Int, Pair<Int, Int>>()

    private var frameCount = 0L

    /*
     * OCR ระหว่าง CAPTURE เท่านั้น
     */
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
                    intent?.action !=
                    ACTION_COMMAND
                ) {
                    return
                }

                when (
                    intent.getStringExtra(
                        "command"
                    )
                ) {

                    CMD_CAPTURE -> {
                        startManualCapture()
                    }

                    CMD_ROUTE -> {

                        val group =
                            intent.getIntExtra(
                                "group",
                                1
                            )

                        showRouteGroup(group)
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
                    NotificationManager
                        .IMPORTANCE_LOW
                )
            )
        }

        val filter =
            IntentFilter(
                ACTION_COMMAND
            )

        if (
            Build.VERSION.SDK_INT >= 33
        ) {

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
                    "Phase 5A manual route"
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_menu_view
                )
                .build()

        startForeground(
            1001,
            notification
        )

        /*
         * Service อาจได้รับ command
         * หลังจาก projection เปิดอยู่แล้ว
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
                    intent
                        ?.getParcelableExtra<Intent>(
                            "data"
                        )
                }

            if (
                resultCode !=
                Activity.RESULT_OK ||
                data == null
            ) {

                sendStatus(
                    "P5A • ERROR permission"
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
                "P5A ERROR: " +
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

                                reader
                                    .acquireLatestImage()

                            } catch (_: Exception) {

                                null
                            }

                        if (image != null) {

                            frameCount++

                            /*
                             * สำคัญ:
                             *
                             * ถ้าไม่ได้กด CAPTURE
                             * เราไม่ OCR
                             */
                            if (
                                captureRequested &&
                                !ocrBusy &&
                                (
                                    frameCount == 1L ||
                                    frameCount %
                                        ocrEveryFrames ==
                                        0L
                                )
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
                                            Bitmap.Config
                                                .ARGB_8888
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

                                } catch (
                                    e: Exception
                                ) {

                                    sendStatus(
                                        "P5A OCR ERROR: " +
                                            e.javaClass
                                                .simpleName
                                    )
                                }
                            }

                            image.close()
                        }
                    },
                    handler
                )

            virtualDisplay =
                projection
                    ?.createVirtualDisplay(
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
                "P5A • กด CAPTURE เมื่อบอร์ดพร้อม"
            )

        } catch (e: Exception) {

            sendStatus(
                "P5A ERROR: " +
                    e.javaClass.simpleName
            )
        }
    }

    /*
     * =====================================================
     * MANUAL CAPTURE
     * =====================================================
     */

    private fun startManualCapture() {

        captureRequested = true

        board.clear()
        captureAccumulator.clear()

        sendEmptyRoute()

        sendStatus(
            "P5A • CAPTURING 0/25"
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

                /*
                 * รวมผลหลาย OCR frame
                 *
                 * ถ้า OCR frame แรกเห็น 22 ตัว
                 * frame ต่อมาเห็นอีก 3 ตัว
                 * ก็ครบได้
                 */
                for (
                    entry in detected
                ) {

                    val number =
                        entry.key

                    val position =
                        entry.value

                    if (
                        number in 1..25
                    ) {

                        captureAccumulator[
                            number
                        ] = position
                    }
                }

                val count =
                    captureAccumulator
                        .keys
                        .count {
                            it in 1..25
                        }

                sendStatus(
                    "P5A • CAPTURING $count/25"
                )

                /*
                 * LOCK เฉพาะเมื่อครบจริง 1..25
                 */
                val complete =
                    (1..25).all {
                        captureAccumulator
                            .containsKey(it)
                    }

                if (complete) {

                    board.clear()

                    for (
                        n in 1..25
                    ) {

                        board[n] =
                            captureAccumulator[n]!!
                    }

                    captureRequested =
                        false

                    sendStatus(
                        "P5A • READY ✓ • 25/25 • ROUTE 1–10"
                    )

                    /*
                     * Capture เสร็จ
                     * แสดง 1-10 ทันที
                     */
                    showRouteGroup(1)
                }
            }
            .addOnFailureListener { e ->

                sendStatus(
                    "P5A OCR ERROR: " +
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
     * ROUTE GROUP
     *
     * 1 = 1..10
     * 2 = 11..20
     * 3 = 21..30
     * 4 = 31..40
     * 5 = 41..50
     * =====================================================
     */

    private fun showRouteGroup(
        group: Int
    ) {

        if (board.size < 25) {

            sendStatus(
                "P5A • ยังไม่ได้ CAPTURE 25/25"
            )

            return
        }

        val safeGroup =
            group.coerceIn(
                1,
                5
            )

        val start =
            when (safeGroup) {

                1 -> 1
                2 -> 11
                3 -> 21
                4 -> 31
                else -> 41
            }

        val end =
            minOf(
                start + 9,
                50
            )

        val intent =
            Intent(
                ACTION_ROUTE
            )

        intent.setPackage(
            packageName
        )

        var count = 0

        for (
            number in start..end
        ) {

            /*
             * 26..50 ใช้ตำแหน่ง
             * 1..25 เดิม
             */
            val baseNumber =
                if (
                    number <= 25
                ) {
                    number
                } else {
                    number - 25
                }

            val position =
                board[baseNumber]
                    ?: continue

            /*
             * Marker อยู่ในปุ่มจริง
             *
             * ขยับขึ้นเล็กน้อย
             * แต่ยังอยู่ด้านในช่อง
             *
             * จุดนี้คือจุดที่ user
             * สามารถแตะตามได้เลย
             */
            val markerYOffset =
                (
                    screenHeight *
                        0.010f
                ).toInt()

            val x =
                position.first

            val y =
                position.second -
                    markerYOffset

            intent.putExtra(
                "x_$count",
                x
            )

            intent.putExtra(
                "y_$count",
                y
            )

            intent.putExtra(
                "number_$count",
                number
            )

            count++
        }

        intent.putExtra(
            "count",
            count
        )

        intent.putExtra(
            "group",
            safeGroup
        )

        sendBroadcast(intent)

        sendStatus(
            "P5A • ROUTE $start–$end"
        )
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

        val detected =
            mutableMapOf<
                Int,
                Pair<Int, Int>
            >()

        val occupiedCells =
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

                    /*
                     * ตำแหน่ง marker
                     * ใช้ CENTER ของ cell
                     * ไม่ใช้ center OCR glyph
                     *
                     * ทำให้ตำแหน่งแตะ
                     * อยู่กลางปุ่มอย่างสม่ำเสมอ
                     */
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
                        ).toInt()

                    if (
                        number !in detected &&
                        cell !in occupiedCells
                    ) {

                        detected[number] =
                            Pair(
                                x,
                                y
                            )

                        occupiedCells.add(
                            cell
                        )
                    }
                }
            }
        }

        return detected
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

        /*
         * ป้องกัน OCR จาก header
         * ถูกเอามาเป็นเลขบน board
         */
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

        board.clear()
        captureAccumulator.clear()

        sendEmptyRoute()

        sendStatus(
            "P5A • RESET ✓ • กด CAPTURE เกมใหม่"
        )
    }

    private fun sendEmptyRoute() {

        val intent =
            Intent(
                ACTION_ROUTE
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "count",
            0
        )

        intent.putExtra(
            "group",
            0
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
