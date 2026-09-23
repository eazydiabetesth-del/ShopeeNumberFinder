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

    private lateinit var wm: WindowManager

    private var panel: View? = null
    private var statusText: TextView? = null

    private lateinit var routeView: RouteView

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (
                    intent?.action
                ) {

                    "NUMBER_FINDER_STATUS" -> {

                        val msg =
                            intent.getStringExtra(
                                "status"
                            ) ?: return

                        statusText?.text =
                            "Phase 4E\n$msg"
                    }

                    "NUMBER_FINDER_ROUTE" -> {

                        val count =
                            intent.getIntExtra(
                                "count",
                                0
                            )

                        val points =
                            mutableListOf<RoutePoint>()

                        for (
                            i in 0 until count
                        ) {

                            val x =
                                intent.getIntExtra(
                                    "x_$i",
                                    -1
                                )

                            val y =
                                intent.getIntExtra(
                                    "y_$i",
                                    -1
                                )

                            val number =
                                intent.getIntExtra(
                                    "number_$i",
                                    -1
                                )

                            if (
                                x >= 0 &&
                                y >= 0 &&
                                number >= 0
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
                         * replace ทั้ง route
                         * ไม่มีการ append ของเก่า
                         */
                        routeView.setRoute(
                            points
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
                    "NUMBER_FINDER_STATUS"
                )

                addAction(
                    "NUMBER_FINDER_ROUTE"
                )
            }

        if (
            Build.VERSION.SDK_INT >= 33
        ) {

            registerReceiver(
                statusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                statusReceiver,
                filter
            )
        }

        createRouteOverlay()
        showPanel()
    }

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
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams
                        .FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat
                    .TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or
                Gravity.START

        wm.addView(
            routeView,
            lp
        )
    }

    private fun showPanel() {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.argb(
                        220,
                        25,
                        25,
                        25
                    )
                )

                setPadding(
                    18,
                    10,
                    18,
                    10
                )
            }

        statusText =
            TextView(this).apply {

                text =
                    "Phase 4E\nSEARCHING"

                setTextColor(
                    Color.WHITE
                )

                textSize = 14f
            }

        box.addView(
            statusText
        )

        box.addView(
            Button(this).apply {

                text = "STOP"

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
                android.graphics.PixelFormat
                    .TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or
                Gravity.START

        lp.x = 20
        lp.y = 100

        wm.addView(
            box,
            lp
        )
    }

    override fun onDestroy() {

        runCatching {
            unregisterReceiver(
                statusReceiver
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
 * =====================================================
 * ROUTE DATA
 * =====================================================
 */

data class RoutePoint(
    val x: Float,
    val y: Float,
    val number: Int
)

/*
 * =====================================================
 * ROUTE CANVAS
 * =====================================================
 */

class RouteView(
    context: Context
) : View(context) {

    /*
     * route ใหม่ replace ของเก่าทั้งชุด
     */
    private var points:
        List<RoutePoint> =
        emptyList()

    /*
     * สีเลือกให้ contrast สูงกับ
     * background ขาว/เทาของเกม
     */
    private val colors =
        intArrayOf(
            Color.rgb(
                235,
                30,
                45
            ),      // RED

            Color.rgb(
                255,
                145,
                0
            ),      // ORANGE

            Color.rgb(
                0,
                155,
                75
            ),      // GREEN

            Color.rgb(
                0,
                90,
                220
            ),      // BLUE

            Color.rgb(
                145,
                35,
                200
            )       // PURPLE
        )

    private val linePaint =
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
                    35,
                    35,
                    35
                )
        }

    /*
     * เส้น outline อีกชั้น
     * ทำให้มองเห็นแม้ผ่านพื้นที่สีเข้ม
     */
    private val lineOutlinePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 15f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    255,
                    190,
                    0
                )
        }

    private val circlePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 9f
        }

    /*
     * เลขเล็กบน marker
     * ใช้สีดำเพื่อ contrast
     */
    private val textPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {

            color =
                Color.BLACK

            textSize = 28f

            typeface =
                Typeface.DEFAULT_BOLD

            textAlign =
                Paint.Align.CENTER
        }

    fun setRoute(
        newPoints:
            List<RoutePoint>
    ) {

        /*
         * สำคัญ:
         * replace ไม่ใช่ add
         *
         * route เก่าจึงหายทันที
         */
        points =
            newPoints.toList()

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(
            canvas
        )

        if (
            points.isEmpty()
        ) {
            return
        }

        /*
         * -------------------------------------------------
         * STEP 1
         * วาดเส้นก่อน
         *
         * marker จะถูกวาดทับทีหลัง
         * -------------------------------------------------
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
         * -------------------------------------------------
         * STEP 2
         * วาด marker
         * -------------------------------------------------
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
         * ไม่ลากเข้า center ของเลข
         *
         * ตัดหัวท้ายออก ~48 px
         */
        val margin = 48f

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
         * สีเหลืองเข้มด้านนอก
         * + ดำด้านใน
         *
         * มองเห็นได้บนพื้นขาว
         */
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

        /*
         * หัวลูกศร
         */
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
            27f

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
         * outline ก่อน
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
         * เส้นดำด้านใน
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

        val color =
            colors[
                index %
                    colors.size
            ]

        circlePaint.color =
            color

        /*
         * จุดแรกใหญ่กว่า
         * เพราะคือเป้าหมายปัจจุบัน
         */
        val radius =
            if (
                index == 0
            ) {
                49f
            } else {
                42f
            }

        /*
         * วงกลมไม่มี fill
         * จึงไม่บังเลขของเกม
         */
        canvas.drawCircle(
            point.x,
            point.y,
            radius,
            circlePaint
        )

        /*
         * แสดงลำดับ 1..5
         *
         * ไม่แสดงเลขจริงซ้ำ
         * เพราะเลขจริงอยู่กลางช่องแล้ว
         *
         * วางไว้ด้านบนของวง
         */
        val label =
            (index + 1)
                .toString()

        /*
         * background สี marker
         * เป็นจุดเล็กเหนือวง
         */
        val labelY =
            point.y -
                radius -
                17f

        val labelPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                style =
                    Paint.Style.FILL

                this.color =
                    color
            }

        canvas.drawCircle(
            point.x,
            labelY,
            20f,
            labelPaint
        )

        val fm =
            textPaint.fontMetrics

        val textY =
            labelY -
                (
                    fm.ascent +
                        fm.descent
                    ) / 2f

        canvas.drawText(
            label,
            point.x,
            textY,
            textPaint
        )
    }
}
