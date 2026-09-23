package com.example.shopeenumberfinder

import android.app.Service
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*

class OverlayService : Service() {

    private lateinit var wm: WindowManager

    private var panel: View? = null
    private var marker: View? = null
    private var statusText: TextView? = null

    private val markerWidth = 105
    private val markerHeight = 135

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent?.action != "NUMBER_FINDER_STATUS") {
                    return
                }

                val msg =
                    intent.getStringExtra("status")
                        ?: "Unknown"

                statusText?.text =
                    "Phase 4A\n$msg"

                if (
                    intent.hasExtra("highlight_x") &&
                    intent.hasExtra("highlight_y")
                ) {

                    val x =
                        intent.getIntExtra(
                            "highlight_x",
                            -1
                        )

                    val y =
                        intent.getIntExtra(
                            "highlight_y",
                            -1
                        )

                    if (x >= 0 && y >= 0) {
                        moveMarker(x, y)
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        wm =
            getSystemService(WINDOW_SERVICE)
                    as WindowManager

        val filter =
            IntentFilter(
                "NUMBER_FINDER_STATUS"
            )

        if (Build.VERSION.SDK_INT >= 33) {

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
        createMarker()
    }

    private fun params(
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        flags: Int
    ): WindowManager.LayoutParams {

        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {

            gravity =
                Gravity.TOP or Gravity.START

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
                        230,
                        30,
                        30,
                        30
                    )
                )

                setPadding(
                    18,
                    12,
                    18,
                    12
                )
            }

        statusText =
            TextView(this).apply {

                text =
                    "Phase 4A\nWaiting for board"

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
            params(
                -2,
                -2,
                20,
                100,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        )
    }

    private fun createMarker() {

        val border =
            GradientDrawable().apply {

                shape =
                    GradientDrawable.RECTANGLE

                setColor(
                    Color.TRANSPARENT
                )

                setStroke(
                    8,
                    Color.rgb(
                        0,
                        220,
                        80
                    )
                )

                cornerRadius = 28f
            }

        val v =
            View(this).apply {

                background =
                    border

                visibility =
                    View.INVISIBLE
            }

        marker = v

        wm.addView(
            v,
            params(
                markerWidth,
                markerHeight,
                0,
                0,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        )
    }

    private fun moveMarker(
        centerX: Int,
        centerY: Int
    ) {

        val v =
            marker ?: return

        val lp =
            v.layoutParams
                    as WindowManager.LayoutParams

        lp.x =
            centerX -
                markerWidth / 2

        lp.y =
            centerY -
                markerHeight / 2

        wm.updateViewLayout(
            v,
            lp
        )

        v.visibility =
            View.VISIBLE
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

        marker?.let {
            runCatching {
                wm.removeView(it)
            }
        }

        panel = null
        marker = null

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
