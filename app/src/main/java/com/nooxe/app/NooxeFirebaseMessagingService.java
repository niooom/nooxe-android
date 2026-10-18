package com.nooxe.app;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import com.nooxe.app.call.CallManager;

import java.util.Map;

/** Receives the phone's push token, incoming calls and message notifications. */
public class NooxeFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        NooxePush.saveToken(this, token);
        MainActivity.deliverPushToken(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();

        if ("call".equals(data.get("type"))) {
            String event = data.get("event");
            if (event == null || "incoming".equals(event)) {
                CallManager.INSTANCE.handleIncoming(this, data);
            } else {
                CallManager.INSTANCE.handleCallEvent(this, event, data.get("call_id"));
            }
            return;
        }

        String title = data.get("title");
        String body = data.get("body");
        NooxePush.showMessage(
                this,
                title == null || title.isEmpty() ? "Nooxe" : title,
                body == null || body.isEmpty() ? "নতুন মেসেজ এসেছে" : body,
                data.get("chat_id"));
    }
}
