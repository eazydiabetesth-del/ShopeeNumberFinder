package com.example.shopeenumberfinder

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    private lateinit var wm: WindowManager

    private var panel: View? = null
    private var statusText: TextView? = null

    /*
     * สูงสุด 5 hint พร้อมกัน
     */
    private val hints =
        mutableListOf<TextView>()

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action !=
                    "NUMBER_FINDER_STATUS"
                ) {
                    return
                }

                val msg =
                    intent.getStringExtra(
                        "status"
                    ) ?: "Unknown"

                statusText?.text =
                    "Phase 4D\n$msg"

                /*
                 * ถ้าไม่มี preview_count
                 * หมายถึงเป็น status ธรรมดา
                 */
                if (
                    !intent.hasExtra(
                        "preview_count"
                    )
                ) {
                    return
                }

                val count =
                    intent.getIntExtra(
                        "preview_count",
                        0
                    )

                updateHints(
                    intent,
                    count
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        wm =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        val filter =
            IntentFilter(
                "NUMBER_FINDER_STATUS"
            )

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

        showPanel()
        createHints()
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        flags: Int
    ): WindowManager.LayoutParams {

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams
                .TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity =
                Gravity.TOP or
                    Gravity.START

            this.x = x
            this.y = y
        }
    }

    private fun showPanel() {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    Color.argb(
                        225,
                        30,
                        30,
                        30
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
                    "Phase 4D\nSEARCHING"

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

        wm.addView(
            box,
            overlayParams(
                -2,
                -2,
                20,
                100,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE
            )
        )
    }

    /*
     * สร้าง hint 5 อันครั้งเดียว
     * แล้วค่อยย้ายตำแหน่ง
     */
    private fun createHints() {

        repeat(5) { index ->

            val hint =
                TextView(this).apply {

                    gravity =
                        Gravity.CENTER

                    textSize =
                        if (index == 0) {
                            20f
                        } else {
                            16f
                        }

                    setTextColor(
                        Color.WHITE
                    )

                    visibility =
                        View.GONE
                }

            hints.add(hint)

            wm.addView(
                hint,
                overlayParams(
                    if (index == 0) 88 else 72,
                    if (index == 0) 88 else 72,
                    0,
                    0,
                    WindowManager.LayoutParams
                        .FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams
                            .FLAG_NOT_TOUCHABLE
                )
            )
        }
    }

    private fun updateHints(
        intent: Intent,
        count: Int
    ) {

        for (
            index in
            hints.indices
        ) {

            val hint =
                hints[index]

            if (index >= count) {

                hint.visibility =
                    View.GONE

                continue
            }

            val x =
                intent.getIntExtra(
                    "preview_${index}_x",
                    -1
                )

            val y =
                intent.getIntExtra(
                    "preview_${index}_y",
                    -1
                )

            val number =
                intent.getIntExtra(
                    "preview_${index}_number",
                    -1
                )

            if (
                x < 0 ||
                y < 0 ||
                number < 0
            ) {

                hint.visibility =
                    View.GONE

                continue
            }

            /*
             * ตัวแรกเด่นที่สุด
             *
             * 1 = แดง
             * 2 = เหลือง/ส้ม
             * 3 = เขียว
             * 4-5 = ฟ้า/ขาวบาง
             */
            val background =
                makeHintBackground(
                    index
                )

            hint.background =
                background

            hint.text =
                number.toString()

            hint.textSize =
                when (index) {

                    0 -> 22f
                    1 -> 19f
                    2 -> 18f
                    else -> 16f
                }

            hint.visibility =
                View.VISIBLE

            val size =
                if (index == 0) {
                    88
                } else {
                    72
                }

            val lp =
                hint.layoutParams
                    as WindowManager.LayoutParams

            lp.width = size
            lp.height = size

            /*
             * x,y ที่ CaptureService ส่งมา
             * คือ center ของ cell
             */
            lp.x =
                x - size / 2

            lp.y =
                y - size / 2

            wm.updateViewLayout(
                hint,
                lp
            )
        }
    }

    private fun makeHintBackground(
        index: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.RECTANGLE

            cornerRadius =
                18f

            when (index) {

                /*
                 * กดตอนนี้
                 */
                0 -> {

                    setColor(
                        Color.argb(
                            55,
                            255,
                            0,
                            0
                        )
                    )

                    setStroke(
                        7,
                        Color.rgb(
                            255,
                            40,
                            40
                        )
                    )
                }

                /*
                 * ตัวถัดไป
                 */
                1 -> {

                    setColor(
                        Color.argb(
                            45,
                            255,
                            190,
                            0
                        )
                    )

                    setStroke(
                        6,
                        Color.rgb(
                            255,
                            180,
                            0
                        )
                    )
                }

                /*
                 * ตัวที่ 3
                 */
                2 -> {

                    setColor(
                        Color.argb(
                            40,
                            0,
                            220,
                            90
                        )
                    )

                    setStroke(
                        5,
                        Color.rgb(
                            0,
                            210,
                            90
                        )
                    )
                }

                /*
                 * ตัวที่ 4
                 */
                3 -> {

                    setColor(
                        Color.argb(
                            25,
                            0,
                            160,
                            255
                        )
                    )

                    setStroke(
                        4,
                        Color.rgb(
                            0,
                            160,
                            255
                        )
                    )
                }

                /*
                 * ตัวที่ 5
                 */
                else -> {

                    setColor(
                        Color.argb(
                            20,
                            255,
                            255,
                            255
                        )
                    )

                    setStroke(
                        3,
                        Color.WHITE
                    )
                }
            }
        }
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

        for (hint in hints) {

            runCatching {
                wm.removeView(hint)
            }
        }

        hints.clear()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
