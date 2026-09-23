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

    /*
     * number -> (row,column)
     *
     * map นี้จะมีทั้ง 1-25 และเมื่อเกมเปลี่ยน
     * ก็จะค่อย ๆ กลายเป็น 26-50
     */
    private val currentBoard =
        mutableMapOf<Int, Pair<Int, Int>>()

    /*
     * OCR ล่าสุดที่ยืนยันแล้วว่า next คืออะไร
     */
    private var currentTarget = 1

    /*
     * ใช้ป้องกัน route กระพริบจาก OCR frame เดียว
     */
    private var candidateTarget = -1
    private var candidateCount = 0

    /*
     * OCR ทุกประมาณ 6 captured frames
     * ไม่ต้องรอ pixel detector แบบ 4D
     */
    private val ocrEveryFrames = 6L

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
                    "Phase 4E rolling route"
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
                    "P4E • ERROR permission"
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
                "P4E ERROR: " +
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

                            /*
                             * OCR ไม่ต้องทำทุก frame
                             */
                            if (
                                frameCount == 1L ||
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

                                    val gridReady =
                                        analyzeGrid(
                                            bitmap,
                                            width,
                                            height
                                        )

                                    if (
                                        gridReady &&
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

                                        analyzeBoardOcr(
                                            copy,
                                            width,
                                            height
                                        )
                                    }

                                    bitmap.recycle()

                                } catch (e: Exception) {

                                    sendStatus(
                                        "P4E ERROR: " +
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

            sendStatus(
                "P4E • SEARCHING"
            )

        } catch (e: Exception) {

            sendStatus(
                "P4E ERROR: " +
                    e.javaClass.simpleName
            )
        }
    }

    private fun analyzeBoardOcr(
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

                val detected =
                    readBoard(
                        result,
                        width,
                        height
                    )

                /*
                 * ต้องเห็นจำนวนช่องมากพอ
                 * ถึงจะยอมใช้ frame นี้
                 */
                if (detected.size >= 20) {

                    /*
                     * update map ด้วยเลขที่ OCR เห็น
                     *
                     * ก่อนใส่เลขใหม่ใน cell เดียวกัน
                     * ลบเลขเก่าที่เคยอยู่ cell นั้นก่อน
                     */
                    for (
                        entry in detected
                    ) {

                        val number =
                            entry.key

                        val cell =
                            entry.value

                        val oldNumbers =
                            currentBoard
                                .filterValues {
                                    it == cell
                                }
                                .keys
                                .toList()

                        for (
                            old in oldNumbers
                        ) {
                            currentBoard.remove(old)
                        }

                        currentBoard[number] =
                            cell
                    }

                    /*
                     * หาเลขที่เกมกำลังต้องการ
                     *
                     * รอบแรก:
                     * ถ้า 1 หาย แต่ 2 ยังอยู่ -> next 2
                     *
                     * ทำแบบเดียวกันไปจน 25
                     *
                     * หลัง 25 จะเริ่มหา 26..50
                     */
                    val inferred =
                        inferTarget(
                            detected
                        )

                    if (
                        inferred != null
                    ) {

                        confirmTarget(
                            inferred,
                            width,
                            height
                        )
                    }

                    /*
                     * ถ้ายังอยู่ target เดิม
                     * แต่ map มีข้อมูลใหม่
                     * ก็ redraw ได้
                     */
                    sendRoute(
                        width,
                        height
                    )
                }
            }
            .addOnFailureListener { e ->

                sendStatus(
                    "P4E OCR ERROR: " +
                        e.javaClass.simpleName
                )
            }
            .addOnCompleteListener {

                bitmap.recycle()
                ocrBusy = false
            }
    }

    private fun inferTarget(
        detected:
            Map<Int, Pair<Int, Int>>
    ): Int? {

        /*
         * เกมเปลี่ยน N -> N+25
         *
         * ตัวอย่าง:
         * 1 ถูกกดแล้ว จะเห็น 26
         * แต่ไม่เห็น 1
         *
         * ดังนั้นหาเลขต่ำสุดใน 1..25
         * ที่ยังปรากฏอยู่
         */

        for (
            n in currentTarget..25
        ) {

            if (
                detected.containsKey(n)
            ) {
                return n
            }
        }

        /*
         * เมื่อ 1..25 หมดแล้ว
         * เริ่มรอบ 26..50
         */
        if (currentTarget >= 25) {

            for (n in 26..50) {

                if (
                    detected.containsKey(n)
                ) {
                    return n
                }
            }
        }

        return null
    }

    private fun confirmTarget(
        target: Int,
        width: Int,
        height: Int
    ) {

        /*
         * ห้ามถอยหลัง
         */
        if (
            target < currentTarget
        ) {
            return
        }

        if (
            target ==
            currentTarget
        ) {

            candidateTarget = -1
            candidateCount = 0

            return
        }

        if (
            candidateTarget ==
            target
        ) {

            candidateCount++

        } else {

            candidateTarget =
                target

            candidateCount = 1
        }

        /*
         * ต้องเห็นตรงกัน 2 OCR
         * ติดต่อกันก่อนเปลี่ยน route
         */
        if (
            candidateCount >= 2
        ) {

            currentTarget =
                target

            candidateTarget = -1
            candidateCount = 0

            sendRoute(
                width,
                height
            )
        }
    }

    /*
     * ส่ง route สูงสุด 5 จุด
     */
    private fun sendRoute(
        width: Int,
        height: Int
    ) {

        val intent =
            Intent(
                "NUMBER_FINDER_ROUTE"
            )

        intent.setPackage(
            packageName
        )

        var count = 0

        /*
         * จุดที่ต้องการคือ
         * currentTarget ถึง +4
         */
        for (
            number in
            currentTarget..
                minOf(
                    50,
                    currentTarget + 4
                )
        ) {

            val cell =
                currentBoard[number]
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
            "target",
            currentTarget
        )

        sendBroadcast(intent)

        sendStatus(
            "P4E • NEXT $currentTarget • ROUTE $count"
        )
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

        return (
            consecutiveGridFrames >= 2
        )
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
                        number !in 1..50
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

        for (
            row in 0 until 5
        ) {

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

        for (
            column in 0 until 5
        ) {

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
                (
                    width *
                        0.025f
                    ).toInt()
            )

        val dy =
            maxOf(
                6,
                (
                    height *
                        0.012f
                    ).toInt()
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

        var light = 0
        var neutral = 0

        for (
            offset in offsets
        ) {

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
                light++
            }

            if (
                maxOf(r, g, b) -
                    minOf(r, g, b)
                <= 35
            ) {
                neutral++
            }
        }

        return (
            light >= 4 &&
            neutral >= 4
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
