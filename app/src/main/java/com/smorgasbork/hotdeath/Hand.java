package com.smorgasbork.hotdeath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import android.os.Build;
import org.json.*;

/**
 * Represents a player's hand.  Cards are stored in a single array; the first
 * {@code numCardsOnTable} entries are "table" cards (face-up, played onto the
 * table under Glasnost / face-up rules), and the remainder are "held" cards.
 */
public class Hand {

	private final Player m_player;
	private Card[]  m_cards;
	private int     m_numCards;
	private int     m_numCardsOnTable;
	private boolean faceUp;

	// ── constructors ──────────────────────────────────────────────────────────

	public Hand(Player player, boolean faceUp) {
		m_player          = player;
		m_cards           = new Card[Game.MAX_NUM_CARDS];
		m_numCards        = 0;
		m_numCardsOnTable = 0;
		this.faceUp       = faceUp;
	}

	/** Restore a hand from a saved JSON snapshot. */
	public Hand(JSONObject o, Player player, CardDeck deck, GameOptions go) throws JSONException {
		this(player, go.getFaceUp());
		if (player instanceof HumanPlayer) faceUp = true;

		JSONArray cards         = o.getJSONArray("cards");
		int       numOnTable    = o.getInt("numCardsOnTable");

		for (int i = 0; i < cards.length(); i++) {
			Card c = deck.getCard(cards.getInt(i));
			if (i < numOnTable) m_numCardsOnTable++;
			addCard(c, true);
			c.setState(Card.CardState.HAND);
		}
	}

	// ── accessors ─────────────────────────────────────────────────────────────

	Card[]     getCards()    { return m_cards; }
	int        getNumCards() { return m_numCards; }
	public boolean isFaceUp()     { return faceUp; }

	Card getCard(int i) {
		return (i >= 0 && i < m_numCards) ? m_cards[i] : null;
	}

	public List<Card> getTableCards() {
		List<Card> list = new ArrayList<>(m_numCardsOnTable);
		for (int i = 0; i < m_numCardsOnTable; i++) list.add(m_cards[i]);
		return list;
	}

	public List<Card> getHeldCards() {
		List<Card> list = new ArrayList<>(m_numCards - m_numCardsOnTable);
		for (int i = m_numCardsOnTable; i < m_numCards; i++) list.add(m_cards[i]);
		return list;
	}

	// ── mutation ──────────────────────────────────────────────────────────────

	public void reset() {
		for (int i = 0; i < m_numCards; i++) m_cards[i].setHand(null);
		m_cards           = new Card[Game.MAX_NUM_CARDS];
		m_numCards        = 0;
		m_numCardsOnTable = 0;
	}

	public void addCard(Card c, boolean instant) {
		// Check if the player is currently in Glasnost mode:
		// If they have cards and all of them are currently on the table.
		boolean isGlasnostActive = (m_numCards > 0 && m_numCards == m_numCardsOnTable);

		m_cards[m_numCards++] = c;
		c.setHand(this);

		// If Glasnost was active for the existing cards,
		// include this new card in the table count.
		if (isGlasnostActive) {
			m_numCardsOnTable++;
		}

		if (faceUp) sort();

		if (instant) {
			// Ensure the card is face-up if it's part of the table group
			c.setFaceUp(faceUp || (m_numCards <= m_numCardsOnTable));
		}
	}

	/** Swap a random held card with {@code newCard}; returns the evicted card. */
	public Card swapCard(Card newCard) {
		int idx = new Random().nextInt(m_numCards);
		Card evicted  = m_cards[idx];
		m_cards[idx]  = newCard;
		newCard.setHand(this);
		evicted.setHand(null);
		return evicted;
	}

	public void removeCard(Card c) {
		boolean found     = false;
		boolean fromTable = false;

		for (int i = 0; i < m_numCards; i++) {
			if (m_cards[i] == c) {
				found     = true;
				fromTable = (i < m_numCardsOnTable);
			}
			if (found && i < m_numCards - 1) {
				m_cards[i] = m_cards[i + 1];
			}
		}

		if (found) {
			c.setHand(null);
			m_cards[--m_numCards] = null;
			if (fromTable) m_numCardsOnTable--;
		}
	}

	// ── visibility helpers ────────────────────────────────────────────────────

	/** Reveal all held cards face-up (e.g. end of round). */
	public void reveal() {
		if (faceUp) return;
		faceUp = true;
		sort();
		GameTable gt = m_player.m_game.getGameTable();
		for (int i = m_numCardsOnTable; i < m_numCards; i++) {
			gt.moveCardToTable(m_cards[i], (i == m_numCards - 1) ? 2 : 120);
		}
	}

	/** Move all held cards to the table area one-by-one (Glasnost penalty). */
	public void placeOnTable() {
		sort();
		GameTable gt = m_player.m_game.getGameTable();
		for (int i = m_numCardsOnTable; i < m_numCards; i++) {
			Card c = m_cards[i];
			m_numCardsOnTable = i + 1;
			sort();
			gt.moveCardToTable(c, (i == m_numCards - 1) ? 2 : 120);
		}
	}

	// ── queries ───────────────────────────────────────────────────────────────

	public boolean isInHand(Card c) {
		for (int i = 0; i < m_numCards; i++) {
			if (m_cards[i] == c) return true;
		}
		return false;
	}

	public boolean isInHand(int color, int val) {
		for (int i = 0; i < m_numCards; i++) {
			if (m_cards[i].getColor() == color && m_cards[i].getValue() == val) return true;
		}
		return false;
	}

	public boolean hasColorMatch(int color) {
		for (int i = 0; i < m_numCards; i++) {
			if (m_cards[i].getColor() == color) return true;
		}
		return false;
	}

	public boolean hasValidCards(Game g) {
		for (int i = 0; i < m_numCards; i++) {
			if (g.checkCard(this, m_cards[i])) return true;
		}
		return false;
	}

	public int countSuit(int color) {
		int total = 0;
		for (int i = 0; i < m_numCards; i++) {
			if (m_cards[i].getColor() == color) total++;
		}
		return total;
	}

	public Card getLowestCard(int color) {
		Card best   = null;
		int  lowest = Integer.MAX_VALUE;
		for (int i = 0; i < m_numCards; i++) {
			Card c = m_cards[i];
			if (color > 0 && c.getColor() != color) continue;
			if (c.getValue() < lowest) { lowest = c.getValue(); best = c; }
		}
		return best;
	}

	public Card getHighestCard(int color) {
		Card best    = null;
		int  highest = Integer.MIN_VALUE;
		for (int i = 0; i < m_numCards; i++) {
			Card c = m_cards[i];
			if (color > 0 && c.getColor() != color) continue;
			if (c.getValue() > highest) { highest = c.getValue(); best = c; }
		}
		return best;
	}

	public Card getHighestNonTrump(int color) {
		Card best    = null;
		int  highest = Integer.MIN_VALUE;
		for (int i = 0; i < m_numCards; i++) {
			Card c = m_cards[i];
			if (c.getColor() == color) continue;
			if (c.getValue() > highest) { highest = c.getValue(); best = c; }
		}
		return best;
	}

	public void replaceCard(Card old, Card replacement) {
		for (int i = 0; i < m_numCards; i++) {
			if (m_cards[i] == old) { m_cards[i] = replacement; return; }
		}
	}

	// ── sorting ───────────────────────────────────────────────────────────────

	public void sort() {
		List<Card> table = getTableCards();
		List<Card> held  = getHeldCards();
		sortByDeckIndex(table);
		sortByDeckIndex(held);
		copyBack(table, 0);
		copyBack(held,  m_numCardsOnTable);
	}

	private static void sortByDeckIndex(List<Card> cards) {
		Collections.sort(cards, Comparator.comparingInt(Card::getDeckIndex));
    }

	private void copyBack(List<Card> src, int offset) {
		for (int i = 0; i < src.size(); i++) m_cards[offset + i] = src.get(i);
	}

	// ── score calculation ─────────────────────────────────────────────────────

	public int calculateValue()                             { return calculateValue(false, null); }
	public int calculateValue(boolean isFinal)              { return calculateValue(isFinal, null); }

	/**
	 * Calculates the point value of this hand according to the Hot Death rules.
	 *
	 * @param isFinal    true when the round has ended (virus penalties are locked in).
	 * @param withoutCard if non-null, pretend this card is not in the hand (AI look-ahead).
	 */
	public int calculateValue(boolean isFinal, Card withoutCard) {
		int effectiveCount = m_numCards - (withoutCard != null ? 1 : 0);
		if (effectiveCount == 0) return 0;

		// ── Step 1: locate special cards ─────────────────────────────────────
		Card cFuckYou      = null;
		Card cShitter      = null;
		Card cQuitter      = null;
		Card cMystery      = null;
		Card cBlueShield   = null;
		Card cSixtyNine    = null;
		Card cMagic5       = null;
		Card cHolyDefender = null;

		for (int i = 0; i < m_numCards; i++) {
			Card c = m_cards[i];
			if (c == withoutCard) continue;
			switch (c.getID()) {
				case Card.ID_BLUE_0_FUCK_YOU:   cFuckYou      = c; break;
				case Card.ID_YELLOW_0_SHITTER:  cShitter      = c; break;
				case Card.ID_GREEN_0_QUITTER:   cQuitter      = c; break;
			}
		}

		// ── Step 2: Full-Monty check (F.U. + Quitter = 1000) ─────────────────
		boolean bFullMonty = (cFuckYou != null && cQuitter != null);
		int     total      = 0;

		if (bFullMonty) {
			total = 1000;
			cFuckYou.setCurrentValue(500);
			cQuitter.setCurrentValue(500);
		} else if (cShitter != null) {
			cShitter.setCurrentValue(150);
		}

		// ── Step 3: sum fixed-value cards ────────────────────────────────────
		int highest    = -5;
		int highestNum = -5;

		for (int i = 0; i < m_numCards; i++) {
			Card c  = m_cards[i];
			int  id = c.getID();
			if (c == withoutCard) continue;
			if (bFullMonty && (id == Card.ID_BLUE_0_FUCK_YOU
					|| id == Card.ID_YELLOW_0_SHITTER
					|| id == Card.ID_GREEN_0_QUITTER)) continue;

			if (isFinal && id == Card.ID_GREEN_3_AIDS) {
				m_player.setVirusPenalty(m_player.getVirusPenalty() + 10);
			}

			// defer these for later steps
			switch (id) {
				case Card.ID_WILD_MYSTERY:   cMystery      = c; continue;
				case Card.ID_BLUE_2_SHIELD:  cBlueShield   = c; continue;
				case Card.ID_YELLOW_69:      cSixtyNine    = c; continue;
				case Card.ID_RED_0_HD:       cHolyDefender = c; continue;
				case Card.ID_BLUE_0_FUCK_YOU:
				case Card.ID_YELLOW_0_SHITTER: continue;
			}

			if (id == Card.ID_RED_5_MAGIC) cMagic5 = c;

			int pv = c.getPointValue();
			if (pv > highest) highest = pv;
			if (pv > 0 && pv < 10 && pv > highestNum) highestNum = pv;
			c.setCurrentValue(pv);
			total += pv;
		}

		// ── Step 4: Mystery Wild = 10× highest numeric card ──────────────────
		if (cMystery != null) {
			int pv = (highestNum > 0) ? 10 * highestNum : 10;
			if (pv > highest) highest = pv;
			cMystery.setCurrentValue(pv);
			total += pv;
		}

		// ── Step 5: Blue Shield mirrors the highest card value ────────────────
		if (cBlueShield != null) {
			cBlueShield.setCurrentValue(highest);
			total += highest;
		}

		// ── Step 6: 69 card pegs total at 69 (Magic 5 still subtracts) ────────
		if (cSixtyNine != null) {
			cSixtyNine.setCurrentValue(69 - total);
			total = 69;
			if (cMagic5 != null) {
				cMagic5.setCurrentValue(-5);
				total -= 5;
			}
		}

		// ── Step 7: F.U. doubles the total ───────────────────────────────────
		if (!bFullMonty && cFuckYou != null) {
			cFuckYou.setCurrentValue(total);
			total *= 2;
		}

		// ── Step 8: Holy Defender halves the total (rounded up) ──────────────
		if (cHolyDefender != null) {
			int halved = (total + 1) / 2;
			cHolyDefender.setCurrentValue(halved - total);
			total = halved;
		}

		// ── Step 9: Shitter penalty (mid-game estimate only) ──────────────────
		if (cShitter != null && !isFinal && total < cShitter.getCurrentValue()) {
			total = cShitter.getCurrentValue();
		}

		return total;
	}

	// ── serialization ─────────────────────────────────────────────────────────

	public JSONObject toJSON() throws JSONException {
		JSONArray cards = new JSONArray();
		for (int i = 0; i < m_numCards; i++) cards.put(m_cards[i].getDeckIndex());

		JSONObject o = new JSONObject();
		o.put("cards",            cards);
		o.put("numCardsOnTable",  m_numCardsOnTable);
		return o;
	}
}