package com.example.shopeenumberfinder

import android.app.*
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.IBinder

class CaptureService:Service(){
    private val channel="capture"
    override fun onCreate(){super.onCreate(); val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel(channel,"Screen capture",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val n=Notification.Builder(this,channel).setContentTitle("Number Finder").setContentText("Screen capture พร้อมสำหรับ Phase 2").setSmallIcon(android.R.drawable.ic_menu_view).build(); startForeground(1001,n)
        val code=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
        val data=if(android.os.Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if(code==Activity.RESULT_OK && data!=null){ val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; mgr.getMediaProjection(code,data) }
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
