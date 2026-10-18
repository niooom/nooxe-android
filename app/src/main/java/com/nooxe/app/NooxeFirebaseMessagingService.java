package com.nooxe.app;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Receives the phone's push token and every notification Firebase sends. */
public class NooxeFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        NooxePush.saveToken(this, token);
        MainActivity.deliverPushToken(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        String title = data.get("title");
        String body = data.get("body");
        NooxePush.showMessage(
                this,
                title == null || title.isEmpty() ? "Nooxe" : title,
                body == null || body.isEmpty() ? "নতুন মেসেজ এসেছে" : body,
                data.get("chat_id"));
    }
}
