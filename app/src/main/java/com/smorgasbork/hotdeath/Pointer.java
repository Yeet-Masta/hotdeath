package com.smorgasbork.hotdeath;

import android.util.Log;

public class Pointer implements Animatable{
    private static Pointer instance;

    // Animation related properties
    private float rot = 0;

    private float scale = 1;
    private boolean jump = false;
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
        this.startTime = params.startTime;
        this.duration = params.duration;
        this.startRot = this.rot;
        this.targetRot = params.toRot;
        this.scale = 1;
        this.jump = false;
        if (params.toDirection == Game.DIR_NONE) {
          this.jump = true;
        } else if (params.toDirection == Game.DIR_CLOCKWISE && this.targetRot <= this.startRot)
        {
            this.targetRot += 360;
        } else if (params.toDirection == Game.DIR_CCLOCKWISE && this.targetRot >= this.startRot) {
            this.targetRot -= 360;
        }

        this.isAnimating = true;
    }

    public void update() {
        if (!isAnimating) return;

        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime >= duration) {
            rot = (targetRot + 360) % 360;
            scale = 1;
            isAnimating = false;
            return;
        }

        float progress = (float) elapsedTime / duration;
        if (jump) {
            rot = progress < 0.5 ? startRot : targetRot;
            this.scale = Math.abs(1 - 2 * progress);
        } else {
            this.rot = startRot + progress * (targetRot - startRot);
        }
    }

    public boolean isAnimating() {
        return isAnimating;
    }

    public float getRot() { return rot;}

    public float getScale() {return scale;}

    public void setRot(float rot) {
        this.rot = rot;
    }
}
