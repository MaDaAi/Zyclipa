package io.zyclipra.ui;

import io.zyclipra.app.AppContext;
import io.zyclipra.core.EventBus;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application frame: a slim icon rail on the left (Clips, Audio Mixer, Soundboard, and Settings
 * at the bottom) and the current section filling the rest. Recording status lives in the Clips
 * capture bar and as a red dot on the Clips icon, so nothing else competes for attention.
 */
public final class MainWindow {
    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final VBox toasts = new VBox(8);
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, ToggleButton> railButtons = new LinkedHashMap<>();
    private final ToggleGroup rail = new ToggleGroup();
    private final javafx.scene.control.Label recBadge = new javafx.scene.control.Label("REC");
    private View current;
    private final Scene scene;
    private final ClipsView clips;

    public MainWindow(AppContext ctx) {
        this.ctx = ctx;
        clips = new ClipsView(ctx, this);
        add(clips);
        add(new MixerView(ctx, this));
        add(new SoundboardView(ctx, this));
        add(new SettingsView(ctx, this));

        VBox railBox = new VBox();
        railBox.getStyleClass().add("rail");
        railBox.setMinWidth(76);
        railBox.setPrefWidth(76);
        // The logo (without its background tile) at the top of the rail; the tiled version is the window icon.
        try (var in = MainWindow.class.getResourceAsStream("/ui/logo-128.png")) {
            if (in != null) {
                javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(new javafx.scene.image.Image(in, 40, 40, true, true));
                logo.setAccessibleText("Zyclipra");
                StackPane logoBox = new StackPane(logo);
                logoBox.getStyleClass().add("rail-logo");
                railBox.getChildren().add(logoBox);
            }
        } catch (Exception ignored) { }
        int i = 1;
        for (View v : views.values()) {
            ToggleButton b = new ToggleButton(v.id().equals("settings") ? "" : v.title().replace("Audio Mixer", "Audio\nMixer"), Icons.of(v.icon(), 22));
            b.getStyleClass().setAll("rail-item");
            b.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            b.setToggleGroup(rail);
            b.setMnemonicParsing(false);
            b.setAccessibleText(v.title() + " (Ctrl+" + i++ + ")");
            b.setTooltip(new javafx.scene.control.Tooltip(v.title()));
            b.setOnAction(e -> show(v.id()));
            railButtons.put(v.id(), b);
            if (v.id().equals("settings")) {
                Region gap = new Region();
                VBox.setVgrow(gap, javafx.scene.layout.Priority.ALWAYS);
                railBox.getChildren().add(gap);
            }
            if (v.id().equals("clips")) {
                recBadge.getStyleClass().add("rec-badge");
                recBadge.setVisible(false);
                recBadge.setMouseTransparent(true);
                StackPane withBadge = new StackPane(b, recBadge);
                StackPane.setAlignment(recBadge, Pos.TOP_RIGHT);
                StackPane.setMargin(recBadge, new Insets(6, 8, 0, 0));
                railBox.getChildren().add(withBadge);
            } else railBox.getChildren().add(b);
        }
        rail.selectedToggleProperty().addListener((o, a, b) -> { if (b == null && a != null) a.setSelected(true); });

        toasts.setAlignment(Pos.BOTTOM_RIGHT);
        toasts.setPickOnBounds(false);
        toasts.setPadding(new Insets(0, 24, 24, 0));
        StackPane center = new StackPane(content, toasts);
        root.setLeft(railBox);
        root.setCenter(center);

        scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(MainWindow.class.getResource("/ui/zyclipra.css").toExternalForm());
        applyAccent(ctx.settings.general.accent);
        KeyCode[] digits = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4};
        int k = 0;
        for (String id : views.keySet()) scene.getAccelerators().put(new KeyCodeCombination(digits[k++], KeyCombination.SHORTCUT_DOWN), () -> show(id));

        ctx.bus.on(EventBus.NOTIFY, m -> Ui.fx(() -> toast(String.valueOf(m), false)));
        ctx.bus.on(EventBus.ERROR, m -> Ui.fx(() -> toast(String.valueOf(m), true)));
        Runnable badge = () -> recBadge.setVisible(ctx.recorder.recording() || ctx.replay.running());
        ctx.bus.on(EventBus.RECORDING_CHANGED, m -> Ui.fx(badge));
        ctx.bus.on(EventBus.REPLAY_CHANGED, m -> Ui.fx(badge));
        show("clips");
        if (ctx.store.lastError() != null) toast(ctx.store.lastError(), true);
    }

    private void add(View v) { views.put(v.id(), v); }

    public Scene scene() { return scene; }
    public BorderPane root() { return root; }
    public String currentId() { return current == null ? "" : current.id(); }
    public List<String> viewIds() { return List.copyOf(views.keySet()); }
    public View view(String id) { return views.get(id); }
    /** The capture status text shown at the top of the Clips page ("READY TO CAPTURE", "RECORDING 00:12"…). */
    public String captureStatus() { return clips.captureStatusText(); }
    public String replayStatus() { return clips.replayStatusText(); }

    public void show(String id) {
        View v = views.get(id);
        if (v == null || v == current) return;
        if (current != null) current.hidden();
        current = v;
        content.getChildren().setAll(v.node());
        railButtons.get(id).setSelected(true);
        v.shown();
        if (!ctx.settings.general.reduceMotion) {
            FadeTransition ft = new FadeTransition(Duration.millis(120), v.node());
            ft.setFromValue(0.5);
            ft.setToValue(1);
            ft.play();
        }
    }

    public void applyAccent(String hex) { root.setStyle("-accent: " + hex + ";"); Ui.accent = hex; }

    /** Standard page top: grey title and a thin rule, like every section uses. */
    static VBox pageHead(String title) {
        VBox v = new VBox(14, Ui.label(title, "page-title"), rule());
        v.getStyleClass().add("page-head");
        return v;
    }

    static Region rule() {
        Region r = new Region();
        r.getStyleClass().add("page-rule");
        return r;
    }

    public void toast(String msg, boolean error) {
        Text t = new Text(msg);
        t.setStyle("-fx-fill: #E6E8EC;");
        StackPane box = new StackPane(new TextFlow(t));
        box.getStyleClass().add("toast");
        if (error) box.getStyleClass().add("toast-error");
        box.setMaxWidth(420);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        toasts.getChildren().add(box);
        if (toasts.getChildren().size() > 4) toasts.getChildren().remove(0);
        PauseTransition p = new PauseTransition(Duration.seconds(error ? 7 : 4));
        p.setOnFinished(e -> toasts.getChildren().remove(box));
        p.play();
    }
}
