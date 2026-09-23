package com.example.shopeenumberfinder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class CaptureService : Service() {

    private val channel = "capture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private var frameCount = 0L
    private var consecutiveGridFrames = 0

    private var ocrBusy = false
    private var boardLocked = false

    /*
     * number -> row,column
     */
    private val savedBoard =
        mutableMapOf<Int, Pair<Int, Int>>()

    /*
     * เป้าหมายปัจจุบัน 1..25
     */
    private var currentTarget = 1

    /*
     * Fast detector
     *
     * เก็บ signature ของช่องเป้าหมาย
     * แล้วดูว่าภาพในช่องเปลี่ยนมากพอหรือยัง
     */
    private var targetSignature: Long? = null
    private var targetStableFrames = 0
    private var changeFrames = 0

    /*
     * หลังเลื่อนไป target ใหม่
     * เว้น frame สั้น ๆ เพื่อสร้าง baseline ใหม่
     */
    private var baselineDelayFrames = 0

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

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
                .setContentTitle("Number Finder")
                .setContentText(
                    "Phase 4D five-step preview"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .build()

        startForeground(
            1001,
            notification
        )

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
                    intent?.getParcelableExtra<Intent>(
                        "data"
                    )
                }

            if (
                resultCode != Activity.RESULT_OK ||
                data == null
            ) {
                sendStatus(
                    "P4D • ERROR permission"
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
                "P4D ERROR: " +
                    e.javaClass.simpleName
            )
        }

        return START_NOT_STICKY
    }

    private fun startCapture() {

        try {

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            (
                getSystemService(
                    WINDOW_SERVICE
                ) as WindowManager
            )
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
                                        pixelStride * width

                                val bitmapWidth =
                                    width +
                                        rowPadding /
                                        pixelStride

                                val bitmap =
                                    Bitmap.createBitmap(
                                        bitmapWidth,
                                        height,
                                        Bitmap.Config.ARGB_8888
                                    )

                                bitmap.copyPixelsFromBuffer(
                                    buffer
                                )

                                /*
                                 * ก่อนล็อก board:
                                 * ใช้ detector + OCR
                                 *
                                 * หลังล็อก:
                                 * ทุก frame ใช้ fast detector
                                 */
                                if (!boardLocked) {

                                    if (
                                        frameCount == 1L ||
                                        frameCount % 6L == 0L
                                    ) {

                                        val ready =
                                            analyzeGrid(
                                                bitmap,
                                                width,
                                                height
                                            )

                                        if (
                                            ready &&
                                            !ocrBusy
                                        ) {

                                            val copy =
                                                Bitmap.createBitmap(
                                                    bitmap,
                                                    0,
                                                    0,
                                                    width,
                                                    height
                                                )

                                            readInitialBoard(
                                                copy,
                                                width,
                                                height
                                            )
                                        }
                                    }

                                } else {

                                    analyzeCurrentTargetFast(
                                        bitmap,
                                        width,
                                        height
                                    )
                                }

                                bitmap.recycle()

                            } catch (e: Exception) {

                                sendStatus(
                                    "P4D ERROR: " +
                                        e.javaClass.simpleName
                                )
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

            sendStatus(
                "P4D • SEARCHING"
            )

        } catch (e: Exception) {

            sendStatus(
                "P4D ERROR: " +
                    e.javaClass.simpleName
            )
        }
    }

    private fun readInitialBoard(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {

        ocrBusy = true

        recognizer
            .process(
                InputImage.fromBitmap(
                    bitmap,
                    0
                )
            )
            .addOnSuccessListener { result ->

                val board =
                    readBoard(
                        result,
                        width,
                        height
                    )

                val complete =
                    board.size == 25 &&
                        (1..25).all {
                            board.containsKey(it)
                        }

                if (complete) {

                    savedBoard.clear()
                    savedBoard.putAll(board)

                    currentTarget = 1
                    boardLocked = true

                    targetSignature = null
                    targetStableFrames = 0
                    changeFrames = 0
                    baselineDelayFrames = 2

                    sendPreview(
                        width,
                        height
                    )

                } else {

                    sendStatus(
                        "P4D • BOARD " +
                            "${board.size}/25"
                    )
                }
            }
            .addOnFailureListener { e ->

                sendStatus(
                    "P4D OCR ERROR: " +
                        e.javaClass.simpleName
                )
            }
            .addOnCompleteListener {

                bitmap.recycle()
                ocrBusy = false
            }
    }

    /*
     * FAST PATH
     *
     * ไม่มี ML Kit ตรงนี้
     *
     * ดู pixel pattern รอบตัวเลขของ
     * currentTarget ทุก captured frame
     */
    private fun analyzeCurrentTargetFast(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {

        if (currentTarget !in 1..25) {
            return
        }

        val cell =
            savedBoard[currentTarget]
                ?: return

        val cx =
            (
                width *
                    columnCenters[
                        cell.second - 1
                    ]
            ).toInt()

        val cy =
            (
                height *
                    rowCenters[
                        cell.first - 1
                    ]
            ).toInt()

        val signature =
            cellSignature(
                bitmap,
                cx,
                cy,
                width,
                height
            )

        /*
         * หลังเปลี่ยน target
         * รอเล็กน้อยก่อนสร้าง baseline
         */
        if (baselineDelayFrames > 0) {

            baselineDelayFrames--

            if (baselineDelayFrames == 0) {
                targetSignature = signature
                targetStableFrames = 1
            }

            return
        }

        val baseline =
            targetSignature

        if (baseline == null) {

            targetSignature = signature
            targetStableFrames = 1

            return
        }

        val difference =
            signatureDifference(
                baseline,
                signature
            )

        /*
         * ถ้าภาพยังเหมือนเดิม
         * ค่อย ๆ update baseline
         */
        if (difference < 18) {

            changeFrames = 0

            if (targetStableFrames < 8) {
                targetStableFrames++
            }

            if (targetStableFrames <= 4) {
                targetSignature = signature
            }

            return
        }

        /*
         * ต้องเห็นการเปลี่ยน 2 frame
         * ติดต่อกัน ป้องกัน animation/noise
         */
        changeFrames++

        if (
            changeFrames >= 2 &&
            targetStableFrames >= 1
        ) {

            advanceTarget(
                width,
                height
            )
        }
    }

    private fun advanceTarget(
        width: Int,
        height: Int
    ) {

        currentTarget++

        targetSignature = null
        targetStableFrames = 0
        changeFrames = 0

        baselineDelayFrames = 2

        if (currentTarget <= 25) {

            sendPreview(
                width,
                height
            )

        } else {

            sendClearPreview(
                "P4D • 1-25 COMPLETE ✓"
            )
        }
    }

    /*
     * สร้าง signature 5x5 จุด
     * รอบบริเวณตัวเลข
     *
     * pack brightness เป็น 4-bit ต่อ sample
     * ใช้ 16 samplesหลักสำหรับ Long
     */
    private fun cellSignature(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int
    ): Long {

        val stepX =
            maxOf(
                5,
                (width * 0.012f).toInt()
            )

        val stepY =
            maxOf(
                5,
                (height * 0.008f).toInt()
            )

        val offsets =
            arrayOf(
                intArrayOf(-2, -2),
                intArrayOf(-1, -2),
                intArrayOf(0, -2),
                intArrayOf(1, -2),

                intArrayOf(-2, -1),
                intArrayOf(-1, -1),
                intArrayOf(0, -1),
                intArrayOf(1, -1),

                intArrayOf(-2, 0),
                intArrayOf(-1, 0),
                intArrayOf(0, 0),
                intArrayOf(1, 0),

                intArrayOf(-2, 1),
                intArrayOf(-1, 1),
                intArrayOf(0, 1),
                intArrayOf(1, 1)
            )

        var signature = 0L

        for (
            index in offsets.indices
        ) {

            val offset =
                offsets[index]

            val x =
                (
                    centerX +
                        offset[0] *
                        stepX
                ).coerceIn(
                    0,
                    bitmap.width - 1
                )

            val y =
                (
                    centerY +
                        offset[1] *
                        stepY
                ).coerceIn(
                    0,
                    bitmap.height - 1
                )

            val color =
                bitmap.getPixel(
                    x,
                    y
                )

            val brightness =
                (
                    Color.red(color) +
                        Color.green(color) +
                        Color.blue(color)
                    ) / 3

            /*
             * 0..255 -> 0..15
             */
            val level =
                (brightness / 16)
                    .coerceIn(
                        0,
                        15
                    )

            signature =
                signature or
                    (
                        level.toLong() shl
                            (index * 4)
                    )
        }

        return signature
    }

    private fun signatureDifference(
        a: Long,
        b: Long
    ): Int {

        var difference = 0

        for (i in 0 until 16) {

            val shift =
                i * 4

            val va =
                (
                    (a shr shift) and 0xF
                ).toInt()

            val vb =
                (
                    (b shr shift) and 0xF
                ).toInt()

            difference +=
                abs(va - vb)
        }

        return difference
    }

    /*
     * ส่งตำแหน่งล่วงหน้าสูงสุด 5 ตัว
     */
    private fun sendPreview(
        width: Int,
        height: Int
    ) {

        val intent =
            Intent(
                "NUMBER_FINDER_STATUS"
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "status",
            "P4D • NEXT $currentTarget"
        )

        var slot = 0

        for (
            number in
            currentTarget..
                minOf(
                    25,
                    currentTarget + 4
                )
        ) {

            val cell =
                savedBoard[number]
                    ?: continue

            val x =
                (
                    width *
                        columnCenters[
                            cell.second - 1
                        ]
                ).toInt()

            val y =
                (
                    height *
                        rowCenters[
                            cell.first - 1
                        ]
                ).toInt()

            intent.putExtra(
                "preview_${slot}_x",
                x
            )

            intent.putExtra(
                "preview_${slot}_y",
                y
            )

            /*
             * แสดงเลขจริงที่ต้องกด
             */
            intent.putExtra(
                "preview_${slot}_number",
                number
            )

            slot++
        }

        intent.putExtra(
            "preview_count",
            slot
        )

        sendBroadcast(intent)
    }

    private fun sendClearPreview(
        message: String
    ) {

        val intent =
            Intent(
                "NUMBER_FINDER_STATUS"
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "status",
            message
        )

        intent.putExtra(
            "preview_count",
            0
        )

        sendBroadcast(intent)
    }

    private fun analyzeGrid(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Boolean {

        var validCells = 0

        for (row in 0 until 5) {

            for (column in 0 until 5) {

                val centerX =
                    (
                        width *
                            columnCenters[column]
                    ).toInt()

                val centerY =
                    (
                        height *
                            rowCenters[row]
                    ).toInt()

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

        if (validCells >= 23) {
            consecutiveGridFrames++
        } else {
            consecutiveGridFrames = 0
        }

        if (
            consecutiveGridFrames < 3
        ) {

            sendStatus(
                "P4D • SEARCHING • " +
                    "$validCells/25"
            )

            return false
        }

        return true
    }

    private fun readBoard(
        result:
            com.google.mlkit.vision.text.Text,
        width: Int,
        height: Int
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

            for (line in block.lines) {

                for (
                    element in line.elements
                ) {

                    val raw =
                        element.text
                            .trim()
                            .replace(
                                Regex("[^0-9]"),
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
                            box.exactCenterY(),
                            width,
                            height
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
        y: Float,
        width: Int,
        height: Int
    ): Pair<Int, Int>? {

        var bestRow = -1
        var bestColumn = -1

        var bestDx =
            Float.MAX_VALUE

        var bestDy =
            Float.MAX_VALUE

        for (row in 0 until 5) {

            val cy =
                height *
                    rowCenters[row]

            val dy =
                abs(y - cy)

            if (dy < bestDy) {

                bestDy = dy
                bestRow = row
            }
        }

        for (column in 0 until 5) {

            val cx =
                width *
                    columnCenters[column]

            val dx =
                abs(x - cx)

            if (dx < bestDx) {

                bestDx = dx
                bestColumn = column
            }
        }

        if (
            bestRow == -1 ||
            bestColumn == -1
        ) {
            return null
        }

        if (
            bestDx >
            width * 0.065f ||
            bestDy >
            height * 0.045f
        ) {
            return null
        }

        return Pair(
            bestRow + 1,
            bestColumn + 1
        )
    }

    private fun looksLikeCell(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int
    ): Boolean {

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

        for (offset in offsets) {

            val x =
                (
                    centerX +
                        offset[0]
                ).coerceIn(
                    0,
                    bitmap.width - 1
                )

            val y =
                (
                    centerY +
                        offset[1]
                ).coerceIn(
                    0,
                    bitmap.height - 1
                )

            val color =
                bitmap.getPixel(
                    x,
                    y
                )

            val r =
                Color.red(color)

            val g =
                Color.green(color)

            val b =
                Color.blue(color)

            val brightness =
                (r + g + b) / 3

            if (
                brightness >= 185
            ) {
                lightSamples++
            }

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
        }

        return (
            lightSamples >= 4 &&
            neutralSamples >= 4
        )
    }

    private fun sendStatus(
        message: String
    ) {

        val intent =
            Intent(
                "NUMBER_FINDER_STATUS"
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
