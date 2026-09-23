package com.example.shopeenumberfinder

import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val captureCode = 7001
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(48,48,48,48) }
        root.addView(TextView(this).apply { text="Number Finder"; textSize=30f; gravity=Gravity.CENTER })
        root.addView(TextView(this).apply { text="Phase 1 • Overlay + Screen Capture"; textSize=16f; gravity=Gravity.CENTER })
        status = TextView(this).apply { text="พร้อมเริ่มทดสอบ"; textSize=18f; gravity=Gravity.CENTER; setPadding(0,48,0,48) }; root.addView(status)
        root.addView(Button(this).apply { text="START"; setOnClickListener { startFlow() } }, LinearLayout.LayoutParams(-1,-2))
        root.addView(Button(this).apply { text="STOP"; setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)); stopService(Intent(this@MainActivity, CaptureService::class.java)); status.text="หยุดแล้ว" } }, LinearLayout.LayoutParams(-1,-2))
        setContentView(root)
    }
    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this,"อนุญาต Display over other apps แล้วกลับมากด START อีกครั้ง",Toast.LENGTH_LONG).show(); return
        }
        val mpm=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), captureCode)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==captureCode && resultCode==RESULT_OK && data!=null){
            val i=Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("data",data)
            startForegroundService(i); startService(Intent(this,OverlayService::class.java)); status.text="ทำงานอยู่ • กลับไปที่เกมได้"; moveTaskToBack(true)
        } else if(requestCode==captureCode) status.text="ยังไม่ได้รับสิทธิ์ Screen Capture"
    }
}
