package com.mtbanalyzer.viewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mtbanalyzer.R

/**
 * Keeps the viewer link alive once the recorder is on the tripod with its screen off.
 *
 * Without a foreground service the server dies the moment the system trims the process,
 * which is exactly when the coach is walking away from the tripod.
 */
class ViewerLinkService : Service() {

    companion object {
        private const val CHANNEL_ID = "viewer_link"
        private const val NOTIFICATION_ID = 4711

        const val ACTION_START = "com.mtbanalyzer.viewer.action.START"
        const val ACTION_STOP = "com.mtbanalyzer.viewer.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ViewerLinkService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ViewerLinkService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ViewerLinkController.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val status = ViewerLinkController.start(this)
        if (!status.running) {
            // The screen surfaces the reason; there is nothing to keep alive.
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(status), type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ViewerLinkController.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.viewer_link_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.viewer_link_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(status: ViewerLinkController.Status): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ViewerLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The address without the token: enough to recognise, useless to a shoulder-surfer.
        val where = "http://${status.address}:${status.port}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.viewer_link_notification_title))
            .setContentText(where)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.viewer_link_stop), stopIntent)
            .build()
    }
}
