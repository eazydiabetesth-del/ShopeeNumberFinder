package com.example.shopeenumberfinder

import android.app.Service
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*

class OverlayService : Service() {

    private lateinit var wm: WindowManager

    private var panel: View? = null
    private var marker: View? = null
    private var statusText: TextView? = null

    private val statusReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent?.action == "NUMBER_FINDER_STATUS") {

                    val msg =
                        intent.getStringExtra("status")
                            ?: "Unknown"

                    statusText?.text =
                        "Phase 2C\n$msg"
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        wm =
            getSystemService(WINDOW_SERVICE)
                    as WindowManager

        val filter =
            IntentFilter("NUMBER_FINDER_STATUS")

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
        showMarker()
    }

    private fun params(
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        flags: Int
    ) =
        WindowManager.LayoutParams(
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
                    "Phase 2C\nOverlay OK"

                setTextColor(Color.WHITE)

                textSize = 14f
            }

        box.addView(statusText)

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

    private fun showMarker() {

        val v =
            TextView(this).apply {

                text = "◎"
                textSize = 62f

                gravity =
                    Gravity.CENTER

                setTextColor(Color.RED)

                setBackgroundColor(
                    Color.argb(
                        35,
                        255,
                        0,
                        0
                    )
                )
            }

        marker = v

        wm.addView(
            v,
            params(
                120,
                120,
                250,
                650,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
        )
    }

    override fun onDestroy() {

        runCatching {
            unregisterReceiver(statusReceiver)
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

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
