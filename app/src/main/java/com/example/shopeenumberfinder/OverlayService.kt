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

    private var panel:
        View? = null

    private var statusText:
        TextView? = null

    private lateinit var routeView:
        RouteView

    private val groupButtons =
        mutableListOf<Button>()

    private var captureButton:
        Button? = null

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
                            intent
                                .getStringExtra(
                                    "status"
                                )
                                ?: return

                        statusText?.text =
                            msg
                    }

                    CaptureService
                        .ACTION_ROUTE -> {

                        val count =
                            intent
                                .getIntExtra(
                                    "count",
                                    0
                                )

                        val group =
                            intent
                                .getIntExtra(
                                    "group",
                                    0
                                )

                        val points =
                            mutableListOf<
                                RoutePoint
                            >()

                        for (
                            i in 0 until count
                        ) {

                            val x =
                                intent
                                    .getIntExtra(
                                        "x_$i",
                                        -1
                                    )

                            val y =
                                intent
                                    .getIntExtra(
                                        "y_$i",
                                        -1
                                    )

                            val number =
                                intent
                                    .getIntExtra(
                                        "number_$i",
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

                        /*
                         * Atomic replacement
                         *
                         * route เก่าหายทั้งชุด
                         * แล้ว route ใหม่ขึ้น
                         */
                        routeView
                            .setRoute(
                                points
                            )

                        highlightGroup(
                            group
                        )
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
                    CaptureService
                        .ACTION_STATUS
                )

                addAction(
                    CaptureService
                        .ACTION_ROUTE
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
        createControlPanel()
    }

    /*
     * =====================================================
     * FULL SCREEN ROUTE CANVAS
     * =====================================================
     */

    private fun createRouteOverlay() {

        routeView =
            RouteView(this)

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams
                    .MATCH_PARENT,
                WindowManager.LayoutParams
                    .MATCH_PARENT,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,

                /*
                 * สำคัญมาก:
                 *
                 * Route overlay รับ touch ไม่ได้
                 * นิ้วจึงทะลุผ่านลงไป
                 * กดปุ่มเกมด้านล่างได้
                 */
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams
                        .FLAG_LAYOUT_IN_SCREEN,

                PixelFormat.TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or
                Gravity.START

        wm.addView(
            routeView,
            lp
        )
    }

    /*
     * =====================================================
     * CONTROL PANEL
     * =====================================================
     */

    private fun createControlPanel() {

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
                    10,
                    8,
                    10,
                    8
                )
            }

        statusText =
            TextView(this).apply {

                text =
                    "P5A • กด CAPTURE"

                setTextColor(
                    Color.WHITE
                )

                textSize = 12f

                setPadding(
                    6,
                    2,
                    6,
                    4
                )
            }

        box.addView(
            statusText
        )

        /*
         * CAPTURE
         */
        captureButton =
            Button(this).apply {

                text =
                    "CAPTURE"

                textSize = 12f

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

                setPadding(
                    8,
                    0,
                    8,
                    0
                )

                minHeight = 0
                minimumHeight = 0

                setOnClickListener {

                    sendCommand(
                        CaptureService
                            .CMD_CAPTURE
                    )
                }
            }

        box.addView(
            captureButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                70
            )
        )

        /*
         * 1 2 3 4 5
         */
        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    5,
                    0,
                    5
                )
            }

        for (
            group in 1..5
        ) {

            val button =
                Button(this).apply {

                    text =
                        group.toString()

                    textSize = 13f

                    setTextColor(
                        Color.WHITE
                    )

                    setBackgroundColor(
                        normalGroupColor()
                    )

                    setPadding(
                        0,
                        0,
                        0,
                        0
                    )

                    minWidth = 0
                    minimumWidth = 0

                    minHeight = 0
                    minimumHeight = 0

                    setOnClickListener {

                        sendRouteCommand(
                            group
                        )
                    }
                }

            groupButtons.add(
                button
            )

            row.addView(
                button,
                LinearLayout.LayoutParams(
                    62,
                    62
                ).apply {

                    marginEnd = 3
                }
            )
        }

        box.addView(row)

        /*
         * END
         *
         * ไม่ปิด Service
         * แค่ reset session
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
                        75,
                        75,
                        75
                    )
                )

                setPadding(
                    5,
                    0,
                    5,
                    0
                )

                minHeight = 0
                minimumHeight = 0

                setOnClickListener {

                    sendCommand(
                        CaptureService
                            .CMD_END
                    )

                    highlightGroup(0)
                }
            }

        box.addView(
            endButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                58
            )
        )

        panel = box

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or
                Gravity.START

        /*
         * panel อยู่ด้านบน
         * ไม่บัง board
         */
        lp.x = 10
        lp.y = 70

        wm.addView(
            box,
            lp
        )
    }

    private fun sendCommand(
        command: String
    ) {

        val intent =
            Intent(
                CaptureService
                    .ACTION_COMMAND
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

    private fun sendRouteCommand(
        group: Int
    ) {

        val intent =
            Intent(
                CaptureService
                    .ACTION_COMMAND
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            "command",
            CaptureService
                .CMD_ROUTE
        )

        intent.putExtra(
            "group",
            group
        )

        sendBroadcast(intent)
    }

    /*
     * =====================================================
     * BUTTON HIGHLIGHT
     * =====================================================
     */

    private fun highlightGroup(
        selected: Int
    ) {

        for (
            i in groupButtons.indices
        ) {

            val group =
                i + 1

            groupButtons[i]
                .setBackgroundColor(
                    if (
                        group == selected
                    ) {

                        /*
                         * group ปัจจุบัน:
                         * ส้มเข้ม
                         */
                        Color.rgb(
                            230,
                            105,
                            0
                        )

                    } else {

                        normalGroupColor()
                    }
                )
        }
    }

    private fun normalGroupColor():
        Int {

        return Color.rgb(
            0,
            125,
            70
        )
    }

    override fun onDestroy() {

        runCatching {

            unregisterReceiver(
                receiver
            )
        }

        panel?.let {

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

    private var points:
        List<RoutePoint> =
        emptyList()

    /*
     * =====================================================
     * COLORS
     *
     * พื้นเกมเป็นขาว/เทาอ่อน
     * จึงใช้สีเข้ม contrast สูง
     * =====================================================
     */

    private val startColor =
        Color.rgb(
            225,
            20,
            35
        )

    private val routeColor =
        Color.rgb(
            0,
            145,
            70
        )

    private val lineColor =
        Color.rgb(
            25,
            25,
            25
        )

    private val lineOutlineColor =
        Color.rgb(
            255,
            145,
            0
        )

    /*
     * Marker intentionally เล็ก
     *
     * เพราะตำแหน่ง marker
     * คือจุดที่ user จะเอานิ้วแตะ
     */
    private val markerRadius =
        22f

    private val startRadius =
        27f

    private val linePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 7f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                lineColor
        }

    private val lineOutlinePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 12f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                lineOutlineColor
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

            strokeWidth = 5f

            color =
                Color.BLACK
        }

    /*
     * ไม่เขียนเลขทับ marker
     *
     * user ไม่ต้องอ่านเลข
     * ใช้เส้นเป็นตัวนำสายตา
     */

    fun setRoute(
        newPoints:
            List<RoutePoint>
    ) {

        /*
         * replace ทั้งชุด
         * ไม่ append
         *
         * route เก่าจึงหาย
         */
        points =
            newPoints.toList()

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        if (
            points.isEmpty()
        ) {
            return
        }

        /*
         * วาดเส้นก่อน
         */
        if (
            points.size >= 2
        ) {

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
        }

        /*
         * วาด marker ทีหลัง
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
         * เส้นเริ่ม/จบตรงขอบ marker
         * ไม่พาดทับจุดที่จะกด
         */
        val margin =
            31f

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

        /*
         * ส้มเข้มด้านนอก
         */
        canvas.drawLine(
            startX,
            startY,
            endX,
            endY,
            lineOutlinePaint
        )

        /*
         * ดำด้านใน
         */
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

        val arrowLength =
            22f

        val arrowAngle =
            Math.toRadians(
                28.0
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

        /*
         * outline
         */
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

        /*
         * inner
         */
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

    private fun drawMarker(
        canvas: Canvas,
        point: RoutePoint,
        index: Int
    ) {

        val isStart =
            index == 0

        val radius =
            if (
                isStart
            ) {
                startRadius
            } else {
                markerRadius
            }

        markerPaint.color =
            if (
                isStart
            ) {
                startColor
            } else {
                routeColor
            }

        /*
         * Fill marker
         *
         * user กดตรงจุดนี้ได้เลย
         */
        canvas.drawCircle(
            point.x,
            point.y,
            radius,
            markerPaint
        )

        /*
         * ขอบดำ
         * ช่วยให้เห็นชัดบนพื้นขาว
         */
        canvas.drawCircle(
            point.x,
            point.y,
            radius,
            markerOutlinePaint
        )
    }
}
