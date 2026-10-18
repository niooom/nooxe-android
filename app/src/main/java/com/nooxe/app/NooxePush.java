package com.nooxe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** Device token storage and the message notifications the app shows. */
public final class NooxePush {

    public static final String CHANNEL_ID = "nooxe_messages";
    public static final String EXTRA_CHAT_ID = "chat_id";

    private static final String PREFS = "nooxe_push";
    private static final String KEY_TOKEN = "fcm_token";
    private static final int NOTIFICATION_ID = 4401;

    private NooxePush() {
    }

    public static void saveToken(Context context, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        prefs(context).edit().putString(KEY_TOKEN, token).apply();
    }

    public static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, null);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Shows a new-message notification that opens that chat when tapped. */
    public static void showMessage(Context context, String title, String body, String chatId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "মেসেজ", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("নতুন মেসেজের নোটিফিকেশন");
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (chatId != null && !chatId.isEmpty()) {
            open.putExtra(EXTRA_CHAT_ID, chatId);
        }
        PendingIntent pending = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pending);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        manager.notify(NOTIFICATION_ID, builder.build());
    }
}
