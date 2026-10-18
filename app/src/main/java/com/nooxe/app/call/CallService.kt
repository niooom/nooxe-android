package com.nooxe.app.call

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the call alive, and the phone ringing, while the app is in the background. */
class CallService : Service() {

    companion object {
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_RINGING = "ringing"
        private const val EXTRA_ONGOING = "ongoing"

        fun start(context: Context, data: CallData, ringing: Boolean) {
            val intent = Intent(context, CallService::class.java)
            intent.putExtra(EXTRA_PAYLOAD, data.toJson())
            intent.putExtra(EXTRA_RINGING, ringing)
            launch(context, intent)
        }

        fun showOngoing(context: Context, data: CallData) {
            val intent = Intent(context, CallService::class.java)
            intent.putExtra(EXTRA_PAYLOAD, data.toJson())
            intent.putExtra(EXTRA_ONGOING, true)
            launch(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }

        private fun launch(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val parsed = CallData.fromJson(intent?.getStringExtra(EXTRA_PAYLOAD))
        if (parsed == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val ringing = intent?.getBooleanExtra(EXTRA_RINGING, false) ?: false
        val ongoing = intent?.getBooleanExtra(EXTRA_ONGOING, false) ?: false

        if (ongoing) {
            CallNotification.cancelIncoming(this)
            Ringer.stop()
            foreground(
                CallNotification.ID_ONGOING,
                CallNotification.ongoing(this, parsed),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else if (ringing) {
            Ringer.start(this)
            foreground(
                CallNotification.ID_INCOMING,
                CallNotification.incoming(this, parsed),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            foreground(
                CallNotification.ID_ONGOING,
                CallNotification.ongoing(this, parsed),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Ringer.stop()
        CallNotification.cancelAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foreground(notificationId: Int, notification: Notification, type: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification, type)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (error: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(notificationId, notification)
                }
            } catch (inner: Exception) {
                runCatching { startForeground(notificationId, notification) }
            }
        }
    }
}
