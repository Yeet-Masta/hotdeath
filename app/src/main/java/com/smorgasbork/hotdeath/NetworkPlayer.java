package com.smorgasbork.hotdeath;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Represents a remote human player in a multiplayer game.
 *
 * <p>All decision methods (startTurn, chooseColor, chooseVictim, getNumCardsToDeal)
 * block the game thread on an action queue until the corresponding action message
 * arrives via the WebSocket relay.
 *
 * <p>{@link MultiplayerSession} calls {@link #receiveAction(JSONObject)} from the
 * main thread whenever a RELAYED message is decoded for this seat.
 */
public class NetworkPlayer extends Player {

    private static final String TAG              = "HDU-NP";
    private static final long   ACTION_TIMEOUT_S = 120; // 2-minute timeout per action

    /** Single queue for all incoming actions — they always arrive in game order. */
    private final BlockingQueue<JSONObject> m_actionQueue = new LinkedBlockingQueue<>();
    private volatile boolean m_disconnected = false;

    // ── constructor ───────────────────────────────────────────────────────────

    public NetworkPlayer(Game game, GameOptions go) {
        super(game, go);
    }

    // ── message delivery (called from main/WebSocket thread) ──────────────────

    /** Enqueue an incoming action so the blocking game thread can consume it. */
    public void receiveAction(JSONObject action) {
        m_actionQueue.offer(action);
    }

    /** Called when this seat's connection drops mid-game. */
    public void onDisconnected() {
        m_disconnected = true;
        // Unblock any waiting game thread with a sentinel.
        try {
            JSONObject sentinel = new JSONObject();
            sentinel.put("action", "DISCONNECT");
            m_actionQueue.offer(sentinel);
        } catch (JSONException ignored) {}
    }

    // ── Player overrides ──────────────────────────────────────────────────────

    /**
     * Blocks the game thread until this remote player's turn action arrives:
     * PLAY_CARD, DRAW_CARD, or PASS.
     */
    @Override
    public boolean startTurn() {
        // Reset flags exactly as the base class would.
        m_wantsToDraw     = false;
        m_wantsToPass     = false;
        m_wantsToPlayCard = false;

        // Handle under-penalty case (no valid cards → must pass).
        if (m_game.getPenalty().getType() != Penalty.PENTYPE_NONE
                && !m_hand.hasValidCards(m_game)) {
            m_game.waitABit();
            m_wantsToPass = true;
            return false;
        }

        JSONObject action = pollAction("turn");
        if (action == null) return false;

        String type = action.optString("action", "");
        switch (type) {
            case "PLAY_CARD": {
                int deckIndex = action.optInt("card_index", -1);
                Card c = findCardByDeckIndex(deckIndex);
                if (c != null) {
                    m_playingCard     = c;
                    m_wantsToPlayCard = true;
                } else {
                    Log.w(TAG, "PLAY_CARD: card index " + deckIndex + " not found in hand");
                    m_wantsToDraw = true;
                }
                break;
            }
            case "DRAW_CARD":
                m_wantsToDraw = true;
                break;
            case "PASS":
                m_wantsToPass = true;
                break;
            case "DISCONNECT":
                m_wantsToPass = true;
                return false;
            default:
                Log.w(TAG, "unexpected turn action: " + type);
                m_wantsToDraw = true;
        }
        return true;
    }

    /** Blocks until a CHOOSE_COLOR action arrives from this player. */
    @Override
    public int chooseColor() {
        m_game.waitABit();
        JSONObject action = pollAction("color");
        if (action == null) return Card.COLOR_RED;
        m_chosenColor = action.optInt("color", Card.COLOR_RED);
        return m_chosenColor;
    }

    /** Blocks until a CHOOSE_VICTIM action arrives from this player. */
    @Override
    public void chooseVictim() {
        m_game.waitABit();
        JSONObject action = pollAction("victim");
        if (action == null) return;
        m_chosenVictim = action.optInt("victim", 0);
    }

    /**
     * Blocks until a CHOOSE_NUM_CARDS action arrives from this player.
     * Only called when this remote player is the dealer.
     */
    @Override
    public int getNumCardsToDeal() {
        JSONObject action = pollAction("num_cards");
        if (action == null) return 7;
        m_numCardsToDeal = action.optInt("num_cards", 7);
        return m_numCardsToDeal;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Block until an action is available or the timeout expires.
     *
     * @param context descriptive label for logging
     */
    private JSONObject pollAction(String context) {
        try {
            while (!m_disconnected && !m_game.getStopping()) {
                JSONObject action = m_actionQueue.poll(5, TimeUnit.SECONDS);
                if (action != null) {
                    Log.d(TAG, "seat " + m_seat + " action[" + context + "]: " + action);
                    return action;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Find a card in this player's hand by its deck index. */
    private Card findCardByDeckIndex(int deckIndex) {
        for (int i = 0; i < m_hand.getNumCards(); i++) {
            if (m_hand.getCard(i).getDeckIndex() == deckIndex) {
                return m_hand.getCard(i);
            }
        }
        return null;
    }
}
