package com.nooxe.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.nooxe.app.call.CallManager;

/** Hands a call the chat page started over to the native call screens. */
public class NooxeCallBridge {

    private final Context context;

    public NooxeCallBridge(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public boolean canCall() {
        return true;
    }

    @JavascriptInterface
    public void startCall(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        CallManager.INSTANCE.startOutgoing(context, payload);
    }
}
