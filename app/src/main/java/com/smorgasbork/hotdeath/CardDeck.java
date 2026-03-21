package com.smorgasbork.hotdeath;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds and holds the card deck.
 *
 * <p>Multiplayer addition: {@link #shuffle(long)} accepts a seed so that all
 * clients produce an identical shuffle order, given the same seed.
 *
 * <p>All other public API ({@link #getCards()}, {@link #getNumCards()},
 * {@link #getCard(int)}, {@link #getCard(int, int)}) is unchanged.
 */
public class CardDeck {

    // ── inner helper ──────────────────────────────────────────────────────────

    private static final class CardDef {
        final int color, value, id, points;
        CardDef(int color, int value, int id, int points) {
            this.color  = color;
            this.value  = value;
            this.id     = id;
            this.points = points;
        }
    }

    // ── fields ────────────────────────────────────────────────────────────────

    private Card[] m_cards;
    private Card[] m_oCards;   // shuffled order
    private int    m_numCards = 0;

    // ── public API ────────────────────────────────────────────────────────────

    public Card[] getCards()    { return m_cards; }
    public int    getNumCards() { return m_numCards; }

    public Card getCard(int i) {
        return (i >= 0 && i < m_numCards) ? m_oCards[i] : null;
    }

    public Card getCard(int color, int value) {
        for (int i = 0; i < m_numCards; i++) {
            if (m_cards[i].getColor() == color && m_cards[i].getValue() == value)
                return m_cards[i];
        }
        return null;
    }

    // ── constructor ───────────────────────────────────────────────────────────

    public CardDeck(boolean standardRules, boolean oneDeck) {
        List<CardDef> defs = buildDefinitions(standardRules, oneDeck);
        m_numCards = defs.size();
        m_cards  = new Card[m_numCards];
        m_oCards = new Card[m_numCards];

        for (int i = 0; i < m_numCards; i++) {
            CardDef d = defs.get(i);
            m_cards[i]  = new Card(i, d.color, d.value, d.id, d.points);
            m_oCards[i] = m_cards[i];
        }
    }

    // ── shuffle ───────────────────────────────────────────────────────────────

    /** Standard shuffle with a fresh random seed (single-player). */
    public void shuffle() {
        for (int i = 0; i < m_numCards; i++) m_cards[i].setFaceUp(false);
        doShuffle(7, new Random());
    }

    /** Convenience overload with custom pass count. */
    public void shuffle(int numTimes) {
        for (int i = 0; i < m_numCards; i++) m_cards[i].setFaceUp(false);
        doShuffle(numTimes, new Random());
    }

    /**
     * Deterministic shuffle using the provided {@code seed}.
     *
     * <p>All clients in a multiplayer game call this method with the same seed
     * (broadcast by the host at the start of each round), guaranteeing that
     * every client works with an identical deck order.
     */
    public void shuffle(long seed) {
        for (int i = 0; i < m_numCards; i++) m_cards[i].setFaceUp(false);
        doShuffle(7, new Random(seed));
    }

    private void doShuffle(int numTimes, Random rng) {
        for (int pass = 0; pass < numTimes; pass++) {
            for (int j = 0; j < m_numCards; j++) {
                int k = rng.nextInt(m_numCards);
                Card tmp    = m_oCards[j];
                m_oCards[j] = m_oCards[k];
                m_oCards[k] = tmp;
            }
        }
    }

    // ── definition builder ────────────────────────────────────────────────────

    private static List<CardDef> buildDefinitions(boolean standardRules, boolean oneDeck) {
        List<CardDef> defs = new ArrayList<>(oneDeck ? 108 : 216);
        if (standardRules) {
            addStandardCards(defs, oneDeck ? 1 : 2);
        } else {
            addHotDeathCards(defs, oneDeck);
        }
        return defs;
    }

    // ── standard (vanilla) deck ───────────────────────────────────────────────

    private static void addStandardCards(List<CardDef> defs, int copies) {
        int[] colors = {
                Card.COLOR_RED, Card.COLOR_GREEN, Card.COLOR_BLUE, Card.COLOR_YELLOW
        };
        int[][] colorIds = {
                { Card.ID_RED_0, Card.ID_RED_1, Card.ID_RED_2, Card.ID_RED_3, Card.ID_RED_4,
                  Card.ID_RED_5, Card.ID_RED_6, Card.ID_RED_7, Card.ID_RED_8, Card.ID_RED_9,
                  Card.ID_RED_D, Card.ID_RED_S, Card.ID_RED_R },
                { Card.ID_GREEN_0, Card.ID_GREEN_1, Card.ID_GREEN_2, Card.ID_GREEN_3, Card.ID_GREEN_4,
                  Card.ID_GREEN_5, Card.ID_GREEN_6, Card.ID_GREEN_7, Card.ID_GREEN_8, Card.ID_GREEN_9,
                  Card.ID_GREEN_D, Card.ID_GREEN_S, Card.ID_GREEN_R },
                { Card.ID_BLUE_0, Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_BLUE_3, Card.ID_BLUE_4,
                  Card.ID_BLUE_5, Card.ID_BLUE_6, Card.ID_BLUE_7, Card.ID_BLUE_8, Card.ID_BLUE_9,
                  Card.ID_BLUE_D, Card.ID_BLUE_S, Card.ID_BLUE_R },
                { Card.ID_YELLOW_0, Card.ID_YELLOW_1, Card.ID_YELLOW_2, Card.ID_YELLOW_3, Card.ID_YELLOW_4,
                  Card.ID_YELLOW_5, Card.ID_YELLOW_6, Card.ID_YELLOW_7, Card.ID_YELLOW_8, Card.ID_YELLOW_9,
                  Card.ID_YELLOW_D, Card.ID_YELLOW_S, Card.ID_YELLOW_R }
        };
        int[] values = { 0,1,2,3,4,5,6,7,8,9, Card.VAL_D, Card.VAL_S, Card.VAL_R };
        int[] points = { 0,1,2,3,4,5,6,7,8,9, 20, 20, 20 };

        for (int ci = 0; ci < colors.length; ci++) {
            for (int vi = 0; vi < values.length; vi++) {
                int count = (values[vi] == 0) ? copies : copies * 2;
                for (int k = 0; k < count; k++)
                    defs.add(new CardDef(colors[ci], values[vi], colorIds[ci][vi], points[vi]));
            }
        }
        int wildCount = 4 * copies;
        for (int i = 0; i < wildCount; i++)
            defs.add(new CardDef(Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD, 50));
        for (int i = 0; i < wildCount; i++)
            defs.add(new CardDef(Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DRAW_FOUR, 50));
    }

    // ── Hot Death deck ────────────────────────────────────────────────────────

    private static final int[][] HD_CARDS = {
            // RED
            { Card.COLOR_RED, 0,              Card.ID_RED_0_HD,       0   },
            { Card.COLOR_RED, 1,              Card.ID_RED_1,          1   },
            { Card.COLOR_RED, 1,              Card.ID_RED_1,          1   },
            { Card.COLOR_RED, 2,              Card.ID_RED_2,          2   },
            { Card.COLOR_RED, 2,              Card.ID_RED_2_GLASNOST, 75  },
            { Card.COLOR_RED, 3,              Card.ID_RED_3,          3   },
            { Card.COLOR_RED, 3,              Card.ID_RED_3,          3   },
            { Card.COLOR_RED, 4,              Card.ID_RED_4,          4   },
            { Card.COLOR_RED, 4,              Card.ID_RED_4,          4   },
            { Card.COLOR_RED, 5,              Card.ID_RED_5,          5   },
            { Card.COLOR_RED, 5,              Card.ID_RED_5_MAGIC,   -5   },
            { Card.COLOR_RED, 6,              Card.ID_RED_6,          6   },
            { Card.COLOR_RED, 6,              Card.ID_RED_6,          6   },
            { Card.COLOR_RED, 7,              Card.ID_RED_7,          7   },
            { Card.COLOR_RED, 7,              Card.ID_RED_7,          7   },
            { Card.COLOR_RED, 8,              Card.ID_RED_8,          8   },
            { Card.COLOR_RED, 8,              Card.ID_RED_8,          8   },
            { Card.COLOR_RED, 9,              Card.ID_RED_9,          9   },
            { Card.COLOR_RED, 9,              Card.ID_RED_9,          9   },
            { Card.COLOR_RED, Card.VAL_D,     Card.ID_RED_D,         20  },
            { Card.COLOR_RED, Card.VAL_D_SPREAD, Card.ID_RED_D_SPREADER, 60 },
            { Card.COLOR_RED, Card.VAL_S,     Card.ID_RED_S,         20  },
            { Card.COLOR_RED, Card.VAL_S_DOUBLE, Card.ID_RED_S_DOUBLE, 40 },
            { Card.COLOR_RED, Card.VAL_R,     Card.ID_RED_R,         20  },
            { Card.COLOR_RED, Card.VAL_R_SKIP, Card.ID_RED_R_SKIP,  40  },
            // GREEN
            { Card.COLOR_GREEN, 0,            Card.ID_GREEN_0_QUITTER, 100 },
            { Card.COLOR_GREEN, 1,            Card.ID_GREEN_1,        1   },
            { Card.COLOR_GREEN, 1,            Card.ID_GREEN_1,        1   },
            { Card.COLOR_GREEN, 2,            Card.ID_GREEN_2,        2   },
            { Card.COLOR_GREEN, 2,            Card.ID_GREEN_2,        2   },
            { Card.COLOR_GREEN, 3,            Card.ID_GREEN_3,        3   },
            { Card.COLOR_GREEN, 3,            Card.ID_GREEN_3_AIDS,   3   },
            { Card.COLOR_GREEN, 4,            Card.ID_GREEN_4,        4   },
            { Card.COLOR_GREEN, 4,            Card.ID_GREEN_4_IRISH,  75  },
            { Card.COLOR_GREEN, 5,            Card.ID_GREEN_5,        5   },
            { Card.COLOR_GREEN, 5,            Card.ID_GREEN_5,        5   },
            { Card.COLOR_GREEN, 6,            Card.ID_GREEN_6,        6   },
            { Card.COLOR_GREEN, 6,            Card.ID_GREEN_6,        6   },
            { Card.COLOR_GREEN, 7,            Card.ID_GREEN_7,        7   },
            { Card.COLOR_GREEN, 7,            Card.ID_GREEN_7,        7   },
            { Card.COLOR_GREEN, 8,            Card.ID_GREEN_8,        8   },
            { Card.COLOR_GREEN, 8,            Card.ID_GREEN_8,        8   },
            { Card.COLOR_GREEN, 9,            Card.ID_GREEN_9,        9   },
            { Card.COLOR_GREEN, 9,            Card.ID_GREEN_9,        9   },
            { Card.COLOR_GREEN, Card.VAL_D,   Card.ID_GREEN_D,       20  },
            { Card.COLOR_GREEN, Card.VAL_D_SPREAD, Card.ID_GREEN_D_SPREADER, 60 },
            { Card.COLOR_GREEN, Card.VAL_S,   Card.ID_GREEN_S,       20  },
            { Card.COLOR_GREEN, Card.VAL_S_DOUBLE, Card.ID_GREEN_S_DOUBLE, 40 },
            { Card.COLOR_GREEN, Card.VAL_R,   Card.ID_GREEN_R,       20  },
            { Card.COLOR_GREEN, Card.VAL_R_SKIP, Card.ID_GREEN_R_SKIP, 40 },
            // BLUE
            { Card.COLOR_BLUE, 0,             Card.ID_BLUE_0_FUCK_YOU, 0 },
            { Card.COLOR_BLUE, 1,             Card.ID_BLUE_1,         1  },
            { Card.COLOR_BLUE, 1,             Card.ID_BLUE_1,         1  },
            { Card.COLOR_BLUE, 2,             Card.ID_BLUE_2,         2  },
            { Card.COLOR_BLUE, 2,             Card.ID_BLUE_2_SHIELD,  0  },
            { Card.COLOR_BLUE, 3,             Card.ID_BLUE_3,         3  },
            { Card.COLOR_BLUE, 3,             Card.ID_BLUE_3,         3  },
            { Card.COLOR_BLUE, 4,             Card.ID_BLUE_4,         4  },
            { Card.COLOR_BLUE, 4,             Card.ID_BLUE_4,         4  },
            { Card.COLOR_BLUE, 5,             Card.ID_BLUE_5,         5  },
            { Card.COLOR_BLUE, 5,             Card.ID_BLUE_5,         5  },
            { Card.COLOR_BLUE, 6,             Card.ID_BLUE_6,         6  },
            { Card.COLOR_BLUE, 6,             Card.ID_BLUE_6,         6  },
            { Card.COLOR_BLUE, 7,             Card.ID_BLUE_7,         7  },
            { Card.COLOR_BLUE, 7,             Card.ID_BLUE_7,         7  },
            { Card.COLOR_BLUE, 8,             Card.ID_BLUE_8,         8  },
            { Card.COLOR_BLUE, 8,             Card.ID_BLUE_8,         8  },
            { Card.COLOR_BLUE, 9,             Card.ID_BLUE_9,         9  },
            { Card.COLOR_BLUE, 9,             Card.ID_BLUE_9,         9  },
            { Card.COLOR_BLUE, Card.VAL_D,    Card.ID_BLUE_D,        20  },
            { Card.COLOR_BLUE, Card.VAL_D_SPREAD, Card.ID_BLUE_D_SPREADER, 60 },
            { Card.COLOR_BLUE, Card.VAL_S,    Card.ID_BLUE_S,        20  },
            { Card.COLOR_BLUE, Card.VAL_S_DOUBLE, Card.ID_BLUE_S_DOUBLE, 40 },
            { Card.COLOR_BLUE, Card.VAL_R,    Card.ID_BLUE_R,        20  },
            { Card.COLOR_BLUE, Card.VAL_R_SKIP, Card.ID_BLUE_R_SKIP, 40 },
            // YELLOW
            { Card.COLOR_YELLOW, 0,           Card.ID_YELLOW_0_SHITTER, 0  },
            { Card.COLOR_YELLOW, 1,           Card.ID_YELLOW_1,       1   },
            { Card.COLOR_YELLOW, 1,           Card.ID_YELLOW_1_MAD,   100 },
            { Card.COLOR_YELLOW, 2,           Card.ID_YELLOW_2,       2   },
            { Card.COLOR_YELLOW, 2,           Card.ID_YELLOW_2,       2   },
            { Card.COLOR_YELLOW, 3,           Card.ID_YELLOW_3,       3   },
            { Card.COLOR_YELLOW, 3,           Card.ID_YELLOW_3,       3   },
            { Card.COLOR_YELLOW, 4,           Card.ID_YELLOW_4,       4   },
            { Card.COLOR_YELLOW, 4,           Card.ID_YELLOW_4,       4   },
            { Card.COLOR_YELLOW, 5,           Card.ID_YELLOW_5,       5   },
            { Card.COLOR_YELLOW, 5,           Card.ID_YELLOW_5,       5   },
            { Card.COLOR_YELLOW, 6,           Card.ID_YELLOW_6,       6   },
            { Card.COLOR_YELLOW, 6,           Card.ID_YELLOW_69,      6   },
            { Card.COLOR_YELLOW, 7,           Card.ID_YELLOW_7,       7   },
            { Card.COLOR_YELLOW, 7,           Card.ID_YELLOW_7,       7   },
            { Card.COLOR_YELLOW, 8,           Card.ID_YELLOW_8,       8   },
            { Card.COLOR_YELLOW, 8,           Card.ID_YELLOW_8,       8   },
            { Card.COLOR_YELLOW, 9,           Card.ID_YELLOW_9,       9   },
            { Card.COLOR_YELLOW, 9,           Card.ID_YELLOW_9,       9   },
            { Card.COLOR_YELLOW, Card.VAL_D,  Card.ID_YELLOW_D,      20  },
            { Card.COLOR_YELLOW, Card.VAL_D_SPREAD, Card.ID_YELLOW_D_SPREADER, 60 },
            { Card.COLOR_YELLOW, Card.VAL_S,  Card.ID_YELLOW_S,      20  },
            { Card.COLOR_YELLOW, Card.VAL_S_DOUBLE, Card.ID_YELLOW_S_DOUBLE, 40 },
            { Card.COLOR_YELLOW, Card.VAL_R,  Card.ID_YELLOW_R,      20  },
            { Card.COLOR_YELLOW, Card.VAL_R_SKIP, Card.ID_YELLOW_R_SKIP, 40 },
            // WILD
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DRAW_FOUR, 50 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DRAW_FOUR, 50 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DRAW_FOUR, 50 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DRAW_FOUR, 50 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_HD,      100 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DB,      100 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_HOS,       0 },
            { Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_MYSTERY,   0 },
            // v3
            { Card.COLOR_RED,    Card.VAL_R_BACKSTAB, Card.ID_RED_R_BACKSTAB,    20 },
            { Card.COLOR_GREEN,  Card.VAL_R_BACKSTAB, Card.ID_GREEN_R_BACKSTAB,  20 },
            { Card.COLOR_BLUE,   Card.VAL_R_BACKSTAB, Card.ID_BLUE_R_BACKSTAB,   20 },
            { Card.COLOR_YELLOW, Card.VAL_R_BACKSTAB, Card.ID_YELLOW_R_BACKSTAB, 20 },
            { Card.COLOR_GREEN,  Card.VAL_CLONE, Card.ID_GREEN_2_CLONE,  20 },
            { Card.COLOR_YELLOW, Card.VAL_CLONE, Card.ID_YELLOW_2_CLONE, 20 },
            { Card.COLOR_BLUE,   Card.VAL_PING,  Card.ID_BLUE_1_PING,    1  },
            { Card.COLOR_GREEN,  Card.VAL_SWAP,  Card.ID_GREEN_R_SWAP,   20 },
            { Card.COLOR_YELLOW, Card.VAL_SWAP,  Card.ID_YELLOW_R_SWAP,  20 },
    };

    private static boolean isOncePerDeckValue(int value) {
        return value == Card.VAL_D_SPREAD
                || value == Card.VAL_S_DOUBLE
                || value == Card.VAL_R_SKIP;
    }

    private static void addHotDeathCards(List<CardDef> defs, boolean oneDeck) {
        for (int[] row : HD_CARDS)
            defs.add(new CardDef(row[0], row[1], row[2], row[3]));
        if (!oneDeck) addVanillaSecondDeck(defs);
    }

    private static void addVanillaSecondDeck(List<CardDef> defs) {
        int[] colors = {
                Card.COLOR_RED, Card.COLOR_GREEN, Card.COLOR_BLUE, Card.COLOR_YELLOW
        };
        int[][] colorIds = {
                { Card.ID_RED_0, Card.ID_RED_1, Card.ID_RED_2, Card.ID_RED_3, Card.ID_RED_4,
                  Card.ID_RED_5, Card.ID_RED_6, Card.ID_RED_7, Card.ID_RED_8, Card.ID_RED_9,
                  Card.ID_RED_D, Card.ID_RED_S, Card.ID_RED_R },
                { Card.ID_GREEN_0, Card.ID_GREEN_1, Card.ID_GREEN_2, Card.ID_GREEN_3, Card.ID_GREEN_4,
                  Card.ID_GREEN_5, Card.ID_GREEN_6, Card.ID_GREEN_7, Card.ID_GREEN_8, Card.ID_GREEN_9,
                  Card.ID_GREEN_D, Card.ID_GREEN_S, Card.ID_GREEN_R },
                { Card.ID_BLUE_0, Card.ID_BLUE_1, Card.ID_BLUE_2, Card.ID_BLUE_3, Card.ID_BLUE_4,
                  Card.ID_BLUE_5, Card.ID_BLUE_6, Card.ID_BLUE_7, Card.ID_BLUE_8, Card.ID_BLUE_9,
                  Card.ID_BLUE_D, Card.ID_BLUE_S, Card.ID_BLUE_R },
                { Card.ID_YELLOW_0, Card.ID_YELLOW_1, Card.ID_YELLOW_2, Card.ID_YELLOW_3, Card.ID_YELLOW_4,
                  Card.ID_YELLOW_5, Card.ID_YELLOW_6, Card.ID_YELLOW_7, Card.ID_YELLOW_8, Card.ID_YELLOW_9,
                  Card.ID_YELLOW_D, Card.ID_YELLOW_S, Card.ID_YELLOW_R }
        };
        int[] values = { 0,1,2,3,4,5,6,7,8,9, Card.VAL_D, Card.VAL_S, Card.VAL_R };
        int[] points = { 0,1,2,3,4,5,6,7,8,9, 20, 20, 20 };

        for (int ci = 0; ci < colors.length; ci++) {
            for (int vi = 0; vi < values.length; vi++) {
                int count = (values[vi] == 0) ? 1 : 2;
                for (int k = 0; k < count; k++)
                    defs.add(new CardDef(colors[ci], values[vi], colorIds[ci][vi], points[vi]));
            }
        }
        for (int i = 0; i < 4; i++)
            defs.add(new CardDef(Card.COLOR_WILD, Card.VAL_WILD, Card.ID_WILD, 50));
        for (int i = 0; i < 4; i++)
            defs.add(new CardDef(Card.COLOR_WILD, Card.VAL_WILD_DRAW, Card.ID_WILD_DRAW_FOUR, 50));
    }
}
