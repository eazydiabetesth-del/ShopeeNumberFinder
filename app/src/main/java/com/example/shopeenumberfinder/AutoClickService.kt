package com.example.shopeenumberfinder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AutoClickService : AccessibilityService() {

    companion object {

        var instance: AutoClickService? = null
            private set

        const val TAP_DURATION_MS = 40L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        // ไม่ต้องอ่าน Accessibility event
    }

    override fun onInterrupt() {
        // ไม่มีงานที่ต้องทำ
    }

    override fun onDestroy() {

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    /*
     * แตะหนึ่งตำแหน่ง
     */
    fun tap(
        x: Float,
        y: Float,
        onFinished: () -> Unit
    ) {

        val path =
            Path().apply {
                moveTo(x, y)
            }

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0L,
                TAP_DURATION_MS
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        val accepted =
            dispatchGesture(
                gesture,
                object :
                    GestureResultCallback() {

                    override fun onCompleted(
                        gestureDescription:
                            GestureDescription?
                    ) {

                        super.onCompleted(
                            gestureDescription
                        )

                        onFinished()
                    }

                    override fun onCancelled(
                        gestureDescription:
                            GestureDescription?
                    ) {

                        super.onCancelled(
                            gestureDescription
                        )

                        /*
                         * แม้ gesture ถูก cancel
                         * ให้คืน control เพื่อไม่ให้ค้าง
                         */
                        onFinished()
                    }
                },
                null
            )

        if (!accepted) {
            onFinished()
        }
    }
}
