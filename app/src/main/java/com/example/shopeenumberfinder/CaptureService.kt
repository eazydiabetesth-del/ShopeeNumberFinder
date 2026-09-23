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

    private val handler =
        Handler(Looper.getMainLooper())

    private var frameCount = 0L
    private var consecutiveGridFrames = 0

    private var ocrBusy = false
    private var lastOcrTime = 0L

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

                sendStatus(
                    "Projection stopped"
                )

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

                Notification.Builder(
                    this
                )
            }
                .setContentTitle(
                    "Number Finder"
                )
                .setContentText(
                    "Phase 4A highlight"
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
                )
                    ?: Activity.RESULT_CANCELED

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
                    "P4A • ERROR permission"
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
                "P4A ERROR: " +
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
                .getRealMetrics(
                    metrics
                )

            val width =
                metrics.widthPixels

            val height =
                metrics.heightPixels

            val density =
                metrics.densityDpi

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

                            } catch (
                                _: Exception
                            ) {

                                null
                            }

                        if (image != null) {

                            frameCount++

                            if (
                                frameCount == 1L ||
                                frameCount % 10L == 0L
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
                                            width

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

                                    bitmap
                                        .copyPixelsFromBuffer(
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
                                        !ocrBusy &&
                                        SystemClock
                                            .elapsedRealtime() -
                                            lastOcrTime >=
                                        700
                                    ) {

                                        lastOcrTime =
                                            SystemClock
                                                .elapsedRealtime()

                                        val copy =
                                            Bitmap.createBitmap(
                                                bitmap,
                                                0,
                                                0,
                                                width,
                                                height
                                            )

                                        runOcr(
                                            copy,
                                            width,
                                            height
                                        )
                                    }

                                    bitmap.recycle()

                                } catch (
                                    e: Exception
                                ) {

                                    sendStatus(
                                        "P4A ERROR: " +
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
                "P4A • SEARCHING"
            )

        } catch (
            e: Exception
        ) {

            sendStatus(
                "P4A ERROR: " +
                    e.javaClass.simpleName
            )
        }
    }

    private fun analyzeGrid(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Boolean {

        var validCells = 0

        for (
            row in 0 until 5
        ) {

            for (
                column in 0 until 5
            ) {

                val centerX =
                    (
                        width *
                            columnCenters[
                                column
                            ]
                    ).toInt()

                val centerY =
                    (
                        height *
                            rowCenters[
                                row
                            ]
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

        if (
            validCells >= 23
        ) {

            consecutiveGridFrames++

        } else {

            consecutiveGridFrames = 0
        }

        if (
            consecutiveGridFrames < 3
        ) {

            sendStatus(
                "P4A • SEARCHING • " +
                    "$validCells/25"
            )

            return false
        }

        return true
    }

    private fun runOcr(
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {

        ocrBusy = true

        val input =
            InputImage.fromBitmap(
                bitmap,
                0
            )

        recognizer
            .process(input)
            .addOnSuccessListener {
                result ->

                val board =
                    mutableMapOf<
                        Int,
                        Pair<Int, Int>
                    >()

                val occupiedCells =
                    mutableSetOf<
                        Pair<Int, Int>
                    >()

                for (
                    block in
                    result.textBlocks
                ) {

                    for (
                        line in
                        block.lines
                    ) {

                        for (
                            element in
                            line.elements
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
                                number !in
                                1..25
                            ) {

                                continue
                            }

                            val box =
                                element.boundingBox
                                    ?: continue

                            val x =
                                box.exactCenterX()

                            val y =
                                box.exactCenterY()

                            var bestRow = -1
                            var bestColumn = -1

                            var bestDx =
                                Float.MAX_VALUE

                            var bestDy =
                                Float.MAX_VALUE

                            for (
                                row in
                                0 until 5
                            ) {

                                val cy =
                                    height *
                                        rowCenters[
                                            row
                                        ]

                                val dy =
                                    abs(
                                        y - cy
                                    )

                                if (
                                    dy < bestDy
                                ) {

                                    bestDy = dy
                                    bestRow = row
                                }
                            }

                            for (
                                column in
                                0 until 5
                            ) {

                                val cx =
                                    width *
                                        columnCenters[
                                            column
                                        ]

                                val dx =
                                    abs(
                                        x - cx
                                    )

                                if (
                                    dx < bestDx
                                ) {

                                    bestDx = dx
                                    bestColumn =
                                        column
                                }
                            }

                            if (
                                bestRow == -1 ||
                                bestColumn == -1
                            ) {

                                continue
                            }

                            val maxDx =
                                width *
                                    0.065f

                            val maxDy =
                                height *
                                    0.045f

                            if (
                                bestDx > maxDx ||
                                bestDy > maxDy
                            ) {

                                continue
                            }

                            val cell =
                                Pair(
                                    bestRow + 1,
                                    bestColumn + 1
                                )

                            if (
                                number !in board &&
                                cell !in
                                occupiedCells
                            ) {

                                board[
                                    number
                                ] = cell

                                occupiedCells
                                    .add(
                                        cell
                                    )
                            }
                        }
                    }
                }

                val complete =
                    board.size == 25 &&
                        (
                            1..25
                        ).all {

                            board
                                .containsKey(
                                    it
                                )
                        } &&
                        occupiedCells
                            .size == 25

                if (complete) {

                    val one =
                        board[1]!!

                    val oneX =
                        (
                            width *
                                columnCenters[
                                    one.second -
                                        1
                                ]
                        ).toInt()

                    val oneY =
                        (
                            height *
                                rowCenters[
                                    one.first -
                                        1
                                ]
                        ).toInt()

                    sendStatus(
                        "READ 25/25 ✓ • " +
                            "HIGHLIGHT 1",
                        oneX,
                        oneY
                    )

                } else {

                    sendStatus(
                        "OCR " +
                            "${board.size}/25"
                    )
                }
            }
            .addOnFailureListener {
                e ->

                sendStatus(
                    "P4A OCR ERROR: " +
                        e.javaClass
                            .simpleName
                )
            }
            .addOnCompleteListener {

                bitmap.recycle()

                ocrBusy = false
            }
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
     
