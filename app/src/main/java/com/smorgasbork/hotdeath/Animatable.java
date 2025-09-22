package com.smorgasbork.hotdeath;

public interface Animatable {

    void startAnimation(AnimationParams params);
    boolean isAnimating();
    void update();
    // Add any other animation-related methods here
}
