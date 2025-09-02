package com.smorgasbork.hotdeath;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class AnimationManager {
    private final GameTable gameTable;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable animRunnable;
    private final List<Card> animatingCards = new ArrayList<>();

    public AnimationManager(GameTable gameTable) {
        this.gameTable = gameTable;
    }

    public void startCardAnimation(Card card) {
        handler.post(() -> {
            animatingCards.add(card);

            if (animRunnable == null) {
                animRunnable = new Runnable() {
                    @Override
                    public void run() {
                        boolean shouldContinue = false;
                        for (Card card : animatingCards) {
                            if (card.isAnimating()) {
                                card.updatePosition();
                                shouldContinue = true;
                            }
    //                        else {
    //                            Log.d("Animation", "removing card " + + card.getID());
    //                            animatingCards.remove(card);
    //                        }
                        }
                        gameTable.postInvalidate(); // Request redraw on GameTable
                        if (shouldContinue) {
                            handler.postDelayed(this, 16); // Aim for ~60fps
                        } else {
                            animatingCards.clear(); // Clear finished animations
                            animRunnable = null; // allow future animations
                        }
                    }
                };
            }
            handler.post(animRunnable);
        });
    }

    public List<Card> getAnimatingCards() {
        return animatingCards;
    }
}
