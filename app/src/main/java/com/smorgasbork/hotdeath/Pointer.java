package com.smorgasbork.hotdeath;

import android.util.Log;

public class Pointer implements Animatable{
    private static Pointer instance;

    // Animation related properties
    private float rot = 0;
    private float startRot; // Starting rotation about the X-axis
    private float targetRot; // Target rotation about the X-axis
    private long startTime;  // Start time of the animation
    private long duration; // Animation duration in milliseconds
    private boolean isAnimating;  // Animation status

    private Pointer() {} // Private constructor

    public static synchronized Pointer getInstance() {
        if (instance == null) {
            instance = new Pointer();
        }
        return instance;
    }

    public void startAnimation(AnimationParams params) {
        this.startRot = this.rot;
        this.targetRot = params.toRot;
        if (params.toDirection && this.targetRot <= this.startRot)
        {
            this.targetRot += 360;
        } else if (!params.toDirection && this.targetRot >= this.startRot) {
            this.targetRot -= 360;
        }
        this.startTime = params.startTime;
        this.duration = params.duration;
        this.isAnimating = true;
    }

    public void update() {
        if (!isAnimating) return;

        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime >= duration) {
            rot = (targetRot + 360) % 360;
            isAnimating = false;
            return;
        }

        float progress = (float) elapsedTime / duration;
        this.rot = startRot + progress * (targetRot - startRot);
    }

    public boolean isAnimating() {
        return isAnimating;
    }

    public float getRot() { return rot;}
}
