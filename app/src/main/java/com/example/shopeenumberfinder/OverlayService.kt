package com.example.shopeenumberfinder

import android.app.Service
import android.content.*
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayService : Service() {

    private lateinit var wm:
        WindowManager

    private var leftPanel:
        View? = null

    private var nextPanel:
        View? = null

    private var statusText:
        TextView? = null

    private var nextButton:
        Button? = null

    private lateinit var routeView:
        RouteView

    private var currentGroup = 0

    private var routesReady = false

    private val receiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (
                    intent?.action
                ) {

                    CaptureService
                        .ACTION_STATUS -> {

                        val msg =
                            intent.getStringExtra(
                                "status"
                            ) ?: return

                        statusText?.text =
                            msg
                    }

                    /*
                     * รับ route ทั้ง 5 ชุด
                     * เพียงครั้งเดียว
                     */
                    CaptureService
                        .ACTION_ROUTES_READY -> {

                        val allRoutes =
                            mutableListOf<
                                List<RoutePoint>
                            >()

                        for (
                            group in 0 until 5
                        ) {

                            val points =
                                mutableListOf<
                                    RoutePoint
                                >()

                            for (
                                index in 0 until 10
                            ) {

                                val x =
                                    intent.getIntExtra(
                                        "x_${group}_$index",
                                        -1
                                    )

                                val y =
                                    intent.getIntExtra(
                                        "y_${group}_$index",
                                        -1
                                    )

                                val number =
                                    intent.getIntExtra(
                                        "number_${group}_$index",
                                        -1
                                    )

                                if (
                                    x >= 0 &&
                                    y >= 0 &&
                                    number > 0
                                ) {

                                    points.add(
                                        RoutePoint(
                                            x.toFloat(),
                                            y.toFloat(),
                                            number
                                        )
                                    )
                                }
                            }

                            allRoutes.add(
                                points
                            )
                        }

                        /*
                         * RouteView เก็บทั้งหมดไว้
                         * ใน memory
                         */
                        routeView.setAllRoutes(
                            allRoutes
                        )

                        currentGroup = 0
                        routesReady = true

                        /*
                         * แสดง 1..10 ทันที
                         */
                        routeView.showGroup(
                            currentGroup
                        )

                        updateNextButton()
                    }

                    CaptureService
                        .ACTION_CLEAR -> {

                        routesReady = false
                        currentGroup = 0

                        routeView.clearRoutes()

                        updateNextButton()
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        wm =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        val filter =
            IntentFilter().apply {

                addAction(
                    CaptureService.ACTION_STATUS
                )

                addAction(
                    CaptureService.ACTION_ROUTES_READY
                )

                addAction(
                    CaptureService.ACTION_CLEAR
                )
            }

        if (
            Build.VERSION.SDK_INT >= 33
        ) {

            registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                receiver,
                filter
            )
        }

        createRouteOverlay()

        createLeftPanel()

        createNextPanel()
    }

    /*
     * =====================================================
     * ROUTE CANVAS
     * =====================================================
     */

    private fun createRouteOverlay() {

        routeView =
            RouteView(this)

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,

                /*
                 * Touch ผ่าน Canvas ลงเกม
                 */
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

                PixelFormat.TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or Gravity.START

        wm.addView(
            routeView,
            lp
        )
    }

    /*
     * =====================================================
     * LEFT PANEL
     *
     * CAPTURE
     * END
     * CLOSE
     * =====================================================
     */

    private fun createLeftPanel() {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.argb(
                        225,
                        20,
                        20,
                        20
                    )
                )

                setPadding(
                    7,
                    7,
                    7,
                    7
                )
            }

        statusText =
            TextView(this).apply {

                text =
                    "P5B • กด CAPTURE"

                setTextColor(
                    Color.WHITE
                )

                textSize = 11f

                setPadding(
                    5,
                    2,
                    5,
                    4
                )
            }

        box.addView(
            statusText
        )

        /*
         * CAPTURE
         */
        val captureButton =
            Button(this).apply {

                text = "CAPTURE"

                textSize = 11f

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.rgb(
                        190,
                        25,
                        35
                    )
                )

                minHeight = 0
                minimumHeight = 0

                setOnClickListener {

                    sendCommand(
                        CaptureService.CMD_CAPTURE
                    )
                }
            }

        box.addView(
            captureButton,
            LinearLayout.LayoutParams(
                200,
                60
            )
        )

        /*
         * END
         *
         * Clear session
         * แต่ app/overlay ยังอยู่
         */
        val endButton =
            Button(this).apply {

                text = "END"

                textSize = 11f

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.rgb(
                        80,
                        80,
                        80
                    )
                )

                minHeight = 0
                minimumHeight = 0

                setOnClickListener {

                    sendCommand(
                        CaptureService.CMD_END
                    )
                }
            }

        box.addView(
            endButton,
            LinearLayout.LayoutParams(
                200,
                55
            )
        )

        /*
         * CLOSE
         *
         * ปิดทั้ง CaptureService
         * และ OverlayService
         */
        val closeButton =
            Button(this).apply {

                text = "CLOSE"

                textSize = 11f

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.rgb(
                        120,
                        20,
                        20
                    )
                )

                minHeight = 0
                minimumHeight = 0

                setOnClickListener {

                    stopService(
                        Intent(
                            this@OverlayService,
                            CaptureService::class.java
                        )
                    )

                    stopSelf()
                }
            }

        box.addView(
            closeButton,
            LinearLayout.LayoutParams(
                200,
                55
            )
        )

        leftPanel = box

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or Gravity.START

        lp.x = 8
        lp.y = 70

        wm.addView(
            box,
            lp
        )
    }

    /*
     * =====================================================
     * NEXT PANEL
     *
     * อยู่ขวา
     * ปุ่มใหญ่ กดง่าย
     * =====================================================
     */

    private fun createNextPanel() {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.argb(
                        210,
                        20,
                        20,
                        20
                    )
                )

                setPadding(
                    5,
                    5,
                    5,
                    5
                )
            }

        nextButton =
            Button(this).apply {

                text =
                    "NEXT\nWAIT"

                textSize = 15f

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.rgb(
                        0,
                        125,
                        70
                    )
                )

                minHeight = 0
                minimumHeight = 0

                setOnClickListener {

                    nextRoute()
                }
            }

        box.addView(
            nextButton,
            LinearLayout.LayoutParams(
                170,
                100
            )
        )

        nextPanel = box

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or Gravity.END

        lp.x = 8
        lp.y = 90

        wm.addView(
            box,
            lp
        )
    }

    /*
     * =====================================================
     * INSTANT NEXT
     *
     * ไม่มี Broadcast
     * ไม่มี OCR
     * ไม่มีการสร้าง route ใหม่
     *
     * แค่เปลี่ยน index แล้ว invalidate Canvas
     * =====================================================
     */

    private fun nextRoute() {

        if (!routesReady) {
            return
        }

        /*
         * ถึง 41..50 แล้ว
         * ไม่วนกลับ
         */
        if (
            currentGroup >= 4
        ) {
            return
        }

        currentGroup++

        routeView.showGroup(
            currentGroup
        )

        updateNextButton()
    }

    private fun updateNextButton() {

        if (!routesReady) {

            nextButton?.text =
                "NEXT\nWAIT"

            return
        }

        val start =
            currentGroup * 10 + 1

        val end =
            start + 9

        nextButton?.text =
            if (
                currentGroup < 4
            ) {

                /*
                 * บรรทัดล่างบอกชุด
                 * ที่กำลังแสดง
                 */
                "NEXT\n$start–$end"

            } else {

                "DONE\n41–50"
            }
    }

    private fun sendCommand(
        command: String
    ) {

        val intent =
            Intent(
                CaptureService.ACTION_COMMAND
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "command",
            command
        )

        sendBroadcast(intent)
    }

    override fun onDestroy() {

        runCatching {
            unregisterReceiver(
                receiver
            )
        }

        leftPanel?.let {

            runCatching {
                wm.removeView(it)
            }
        }

        nextPanel?.let {

            runCatching {
                wm.removeView(it)
            }
        }

        if (
            ::routeView.isInitialized
        ) {

            runCatching {
                wm.removeView(
                    routeView
                )
            }
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}

/*
 * =========================================================
 * ROUTE DATA
 * =========================================================
 */

data class RoutePoint(
    val x: Float,
    val y: Float,
    val number: Int
)

/*
 * =========================================================
 * ROUTE VIEW
 * =========================================================
 */

class RouteView(
    context: Context
) : View(context) {

    /*
     * ทั้ง 5 route อยู่ใน RAM
     *
     * [0] = 1..10
     * [1] = 11..20
     * ...
     * [4] = 41..50
     */
    private var allRoutes:
        List<List<RoutePoint>> =
        emptyList()

    private var visibleGroup = 0

    /*
     * =====================================================
     * COLORS
     * =====================================================
     */

    private val red =
        Color.rgb(
            220,
            25,
            40
        )

    private val green =
        Color.rgb(
            0,
            145,
            70
        )

    private val blue =
        Color.rgb(
            0,
            85,
            210
        )

    /*
     * เส้นลดความเด่นลงจาก Phase 5A
     *
     * Marker + เลข เป็นข้อมูลหลัก
     */
    private val linePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 5f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    55,
                    55,
                    55
                )
        }

    private val lineOutlinePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 9f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    245,
                    155,
                    20
                )
        }

    private val markerPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.FILL
        }

    private val markerOutlinePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 4f

            color =
                Color.rgb(
                    20,
                    20,
                    20
                )
        }

    /*
     * เลขสีขาวบน marker
     */
    private val textPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            color = Color.WHITE

            textSize = 24f

            typeface =
                Typeface.DEFAULT_BOLD

            textAlign =
                Paint.Align.CENTER
        }

    /*
     * ใหญ่กว่าเดิมเล็กน้อย
     * เพื่ออ่าน 2 หลักง่าย
     * และยังเป็น touch target
     */
    private val normalRadius = 25f
    private val firstRadius = 29f

    fun setAllRoutes(
        routes:
            List<List<RoutePoint>>
    ) {

        allRoutes =
            routes.map {
                it.toList()
            }

        visibleGroup = 0

        invalidate()
    }

    fun showGroup(
        group: Int
    ) {

        if (
            allRoutes.isEmpty()
        ) {
            return
        }

        visibleGroup =
            group.coerceIn(
                0,
                allRoutes.lastIndex
            )

        /*
         * นี่คือสิ่งเดียวที่ NEXT ต้องทำ
         */
        invalidate()
    }

    fun clearRoutes() {

        allRoutes =
            emptyList()

        visibleGroup = 0

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        if (
            allRoutes.isEmpty() ||
            visibleGroup !in
                allRoutes.indices
        ) {
            return
        }

        /*
         * สำคัญ:
         *
         * ถึงจะเก็บ 5 route ไว้ทั้งหมด
         * แต่ Canvas วาดเฉพาะ route ปัจจุบัน
         *
         * จึงเทียบเท่ากับอีก 4 route
         * โปร่งใส 100%
         */
        val points =
            allRoutes[
                visibleGroup
            ]

        if (
            points.isEmpty()
        ) {
            return
        }

        /*
         * เส้นก่อน
         */
        for (
            i in 0 until
                points.size - 1
        ) {

            drawConnection(
                canvas,
                points[i],
                points[i + 1]
            )
        }

        /*
         * Marker + เลขทีหลัง
         */
        for (
            i in points.indices
        ) {

            drawMarker(
                canvas,
                points[i],
                i
            )
        }
    }

    /*
     * =====================================================
     * CONNECTION
     * =====================================================
     */

    private fun drawConnection(
        canvas: Canvas,
        from: RoutePoint,
        to: RoutePoint
    ) {

        val dx =
            to.x - from.x

        val dy =
            to.y - from.y

        val distance =
            sqrt(
                dx * dx +
                    dy * dy
            )

        if (
            distance < 1f
        ) {
            return
        }

        val ux =
            dx / distance

        val uy =
            dy / distance

        /*
         * ไม่ให้เส้นเข้าไปทับเลข
         */
        val margin = 34f

        val startX =
            from.x +
                ux * margin

        val startY =
            from.y +
                uy * margin

        val endX =
            to.x -
                ux * margin

        val endY =
            to.y -
                uy * margin

        canvas.drawLine(
            startX,
            startY,
            endX,
            endY,
            lineOutlinePaint
        )

        canvas.drawLine(
            startX,
            startY,
            endX,
            endY,
            linePaint
        )

        drawArrowHead(
            canvas,
            startX,
            startY,
            endX,
            endY
        )
    }

    private fun drawArrowHead(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {

        val angle =
            atan2(
                (
                    endY -
                        startY
                    ).toDouble(),
                (
                    endX -
                        startX
                    ).toDouble()
            )

        val arrowLength = 18f

        val arrowAngle =
            Math.toRadians(
                27.0
            )

        val x1 =
            endX -
                (
                    arrowLength *
                        cos(
                            angle -
                                arrowAngle
                        )
                    ).toFloat()

        val y1 =
            endY -
                (
                    arrowLength *
                        sin(
                            angle -
                                arrowAngle
                        )
                    ).toFloat()

        val x2 =
            endX -
                (
                    arrowLength *
                        cos(
                            angle +
                                arrowAngle
                        )
                    ).toFloat()

        val y2 =
            endY -
                (
                    arrowLength *
                        sin(
                            angle +
                                arrowAngle
                        )
                    ).toFloat()

        canvas.drawLine(
            endX,
            endY,
            x1,
            y1,
            lineOutlinePaint
        )

        canvas.drawLine(
            endX,
            endY,
            x2,
            y2,
            lineOutlinePaint
        )

        canvas.drawLine(
            endX,
            endY,
            x1,
            y1,
            linePaint
        )

        canvas.drawLine(
            endX,
            endY,
            x2,
            y2,
            linePaint
        )
    }

    /*
     * =====================================================
     * MARKER
     *
     * index 0 = RED
     *
     * index 1 = GREEN
     * index 2 = BLUE
     * index 3 = GREEN
     * index 4 = BLUE
     * ...
     *
     * เช่น:
     *
     * 21 RED
     * 22 GREEN
     * 23 BLUE
     * 24 GREEN
     * ...
     * 30 GREEN
     * =====================================================
     */

    private fun drawMarker(
        canvas: Canvas,
        point: RoutePoint,
        index: Int
    ) {

        val markerColor =
            when {

                index == 0 -> {
                    red
                }

                index % 2 == 1 -> {
                    green
                }

                else -> {
                    blue
                }
            }

        val radius =
            if (
                index == 0
            ) {
                firstRadius
            } else {
                normalRadius
            }

        markerPaint.color =
            markerColor

        /*
         * Marker fill
         */
        canvas.drawCircle(
            point.x,
            point.y,
            radius,
            markerPaint
        )

        /*
         * ขอบเข้ม
         */
        canvas.drawCircle(
            point.x,
            point.y,
            radius,
            markerOutlinePaint
        )

        /*
         * เลขจริง เช่น
         * 21,22,...30
         *
         * อยู่กลาง marker
         */
        val fm =
            textPaint.fontMetrics

        val textY =
            point.y -
                (
                    fm.ascent +
                        fm.descent
                ) / 2f

        canvas.drawText(
            point.number.toString(),
            point.x,
            textY,
            textPaint
        )
    }
}
