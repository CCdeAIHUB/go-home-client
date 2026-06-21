package com.ccdeaihub.gohome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class GoHomeTunnelForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Go Home tunnel",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Go Home direct tunnel active"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Go Home")
            .setContentText("正在保持直连隧道")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GoHome:TunnelKeepalive"
        ).apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to acquire tunnel wake lock", error)
            }
        }
    }

    private fun releaseWakeLock() {
        val current = wakeLock
        wakeLock = null
        if (current?.isHeld == true) {
            runCatching { current.release() }
        }
    }

    companion object {
        private const val TAG = "GoHomeTunnelSvc"
        private const val CHANNEL_ID = "go_home_tunnel"
        private const val NOTIFICATION_ID = 4702

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GoHomeTunnelForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context?) {
            val appContext = context?.applicationContext ?: return
            appContext.stopService(Intent(appContext, GoHomeTunnelForegroundService::class.java))
        }
    }
}
