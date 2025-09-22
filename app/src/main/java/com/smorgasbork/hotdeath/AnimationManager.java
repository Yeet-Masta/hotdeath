package com.smorgasbork.hotdeath;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class AnimationManager {
    private final GameTable gameTable;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable animRunnable;
    private final List<Animatable> animatables = new ArrayList<>();

    public AnimationManager(GameTable gameTable) {
        this.gameTable = gameTable;
    }

    public void startAnimation(Animatable animatable, AnimationParams params) {
        handler.post(() -> {
            animatables.add(animatable);
            animatable.startAnimation(params);

            if (animRunnable == null) {
                animRunnable = new Runnable() {
                    @Override
                    public void run() {
                        boolean shouldContinue = false;
                        for (Animatable animatable : animatables) {
                            if (animatable.isAnimating()) {
                                animatable.update();
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
                            animatables.clear(); // Clear finished animations
                            animRunnable = null; // allow future animations
                        }
                    }
                };
            }
            handler.post(animRunnable);
        });
    }

    public List<Animatable> getAnimatables() {
        return animatables;
    }
}
