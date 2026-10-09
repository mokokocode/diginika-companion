package com.diginika.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Companion app for the Diginika camera: the Pi's gallery / Wigglegram Bench pages in a
 * WebView, plus a button that joins the camera's Wi-Fi.
 */
public class MainActivity extends Activity {

    // Same values as WIFI_AP_SSID / WIFI_AP_PASSWORD / WIFI_PI_IP / WIFI_HTTP_PORT in diginika_capture.py
    private static final String SSID = "Diginika-Pi";
    private static final String PASSWORD = "diginika123";
    private static final String HOST = "192.168.4.1";
    private static final int PORT = 8000;
    private static final String HOME_URL = "http://" + HOST + ":" + PORT + "/";

    private static final int CONNECT_TIMEOUT_MS = 30000;   // includes the time you take to answer Android's prompt
    private static final int MAX_AUTO_RETRIES = 2;
    private static final int REQ_PERMISSION = 1;
    private static final int REQ_FILE = 2;

    private static final int INK = Color.rgb(28, 41, 60);
    private static final int SURFACE = Color.rgb(251, 251, 249);
    private static final int YELLOW = Color.rgb(253, 200, 0);
    private static final int VIOLET = Color.rgb(67, 45, 215);
    private static final int GRAY = Color.rgb(91, 100, 112);

    private enum State {
        IDLE,          // not connected
        CONNECTING,    // waiting for Android to join the camera's Wi-Fi
        ON_REQUEST,    // joined by this app (can be disconnected here)
        ON_SYSTEM      // the phone is already on the camera's Wi-Fi through its own settings
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private ConnectivityManager cm;
    private WebView web;
    private TextView status;
    private TextView panelMsg;
    private Button connectBtn;
    private Button disconnectBtn;
    private Button panelBtn;
    private View panel;

    private State state = State.IDLE;
    private ConnectivityManager.NetworkCallback activeCb;
    private Network currentNet;
    private int autoRetries;
    private boolean detecting;
    private ValueCallback<Uri[]> chooserCb;

    // ---------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        buildUi();
        showPanel("Not connected.\nTap Connect to join the camera's Wi-Fi.", true, "Connect");
        updateUi();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTextColor(INK);
        status.setPadding(dp(12), dp(10), dp(12), dp(4));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), 0, dp(8), dp(8));
        connectBtn = makeButton(YELLOW, INK);
        disconnectBtn = makeButton(VIOLET, Color.WHITE);
        disconnectBtn.setText("Disconnect");
        row.addView(connectBtn, buttonParams());
        row.addView(disconnectBtn, buttonParams());
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Bridge(), "DiginikaApp");
        web.setWebViewClient(new CameraClient());
        web.setWebChromeClient(new FileChooserClient());
        web.setDownloadListener((url, userAgent, contentDisposition, mime, length) ->
                startDownload(url, contentDisposition, mime));
        body.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        p.setBackgroundColor(SURFACE);
        p.setPadding(dp(24), dp(24), dp(24), dp(24));
        p.setClickable(true);   // swallow taps so they don't reach the page underneath
        panelMsg = new TextView(this);
        panelMsg.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        panelMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panelMsg.setTextColor(INK);
        panelMsg.setGravity(Gravity.CENTER);
        p.addView(panelMsg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panelBtn = makeButton(YELLOW, INK);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(20);
        p.addView(panelBtn, bp);
        panel = p;
        body.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        connectBtn.setOnClickListener(v -> {
            if (state == State.CONNECTING) {
                cancelConnect();
            } else if (state != State.ON_REQUEST) {
                state = State.IDLE;   // also covers "on the phone's own Wi-Fi but the camera isn't answering"
                startConnect();
            }
        });
        disconnectBtn.setOnClickListener(v -> disconnect());
        panelBtn.setOnClickListener(v -> {
            if (state == State.ON_REQUEST || state == State.ON_SYSTEM) {
                loadCamera();
            } else if (state == State.IDLE) {
                startConnect();
            }
        });
    }

    private Button makeButton(int bg, int fg) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setBackgroundColor(bg);
        b.setTextColor(fg);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------ UI state

    private void updateUi() {
        switch (state) {
            case CONNECTING:
                status.setText("Connecting to " + SSID + "...");
                connectBtn.setText("Cancel");
                setEnabled(connectBtn, true);
                setEnabled(disconnectBtn, false);
                break;
            case ON_REQUEST:
                status.setText("Connected to camera");
                connectBtn.setText("Connected");
                setEnabled(connectBtn, false);
                setEnabled(disconnectBtn, true);
                break;
            case ON_SYSTEM:
                status.setText("Connected (phone Wi-Fi)");
                connectBtn.setText("Connect to camera");
                setEnabled(connectBtn, true);
                setEnabled(disconnectBtn, true);
                break;
            default:
                status.setText("Not connected");
                connectBtn.setText("Connect to camera");
                setEnabled(connectBtn, true);
                setEnabled(disconnectBtn, false);
                break;
        }
    }

    private void setEnabled(Button b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.4f);
    }

    private void showPanel(String msg, boolean showButton, String buttonLabel) {
        panelMsg.setText(msg);
        panelBtn.setVisibility(showButton ? View.VISIBLE : View.GONE);
        if (buttonLabel != null) {
            panelBtn.setText(buttonLabel);
        }
        panel.setVisibility(View.VISIBLE);
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // --------------------------------------------------- Wi-Fi connection

    private String neededPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void startConnect() {
        String perm = neededPermission();
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm}, REQ_PERMISSION);
            return;
        }
        doConnect();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMISSION) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            doConnect();
        } else {
            state = State.IDLE;
            updateUi();
            showPanel("Android needs this permission to join the camera's Wi-Fi.\nAllow it, then tap Retry.", true, "Retry");
        }
    }

    private void doConnect() {
        releaseQuietly();
        state = State.CONNECTING;
        updateUi();
        showPanel("Connecting to " + SSID + "...\nApprove the Android prompt if it appears.", false, null);

        final ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ui.post(() -> {
                    if (activeCb != this) {
                        return;
                    }
                    currentNet = network;
                    cm.bindProcessToNetwork(network);   // so the page and downloads go over the camera's Wi-Fi
                    state = State.ON_REQUEST;
                    updateUi();
                    loadCamera();
                });
            }

            @Override
            public void onUnavailable() {
                ui.post(() -> {
                    if (activeCb != this) {
                        return;
                    }
                    failConnect("Couldn't connect to " + SSID + ".\nYou may have declined the prompt, or the camera is off or out of range.");
                });
            }

            @Override
            public void onLost(Network network) {
                ui.post(() -> {
                    if (activeCb != this || !network.equals(currentNet)) {
                        return;
                    }
                    failConnect("Lost the connection to the camera.");
                });
            }
        };
        activeCb = cb;

        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(SSID)
                    .setWpa2Passphrase(PASSWORD)
                    .build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // the camera has no internet
                    .setNetworkSpecifier(spec)
                    .build();
            cm.requestNetwork(req, cb, CONNECT_TIMEOUT_MS);
        } catch (RuntimeException e) {
            failConnect("Couldn't start the connection:\n" + e.getMessage());
        }
    }

    private void failConnect(String msg) {
        releaseQuietly();
        state = State.IDLE;
        updateUi();
        web.stopLoading();
        showPanel(msg, true, "Retry");
    }

    private void cancelConnect() {
        releaseQuietly();
        state = State.IDLE;
        updateUi();
        showPanel("Cancelled.", true, "Connect");
    }

    private void disconnect() {
        if (state == State.ON_REQUEST) {
            releaseQuietly();
            web.stopLoading();
            web.loadUrl("about:blank");
            state = State.IDLE;
            updateUi();
            showPanel("Disconnected from the camera.", true, "Connect");
        } else if (state == State.ON_SYSTEM) {
            toast("The phone joined the camera's Wi-Fi through its own settings. Disconnect it there.");
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                // no settings screen to open
            }
        }
    }

    /** Gives the camera's Wi-Fi back to Android (the phone returns to its normal Wi-Fi or mobile data). */
    private void releaseQuietly() {
        ConnectivityManager.NetworkCallback c = activeCb;
        activeCb = null;
        currentNet = null;
        if (c != null) {
            try {
                cm.unregisterNetworkCallback(c);
            } catch (RuntimeException ignored) {
                // already released
            }
        }
        try {
            cm.bindProcessToNetwork(null);
        } catch (RuntimeException ignored) {
            // nothing bound
        }
    }

    // ------------------------------------------------------------- loading

    private void loadCamera() {
        autoRetries = 0;
        hidePanel();
        web.loadUrl(HOME_URL);
    }

    /** The camera's page didn't load. */
    private void pageFailed() {
        if (state == State.ON_REQUEST && autoRetries < MAX_AUTO_RETRIES) {
            autoRetries++;   // the Pi's server can need a moment right after the Wi-Fi comes up
            ui.postDelayed(() -> {
                if (state == State.ON_REQUEST) {
                    web.reload();
                }
            }, 1500);
            return;
        }
        if (state == State.ON_SYSTEM) {
            state = State.IDLE;
            updateUi();
            showPanel("Can't reach the camera.\nTap Connect to join its Wi-Fi.", true, "Connect");
        } else if (state == State.ON_REQUEST) {
            showPanel("Connected to the camera's Wi-Fi, but the camera app isn't answering.\nCheck that the camera is on and its app is running.", true, "Retry");
        }
    }

    /** On launch / return to the app: if the phone is already on the camera's Wi-Fi, just open the page. */
    private void detectCamera() {
        if (detecting) {
            return;
        }
        detecting = true;
        io.execute(() -> {
            boolean up = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(HOME_URL).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                up = c.getResponseCode() > 0;
                c.disconnect();
            } catch (IOException ignored) {
                // not reachable
            }
            final boolean reachable = up;
            ui.post(() -> {
                detecting = false;
                if (reachable && state == State.IDLE && activeCb == null) {
                    state = State.ON_SYSTEM;
                    updateUi();
                    loadCamera();
                }
            });
        });
    }

    private boolean isCameraUrl(Uri u) {
        return "http".equals(u.getScheme()) && HOST.equals(u.getHost()) && u.getPort() == PORT;
    }

    private class CameraClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String scheme = u.getScheme();
            if (isCameraUrl(u) || "blob".equals(scheme) || "about".equals(scheme) || "data".equals(scheme)) {
                return false;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));   // anything that isn't the camera opens in the browser
                } catch (ActivityNotFoundException ignored) {
                    // no browser installed
                }
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && isCameraUrl(request.getUrl())) {
                pageFailed();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && isCameraUrl(request.getUrl()) && response.getStatusCode() >= 500) {
                pageFailed();
            }
        }
    }

    // ------------------------------------------------------ file chooser

    private class FileChooserClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (chooserCb != null) {
                chooserCb.onReceiveValue(null);
            }
            chooserCb = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                chooserCb = null;
                callback.onReceiveValue(null);
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && chooserCb != null) {
            chooserCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            chooserCb = null;
        }
    }

    // ----------------------------------------------------------- downloads
    // The page's downloads are done here, not by Android's download manager: the download manager
    // is a system service and would not go over the camera's Wi-Fi.

    private void startDownload(String url, String contentDisposition, String mime) {
        String name = URLUtil.guessFileName(url, contentDisposition, mime);
        String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        if (url.startsWith("blob:")) {
            fetchBlob(url, name);
        } else if (isCameraUrl(Uri.parse(url))) {
            io.execute(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    if (c.getResponseCode() != 200) {
                        throw new IOException("HTTP " + c.getResponseCode());
                    }
                    try (InputStream in = c.getInputStream()) {
                        save(name, type, in);
                    } finally {
                        c.disconnect();
                    }
                } catch (IOException e) {
                    toast("Download failed: " + name + " (" + e.getMessage() + ")");
                }
            });
        } else {
            toast("Blocked a download from outside the camera.");
        }
    }

    /** Files the page builds itself (Wigglegram exports) live in the page as blob: URLs; the page hands them over. */
    private void fetchBlob(String url, String name) {
        String js = "(function(){var u=" + JSONObject.quote(url) + ",n=" + JSONObject.quote(name) + ";"
                + "fetch(u).then(function(r){return r.blob();}).then(function(b){"
                + "var f=new FileReader();"
                + "f.onloadend=function(){DiginikaApp.saveData(n,b.type||'application/octet-stream',String(f.result));};"
                + "f.readAsDataURL(b);"
                + "}).catch(function(e){DiginikaApp.fail(n,String(e));});})();";
        web.evaluateJavascript(js, null);
    }

    public class Bridge {
        @JavascriptInterface
        public void saveData(String name, String mime, String dataUrl) {
            try {
                int comma = dataUrl.indexOf(',');
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                save(name, mime, new ByteArrayInputStream(bytes));
            } catch (IOException | RuntimeException e) {
                toast("Save failed: " + name + " (" + e.getMessage() + ")");
            }
        }

        @JavascriptInterface
        public void fail(String name, String why) {
            toast("Save failed: " + name + " (" + why + ")");
        }
    }

    /** Writes a file into Downloads/Diginika (no storage permission needed on Android 10+). */
    private void save(String name, String mime, InputStream in) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Diginika");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("can't create the file");
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("can't open the file");
            }
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            getContentResolver().delete(uri, null, null);   // don't leave half a file behind
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        toast("Saved to Downloads/Diginika/" + name);
    }

    // ----------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (state == State.IDLE && activeCb == null) {
            detectCamera();
        }
    }

    @Override
    public void onBackPressed() {
        if (panel.getVisibility() != View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            releaseQuietly();   // leaving the app gives the Wi-Fi back
            io.shutdown();
            web.destroy();
        }
        super.onDestroy();
    }
}
