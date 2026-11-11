package io.zyclipra.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

/**
 * A vertical volume fader with the live level meter inside its track, so the level and the handle
 * share one bar. The level is drawn after the fader (scaled by the volume), so it never climbs past
 * the handle. Drag or click anywhere on it, use the mouse wheel, or the arrow / Page / Home / End keys.
 */
public final class MeterFader extends Region {
    private static final double TRACK_W = 6, THUMB_W = 24, THUMB_H = 14;

    private final DoubleProperty value = new SimpleDoubleProperty(this, "value", 1);
    private final Rectangle track = new Rectangle(), fill = new Rectangle(), thumb = new Rectangle(), mark = new Rectangle();
    private double shown;

    public MeterFader(double initial) {
        value.set(clamp(initial));
        getStyleClass().add("meter-fader");
        track.setFill(Color.web("#16191E"));
        track.setArcWidth(TRACK_W);
        track.setArcHeight(TRACK_W);
        fill.setArcWidth(TRACK_W);
        fill.setArcHeight(TRACK_W);
        thumb.setFill(Color.WHITE);
        thumb.setArcWidth(5);
        thumb.setArcHeight(5);
        thumb.getStyleClass().add("fader-thumb");
        mark.getStyleClass().add("fader-mark");
        mark.setFill(Color.web("#5B6CF9"));
        getChildren().addAll(track, fill, thumb, mark);
        setPrefSize(THUMB_W + 8, 240);
        setMinSize(THUMB_W + 8, 80);
        setMaxWidth(THUMB_W + 8);

        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.SLIDER);
        value.addListener((o, a, b) -> { requestLayout(); notifyAccessibleAttributeChanged(AccessibleAttribute.VALUE); });
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> { requestFocus(); setFromY(e.getY()); e.consume(); });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> { setFromY(e.getY()); e.consume(); });
        addEventHandler(ScrollEvent.SCROLL, e -> { if (e.getDeltaY() != 0) { setValue(getValue() + Math.signum(e.getDeltaY()) * 0.02); e.consume(); } });
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case UP, RIGHT -> setValue(getValue() + 0.01);
                case DOWN, LEFT -> setValue(getValue() - 0.01);
                case PAGE_UP -> setValue(getValue() + 0.1);
                case PAGE_DOWN -> setValue(getValue() - 0.1);
                case HOME -> setValue(0);
                case END -> setValue(1);
                default -> { return; }
            }
            e.consume();
        });
    }

    public DoubleProperty valueProperty() { return value; }
    public double getValue() { return value.get(); }
    public void setValue(double v) { value.set(clamp(v)); }

    /** Live level, {@code peak} linear 0..1. Shown in dBFS (-60..0) with fast attack and smooth fall. */
    public void set(double peak) {
        double db = 20 * Math.log10(Math.max(peak, 1e-6));
        double v = Math.max(0, Math.min(1, (db + 60) / 60));
        double next = v > shown ? v : Math.max(v, shown - 0.04);
        if (next != shown) { shown = next; requestLayout(); }
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    /** Top and bottom of the travel, inset so the handle stays fully inside the control. */
    private double top() { return snappedTopInset() + THUMB_H / 2; }
    private double bottom() { return getHeight() - snappedBottomInset() - THUMB_H / 2; }

    private void setFromY(double y) {
        double span = Math.max(1, bottom() - top());
        setValue((bottom() - y) / span);
    }

    @Override protected void layoutChildren() {
        double cx = getWidth() / 2, t = top(), b = bottom(), span = Math.max(1, b - t);
        track.setX(cx - TRACK_W / 2);
        track.setY(t);
        track.setWidth(TRACK_W);
        track.setHeight(span);
        // Gradient fixed to the whole track: green low, amber, then red only near full scale.
        fill.setFill(new LinearGradient(0, b, 0, t, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#2BD9A0")), new Stop(0.7, Color.web("#2BD9A0")), new Stop(0.88, Color.web("#F5A524")), new Stop(1, Color.web("#F04452"))));
        double fh = span * shown * getValue();
        fill.setX(cx - TRACK_W / 2);
        fill.setY(b - fh);
        fill.setWidth(TRACK_W);
        fill.setHeight(fh);
        double ty = b - span * getValue();
        thumb.setX(cx - THUMB_W / 2);
        thumb.setY(ty - THUMB_H / 2);
        thumb.setWidth(THUMB_W);
        thumb.setHeight(THUMB_H);
        mark.setX(cx - 5);
        mark.setY(ty - 1);
        mark.setWidth(10);
        mark.setHeight(2);
    }

    @Override public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        return switch (attribute) {
            case VALUE -> getValue();
            case MIN_VALUE -> 0d;
            case MAX_VALUE -> 1d;
            case ORIENTATION -> javafx.geometry.Orientation.VERTICAL;
            default -> super.queryAccessibleAttribute(attribute, parameters);
        };
    }
}
