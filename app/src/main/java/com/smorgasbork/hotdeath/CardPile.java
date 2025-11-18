package com.smorgasbork.hotdeath;

import java.util.Random;
import org.json.*;

public class CardPile 
{
	private final boolean faceUp;

	private final Card.CardState cardState;
	private int m_numCards = 0;
	final private Card[] m_cards;
		
	public int getNumCards() 
	{ 
		return m_numCards; 
	}
	
	public Card getCard(int i) 
	{ 
		return m_cards[i]; 
	}
	
	public CardPile (boolean faceUp, Card.CardState cardState)
	{
		m_cards = new Card[Game.MAX_NUM_CARDS];
		this.faceUp = faceUp;
		this.cardState = cardState;
	}
	
	public void addCard(Card c, boolean instant)
	{
		m_cards[m_numCards++] = c;
		if (instant) {
			c.setState(this.cardState);
			c.setFaceUp(this.faceUp);
		}
	}


	public Card drawCard()
	{
		if (m_numCards < 1)
		{
			return null;
		}

		Card c = m_cards[m_numCards - 1];
		m_cards[m_numCards - 1] = null;
		m_numCards--;
		
		return c;
	}

	public Card pullCard(int id)
	{
		if (m_numCards < 1)
		{
			return null;
		}
		Card foundCard = null;

		for (int i = 0; i<m_numCards; i++) {
			if (m_cards[i].getID() == id)
			{
				foundCard = m_cards[i];
			}
			if (foundCard != null)
			{
				Card cnew = (i == m_numCards - 1) ? null : m_cards[i+1];
				m_cards[i] = cnew;
			}
		}
		if (foundCard != null)
		{
			m_cards[m_numCards - 1] = null;
			m_numCards--;
		}

		return foundCard;
	}
	
	public void shuffle ()
	{
		shuffle (7);
	}

	public void shuffle (int numTimes)
	{
		int i, j, k;
		Card cTemp;

		for ( i = 0; i < m_numCards; i++) {
			m_cards[i].setFaceUp(false);
		}

		Random rGen = new Random();
		
		for ( i = 0; i < numTimes; i++) 
		{
			for ( j = 0; j < m_numCards; j++) 
			{
				k = rGen.nextInt(m_numCards);

				cTemp = m_cards[j];
				m_cards[j] = m_cards[k];
				m_cards[k] = cTemp;
			}
		}
    }

	public boolean isFaceUp() {
		return faceUp;
	}

	public CardPile (JSONObject o, CardDeck d, boolean faceUp, Card.CardState cardState) throws JSONException
	{
		this(faceUp, cardState);
		
		JSONArray a = o.getJSONArray("cards");
		int numCards = a.length();
		for (int i = 0; i < numCards; i++)
		{
			this.addCard(d.getCard(a.getInt(i)), true);
		}
	}
	
	public JSONObject toJSON () throws JSONException
	{
		JSONArray a = new JSONArray ();
		for (int i = 0; i < m_numCards; i++)
		{
			Card c = m_cards[i];
			a.put(c.getDeckIndex());
		}
		
		JSONObject o = new JSONObject ();
		o.put ("cards", a);
		
		return o;
	}
}
