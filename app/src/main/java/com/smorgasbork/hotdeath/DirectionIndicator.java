package com.smorgasbork.hotdeath;

import android.graphics.Color;

import java.util.Arrays;

public class DirectionIndicator implements Animatable{
    private static DirectionIndicator instance;

    // Animation related properties
    private int [] segmentColors;
    private long startTime;  // Start time of the animation
    private long duration; // Animation duration in milliseconds
    private int color;
    private int startColor;
    private int targetColor;
    private boolean direction;
    private boolean startDirection;
    private boolean targetDirection;
    private boolean isAnimating;  // Animation status

    private DirectionIndicator() {} // Private constructor

    public static synchronized DirectionIndicator getInstance() {
        if (instance == null) {
            instance = new DirectionIndicator();
            instance.segmentColors = new int[12];
        }
        return instance;
    }

    public void reset() {
        this.color = Color.TRANSPARENT;
        this.direction = true;
    }

    public void startAnimation(AnimationParams params) {
        this.startColor = this.color;
        this.targetColor = params.toColor;
        this.startDirection = this.direction;
        this.targetDirection = params.toDirection;
        this.startTime = params.startTime;
        this.duration = params.duration;
        if (targetDirection != startDirection) {
            this.duration *= 2;
        }
        this.isAnimating = true;
    }

    public void update() {
        if (!isAnimating) return;

        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime >= duration) {
            elapsedTime = duration;
            this.color = this.targetColor;
            this.direction = this.targetDirection;
            isAnimating = false;
        }

        float progress = (float) elapsedTime / duration;
        if (targetDirection != startDirection) {
            if (targetDirection != direction) {
                if (progress <= 0.5) {
                    for (int i = 0; i < 12; i++) {
                        segmentColors[i] = ((int) (255 * Math.min(1, Math.max(0, 12 - i - 24 * progress))) << 24) | (color & 0x00FFFFFF);
                    }
                } else {
                    direction = targetDirection;
                    this.color = targetColor;
                }
            }
            if (startDirection != direction) {
                for (int i = 0; i < 12; i++) {
                    segmentColors[i] = ((int) (255 * Math.min(1, Math.max(0, 24 * progress - 12 - i))) << 24) | (color & 0x00FFFFFF);
                }
            }
        }
        else if (targetColor != startColor){
            for (int i = 0; i < 12; i++) {
                segmentColors[i] = progress * 12 >= i + 1 ? this.targetColor : startColor;
            }
        }
//        this.color = this.targetColor & 0x00FFFFFF | ((int) (progress * 255) << 24);
    }

    public boolean isAnimating() {
        return isAnimating;
    }

    public boolean getDirection() {
            return direction;
    }

    public int getSegmentColor(int i) {return segmentColors[i];}
}
