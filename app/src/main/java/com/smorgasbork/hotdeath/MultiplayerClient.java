package com.smorgasbork.hotdeath;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Thin wrapper around OkHttp's WebSocket client.
 *
 * <ul>
 *   <li>All listener callbacks are delivered on the main thread.</li>
 *   <li>{@link #send(JSONObject)} is safe to call from any thread.</li>
 *   <li>Call {@link #connect(String)} once; call {@link #disconnect()} to close.</li>
 * </ul>
 */
public class MultiplayerClient {

    public interface Listener {
        void onConnected();
        void onMessage(JSONObject msg);
        void onDisconnected(String reason);
        void onError(String error);
    }

    private static final String TAG = "HDU-MP";

    private final Handler      m_mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient m_http;
    private       WebSocket    m_ws;
    private       Listener     m_listener;
    private volatile boolean   m_connected = false;

    public MultiplayerClient() {
        m_http = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0,  TimeUnit.SECONDS) // no read timeout for persistent connection
                .build();
    }

    public void setListener(Listener listener) {
        m_listener = listener;
    }

    /** Opens a WebSocket connection to {@code url} (e.g. {@code ws://192.168.1.1:30000}). */
    public void connect(String url) {
        Request request = new Request.Builder().url(url).build();
        m_ws = m_http.newWebSocket(request, new WsListener());
    }

    /** Sends a JSON message.  No-op if not connected. */
    public void send(JSONObject msg) {
        if (m_ws != null && m_connected) {
            boolean ok = m_ws.send(msg.toString());
            if (!ok) {
                Log.w(TAG, "send() returned false — connection may be closed");
            }
        }
    }

    /** Sends a raw string message. */
    public void sendRaw(String text) {
        if (m_ws != null && m_connected) {
            m_ws.send(text);
        }
    }

    /** Gracefully closes the connection. */
    public void disconnect() {
        if (m_ws != null) {
            m_ws.close(1000, "bye");
            m_ws = null;
        }
        m_connected = false;
    }

    public boolean isConnected() {
        return m_connected;
    }

    // ── OkHttp WebSocket listener ─────────────────────────────────────────────

    private class WsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            m_connected = true;
            Log.d(TAG, "WebSocket opened");
            m_mainHandler.post(() -> {
                if (m_listener != null) m_listener.onConnected();
            });
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            Log.d(TAG, "← " + text);
            try {
                JSONObject msg = new JSONObject(text);
                m_mainHandler.post(() -> {
                    if (m_listener != null) m_listener.onMessage(msg);
                });
            } catch (Exception e) {
                Log.w(TAG, "bad JSON: " + text, e);
            }
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            onMessage(ws, bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            m_connected = false;
            Log.d(TAG, "WebSocket closed: " + reason);
            m_mainHandler.post(() -> {
                if (m_listener != null) m_listener.onDisconnected(reason);
            });
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            m_connected = false;
            String msg = t.getMessage() != null ? t.getMessage() : "Connection failed";
            Log.e(TAG, "WebSocket error: " + msg, t);
            m_mainHandler.post(() -> {
                if (m_listener != null) m_listener.onError(msg);
            });
        }
    }
}
