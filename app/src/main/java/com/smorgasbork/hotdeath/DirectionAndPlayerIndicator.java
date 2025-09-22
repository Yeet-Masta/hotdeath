package com.smorgasbork.hotdeath;

public class DirectionAndPlayerIndicator implements Animatable{
    private static DirectionAndPlayerIndicator instance;

    // Animation related properties
    private float circleRot = 0;  // current Rotation about the X-axis
    private float pointerRot = 0;
    private float startPointerRot; // Starting rotation about the X-axis
    private float targetPointerRot; // Target rotation about the X-axis
    private int [] segmentColors;
    private long startTime;  // Start time of the animation
    private long duration; // Animation duration in milliseconds
    private int color;
    private int targetColor;
    private boolean direction;
    private boolean startDirection;
    private boolean targetDirection;
    private boolean isAnimating;  // Animation status

    private DirectionAndPlayerIndicator() {} // Private constructor

    public static synchronized DirectionAndPlayerIndicator getInstance() {
        if (instance == null) {
            instance = new DirectionAndPlayerIndicator();
        }
        instance.segmentColors = new int[12];
        return instance;
    }

    public void startAnimation(AnimationParams params) {
        this.startPointerRot = this.pointerRot;
        this.targetPointerRot = params.toRot;
        this.targetColor = params.toColor;
        this.startDirection = this.direction;
        this.targetDirection = params.toDirection;
        this.startTime = params.startTime;
        this.duration = params.duration;
        this.isAnimating = true;
    }

    public void update() {
        if (!isAnimating) return;

        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime >= duration) {
            elapsedTime = duration;
            circleRot = targetPointerRot;
            color = targetColor;
            isAnimating = false;
        }

        float progress = (float) elapsedTime / duration;
        this.pointerRot = startPointerRot + progress * (targetPointerRot - startPointerRot);
        if (targetDirection != startDirection) {
            if (targetDirection != direction) {
                if (progress <= 0.5) {
                    for (int i = 0; i < 11; i++) {
                        this.segmentColors[i] = ((int) (255 * Math.min(1, Math.max(0, 12 - i - 24 * progress))) << 24) | (color & 0x00FFFFFF);
                    }
                } else {
                    direction = targetDirection;
                    this.color = targetColor;
                }
            }
            if (startDirection != direction) {
                for (int i = 0; i < 11; i++) {
                    this.segmentColors[i] = ((int) (255 * Math.min(1, Math.max(0, 24 * progress - 12 - i))) << 24) | (color & 0x00FFFFFF);
                }
            }
        }
    }

    public boolean isAnimating() {
        return isAnimating;
    }

    public float getPointerRot() { return pointerRot;}

    public float getCircleRot() { return circleRot;}

    public int getSegmentColor(int i) {return this.color;}//segmentColors[i];}
}
