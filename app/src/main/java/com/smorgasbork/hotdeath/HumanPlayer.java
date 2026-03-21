package com.smorgasbork.hotdeath;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Human-controlled player.  All "decisions" are made by the UI thread setting
 * volatile flags; this player's game thread then spins on those flags.
 *
 * <p><strong>Multiplayer hook:</strong> every decision method now also broadcasts
 * the action via {@link MultiplayerSession} when a session is active.  Remote
 * clients receive these actions and unblock their corresponding {@link NetworkPlayer}.
 */
public class HumanPlayer extends Player {

    // Decision flags – written by UI thread, read by game thread.
    private volatile boolean m_turnDecision           = false;
    private volatile boolean m_colorDecision          = false;
    private volatile boolean m_victimDecision         = false;
    private volatile boolean m_numCardsToDealDecision = false;

    public HumanPlayer(Game g, GameOptions go) {
        super(g, go);
    }

    public HumanPlayer(org.json.JSONObject o, Game g, GameOptions go) throws org.json.JSONException {
        super(o, g, go);
    }

    // ── Turn lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean startTurn() {
        m_turnDecision = false;
        if (!super.startTurn()) return false;
        waitForDecision(() -> m_turnDecision);
        return true;
    }

    // ── UI callbacks – called from UI thread ──────────────────────────────────

    public void turnDecisionPass() {
        m_wantsToPass  = true;
        m_turnDecision = true;
        sendAction(new ActionBuilder("PASS").build());
    }

    public void turnDecisionDrawCard() {
        m_wantsToDraw  = true;
        m_turnDecision = true;
        sendAction(new ActionBuilder("DRAW_CARD").build());
    }

    /**
     * Attempts to play {@code c}.  If the card is not valid, a toast is shown
     * and the decision flag is left false so the spin-wait continues.
     */
    public void turnDecisionPlayCard(Card c) {
        if (!m_hand.isInHand(c)) return;

        if (m_game.checkCard(m_hand, c)) {
            m_playingCard      = c;
            m_wantsToPlayCard  = true;
            m_turnDecision     = true;
            sendAction(new ActionBuilder("PLAY_CARD")
                    .put("card_index", c.getDeckIndex())
                    .build());
        } else {
            m_game.promptUser(m_game.getString(R.string.msg_card_no_good), false);
        }
    }

    // ── Decisions that require dialog prompts ─────────────────────────────────

    @Override
    public int getNumCardsToDeal() {
        m_numCardsToDealDecision = false;
        m_game.promptForNumCardsToDeal();
        waitForDecision(() -> m_numCardsToDealDecision);
        return m_numCardsToDeal;
    }

    public void setNumCardsToDeal(int numCardsToDeal) {
        m_numCardsToDeal         = numCardsToDeal;
        m_numCardsToDealDecision = true;
        sendAction(new ActionBuilder("CHOOSE_NUM_CARDS")
                .put("num_cards", numCardsToDeal)
                .build());
    }

    @Override
    public int chooseColor() {
        m_colorDecision = false;
        m_game.promptForColor();
        waitForDecision(() -> m_colorDecision);
        return m_chosenColor;
    }

    public void setColor(int color) {
        m_chosenColor   = color;
        m_colorDecision = true;
        sendAction(new ActionBuilder("CHOOSE_COLOR")
                .put("color", color)
                .build());
    }

    @Override
    public void chooseVictim() {
        // Auto-select if only one other active player.
        int activeCount      = 0;
        int onlyActivePlayer = 0;
        for (int seat : new int[]{Game.SEAT_WEST, Game.SEAT_NORTH, Game.SEAT_EAST}) {
            if (m_game.getPlayer(seat - 1).getActive()) {
                activeCount++;
                onlyActivePlayer = seat;
            }
        }

        if (activeCount == 1) {
            m_chosenVictim   = onlyActivePlayer;
            m_victimDecision = true;
            sendAction(new ActionBuilder("CHOOSE_VICTIM")
                    .put("victim", onlyActivePlayer)
                    .build());
            return;
        }

        m_victimDecision = false;
        m_game.promptForVictim();
        waitForDecision(() -> m_victimDecision);
    }

    public void setVictim(int victim) {
        m_chosenVictim   = victim;
        m_victimDecision = true;
        sendAction(new ActionBuilder("CHOOSE_VICTIM")
                .put("victim", victim)
                .build());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** Spins (sleeping 100 ms per tick) until {@code condition} returns true or game stops. */
    private void waitForDecision(BooleanSupplier condition) {
        while (!condition.get()) {
            try { Thread.sleep(100); }
            catch (InterruptedException ignored) {}
            if (m_game.getStopping()) return;
        }
    }

    /**
     * If a {@link MultiplayerSession} is active, relay this action to remote players.
     * No-op in single-player.
     */
    private void sendAction(JSONObject action) {
        MultiplayerSession session = MultiplayerSession.getInstance();
        if (session != null) {
            session.sendAction(action);
        }
    }

    /** Minimal functional interface so we can pass lambda conditions. */
    private interface BooleanSupplier {
        boolean get();
    }

    // ── fluent JSON builder ───────────────────────────────────────────────────

    private static final class ActionBuilder {
        private final JSONObject m_obj = new JSONObject();

        ActionBuilder(String action) {
            try { m_obj.put("action", action); } catch (JSONException ignored) {}
        }

        ActionBuilder put(String key, int value) {
            try { m_obj.put(key, value); } catch (JSONException ignored) {}
            return this;
        }

        JSONObject build() { return m_obj; }
    }
}
