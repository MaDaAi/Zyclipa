package io.zyclipa.ui;

import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/** Peak meter with fast attack and smooth fall, shown in dBFS (-60..0) so quiet sounds are visible. */
public final class LevelMeter extends Region {
    private final Rectangle bg = new Rectangle();
    private final Rectangle fill = new Rectangle();
    private final boolean vertical;
    private double shown;

    public LevelMeter(boolean vertical, double thickness) {
        this.vertical = vertical;
        bg.getStyleClass().add("meter-bg");
        fill.getStyleClass().add(vertical ? "meter-fill" : "meter-fill-h");
        bg.setArcWidth(6); bg.setArcHeight(6);
        fill.setArcWidth(6); fill.setArcHeight(6);
        getChildren().addAll(bg, fill);
        if (vertical) { setPrefWidth(thickness); setMinWidth(thickness); setPrefHeight(160); }
        else { setPrefHeight(thickness); setMinHeight(thickness); setPrefWidth(160); }
        setAccessibleText("Level meter");
    }

    /** {@code peak} is linear 0..1. */
    public void set(double peak) {
        double db = 20 * Math.log10(Math.max(peak, 1e-6));
        double v = Math.max(0, Math.min(1, (db + 60) / 60));
        shown = v > shown ? v : Math.max(v, shown - 0.04);
        requestLayout();
    }

    public double value() { return shown; }

    @Override protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        bg.setWidth(w); bg.setHeight(h);
        if (vertical) {
            double fh = h * shown;
            fill.setX(0); fill.setY(h - fh); fill.setWidth(w); fill.setHeight(fh);
        } else {
            fill.setX(0); fill.setY(0); fill.setWidth(w * shown); fill.setHeight(h);
        }
    }
}
