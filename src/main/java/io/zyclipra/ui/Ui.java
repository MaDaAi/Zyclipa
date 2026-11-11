package io.zyclipra.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Small factories so every screen looks and behaves the same. */
public final class Ui {
    private Ui() {}

    public static Label label(String text, String... classes) {
        Label l = new Label(text);
        l.getStyleClass().addAll(classes);
        l.setWrapText(true);
        return l;
    }

    public static Button button(String text, String icon, Runnable action, String... classes) {
        Button b = new Button(text);
        if (icon != null) b.setGraphic(Icons.of(icon, 16));
        b.getStyleClass().addAll(classes);
        b.setOnAction(e -> action.run());
        b.setMnemonicParsing(false);
        return b;
    }

    public static Button iconButton(String icon, String tip, Runnable action, String... classes) {
        Button b = button(null, icon, action, classes);
        b.getStyleClass().add("icon-button");
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setTooltip(new Tooltip(tip));
        b.setAccessibleText(tip);
        return b;
    }

    public static ToggleButton toggle(String text, String icon, boolean on, Consumer<Boolean> change) {
        ToggleButton t = new ToggleButton(text);
        if (icon != null) t.setGraphic(Icons.of(icon, 16));
        t.setSelected(on);
        t.setMnemonicParsing(false);
        t.selectedProperty().addListener((o, a, b) -> change.accept(b));
        return t;
    }

    public static VBox card(Node... children) {
        VBox v = new VBox(children);
        v.getStyleClass().add("card");
        return v;
    }

    public static VBox card(String title, Node... children) {
        VBox v = card(children);
        v.getChildren().add(0, label(title, "h2"));
        return v;
    }

    public static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double spacing, Node... children) {
        HBox h = new HBox(spacing, children);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** A slider with a live value label. */
    public static HBox slider(String name, double min, double max, double value, String unit, int decimals, Consumer<Double> change) {
        Label n = label(name, "small");
        n.setMinWidth(110);
        Slider s = fill(new Slider(min, max, value));
        s.setAccessibleText(name);
        HBox.setHgrow(s, Priority.ALWAYS);
        Label v = label(fmt(value, decimals) + unit, "small", "muted");
        v.setMinWidth(62);
        s.valueProperty().addListener((o, a, b) -> {
            v.setText(fmt(b.doubleValue(), decimals) + unit);
            change.accept(b.doubleValue());
        });
        return row(10, n, s, editable(v, min, max, s::setValue));
    }

    /**
     * Draws a coloured bar on a slider's track so you can see how far it's set. A slider that goes from
     * minus to plus (like -12..+12 dB) fills outwards from its middle and has a "detent": a marked centre
     * that the handle briefly sticks to while dragging, so hitting exactly 0 / an even split is easy.
     * Other sliders fill from their start.
     */
    public static Slider fill(Slider s) {
        boolean centred = s.getMin() < 0 && s.getMax() > 0 && Math.abs(s.getMin() + s.getMax()) < 1e-9;
        return fill(s, centred ? 0 : s.getMin(), centred ? 0 : Double.NaN, "-accent", "-accent");
    }

    /**
     * Same, with an explicit origin (where the bar starts), detent (a value the handle sticks to, or NaN for
     * none) and colours for the part below / above the origin.
     */
    public static Slider fill(Slider s, double origin, double detent, String lowColor, String highColor) {
        Runnable paint = () -> {
            double span = s.getMax() - s.getMin();
            if (span <= 0) return;
            double o = (origin - s.getMin()) / span * 100, v = (s.getValue() - s.getMin()) / span * 100;
            double a = Math.min(o, v), b = Math.max(o, v);
            String c = color(s.getValue() < origin ? lowColor : highColor);
            String dir = s.getOrientation() == javafx.geometry.Orientation.VERTICAL ? "to top" : "to right";
            // Coloured segments on a dark track: the filled part, plus a small notch at the detent when it isn't covered.
            List<double[]> segs = new ArrayList<>();
            List<String> cols = new ArrayList<>();
            if (b - a > 0.05) { segs.add(new double[]{a, b}); cols.add(c); }
            if (!Double.isNaN(detent)) {
                double m = (detent - s.getMin()) / span * 100;
                if (m < a - 0.6 || m > b + 0.6 || b - a <= 0.05) { segs.add(new double[]{m - 0.5, m + 0.5}); cols.add("#6A7180"); }
            }
            Integer[] order = new Integer[segs.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            java.util.Arrays.sort(order, (x, y) -> Double.compare(segs.get(x)[0], segs.get(y)[0]));
            StringBuilder g = new StringBuilder("linear-gradient(" + dir + ", #1B1F25 0%");
            for (int k : order) {
                double from = Math.max(0, Math.min(100, segs.get(k)[0])), to = Math.max(0, Math.min(100, segs.get(k)[1]));
                g.append(String.format(Locale.ROOT, ", #1B1F25 %.2f%%, %s %.2f%%, %s %.2f%%, #1B1F25 %.2f%%", from, cols.get(k), from, cols.get(k), to, to));
            }
            g.append(", #1B1F25 100%)");
            // A colour variable on the slider itself; the stylesheet paints the track with it.
            s.setStyle("-slider-fill: " + g + ";");
        };
        // The detent: while you drag within 5 % of the range of the marked value, the handle rests on it, and
        // letting go that close also lands exactly on it. Values set by the app itself (not by dragging) aren't moved.
        double zone = (s.getMax() - s.getMin()) * 0.05;
        s.valueProperty().addListener((o, x, y) -> {
            if (!Double.isNaN(detent) && s.isValueChanging() && y.doubleValue() != detent
                    && Math.abs(y.doubleValue() - detent) < zone) { s.setValue(detent); return; }
            paint.run();
        });
        s.valueChangingProperty().addListener((o, x, y) -> {
            if (!y && !Double.isNaN(detent) && s.getValue() != detent && Math.abs(s.getValue() - detent) < zone) s.setValue(detent);
            paint.run();
        });
        paint.run();
        return s;
    }

    /** The accent colour (set by the theme); used where a colour has to be written out, e.g. slider fills. */
    static volatile String accent = "#5B6CF9";

    private static String color(String c) { return c.equals("-accent") ? accent : c; }

    public static void tip(Node n, String text) { Tooltip.install(n, new Tooltip(text)); }

    /** A typeable "NN%" label for a 0..1 (or more) volume slider; {@code maxPct} is the most you can type. */
    public static javafx.scene.layout.StackPane percent(Slider s, double maxPct) {
        Label pct = label(Math.round(s.getValue() * 100) + "%", "small", "muted");
        pct.setWrapText(false);
        pct.setMinWidth(38);
        s.valueProperty().addListener((o, a, b) -> pct.setText(Math.round(b.doubleValue() * 100) + "%"));
        return editable(pct, 0, maxPct, v -> s.setValue(v / 100));
    }

    /**
     * Makes a value label next to a slider typeable: click it, type a number, press Enter (Esc cancels).
     * The number is kept between {@code min} and {@code max} (in the units the label shows) and handed to
     * {@code apply}, which should move the slider – the slider's own listener then updates the label.
     */
    public static javafx.scene.layout.StackPane editable(Label shown, double min, double max, Consumer<Double> apply) {
        javafx.scene.control.TextField field = new javafx.scene.control.TextField();
        field.getStyleClass().add("value-edit");
        field.setVisible(false);
        field.setAccessibleText("Type a value from " + fmt(min, 0) + " to " + fmt(max, 0));
        field.prefWidthProperty().bind(shown.widthProperty().add(14));
        javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(shown, field);
        box.setAlignment(Pos.CENTER_LEFT);
        shown.getStyleClass().add("value-link");
        shown.setTooltip(new Tooltip("Click to type a value (" + fmt(min, 0) + "–" + fmt(max, 0) + ")"));
        boolean[] open = {false};
        Runnable close = () -> { open[0] = false; field.setVisible(false); shown.setVisible(true); };
        Runnable commit = () -> {
            if (!open[0]) return;
            String t = field.getText().replaceAll("[^0-9.,\\-]", "").replace(',', '.');
            try { apply.accept(Math.max(min, Math.min(max, Double.parseDouble(t)))); } catch (NumberFormatException ignored) { }
            close.run();
        };
        shown.setOnMouseClicked(e -> {
            open[0] = true;
            field.setText(shown.getText().replaceAll("[^0-9.,\\-]", ""));
            shown.setVisible(false);
            field.setVisible(true);
            field.requestFocus();
            field.selectAll();
        });
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((o, a, b) -> { if (!b) commit.run(); });
        field.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) { close.run(); e.consume(); } });
        return box;
    }

    public static String fmt(double v, int decimals) { return String.format(Locale.ROOT, "%." + decimals + "f", v); }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return fmt(k, 0) + " KB";
        double m = k / 1024;
        if (m < 1024) return fmt(m, 1) + " MB";
        return fmt(m / 1024, 2) + " GB";
    }

    public static String duration(double sec) {
        int s = (int) Math.round(sec);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    public static void fx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }

    public static VBox empty(String icon, String title, String hint) {
        VBox v = new VBox(Icons.of(icon, 36), label(title, "h3"), label(hint, "muted", "small"));
        v.getStyleClass().add("empty");
        v.setMaxWidth(520);
        v.setPrefWidth(520);
        return v;
    }

    /** On/off switch (a ToggleButton drawn as a pill with a sliding knob). */
    public static ToggleButton toggleSwitch(boolean on, String accessible, Consumer<Boolean> change) {
        ToggleButton t = new ToggleButton();
        t.getStyleClass().setAll("switch");
        javafx.scene.layout.StackPane track = new javafx.scene.layout.StackPane();
        track.getStyleClass().add("switch-track");
        javafx.scene.shape.Circle knob = new javafx.scene.shape.Circle(8);
        knob.getStyleClass().add("switch-knob");
        track.getChildren().add(knob);
        Runnable place = () -> {
            javafx.scene.layout.StackPane.setAlignment(knob, t.isSelected() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            javafx.scene.layout.StackPane.setMargin(knob, new javafx.geometry.Insets(0, 3, 0, 3));
        };
        t.setGraphic(track);
        t.setSelected(on);
        place.run();
        t.setAccessibleText(accessible);
        t.selectedProperty().addListener((o, a, b) -> { place.run(); change.accept(b); });
        return t;
    }

    /** A control with a small caption above it, like an outlined form field. */
    public static VBox field(String caption, Node control) {
        VBox v = new VBox(3, label(caption, "field-caption"), control);
        HBox.setHgrow(v, Priority.ALWAYS);
        if (control instanceof javafx.scene.control.Control c) c.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    /** Settings-style row: bold name, description underneath, control on the right. */
    public static HBox settingRow(String name, String desc, Node control) {
        VBox text = new VBox(6, label(name, "setting-name"));
        if (desc != null && !desc.isBlank()) text.getChildren().add(label(desc, "setting-desc"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox h = control == null ? new HBox(text) : new HBox(20, text, control);
        h.getStyleClass().add("setting-row");
        return h;
    }

    /** Card with a header line: optional switch, upper-case title, optional extra nodes on the right. */
    public static VBox section(String title, ToggleButton sw, Node... right) {
        HBox head = new HBox(12);
        head.getStyleClass().add("card-header");
        if (sw != null) head.getChildren().add(sw);
        head.getChildren().addAll(label(title.toUpperCase(Locale.ROOT), "card-title"), grow());
        head.getChildren().addAll(right);
        VBox v = new VBox(head);
        v.getStyleClass().add("card");
        if (sw != null) {
            // A switched-off card shows only its title row; its settings appear when you turn it on.
            v.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> {
                for (Node n : v.getChildren()) {
                    if (n == head || n.visibleProperty().isBound()) continue;
                    n.visibleProperty().bind(sw.selectedProperty());
                    n.managedProperty().bind(sw.selectedProperty());
                }
            });
            v.getStyleClass().add("switch-card");
        }
        return v;
    }

    /** "Ctrl+Alt+F9" drawn as separate key caps. */
    public static HBox keycaps(String combo, String style) {
        HBox h = new HBox(4);
        h.setAlignment(Pos.CENTER_LEFT);
        if (combo == null || combo.isBlank()) return h;
        for (String k : combo.split("\\+")) {
            Label l = new Label(k);
            l.getStyleClass().add(style);
            h.getChildren().add(l);
        }
        return h;
    }

    /** Small "x days ago" text. */
    public static String ago(long epochMs) {
        long min = Math.max(0, (System.currentTimeMillis() - epochMs) / 60000);
        if (min < 1) return "JUST NOW";
        if (min < 60) return min + (min == 1 ? " MINUTE AGO" : " MINUTES AGO");
        long h = min / 60;
        if (h < 24) return h + (h == 1 ? " HOUR AGO" : " HOURS AGO");
        long d = h / 24;
        return d + (d == 1 ? " DAY AGO" : " DAYS AGO");
    }
}
