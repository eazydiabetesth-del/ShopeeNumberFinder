package com.example.shopeenumberfinder

import android.app.Service
import android.content.*
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private lateinit var routeView:
        RouteView

    private var leftPanel:
        View? = null

    private var nextPanel:
        View? = null

    private var cPanel:
        View? = null

    private var statusText:
        TextView? = null

    private var nextButton:
        Button? = null

    private var cButton:
        Button? = null

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var currentGroup = 0

    private var routesReady = false

    private var autoClickRunning = false

    /*
     * 200 ms ระหว่างการเริ่มแต่ละ click
     */
    private val clickIntervalMs =
        200L

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

                        routeView.setAllRoutes(
                            allRoutes
                        )

                        currentGroup = 0
                        routesReady = true
                        autoClickRunning = false

                        routeView.showGroup(
                            currentGroup
                        )

                        updateButtons()
                    }

                    CaptureService
                        .ACTION_CLEAR -> {

                        routesReady = false
                        currentGroup = 0
                        autoClickRunning = false

                        handler.removeCallbacksAndMessages(
                            null
                        )

                        routeView.clearRoutes()

                        updateButtons()
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

        createCPanel()

        createNextPanel()
    }

    /*
     * =====================================================
     * ROUTE OVERLAY
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
     * LEFT
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
                        220,
                        20,
                        20,
                        20
                    )
                )

                setPadding(
                    6,
                    6,
                    6,
                    6
                )
            }

        statusText =
            TextView(this).apply {

                text =
                    "P5C • CAPTURE"

                setTextColor(
                    Color.WHITE
                )

                textSize = 10f
            }

        box.addView(
            statusText
        )

        val capture =
            Button(this).apply {

                text = "CAPTURE"

                textSize = 10f

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

                setOnClickListener {

                    if (!autoClickRunning) {

                        sendCommand(
                            CaptureService.CMD_CAPTURE
                        )
                    }
                }
            }

        box.addView(
            capture,
            LinearLayout.LayoutParams(
                180,
                55
            )
        )

        val end =
            Button(this).apply {

                text = "END"

                textSize = 10f

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

                setOnClickListener {

                    stopAutoClick()

                    sendCommand(
                        CaptureService.CMD_END
                    )
                }
            }

        box.addView(
            end,
            LinearLayout.LayoutParams(
                180,
                50
            )
        )

        val close =
            Button(this).apply {

                text = "CLOSE"

                textSize = 10f

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

                setOnClickListener {

                    stopAutoClick()

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
            close,
            LinearLayout.LayoutParams(
                180,
                50
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
     * C BUTTON
     *
     * กลางด้านบน
     * =====================================================
     */

    private fun createCPanel() {

        val box =
            LinearLayout(this).apply {

                setBackgroundColor(
                    Color.argb(
                        215,
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

        cButton =
            Button(this).apply {

                text = "C"

                textSize = 22f

                setTextColor(
                    Color.WHITE
                )

                setBackgroundColor(
                    Color.rgb(
                        0,
                        105,
                        190
                    )
                )

                setOnClickListener {

                    startCurrentRouteAutoClick()
                }
            }

        box.addView(
            cButton,
            LinearLayout.LayoutParams(
                120,
                90
            )
        )

        cPanel = box

        val lp =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        lp.gravity =
            Gravity.TOP or Gravity.CENTER_HORIZONTAL

        lp.y = 70

        wm.addView(
            box,
            lp
        )
    }

    /*
     * =====================================================
     * NEXT
     *
     * ยังเก็บไว้สำหรับ manual test
     * =====================================================
     */

    private fun createNextPanel() {

        val box =
            LinearLayout(this).apply {

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

                textSize = 14f

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

                setOnClickListener {

                    if (!autoClickRunning) {
                        nextRoute()
                    }
                }
            }

        box.addView(
            nextButton,
            LinearLayout.LayoutParams(
                160,
                90
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
        lp.y = 70

        wm.addView(
            box,
            lp
        )
    }

    /*
     * =====================================================
     * AUTO CLICK
     * =====================================================
     */

    private fun startCurrentRouteAutoClick() {

        if (
            !routesReady ||
            autoClickRunning
        ) {
            return
        }

        val service =
            AutoClickService.instance

        if (service == null) {

            statusText?.text =
                "เปิด Accessibility\nNumber Finder ก่อน"

            return
        }

        val points =
            routeView.getCurrentRoute()

        /*
         * ต้องครบ 10 จุด
         * ไม่งั้นไม่ยอมเริ่ม
         */
        if (
            points.size != 10
        ) {

            statusText?.text =
                "ERROR route ${points.size}/10"

            return
        }

        autoClickRunning = true

        updateButtons()

        statusText?.text =
            "AUTO ${points.first().number}–${points.last().number}"

        clickPoint(
            points = points,
            index = 0,
            service = service
        )
    }

    private fun clickPoint(
        points: List<RoutePoint>,
        index: Int,
        service: AutoClickService
    ) {

        if (!autoClickRunning) {
            return
        }

        /*
         * ครบทั้ง 10 จุดแล้ว
         */
        if (
            index >= points.size
        ) {

            autoClickRunning = false

            /*
             * เปลี่ยน route ทันที
             * หลังชุดปัจจุบันครบ
             */
            if (
                currentGroup < 4
            ) {

                currentGroup++

                routeView.showGroup(
                    currentGroup
                )

                statusText?.text =
                    "READY ${currentGroup * 10 + 1}–${currentGroup * 10 + 10}"

            } else {

                statusText?.text =
                    "DONE 1–50"
            }

            updateButtons()

            return
        }

        val point =
            points[index]

        service.tap(
            point.x,
            point.y
        ) {

            if (!autoClickRunning) {
                return@tap
            }

            /*
             * 200 ms ก่อนเริ่ม click ถัดไป
             */
            handler.postDelayed(
                {
                    clickPoint(
                        points,
                        index + 1,
                        service
                    )
                },
                clickIntervalMs
            )
        }
    }

    private fun stopAutoClick() {

        autoClickRunning = false

        handler.removeCallbacksAndMessages(
            null
        )

        updateButtons()
    }

    /*
     * =====================================================
     * MANUAL NEXT
     * =====================================================
     */

    private fun nextRoute() {

        if (
            !routesReady ||
            currentGroup >= 4
        ) {
            return
        }

        currentGroup++

        routeView.showGroup(
            currentGroup
        )

        updateButtons()
    }

    private fun updateButtons() {

        if (!routesReady) {

            nextButton?.text =
                "NEXT\nWAIT"

            cButton?.text =
                "C"

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
                "NEXT\n$start–$end"
            } else {
                "LAST\n41–50"
            }

        cButton?.text =
            if (
                autoClickRunning
            ) {
                "..."
            } else {
                "C"
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

        stopAutoClick()

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

        cPanel?.let {
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

    private var allRoutes:
        List<List<RoutePoint>> =
        emptyList()

    private var visibleGroup
