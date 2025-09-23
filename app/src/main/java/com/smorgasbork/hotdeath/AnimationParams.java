package com.smorgasbork.hotdeath;

import android.graphics.Color;

public class AnimationParams {
    public float toX = Float.NaN;
    public float toY = Float.NaN;
    public float toRot = Float.NaN;
    public Boolean toFaceUp = null;
    public int toColor = 0;
    public boolean toDirection = false;
    public Long duration = null;
    public Long startTime = null;

    // Setters for each parameter
    public AnimationParams setCardParams(float toX, float toY, float toRot, boolean toFaceUp, long startTime, long duration)
    {
        this.toX = toX;
        this.toY = toY;
        this.toRot = toRot;
        this.toFaceUp = toFaceUp;
        this.startTime = startTime != 0 ? startTime: System.currentTimeMillis();
        this.duration = duration;
        return this;
    }
    public AnimationParams setPointerParams(float toRot, boolean toDirection, long startTime, long duration)
    {
        this.toRot = toRot;
        this.toDirection = toDirection;
        this.startTime = startTime != 0 ? startTime: System.currentTimeMillis();
        this.duration = duration;
        return this;
    }

    public AnimationParams setDirectionIndicatorParams(boolean toDirection, int toColor, long startTime, long duration)
    {
        this.toDirection = toDirection;
        this.toColor = toColor;
        this.startTime = startTime != 0 ? startTime: System.currentTimeMillis();
        this.duration = duration;
        return this;
    }
}
