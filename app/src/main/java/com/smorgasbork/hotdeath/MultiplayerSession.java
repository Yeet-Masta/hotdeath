package com.smorgasbork.hotdeath;

import android.app.Activity;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Singleton that represents the current online game session.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Created in {@link MultiplayerActivity} after the player enters the lobby.</li>
 *   <li>Destroyed when the user returns to the main menu.</li>
 * </ol>
 *
 * <p>The session routes incoming WebSocket messages to:
 * <ul>
 *   <li>Lobby callbacks (via {@link LobbyListener}) while the game has not started.</li>
 *   <li>Appropriate {@link NetworkPlayer} instances once the game is running.</li>
 * </ul>
 */
public class MultiplayerSession implements MultiplayerClient.Listener {

    // ── singleton ─────────────────────────────────────────────────────────────

    private static volatile MultiplayerSession s_instance;

    public static MultiplayerSession getInstance() { return s_instance; }

    /** Create and install a new session. Destroys any previous session. */
    public static MultiplayerSession create(MultiplayerClient client) {
        if (s_instance != null) s_instance.destroy();
        s_instance = new MultiplayerSession(client);
        return s_instance;
    }

    // ── listener interface used by MultiplayerActivity ────────────────────────

    public interface LobbyListener {
        void onConnected();
        void onRoomCreated(String code, int seat);
        void onRoomJoined(String code, int seat, Map<Integer, String> players);
        void onPlayerListUpdated(Map<Integer, String> players);
        void onPlayerLeft(int seat);
        void onGameStarting(long seed);
        void onDisconnected(String reason);
        void onError(String message);
    }

    // ── fields ────────────────────────────────────────────────────────────────

    private static final String TAG      = "HDU-Session";
    private static final long   SEED_TIMEOUT_S = 30;

    private final MultiplayerClient        m_client;
    private       LobbyListener            m_lobbyListener;
    private       Activity                 m_activity; // for runOnUiThread

    // Set after CREATE/JOIN response
    private String                m_roomCode  = "";
    private int                   m_localSeat = 0;   // 1-based
    private boolean               m_isHost    = false;
    private final Map<Integer, String> m_players = new HashMap<>(); // seat → name

    // Populated by Game (after game starts)
    private final NetworkPlayer[] m_networkPlayers = new NetworkPlayer[5]; // index 1-4

    // For deterministic deck shuffle across clients
    private final BlockingQueue<Long> m_seedQueue = new LinkedBlockingQueue<>();

    // ── construction / teardown ───────────────────────────────────────────────

    private MultiplayerSession(MultiplayerClient client) {
        m_client = client;
        m_client.setListener(this);
    }

    /** Disconnect and nullify the singleton. */
    public void destroy() {
        m_client.disconnect();
        s_instance = null;
    }

    // ── lobby API ─────────────────────────────────────────────────────────────

    public void setLobbyListener(LobbyListener l)  { m_lobbyListener = l; }
    public void setActivity(Activity a)            { m_activity = a; }

    public void connect(String url) {
        m_client.connect(url);
    }

    /** Host: create a new room with the given display name. */
    public void createRoom(String playerName) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "CREATE");
            msg.put("name", playerName);
            m_client.send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "createRoom", e);
        }
    }

    /** Guest: join an existing room. */
    public void joinRoom(String playerName, String roomCode) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "JOIN");
            msg.put("name", playerName);
            msg.put("room", roomCode.toUpperCase());
            m_client.send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "joinRoom", e);
        }
    }

    /**
     * Host-only: start the game. Generates a shuffle seed, broadcasts GAME_START to
     * other players, then notifies the lobby listener so the host can launch GameActivity.
     */
    public void hostStartGame() {
        if (!m_isHost) return;
        long seed = new Random().nextLong();
        // Relay GAME_START to all other players.
        sendAction(buildPayload("GAME_START", "seed", seed));
        // Notify our own lobby listener so the host also transitions.
        notifyGameStarting(seed);
    }

    // ── game API ──────────────────────────────────────────────────────────────

    /** Called by Game to register NetworkPlayer instances after construction. */
    public void registerNetworkPlayer(int seat, NetworkPlayer np) {
        if (seat >= 1 && seat <= 4) m_networkPlayers[seat] = np;
    }

    /**
     * Send a game action (called by HumanPlayer when making a decision).
     * The server relays it to all other players, who then unblock their NetworkPlayer.
     */
    public void sendAction(JSONObject payload) {
        try {
            JSONObject relay = new JSONObject();
            relay.put("type",    "RELAY");
            relay.put("room",    m_roomCode);
            relay.put("payload", payload.toString());
            m_client.send(relay);
        } catch (JSONException e) {
            Log.e(TAG, "sendAction", e);
        }
    }

    /**
     * Host: generate a round seed, broadcast it, and return it immediately.
     * Called from the Game thread.
     */
    public long generateAndBroadcastRoundSeed() {
        long seed = new Random().nextLong();
        sendAction(buildPayload("ROUND_SEED", "seed", seed));
        return seed;
    }

    /**
     * Non-host: block the game thread until the host broadcasts a ROUND_SEED.
     * Returns a fallback random seed on timeout.
     */
    public long waitForRoundSeed() {
        try {
            Long seed = m_seedQueue.poll(SEED_TIMEOUT_S, TimeUnit.SECONDS);
            return (seed != null) ? seed : new Random().nextLong();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Random().nextLong();
        }
    }

    // ── accessors ─────────────────────────────────────────────────────────────

    public int               getLocalSeat()  { return m_localSeat; }
    public boolean           isHost()        { return m_isHost; }
    public String            getRoomCode()   { return m_roomCode; }
    public Map<Integer,String> getPlayers()  { return m_players; }
    public MultiplayerClient getClient()     { return m_client; }

    /** True if this seat number has a human player connected (active in this session). */
    public boolean isSeatActive(int seat) {
        return m_players.containsKey(seat);
    }

    // ── MultiplayerClient.Listener ────────────────────────────────────────────

    @Override
    public void onConnected() {
        if (m_lobbyListener != null) m_lobbyListener.onConnected();
    }

    @Override
    public void onMessage(JSONObject msg) {
        // All callbacks from MultiplayerClient arrive on the main thread.
        try {
            String type = msg.getString("type");
            switch (type) {
                case "CREATED":
                    handleCreated(msg);
                    break;
                case "JOINED":
                    handleJoined(msg);
                    break;
                case "PLAYER_LIST":
                    handlePlayerList(msg);
                    break;
                case "PLAYER_LEFT":
                    handlePlayerLeft(msg);
                    break;
                case "RELAYED":
                    handleRelayed(msg);
                    break;
                case "ERROR":
                    if (m_lobbyListener != null)
                        m_lobbyListener.onError(msg.optString("message", "Unknown error"));
                    break;
                default:
                    Log.w(TAG, "unknown server message type: " + type);
            }
        } catch (JSONException e) {
            Log.e(TAG, "onMessage parse error", e);
        }
    }

    @Override
    public void onDisconnected(String reason) {
        if (m_lobbyListener != null) m_lobbyListener.onDisconnected(reason);
    }

    @Override
    public void onError(String error) {
        if (m_lobbyListener != null) m_lobbyListener.onError(error);
    }

    // ── server message handlers ───────────────────────────────────────────────

    private void handleCreated(JSONObject msg) throws JSONException {
        m_roomCode  = msg.getString("room");
        m_localSeat = msg.getInt("seat"); // always 1 for host
        m_isHost    = true;
        parsePlayers(msg);
        if (m_lobbyListener != null)
            m_lobbyListener.onRoomCreated(m_roomCode, m_localSeat);
    }

    private void handleJoined(JSONObject msg) throws JSONException {
        m_roomCode  = msg.getString("room");
        m_localSeat = msg.getInt("seat");
        m_isHost    = false;
        parsePlayers(msg);
        if (m_lobbyListener != null)
            m_lobbyListener.onRoomJoined(m_roomCode, m_localSeat, new HashMap<>(m_players));
    }

    private void handlePlayerList(JSONObject msg) throws JSONException {
        parsePlayers(msg);
        if (m_lobbyListener != null)
            m_lobbyListener.onPlayerListUpdated(new HashMap<>(m_players));
    }

    private void handlePlayerLeft(JSONObject msg) throws JSONException {
        int seat = msg.getInt("seat");
        m_players.remove(seat);
        if (m_lobbyListener != null) m_lobbyListener.onPlayerLeft(seat);

        // If a NetworkPlayer is registered, signal it to unblock (game may be in progress).
        NetworkPlayer np = networkPlayerAt(seat);
        if (np != null) np.onDisconnected();
    }

    /**
     * Handles a RELAYED message — either a game action from another player
     * or a control action (GAME_START, ROUND_SEED).
     *
     * NOTE: game actions are dispatched on the main thread here (quick enqueue);
     * the game thread is blocked waiting in NetworkPlayer.
     */
    private void handleRelayed(JSONObject msg) throws JSONException {
        int    fromSeat    = msg.getInt("from");
        String payloadStr  = msg.getString("payload");
        JSONObject payload = new JSONObject(payloadStr);

        String action = payload.optString("action", "");

        switch (action) {
            case "GAME_START": {
                long seed = payload.getLong("seed");
                notifyGameStarting(seed);
                break;
            }
            case "ROUND_SEED": {
                long seed = payload.getLong("seed");
                m_seedQueue.offer(seed);
                break;
            }
            default: {
                // Deliver game action to the appropriate NetworkPlayer.
                NetworkPlayer np = networkPlayerAt(fromSeat);
                if (np != null) {
                    np.receiveAction(payload);
                } else {
                    Log.w(TAG, "no NetworkPlayer for seat " + fromSeat + " action=" + action);
                }
                break;
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void parsePlayers(JSONObject msg) throws JSONException {
        m_players.clear();
        org.json.JSONArray arr = msg.optJSONArray("players");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            m_players.put(p.getInt("seat"), p.getString("name"));
        }
    }

    /**
     * Called by {@link MultiplayerActivity} (non-host path) when a GAME_START
     * message arrives carrying the round seed.  Feeds the seed into the queue
     * so {@link #waitForRoundSeed()} unblocks on the game thread.
     */
    public void offerSeed(long seed) {
        m_seedQueue.offer(seed);
    }

    private void notifyGameStarting(long seed) {
        if (m_lobbyListener != null) {
            if (m_activity != null) {
                m_activity.runOnUiThread(() -> m_lobbyListener.onGameStarting(seed));
            } else {
                m_lobbyListener.onGameStarting(seed);
            }
        }
    }

    private NetworkPlayer networkPlayerAt(int seat) {
        if (seat >= 1 && seat <= 4) return m_networkPlayers[seat];
        return null;
    }

    private static JSONObject buildPayload(String action, String key, long value) {
        JSONObject p = new JSONObject();
        try {
            p.put("action", action);
            p.put(key, value);
        } catch (JSONException ignored) {}
        return p;
    }
}
