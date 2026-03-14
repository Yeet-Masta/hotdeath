package com.smorgasbork.hotdeath;

import java.util.Random;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * AI-controlled player. Skill levels:
 *   0 = Weak   – plays highest-value valid card; victim chosen naively
 *   1 = Strong  – same, but avoids wild cards while opponents still have many cards;
 *                 smarter MAD / 69 / Mystery-draw heuristics
 *   2 = Expert  – all of Strong, plus color-balance optimisation
 */
public class ComputerPlayer extends Player {

	public ComputerPlayer(Game g, GameOptions go) {
		super(g, go);
	}

	public ComputerPlayer(JSONObject o, Game g, GameOptions go) throws JSONException {
		super(o, g, go);
	}

	// -------------------------------------------------------------------------
	// Turn lifecycle
	// -------------------------------------------------------------------------

	@Override
	public boolean startTurn() {
		if (!super.startTurn()) {
			return false;
		}

		m_game.waitABit();

		if (!m_hand.hasValidCards(m_game)) {
			if (m_hasTriedDrawing) {
				m_wantsToPass = true;
			} else {
				m_wantsToDraw = true;
			}
			return false;
		}

		playCard();
		return true;
	}

	@Override
	public void addCardToHand(Card c, boolean instant) {
		super.addCardToHand(c, instant);
		readAggressionAndSkill();
	}

	@Override
	public void finishTrick() {
		readAggressionAndSkill();
	}

	// -------------------------------------------------------------------------
	// Decisions
	// -------------------------------------------------------------------------

	@Override
	public void chooseNumCardsToDeal() {
		m_numCardsToDeal = new Random().nextInt(11) + 5;
	}

	@Override
	public int chooseColor() {
		m_game.waitABit();

		if (m_hand.getNumCards() == 0) {
			return Card.COLOR_RED;
		}

		int maxCount = 0;
		int maxColor = 0;
		for (int color = Card.COLOR_RED; color < Card.COLOR_WILD; color++) {
			int cnt = m_hand.countSuit(color);
			if (cnt > maxCount) {
				maxCount = cnt;
				maxColor = color;
			}
		}

		m_chosenColor = (maxColor == 0) ? Card.COLOR_RED : maxColor;
		return m_chosenColor;
	}

	@Override
	public void chooseVictim() {
		m_game.waitABit();

		if (m_skill == 0) {
			chooseVictimWeak();
			return;
		}

		// Strong / Expert: target the player with the lowest total score,
		// adjusted by aggression towards SEAT_SOUTH.
		int minPoints = Integer.MAX_VALUE;
		int minPlayer = 0;

		for (int i = 3; i >= 0; i--) {
			Player p = m_game.getPlayer(i);
			if (p == this || !p.getActive()) continue;

			int score = p.getTotalScore();
			if (i == Game.SEAT_SOUTH - 1) {
				score -= 25 * m_aggression;
			}
			if (score < minPoints) {
				minPlayer = i + 1;
				minPoints = score;
			}
		}
		m_chosenVictim = minPlayer;
	}

	/** Weak victim selection – clockwise or counter-clockwise depending on aggression. */
	private void chooseVictimWeak() {
		int[] order = (m_aggression > 3)
				? new int[]{0, 1, 2, 3}      // clockwise from SOUTH, aggressive
				: new int[]{3, 2, 1, 0};     // counter-clockwise from EAST

		for (int i : order) {
			if (m_seat == i + 1) continue;
			if (m_game.getPlayer(i).getActive()) {
				m_chosenVictim = i + 1;
				return;
			}
		}
	}

	// -------------------------------------------------------------------------
	// Card selection
	// -------------------------------------------------------------------------

	public void playCard() {
		readAggressionAndSkill();

		m_wantsToPass = false;
		m_hand.calculateValue();

		// If we just drew, play it if legal – otherwise pass.
		if (m_lastDrawn != null) {
			if (m_game.checkCard(m_hand, m_lastDrawn)) {
				m_playingCard = m_lastDrawn;
				m_wantsToPlayCard = true;
			} else {
				m_wantsToPass = true;
			}
			return;
		}

		int opponentMinCards = getMinCardsRemaining();
		int wildCount = countCardsOfColor(Card.COLOR_WILD);

		int maxTestVal = Integer.MIN_VALUE;
		Card bestCard = null;

		for (int i = 0; i < m_hand.getNumCards(); i++) {
			Card tc = m_hand.getCard(i);
			if (!m_game.checkCard(m_hand, tc)) continue;

			// Always play a defender immediately.
			if (m_game.getLastCardCheckedIsDefender()) {
				bestCard = tc;
				break;
			}

			int testVal = scoreCandidate(tc, opponentMinCards, wildCount);

			if (testVal >= maxTestVal) {
				maxTestVal = testVal;
				bestCard = tc;
			}
		}

		if (bestCard != null) {
			m_playingCard = bestCard;
			m_wantsToPlayCard = true;
		} else {
			m_wantsToDraw = true;
		}
	}

	/**
	 * Assigns a heuristic value to playing {@code tc}. Higher = more desirable.
	 */
	private int scoreCandidate(Card tc, int opponentMinCards, int wildCount) {
		if (m_skill == 0) {
			return tc.getCurrentValue();
		}

		// Skill >= 1 (Strong / Expert)
		int testVal;
		if (tc.getColor() == Card.COLOR_WILD) {
			// Hold wilds while opponents still have many cards.
			testVal = (wildCount < opponentMinCards - 1) ? 0 : tc.getCurrentValue();
		} else {
			testVal = tc.getCurrentValue();
		}

		testVal = adjustForMad(tc, testVal);
		testVal = adjustForSixtyNine(tc, testVal);
		testVal = adjustForMysteryDraw(tc, testVal);

		if (m_skill >= 2) {
			testVal = adjustForColorBalance(tc, testVal, opponentMinCards);
		}

		return testVal;
	}

	private int adjustForMad(Card tc, int testVal) {
		if (tc.getID() != Card.ID_YELLOW_1_MAD || m_game.getActivePlayerCount() <= 3) {
			return testVal;
		}
		int newHandValue = m_hand.calculateValue(false, tc);
		if (newHandValue < 10)       return testVal + 100;
		else if (newHandValue < 20)  return testVal + 70;
		else if (newHandValue < 50)  return 0;
		else                         return testVal - 20;
	}

	private int adjustForSixtyNine(Card tc, int testVal) {
		if (tc.getID() != Card.ID_YELLOW_69) return testVal;
		int oldVal = m_hand.calculateValue();
		int newVal = m_hand.calculateValue(false, tc);
		return oldVal - newVal;
	}

	private int adjustForMysteryDraw(Card tc, int testVal) {
		if (tc.getID() != Card.ID_WILD_MYSTERY) return testVal;
		Card lpc = m_game.getLastPlayedCard();
		int lpv = lpc.getValue();
		if (lpc.getID() == Card.ID_YELLOW_69) return testVal + 200;
		if (lpv > 0) {
			if (lpv < 5)  return testVal + 15;
			if (lpv < 8)  return testVal + 30;
			if (lpv < 10) return testVal + 50;
		}
		return testVal;
	}

	private int adjustForColorBalance(Card tc, int testVal, int opponentMinCards) {
		boolean considerBalance = (opponentMinCards + m_aggression / 3) > 3;
		if (!considerBalance) return testVal;

		double improvement = computeChangeInColorBalance(tc);
		if      (improvement < -0.5) testVal += 40;
		else if (improvement < -0.25) testVal += 20;
		else if (improvement >  0.5)  testVal -= 40;
		else if (improvement >  0.25) testVal -= 20;
		return testVal;
	}

	// -------------------------------------------------------------------------
	// Color balance helpers
	// -------------------------------------------------------------------------

	public double computeChangeInColorBalance(Card c) {
		double before = computeColorBalance(null);
		double after  = computeColorBalance(c);
		if (before == 0) return 0;
		return (after - before) / before;
	}

	public double computeColorBalance(Card exclude) {
		int[] totals = new int[4];
		for (int i = 0; i < m_hand.getNumCards(); i++) {
			int color = m_hand.getCard(i).getColor();
			if (color >= Card.COLOR_RED && color <= Card.COLOR_YELLOW) {
				totals[color - Card.COLOR_RED]++;
			}
		}
		if (exclude != null) {
			int ec = exclude.getColor();
			if (ec >= Card.COLOR_RED && ec <= Card.COLOR_YELLOW) {
				totals[ec - Card.COLOR_RED]--;
			}
		}
		double avg = (totals[0] + totals[1] + totals[2] + totals[3]) / 4.0;
		double balance = 0;
		for (int t : totals) {
			balance += Math.pow(t - avg, 2);
		}
		return Math.sqrt(balance);
	}

	// -------------------------------------------------------------------------
	// Utility helpers
	// -------------------------------------------------------------------------

	/** Returns the smallest hand size among active opponents. */
	public int getMinCardsRemaining() {
		int min = Integer.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			Player p = m_game.getPlayer(i);
			if (p == this || !p.getActive()) continue;
			min = Math.min(min, p.getHand().getNumCards());
		}
		return min;
	}

	private int countCardsOfColor(int color) {
		int count = 0;
		for (int i = 0; i < m_hand.getNumCards(); i++) {
			if (m_hand.getCard(i).getColor() == color) count++;
		}
		return count;
	}

	public void readAggressionAndSkill() {
		switch (m_seat) {
			case Game.SEAT_WEST:
				m_aggression = m_go.getP1Agg();
				m_skill      = m_go.getP1Skill();
				break;
			case Game.SEAT_NORTH:
				m_aggression = m_go.getP2Agg();
				m_skill      = m_go.getP2Skill();
				break;
			case Game.SEAT_EAST:
				m_aggression = m_go.getP3Agg();
				m_skill      = m_go.getP3Skill();
				break;
			default:
				// 4th computer player (testing) mirrors player 2
				m_aggression = m_go.getP2Agg();
				m_skill      = m_go.getP2Skill();
				break;
		}
	}
}