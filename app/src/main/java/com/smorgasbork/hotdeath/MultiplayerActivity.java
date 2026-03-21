package com.smorgasbork.hotdeath;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Map;

/**
 * Online lobby: lets players create or join a room and then launch the game.
 *
 * Layout: {@code R.layout.activity_multiplayer}
 */
public class MultiplayerActivity extends Activity
        implements MultiplayerSession.LobbyListener {

    private EditText m_etServerUrl;
    private EditText m_etPlayerName;
    private EditText m_etRoomCode;
    private Button   m_btnCreate;
    private Button   m_btnJoin;
    private Button   m_btnStart;
    private TextView m_tvRoomCode;
    private TextView m_tvStatus;
    private ListView m_lvPlayers;

    private ArrayAdapter<String> m_playerListAdapter;
    private final ArrayList<String> m_playerItems = new ArrayList<>();

    private MultiplayerSession m_session;
    private boolean m_inRoom = false;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multiplayer);

        m_etServerUrl   = findViewById(R.id.et_server_url);
        m_etPlayerName  = findViewById(R.id.et_player_name);
        m_etRoomCode    = findViewById(R.id.et_room_code);
        m_btnCreate     = findViewById(R.id.btn_create);
        m_btnJoin       = findViewById(R.id.btn_join);
        m_btnStart      = findViewById(R.id.btn_start);
        m_tvRoomCode    = findViewById(R.id.tv_room_code);
        m_tvStatus      = findViewById(R.id.tv_status);
        m_lvPlayers     = findViewById(R.id.lv_players);

        m_playerListAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, m_playerItems);
        m_lvPlayers.setAdapter(m_playerListAdapter);

        m_btnCreate.setOnClickListener(v -> onCreateClicked());
        m_btnJoin.setOnClickListener(v   -> onJoinClicked());
        m_btnStart.setOnClickListener(v  -> onStartClicked());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Destroy session only if we are NOT transitioning into the game.
        if (m_session != null && !isGameStarting) {
            m_session.setLobbyListener(null);
        }
    }

    // ── button handlers ───────────────────────────────────────────────────────

    private void onCreateClicked() {
        String url  = m_etServerUrl.getText().toString().trim();
        String name = m_etPlayerName.getText().toString().trim();
        if (TextUtils.isEmpty(url))  { toast("Enter server URL"); return; }
        if (TextUtils.isEmpty(name)) { toast("Enter your name");  return; }

        setStatus("Connecting…");
        setButtonsEnabled(false);

        MultiplayerClient client = new MultiplayerClient();
        m_session = MultiplayerSession.create(client);
        m_session.setLobbyListener(this);
        m_session.setActivity(this);
        m_session.connect(url);

        // We'll create the room once onConnected fires.
        m_pendingAction = () -> m_session.createRoom(name);
    }

    private void onJoinClicked() {
        String url  = m_etServerUrl.getText().toString().trim();
        String name = m_etPlayerName.getText().toString().trim();
        String code = m_etRoomCode.getText().toString().trim().toUpperCase();
        if (TextUtils.isEmpty(url))  { toast("Enter server URL");  return; }
        if (TextUtils.isEmpty(name)) { toast("Enter your name");   return; }
        if (TextUtils.isEmpty(code)) { toast("Enter room code");   return; }

        setStatus("Connecting…");
        setButtonsEnabled(false);

        MultiplayerClient client = new MultiplayerClient();
        m_session = MultiplayerSession.create(client);
        m_session.setLobbyListener(this);
        m_session.setActivity(this);
        m_session.connect(url);

        m_pendingAction = () -> m_session.joinRoom(name, code);
    }

    private void onStartClicked() {
        if (m_session == null || !m_session.isHost()) return;
        if (m_session.getPlayers().size() < 2) {
            toast("Need at least 2 players to start");
            return;
        }
        m_session.hostStartGame(); // broadcasts GAME_START; calls onGameStarting locally
    }

    // ── MultiplayerSession.LobbyListener ─────────────────────────────────────

    @Override
    public void onConnected() {
        setStatus("Connected — setting up room…");
        if (m_pendingAction != null) {
            m_pendingAction.run();
            m_pendingAction = null;
        }
    }

    @Override
    public void onRoomCreated(String code, int seat) {
        m_inRoom = true;
        m_tvRoomCode.setText("Room: " + code);
        setStatus("Room created!  Share this code with friends.");
        m_btnStart.setVisibility(View.VISIBLE); // host sees Start button
        refreshPlayerList(m_session.getPlayers());
    }

    @Override
    public void onRoomJoined(String code, int seat, Map<Integer, String> players) {
        m_inRoom = true;
        m_tvRoomCode.setText("Room: " + code + "  (seat " + seat + ")");
        setStatus("Joined room " + code + ".  Waiting for host to start…");
        refreshPlayerList(players);
    }

    @Override
    public void onPlayerListUpdated(Map<Integer, String> players) {
        refreshPlayerList(players);
        setStatus("Players: " + players.size() + "/4  — waiting…");
        if (m_session != null && m_session.isHost()) {
            m_btnStart.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onPlayerLeft(int seat) {
        refreshPlayerList(m_session != null ? m_session.getPlayers() : new java.util.HashMap<>());
        setStatus("A player disconnected (seat " + seat + ").");
    }

    @Override
    public void onGameStarting(long seed) {
        launchGame(seed);
    }

    @Override
    public void onDisconnected(String reason) {
        setStatus("Disconnected: " + reason);
        setButtonsEnabled(true);
        m_inRoom = false;
        m_btnStart.setVisibility(View.INVISIBLE);
        m_tvRoomCode.setText("");
        m_playerItems.clear();
        m_playerListAdapter.notifyDataSetChanged();
    }

    @Override
    public void onError(String message) {
        setStatus("Error: " + message);
        setButtonsEnabled(!m_inRoom);
        toast(message);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private Runnable m_pendingAction;
    private volatile boolean isGameStarting = false;

    private void launchGame(long seed) {
        if (m_session == null) return;
        isGameStarting = true;

        // Store the first round seed so Game.startRound() can pick it up (non-host only;
        // host generates its own seed at startRound time via generateAndBroadcastRoundSeed).
        if (!m_session.isHost()) {
            // Offer the pre-received seed so the non-host game thread dequeues it.
            // We do this by triggering a ROUND_SEED delivery through the session.
            // (The session's seedQueue is directly accessible via reflection, but we
            // instead use a dedicated method added in a thin extension here.)
            m_session.offerSeed(seed);
        }

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.STARTUP_MODE, GameActivity.STARTUP_MODE_MULTIPLAYER);
        startActivity(intent);
        // Do NOT finish() — we want back-press to work.
    }

    private void refreshPlayerList(Map<Integer, String> players) {
        m_playerItems.clear();
        for (int seat = 1; seat <= 4; seat++) {
            String name = players.get(seat);
            if (name != null) {
                String label = "Seat " + seat + ": " + name;
                if (m_session != null && seat == m_session.getLocalSeat()) label += " (you)";
                if (m_session != null && seat == 1)                        label += " 👑";
                m_playerItems.add(label);
            }
        }
        m_playerListAdapter.notifyDataSetChanged();
    }

    private void setStatus(String msg) {
        if (m_tvStatus != null) m_tvStatus.setText(msg);
    }

    private void setButtonsEnabled(boolean enabled) {
        m_btnCreate.setEnabled(enabled);
        m_btnJoin.setEnabled(enabled);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
