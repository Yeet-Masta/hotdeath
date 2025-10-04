package com.smorgasbork.hotdeath;

import android.graphics.Color;

import java.util.Arrays;

public class ColorChooser implements Animatable{
    public static final int numSegments = 4;
    private static ColorChooser instance;

    // Animation related properties
    private int [] segmentColors;

    private float [] segmentScales;
    private boolean show;
    private long startTime;  // Start time of the animation
    private long duration; // Animation duration in milliseconds
    private int direction;
    private boolean isAnimating;  // Animation status

    private ColorChooser() {} // Private constructor

    public static synchronized ColorChooser getInstance() {
        if (instance == null) {
            instance = new ColorChooser();
            instance.segmentColors = new int[numSegments];
            instance.segmentScales = new float [numSegments];
            instance.reset();
        }
        return instance;
    }

    public void reset() {
        show = false;
        Arrays.fill(segmentScales, 0);
        Arrays.fill(segmentColors, Color.TRANSPARENT);
        this.direction = Game.DIR_NONE;
    }

    public void startAnimation(AnimationParams params) {
        this.show = params.toFaceUp;
        this.direction = params.toDirection;
        this.startTime = params.startTime;
        this.duration = params.duration;
        this.isAnimating = true;
    }

    public void update() {
        if (!isAnimating) return;

        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime >= duration) {
            elapsedTime = duration;
            isAnimating = false;
        }

        float progress = (float) elapsedTime / duration;
        for (int i = 0; i < numSegments; i++) {
            float segmentProgress = Math.min(1, Math.max(0, 2 * numSegments * progress - numSegments - i));
            segmentProgress = show ? segmentProgress : 1 - segmentProgress;
            segmentColors[i] = ((int) (255 * segmentProgress) << 24) | GameTable.getColorRgb(i + 1) & 0x00FFFFFF;
            segmentScales[i] = segmentProgress;
        }
    }

    public boolean isAnimating() {
        return isAnimating;
    }
    public int getSegmentColor(int i) {return segmentColors[i];}
    public float getSegmentScale(int i) {return segmentScales[i];}
}
