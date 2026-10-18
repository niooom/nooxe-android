package com.nooxe.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.browser.customtabs.CustomTabsIntent;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://nooxe.base44.app/";
    private static final String AUTH_RETURN_URL = "https://nooxe.base44.app/auth-return";
    private static final String AUTH_PATH_PREFIX = "/api/apps/auth";
    private static final String DEEP_LINK_SCHEME = "nooxe";
    private static final String DEEP_LINK_HOST = "auth";
    // A plain Chrome user agent: Google refuses to show its sign-in pages to
    // embedded webviews (the "wv" marker in the default one), and this keeps any
    // Google page that still lands in the webview usable.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int MIC_REQUEST = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean clearHistoryAfterLoad = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        }

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(USER_AGENT);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            // A finished sign-in is a fresh start, so the sign-in page is dropped
            // from the back stack instead of showing up after login.
            @Override
            public void onPageFinished(WebView view, String url) {
                if (clearHistoryAfterLoad) {
                    clearHistoryAfterLoad = false;
                    view.clearHistory();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (Exception error) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(webView);

        if (!openAuthReturn(getIntent() == null ? null : getIntent().getData())) {
            webView.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openAuthReturn(intent == null ? null : intent.getData());
    }

    // Provider sign-in only works in a real browser, so anything that starts one
    // is handed to a Chrome Custom Tab; every other link stays in the webview.
    private boolean handleNavigation(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        String path = uri.getPath() == null ? "" : uri.getPath();

        if (DEEP_LINK_SCHEME.equals(scheme) && DEEP_LINK_HOST.equals(host)) {
            return openAuthReturn(uri);
        }

        if (isGoogleSignInHost(host)
                || (path.startsWith(AUTH_PATH_PREFIX) && path.endsWith("/login"))) {
            openInCustomTab(withAuthReturn(uri));
            return true;
        }

        return false;
    }

    private boolean isGoogleSignInHost(String host) {
        return "accounts.google.com".equals(host)
                || "accounts.youtube.com".equals(host)
                || "content-accounts.google.com".equals(host);
    }

    // Points the sign-in's from_url at the page that hands the finished session
    // back to this app, so it does not get stranded inside the browser.
    private Uri withAuthReturn(Uri uri) {
        try {
            Uri.Builder builder = uri.buildUpon().clearQuery();
            boolean replaced = false;
            for (String name : uri.getQueryParameterNames()) {
                if ("from_url".equals(name)) {
                    replaced = true;
                    builder.appendQueryParameter(name, AUTH_RETURN_URL);
                } else {
                    for (String value : uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value);
                    }
                }
            }
            if (!replaced && uri.getPath() != null && uri.getPath().startsWith(AUTH_PATH_PREFIX)) {
                builder.appendQueryParameter("from_url", AUTH_RETURN_URL);
            }
            return builder.build();
        } catch (Exception error) {
            return uri;
        }
    }

    private void openInCustomTab(Uri uri) {
        try {
            new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri);
        } catch (ActivityNotFoundException error) {
            openInBrowser(uri);
        }
    }

    private void openInBrowser(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            webView.loadUrl(uri.toString());
        }
    }

    // Takes the session out of the "nooxe://auth?access_token=…" link Chrome hands
    // over and loads it into the webview, where the app stores it and signs in.
    private boolean openAuthReturn(Uri data) {
        if (data == null
                || !DEEP_LINK_SCHEME.equals(lower(data.getScheme()))
                || !DEEP_LINK_HOST.equals(lower(data.getHost()))) {
            return false;
        }
        String token = data.getQueryParameter("access_token");
        if (token == null && data.getFragment() != null) {
            token = Uri.parse("https://nooxe.local/?" + data.getFragment()).getQueryParameter("access_token");
        }
        if (token == null || token.isEmpty()) {
            return false;
        }
        Uri.Builder target = Uri.parse(APP_URL).buildUpon().appendQueryParameter("access_token", token);
        String isNewUser = data.getQueryParameter("is_new_user");
        if (isNewUser != null && !isNewUser.isEmpty()) {
            target.appendQueryParameter("is_new_user", isNewUser);
        }
        clearHistoryAfterLoad = true;
        webView.loadUrl(target.build().toString());
        return true;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
