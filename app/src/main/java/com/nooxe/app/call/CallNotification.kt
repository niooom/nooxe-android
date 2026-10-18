package com.nooxe.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/** The ringing notification (with a full screen intent) and the in-call one. */
object CallNotification {

    const val CHANNEL_INCOMING = "nooxe_calls_incoming"
    const val CHANNEL_ONGOING = "nooxe_calls_ongoing"
    const val ID_INCOMING = 4521
    const val ID_ONGOING = 4522

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
            val incoming = NotificationChannel(CHANNEL_INCOMING, "ইনকামিং কল", NotificationManager.IMPORTANCE_HIGH)
            incoming.description = "কেউ কল করলে রিংটোনসহ জানায়"
            val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (ringtone != null) {
                incoming.setSound(
                    ringtone,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            incoming.enableVibration(true)
            incoming.vibrationPattern = longArrayOf(0L, 900L, 900L)
            incoming.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(incoming)
        }
        if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
            val ongoing = NotificationChannel(CHANNEL_ONGOING, "চলমান কল", NotificationManager.IMPORTANCE_LOW)
            ongoing.setSound(null, null)
            ongoing.enableVibration(false)
            manager.createNotificationChannel(ongoing)
        }
    }

    fun incoming(context: Context, data: CallData): Notification {
        ensureChannels(context)
        val screen = PendingIntent.getActivity(
            context, 4531, screenIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = builder(context, CHANNEL_INCOMING)
        builder.setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(peerLabel(data))
            .setContentText(if (data.isVideo()) "ইনকামিং ভিডিও কল" else "ইনকামিং অডিও কল")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH)
        }
        return builder.build()
    }

    fun ongoing(context: Context, data: CallData): Notification {
        ensureChannels(context)
        val screen = PendingIntent.getActivity(
            context, 4532, screenIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = builder(context, CHANNEL_ONGOING)
        builder.setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(peerLabel(data))
            .setContentText(if (data.isVideo()) "ভিডিও কল চলছে" else "অডিও কল চলছে")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(screen)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        return builder.build()
    }

    fun cancelIncoming(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(ID_INCOMING) }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { manager.cancel(ID_INCOMING) }
        runCatching { manager.cancel(ID_ONGOING) }
    }

    private fun peerLabel(data: CallData): String {
        return if (data.peerName.isEmpty()) "Nooxe" else data.peerName
    }

    private fun screenIntent(context: Context): Intent {
        val intent = Intent(context, CallActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return intent
    }

    private fun builder(context: Context, channel: String): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channel)
        } else {
            Notification.Builder(context)
        }
    }
}
