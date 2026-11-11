package io.zyclipra.ui;

import io.zyclipra.audio.dsp.Equalizer;
import io.zyclipra.core.Settings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Nine-band parametric equalizer card: band-name strip, response curve with draggable coloured
 * dots (drag = frequency and boost, scroll = width), and Bass / Voice / Treble quick sliders.
 * Works on any {@link Settings.Equalizer}; {@code changed} is called after every edit.
 */
final class EqPanel {
    static final String[] BAND_COLORS = {"#9D63F7", "#6C78F7", "#F0479B", "#F04452", "#FF7A45", "#F5A524", "#B8E62E", "#2BD9A0", "#3DA9F5"};
    private static final int[][] GROUPS = {{0, 1, 2}, {4, 5, 6}, {7, 8}};

    private final Settings.Equalizer eq;
    private final Runnable changed;
    private final Canvas canvas = new Canvas(900, 300);
    private final VBox node;
    private final Label bandInfo = Ui.label("Click a dot to pick a band; drag to change it.", "small", "muted");
    private final Slider[] quick = new Slider[3];
    private int drag = -1, selected = -1;
    private boolean syncing;

    EqPanel(Settings.Equalizer eq, Runnable changed, Region... headerExtras) {
        this.eq = eq;
        this.changed = changed;
        ToggleButton on = Ui.toggleSwitch(eq.enabled, "Equalizer", b -> { eq.enabled = b; edit(); });
        node = Ui.section("Equalizer", on, headerExtras);

        HBox bands = new HBox();
        bands.getStyleClass().add("band-strip");
        bands.setPadding(new Insets(6, 10, 6, 50));
        bands.setMaxHeight(Region.USE_PREF_SIZE);
        String[] names = {"SUB BASS", "BASS", "LOW MIDS", "MID RANGE", "UPPER MIDS", "HIGHS"};
        double[] edges = {20, 60, 250, 500, 2000, 6000, 20000};
        for (int i = 0; i < names.length; i++) {
            Label l = Ui.label(names[i], "band-name");
            l.setWrapText(false);
            l.setMinWidth(0);
            l.setAlignment(Pos.CENTER);
            l.setMaxWidth(Double.MAX_VALUE);
            l.prefWidthProperty().bind(bands.widthProperty().subtract(60).multiply(Math.log10(edges[i + 1] / edges[i]) / 3));
            bands.getChildren().add(l);
        }
        StackPane holder = new StackPane(canvas);
        holder.getStyleClass().add("eq-holder");
        holder.setMinWidth(0);
        holder.widthProperty().addListener((o, a, b) -> { canvas.setWidth(Math.max(300, b.doubleValue())); draw(); });
        canvas.setOnMousePressed(this::press);
        canvas.setOnMouseDragged(this::dragTo);
        canvas.setOnMouseReleased(e -> drag = -1);
        canvas.setOnScroll(e -> {
            if (selected < 0) return;
            Settings.EqBand b = eq.bands.get(selected);
            b.q = Math.max(0.1, Math.min(10, b.q * (e.getDeltaY() > 0 ? 1.15 : 1 / 1.15)));
            eq.preset = "Custom";
            edit();
        });

        HBox sliders = new HBox(24);
        String[] qn = {"Bass", "Voice", "Treble"};
        for (int i = 0; i < 3; i++) {
            int g = i;
            Slider s = Ui.fill(new Slider(-12, 12, groupGain(g)));
            s.setAccessibleText(qn[i]);
            HBox.setHgrow(s, Priority.ALWAYS);
            Label v = Ui.label(Ui.fmt(groupGain(g), 1) + " dB", "small", "muted");
            v.setMinWidth(54);
            s.valueProperty().addListener((o, a, b) -> {
                v.setText(Ui.fmt(b.doubleValue(), 1) + " dB");
                if (syncing) return;
                double delta = b.doubleValue() - a.doubleValue();
                for (int idx : GROUPS[g]) {
                    Settings.EqBand x = eq.bands.get(idx);
                    x.gainDb = Math.max(-12, Math.min(12, Math.round((x.gainDb + delta) * 10) / 10.0));
                }
                eq.preset = "Custom";
                edit();
            });
            quick[i] = s;
            Label n = Ui.label(qn[i], "small", "muted");
            n.setMinWidth(44);
            HBox row = Ui.row(10, n, s, Ui.editable(v, -12, 12, s::setValue));
            HBox.setHgrow(row, Priority.ALWAYS);
            sliders.getChildren().add(row);
        }
        node.getChildren().addAll(bands, holder, sliders, bandInfo);
    }

    VBox node() { return node; }

    private double groupGain(int g) {
        double s = 0;
        for (int idx : GROUPS[g]) s += eq.bands.get(idx).gainDb;
        return s / GROUPS[g].length;
    }

    /** Re-reads band values (after a preset was applied elsewhere). */
    void refresh() {
        syncing = true;
        for (int i = 0; i < 3; i++) quick[i].setValue(groupGain(i));
        syncing = false;
        draw();
    }

    private void edit() {
        changed.run();
        draw();
    }

    private double xOf(double f) { return Math.log10(f / 20) / 3 * (canvas.getWidth() - 60) + 50; }
    private double fOf(double x) { return 20 * Math.pow(1000, (x - 50) / (canvas.getWidth() - 60)); }
    private double yOf(double db) { return canvas.getHeight() / 2 - db / 12 * (canvas.getHeight() / 2 - 30); }
    private double dbOf(double y) { return (canvas.getHeight() / 2 - y) / (canvas.getHeight() / 2 - 30) * 12; }

    private void press(MouseEvent ev) {
        double best = 16;
        drag = -1;
        for (int i = 0; i < eq.bands.size(); i++) {
            double d = Math.hypot(ev.getX() - xOf(eq.bands.get(i).freq), ev.getY() - yOf(eq.bands.get(i).gainDb));
            if (d < best) { best = d; drag = i; }
        }
        if (drag >= 0) selected = drag;
        draw();
    }

    private void dragTo(MouseEvent ev) {
        if (drag < 0) return;
        Settings.EqBand b = eq.bands.get(drag);
        b.freq = Math.round(Math.max(20, Math.min(20000, fOf(ev.getX()))));
        b.gainDb = Math.round(Math.max(-12, Math.min(12, dbOf(ev.getY()))) * 10) / 10.0;
        eq.preset = "Custom";
        syncing = true;
        for (int i = 0; i < 3; i++) quick[i].setValue(groupGain(i));
        syncing = false;
        edit();
    }

    void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(Color.web("#20242B"));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.web("#2C313A"));
        g.setLineWidth(1);
        g.setFill(Color.web("#8E95A2"));
        g.setFont(javafx.scene.text.Font.font(11));
        for (double f : new double[]{20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000}) {
            double x = xOf(f);
            g.strokeLine(x, 8, x, h - 22);
            g.fillText(f >= 1000 ? (int) (f / 1000) + "kHz" : (int) f + "Hz", x - 12, h - 6);
        }
        for (int db = -12; db <= 12; db += 6) {
            double y = yOf(db);
            g.strokeLine(50, y, w - 10, y);
            g.fillText((db > 0 ? "+" : "") + db + " dB", 4, y + 4);
        }
        g.setStroke(eq.enabled ? Color.web("#E6E8EC") : Color.web("#626977"));
        g.setLineWidth(1.6);
        g.beginPath();
        for (double px = 50; px <= w - 10; px += 2) {
            double db = Equalizer.responseDb(eq.bands, 48000, fOf(px));
            double y = yOf(Math.max(-14, Math.min(14, db)));
            if (px == 50) g.moveTo(px, y); else g.lineTo(px, y);
        }
        g.stroke();
        for (int i = 0; i < eq.bands.size(); i++) {
            Settings.EqBand b = eq.bands.get(i);
            double x = xOf(b.freq), y = yOf(b.gainDb);
            g.setFill(eq.enabled ? Color.web(BAND_COLORS[i]) : Color.web(BAND_COLORS[i]).deriveColor(0, 0.4, 0.6, 1));
            double r = i == selected ? 7 : 5;
            g.fillOval(x - r, y - r, r * 2, r * 2);
            if (i == selected) { g.setStroke(Color.WHITE); g.setLineWidth(1.5); g.strokeOval(x - r - 2, y - r - 2, r * 2 + 4, r * 2 + 4); }
        }
        if (selected >= 0) {
            Settings.EqBand b = eq.bands.get(selected);
            bandInfo.setText("Band " + (selected + 1) + ":  " + (b.freq >= 1000 ? Ui.fmt(b.freq / 1000, 2) + " kHz" : (int) b.freq + " Hz")
                    + "   ·   " + (b.gainDb > 0 ? "+" : "") + Ui.fmt(b.gainDb, 1) + " dB   ·   width (Q) " + Ui.fmt(b.q, 2) + "   (scroll to change the width)");
        }
    }

    /** Copies a preset's band values into the live bands. */
    static void apply(Settings.Equalizer eq, List<Settings.EqBand> src, String name) {
        for (int i = 0; i < 9 && i < src.size(); i++) {
            Settings.EqBand d = eq.bands.get(i), x = src.get(i);
            d.freq = x.freq; d.gainDb = x.gainDb; d.q = x.q; d.type = x.type;
        }
        eq.preset = name;
    }

    static Button menuButton(ContextMenu m) {
        Button b = Ui.iconButton(Icons.MORE, "More", () -> { }, "boxed");
        b.setOnAction(e -> m.show(b, Side.BOTTOM, 0, 0));
        return b;
    }

    static MenuItem item(String text, Runnable r) {
        MenuItem i = new MenuItem(text);
        i.setOnAction(e -> r.run());
        return i;
    }
}
