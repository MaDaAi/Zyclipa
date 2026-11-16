package io.zyclipa.ui;

import io.zyclipa.app.AppContext;
import io.zyclipa.audio.AppRouting;
import io.zyclipa.audio.AudioPlatform;
import io.zyclipa.audio.ChannelEngine;
import io.zyclipa.audio.MixerService;
import io.zyclipa.audio.SoundDevices;
import io.zyclipa.audio.VirtualCables;
import io.zyclipa.core.Settings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One app channel's page (Game, Chat, Media, Aux): preset bar with favourites and test sounds,
 * the sound source/output, equalizer, spatial audio, volume boost and smart volume (Chat: noise
 * cancellation, gate and compressor instead of spatial), and the apps in the channel.
 */
final class ChannelTab {
    static final Map<String, Map<String, double[]>> BUILTIN = new LinkedHashMap<>();
    static {
        Map<String, double[]> game = new LinkedHashMap<>();
        game.put("Flat", new double[9]);
        game.put("Footsteps", new double[]{-6, -4, -2, -1, 0, 2, 5, 5, 2});
        game.put("Shooter", new double[]{3, 2, -1, -2, 0, 2, 4, 3, 1});
        game.put("Bass boost", new double[]{6, 5, 3.5, 1.5, 0, 0, 0, 0, 0});
        game.put("Immersive", new double[]{4, 3, 1, 0, -1, 0, 1, 3, 4});
        BUILTIN.put("game", game);
        Map<String, double[]> chat = new LinkedHashMap<>();
        chat.put("Flat", new double[9]);
        chat.put("Clear voice", new double[]{-9, -6, -2, 0, 1, 3, 4, 2, -1});
        chat.put("Warm voice", new double[]{-6, 0, 2, 2, 1, 0, -1, -2, -3});
        BUILTIN.put("chat", chat);
        Map<String, double[]> media = new LinkedHashMap<>();
        media.put("Flat", new double[9]);
        media.put("Music", new double[]{3, 3, 1, 0, -1, 0, 1, 2, 3});
        media.put("Movie", new double[]{5, 4, 1, 0, 1, 2, 1, 1, 0});
        media.put("Bass boost", new double[]{6, 5, 3.5, 1.5, 0, 0, 0, 0, 0});
        BUILTIN.put("media", media);
        Map<String, double[]> aux = new LinkedHashMap<>();
        aux.put("Flat", new double[9]);
        aux.put("Bright", new double[]{0, 0, 0, 0, 0, 1, 3, 5, 6});
        aux.put("Warm", new double[]{3, 2.5, 1.5, 0.5, 0, -0.5, -1.5, -2.5, -3});
        BUILTIN.put("aux", aux);
    }

    private final AppContext ctx;
    private final MainWindow win;
    private final String ch;
    private final Settings.ChannelFx fx;
    private final String color;
    private final VBox apps = new VBox(6);
    private final Label status = Ui.label("", "small");
    private EqPanel eqPanel;
    private final Label presetName = Ui.label("", "h3");
    private final HBox favRow = new HBox(6);
    private final Label favCount = Ui.label("", "tiny-caps");
    private final ToggleButton favStar = new ToggleButton();

    ChannelTab(AppContext ctx, MainWindow win, String channel) {
        this.ctx = ctx;
        this.win = win;
        this.ch = channel;
        this.fx = ctx.mixer.fx(channel);
        this.color = MixerView.COLORS.get(channel);
    }

    private void changed() { ctx.mixer.fxChanged(ch); }

    private Runnable rebuild = () -> { };
    /** How the mixer redraws this tab after "Reset to defaults". */
    void onRebuild(Runnable r) { rebuild = r; }

    Node build() {
        VBox page = new VBox(16, presetBar(), routingBar());
        eqPanel = new EqPanel(fx.eq, this::changed);
        page.getChildren().add(eqPanel.node());
        if (ch.equals("chat")) {
            page.getChildren().add(noiseCard());
            page.getChildren().add(twoColumns(gateCard(), compCard()));
        } else {
            page.getChildren().add(spatialCard());
        }
        page.getChildren().add(twoColumns(boostCard(), smartCard()));
        VBox appsCard = Ui.section("Apps in " + MixerService.CHANNEL_NAMES.get(ch), null);
        appsCard.getChildren().add(apps);
        page.getChildren().add(appsCard);
        page.getStyleClass().add("page");
        refreshPreset();
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        return sp;
    }

    VBox appsBox() { return apps; }

    static GridPane twoColumns(Node a, Node b) {
        GridPane g = new GridPane();
        g.setHgap(16);
        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(50);
        g.getColumnConstraints().addAll(cc, cc);
        g.add(a, 0, 0);
        g.add(b, 1, 0);
        return g;
    }

    // ---------------- preset bar ----------------

    private Node presetBar() {
        StackPane icon = new StackPane(Icons.of(MixerView.ICONS.get(ch), 26));
        icon.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3; -icon-color: white;");
        icon.setMinSize(60, 60);
        icon.setMaxSize(60, 60);
        favStar.setGraphic(Icons.of(Icons.STAR, 14));
        favStar.getStyleClass().setAll("star-toggle");
        favStar.setAccessibleText("Favourite preset");
        favStar.setOnAction(e -> {
            String p = fx.eq.preset;
            if (favStar.isSelected()) {
                if (fx.favorites.size() >= 9) { win.toast("You can keep up to 9 favourites.", true); favStar.setSelected(false); return; }
                if (!fx.favorites.contains(p)) fx.favorites.add(p);
            } else fx.favorites.remove(p);
            changed();
            refreshPreset();
        });
        ContextMenu menu = new ContextMenu(
                EqPanel.item("Save as new preset…", this::savePreset),
                EqPanel.item("Reset to Flat", () -> applyPreset("Flat")),
                EqPanel.item("Reset all " + MixerService.CHANNEL_NAMES.get(ch) + " settings to defaults", this::resetDefaults),
                EqPanel.item("Delete this preset", () -> {
                    String p = fx.eq.preset;
                    if (BUILTIN.get(ch).containsKey(p) || !fx.eq.presets.containsKey(p)) { win.toast("Built-in presets can't be deleted.", true); return; }
                    fx.eq.presets.remove(p);
                    fx.favorites.remove(p);
                    applyPreset("Flat");
                }));
        ComboBox<String> pick = new ComboBox<>();
        pick.setPromptText("Presets");
        pick.setAccessibleText("Choose a preset");
        pick.setOnShowing(e -> pick.getItems().setAll(allPresets()));
        pick.setOnAction(e -> { if (pick.getValue() != null) { applyPreset(pick.getValue()); pick.getSelectionModel().clearSelection(); } });
        VBox nameBox = new VBox(4, Ui.row(6, presetName, favStar), pick);
        HBox preset = new HBox(12, icon, nameBox, Ui.grow(), EqPanel.menuButton(menu));
        preset.setAlignment(Pos.CENTER_LEFT);
        preset.getStyleClass().add("preset-card");
        preset.setPrefWidth(330);

        VBox favs = new VBox(6, favCount, favRow);
        favs.getStyleClass().add("preset-card");
        HBox.setHgrow(favs, Priority.ALWAYS);

        String[] kinds = ch.equals("chat") ? TestSounds.CHAT : TestSounds.GAME;
        javafx.scene.layout.FlowPane tests = new javafx.scene.layout.FlowPane(4, 4);
        tests.setPrefWrapLength(150);
        for (String k : kinds) {
            Button b = Ui.button(k, null, () -> TestSounds.play(k, testDevice()), "boxed");
            b.setStyle("-fx-font-size: 11px; -fx-padding: 4 8;");
            b.setTooltip(new javafx.scene.control.Tooltip("Plays a short made-up " + k.toLowerCase(Locale.ROOT) + " sound through this channel's effects"));
            tests.getChildren().add(b);
        }
        VBox test = new VBox(6, Ui.label("TEST", "tiny-caps"), tests);
        test.getStyleClass().add("preset-card");

        Button reset = Ui.button("Reset to defaults", null, this::resetDefaults, "boxed");
        reset.setTooltip(new javafx.scene.control.Tooltip("Turns every effect on this page back to how it started. Your saved presets and sound routing are kept."));
        reset.setStyle("-fx-font-size: 11px; -fx-padding: 4 8;");
        test.getChildren().add(reset);

        HBox bar = new HBox(10, preset, favs, test);
        bar.setAlignment(Pos.TOP_LEFT);
        return new VBox(6, Ui.row(6, dot(), Ui.label("PRESET", "tiny-caps")), bar);
    }

    private Node dot() {
        javafx.scene.shape.Circle c = new javafx.scene.shape.Circle(4, Theme.web(fx.active() ? "#2BD9A0" : "#626977"));
        return c;
    }

    /** Test sounds go into the source device (so they get the effects) or straight to the output. */
    private String testDevice() {
        if (!fx.sourceDeviceName.isBlank() && ctx.mixer.engine(ch) != null) return fx.sourceDeviceName;
        return fx.outputDevice;
    }

    private List<String> allPresets() {
        List<String> l = new ArrayList<>(BUILTIN.get(ch).keySet());
        l.addAll(fx.eq.presets.keySet());
        return l;
    }

    private void applyPreset(String name) {
        double[] g = BUILTIN.get(ch).get(name);
        if (g != null) {
            List<Settings.EqBand> b = Settings.defaultBands();
            for (int i = 0; i < 9; i++) b.get(i).gainDb = g[i];
            EqPanel.apply(fx.eq, b, name);
        } else if (fx.eq.presets.containsKey(name)) EqPanel.apply(fx.eq, fx.eq.presets.get(name), name);
        if (!name.equals("Flat")) fx.eq.enabled = true;
        changed();
        eqPanel.refresh();
        refreshPreset();
    }

    private void savePreset() {
        TextInputDialog d = new TextInputDialog("My " + MixerService.CHANNEL_NAMES.get(ch).toLowerCase(Locale.ROOT) + " preset");
        d.setTitle("Save preset");
        d.setHeaderText("Preset name");
        ClipsView.style(d.getDialogPane());
        d.showAndWait().ifPresent(n -> {
            String name = n.trim();
            if (name.isEmpty() || name.length() > 40 || BUILTIN.get(ch).containsKey(name) || name.equals("Custom")) { win.toast("Pick another name.", true); return; }
            List<Settings.EqBand> copy = new ArrayList<>();
            for (Settings.EqBand b : fx.eq.bands) copy.add(new Settings.EqBand(b.freq, b.gainDb, b.q, b.type));
            fx.eq.presets.put(name, copy);
            fx.eq.preset = name;
            changed();
            refreshPreset();
        });
    }

    private void refreshPreset() {
        presetName.setText(fx.eq.preset);
        favStar.setSelected(fx.favorites.contains(fx.eq.preset));
        favCount.setText("FAVORITES (" + fx.favorites.size() + "/9)");
        favRow.getChildren().clear();
        for (int i = 0; i < 9; i++) {
            StackPane slot = new StackPane();
            slot.getStyleClass().add("fav-slot");
            slot.setPrefSize(64, 60);
            slot.setMinSize(56, 60);
            if (i < fx.favorites.size()) {
                String p = fx.favorites.get(i);
                Label l = new Label(p, Icons.of(MixerView.ICONS.get(ch), 14));
                l.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
                l.setWrapText(true);
                l.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -icon-color: white;");
                slot.getChildren().add(l);
                slot.setStyle("-fx-background-color: " + color + ";" + (p.equals(fx.eq.preset) ? "-fx-border-color: #2BD9A0; -fx-border-width: 0 0 3 0;" : ""));
                slot.setOnMouseClicked(e -> applyPreset(p));
                slot.setCursor(javafx.scene.Cursor.HAND);
            }
            favRow.getChildren().add(slot);
        }
    }

    // ---------------- reset ----------------

    private void resetDefaults() {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "The equalizer, spatial audio, boost and every other effect on the " + MixerService.CHANNEL_NAMES.get(ch)
                        + " page go back to how they started. Your saved presets, favourites and sound routing are kept.",
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        a.setTitle("Reset to defaults");
        a.setHeaderText("Reset " + MixerService.CHANNEL_NAMES.get(ch) + " to defaults?");
        ClipsView.style(a.getDialogPane());
        if (a.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;
        Settings.ChannelFx d = new Settings.ChannelFx();
        // Changed in place: the running effects engine keeps reading this same equalizer object.
        EqPanel.apply(fx.eq, Settings.defaultBands(), d.eq.preset);
        fx.eq.enabled = d.eq.enabled;
        fx.boostDb = d.boostDb; fx.smartVolume = d.smartVolume; fx.smartLevel = d.smartLevel;
        fx.spatial = d.spatial; fx.spatialMode = d.spatialMode; fx.immersion = d.immersion; fx.distance = d.distance;
        fx.noiseCancel = d.noiseCancel; fx.noiseAmount = d.noiseAmount; fx.gate = d.gate; fx.gateDb = d.gateDb;
        fx.compressor = d.compressor; fx.compAmount = d.compAmount;
        changed();
        win.toast(MixerService.CHANNEL_NAMES.get(ch) + " is back to its defaults", false);
        rebuild.run();
    }

    // ---------------- sound routing ----------------

    /** One line saying whether the effects below reach your ears, with a button to set that up. */
    private Node routingBar() {
        status.setWrapText(true);
        HBox.setHgrow(status, Priority.ALWAYS);
        status.setMaxWidth(Double.MAX_VALUE);
        Button setup = Ui.button("Set up", Icons.SETTINGS, this::routingDialog, "outline");
        setup.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox bar = new HBox(12, status, setup);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("routing-bar");
        tick();
        return bar;
    }

    private void routingDialog() {
        ComboBox<String> source = new ComboBox<>(), output = new ComboBox<>();
        source.setAccessibleText("Sound source for this channel");
        output.setAccessibleText("Play processed sound on");
        source.setMaxWidth(Double.MAX_VALUE);
        output.setMaxWidth(Double.MAX_VALUE);
        Map<String, String> idsByName = new LinkedHashMap<>();
        String name = MixerService.CHANNEL_NAMES.get(ch);
        CompletableFuture.supplyAsync(() -> List.of(ctx.mixer.platform().endpoints(), SoundDevices.outputs())).thenAccept(l -> Ui.fx(() -> {
            @SuppressWarnings("unchecked") List<AudioPlatform.Endpoint> eps = (List<AudioPlatform.Endpoint>) l.get(0);
            @SuppressWarnings("unchecked") List<String> outs = (List<String>) l.get(1);
            List<String> names = new ArrayList<>(List.of("Off – don't process " + name));
            eps.stream().filter(AudioPlatform.Endpoint::output).sorted((a, b) -> Boolean.compare(!VirtualCables.isVirtual(a.name()), !VirtualCables.isVirtual(b.name())))
                    .forEach(e -> { idsByName.put(e.name(), e.id()); names.add(e.name()); });
            source.getItems().setAll(names);
            source.setValue(fx.sourceDeviceName.isBlank() ? names.get(0) : fx.sourceDeviceName);
            MixerView.fill(output, outs.stream().filter(o -> !o.startsWith("Primary Sound")).toList(), fx.outputDevice, "Windows default playback device");
        }));
        Label steps = Ui.label("Windows can't add effects to an app's sound by itself, so the sound takes a short detour:\n\n"
                + "1.  Pick a virtual audio device below (for example “CABLE Input”).\n"
                + "2.  In Windows' app sound settings, make your " + name.toLowerCase(Locale.ROOT) + " apps play on that device.\n"
                + "3.  Zyclipa adds this page's effects and plays the result on your speakers or headphones.\n\n"
                + "It adds about 0.05 s of delay. Leave it Off if you don't need effects on " + name + ".", "small", "muted");
        steps.setMaxWidth(520);
        Button windows = Ui.button("Open Windows app sound settings", Icons.SETTINGS, AppRouting::openWindowsAppVolumeSettings, "outline");
        VBox body = new VBox(14, steps, Ui.field("Take " + name + "'s sound from", source), Ui.field("Play the result on", output), windows);
        body.setPrefWidth(540);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle("Sound routing");
        d.setHeaderText(name + " sound routing");
        d.getDialogPane().setContent(body);
        d.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        ClipsView.style(d.getDialogPane());
        if (d.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;
        String v = source.getValue();
        if (v != null) {
            if (v.startsWith("Off")) { fx.sourceDeviceId = ""; fx.sourceDeviceName = ""; }
            else { fx.sourceDeviceId = idsByName.getOrDefault(v, ""); fx.sourceDeviceName = v; }
        }
        if (output.getValue() != null) fx.outputDevice = MixerView.val(output);
        changed();
        tick();
    }

    /** Called by the mixer's level timer. */
    void tick() {
        ChannelEngine e = ctx.mixer.engine(ch);
        String err = ctx.mixer.engineError(ch);
        String name = MixerService.CHANNEL_NAMES.get(ch);
        String out = fx.outputDevice.isBlank() ? "your default speakers" : fx.outputDevice;
        String text, style;
        if (!err.isBlank()) { text = "⚠  " + err; style = "danger-text"; }
        else if (fx.sourceDeviceId.isBlank()) { text = "Effects on this page are saved but not playing yet – " + name + " isn't routed through Zyclipa. Click Set up to connect it."; style = "warn-text"; }
        else if (!fx.active()) { text = "●  Connected. Turn on any effect below and " + name + " will play through it on " + out + "."; style = "muted"; }
        else if (e != null && e.running()) { text = "●  Effects on – " + name + " plays on " + out + "."; style = "ok-text"; }
        else { text = "Starting…"; style = "muted"; }
        if (!text.equals(status.getText())) { status.setText(text); status.getStyleClass().setAll("label", "small", style); }
    }

    // ---------------- effect cards ----------------

    private VBox spatialCard() {
        ToggleButton on = Ui.toggleSwitch(fx.spatial, "Spatial audio", b -> { fx.spatial = b; changed(); draw(); });
        ToggleGroup g = new ToggleGroup();
        ToggleButton hp = seg("HEADPHONE MODE", g), sp = seg("SPEAKER MODE", g);
        (fx.spatialMode.equals("speakers") ? sp : hp).setSelected(true);
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { a.setSelected(true); return; }
            fx.spatialMode = b == sp ? "speakers" : "headphones";
            changed();
            draw();
        });
        HBox modes = new HBox(hp, sp);
        modes.getStyleClass().add("segmented");
        VBox card = Ui.section("Spatial audio", on, modes);
        spatialCanvas.setOnMouseClicked(e -> draw());
        VBox tuning = new VBox(10, Ui.label("Tuning", "h3"),
                Ui.label("Towards Performance keeps sounds precise and dry – best for competitive games. Towards Immersion widens the sound and adds a little room. "
                        + "Distance sets how far away the sound seems.", "small", "muted"),
                Ui.slider("Performance ⟷ Immersion", 0, 100, fx.immersion * 100, "", 0, v -> { fx.immersion = v / 100; changed(); draw(); }),
                Ui.slider("Distance", 0, 100, fx.distance * 100, "", 0, v -> { fx.distance = v / 100; changed(); draw(); }));
        HBox.setHgrow(tuning, Priority.ALWAYS);
        HBox body = new HBox(30, spatialCanvas, tuning);
        body.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(body);
        draw();
        return card;
    }

    private final Canvas spatialCanvas = new Canvas(220, 220);

    /** Listener in the middle, virtual speakers further out as distance/immersion grow. */
    private void draw() {
        GraphicsContext g = spatialCanvas.getGraphicsContext2D();
        double w = 220, c = 110;
        g.clearRect(0, 0, w, w);
        g.setFill(Theme.web("#1B1F25"));
        g.fillOval(0, 0, w, w);
        g.setFill(Theme.web("#23272F"));
        g.fillOval(c - 50, c - 50, 100, 100);
        double r = 40 + 60 * fx.distance;
        g.setStroke(Theme.web(fx.spatial ? "#6C78F7" : "#4A505C"));
        g.setLineWidth(1.5);
        double spread = fx.spatialMode.equals("speakers") ? 30 + 30 * fx.immersion : 0;
        double[] angles = {-90, -45, 45, -135, 135, 180 - spread, 0, 180};
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(new double[]{-90, -40 - spread, -140 + spread, 0, 180, 90}[i]);
            double x = c + r * Math.cos(a), y = c + r * Math.sin(a);
            g.strokeOval(x - 8, y - 8, 16, 16);
            g.strokeLine(x - 3, y, x + 3, y);
        }
        g.setFill(Theme.web(fx.spatial ? "#E6E8EC" : "#626977"));
        g.fillOval(c - 12, c - 8, 24, 16);
        g.fillRect(c - 15, c - 2, 30, 4);
    }

    private static ToggleButton seg(String text, ToggleGroup g) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().setAll("seg-btn");
        b.setToggleGroup(g);
        return b;
    }

    private VBox boostCard() {
        VBox c = Ui.section("Volume boost", null);
        c.getChildren().addAll(Ui.slider("Gain", 0, 12, fx.boostDb, " dB", 1, v -> { fx.boostDb = v; changed(); }),
                Ui.label("Makes quiet apps louder. A limiter stops it from distorting.", "small", "muted"));
        return c;
    }

    private VBox smartCard() {
        VBox c = Ui.section("Smart volume", Ui.toggleSwitch(fx.smartVolume, "Smart volume", b -> { fx.smartVolume = b; changed(); }));
        c.getChildren().addAll(Ui.slider("Level", 0, 100, fx.smartLevel * 100, "", 0, v -> { fx.smartLevel = v / 100; changed(); }),
                Ui.label("Evens out loud explosions and quiet dialogue.", "small", "muted"));
        return c;
    }

    private VBox noiseCard() {
        VBox c = Ui.section("Noise cancellation", Ui.toggleSwitch(fx.noiseCancel, "Noise cancellation", b -> { fx.noiseCancel = b; changed(); }));
        c.getChildren().addAll(Ui.slider("Min ⟷ Max", 0, 100, fx.noiseAmount * 100, "", 0, v -> { fx.noiseAmount = v / 100; changed(); }),
                Ui.label("Removes steady background noise (fans, hum) from your friends' microphones.", "small", "muted"));
        return c;
    }

    private VBox gateCard() {
        VBox c = Ui.section("Noise gate", Ui.toggleSwitch(fx.gate, "Noise gate", b -> { fx.gate = b; changed(); }));
        c.getChildren().addAll(Ui.slider("Threshold", -90, 0, fx.gateDb, " dB", 1, v -> { fx.gateDb = v; changed(); }),
                Ui.label("Silences chat between words.", "small", "muted"));
        return c;
    }

    private VBox compCard() {
        VBox c = Ui.section("Compressor", Ui.toggleSwitch(fx.compressor, "Compressor", b -> { fx.compressor = b; changed(); }));
        c.getChildren().addAll(Ui.slider("Level", 0, 100, fx.compAmount * 100, "", 0, v -> { fx.compAmount = v / 100; changed(); }),
                Ui.label("Makes loud and quiet friends sound about the same.", "small", "muted"));
        return c;
    }

    static List<String> sortedFx(List<String> l) { return FXCollections.observableArrayList(l); }
}
