package com.seastreams.tv.player.external

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.seastreams.tv.R
import com.seastreams.tv.core.webserver.LocalPortalServer
import com.seastreams.tv.core.webserver.PortalLease
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps SeaStreams alive while an external player reads a Usenet stream from `127.0.0.1:8765/nntp/…` (PRD §12):
 * a `mediaPlayback` foreground service that holds a [PortalLease] for as long as it runs, so the loopback proxy
 * keeps serving after SeaStreams leaves the screen. Started and stopped by [ExternalStreamUrls].
 */
@AndroidEntryPoint
class NntpProxyService : Service() {
    @Inject
    lateinit var portal: LocalPortalServer

    private var lease: PortalLease? = null

    override fun onCreate() {
        super.onCreate()
        lease = portal.acquireLease()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification(this, intent?.getStringExtra(EXTRA_TITLE).orEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lease?.close()
        lease = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "usenet_proxy"
        private const val NOTIFICATION_ID = 0x5EA5
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, NntpProxyService::class.java).putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NntpProxyService::class.java))
        }

        private fun notification(context: Context, title: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Usenet streaming", NotificationManager.IMPORTANCE_LOW))
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_usenet)
                .setContentTitle("Streaming from Usenet")
                .setContentText(title.ifBlank { "An external player is playing a Usenet stream" })
                .setOngoing(true)
                .build()
        }
    }
}
