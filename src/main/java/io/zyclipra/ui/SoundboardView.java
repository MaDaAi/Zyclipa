package io.zyclipra.ui;

import io.zyclipra.app.AppContext;
import io.zyclipra.audio.SoundDevices;
import io.zyclipra.core.Settings;
import io.zyclipra.soundboard.SoundboardService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Soundboard: a grid of sound pads. Click a pad to play/stop; everything else is in its ⋮ menu. */
public final class SoundboardView implements View {
    private final AppContext ctx;
    private final MainWindow win;
    private final BorderPane node = new BorderPane();
    private final FlowPane pads = new FlowPane(16, 16);
    private final HBox tabs = new HBox(4);
    private final ToggleGroup tabGroup = new ToggleGroup();
    private String category = "All";
    private final Timeline timer;
    private final Map<String, VBox> padById = new HashMap<>();

    public SoundboardView(AppContext ctx, MainWindow win) {
        this.ctx = ctx;
        this.win = win;
        SoundboardService sb = ctx.soundboard;
        Settings.Soundboard cfg = sb.config();

        Button importBtn = Ui.button("Add sounds", Icons.PLUS, this::importFiles, "primary");
        Button stopAll = Ui.button("Stop all", Icons.STOP, () -> { sb.stopAll(); refreshState(); });
        Slider master = Ui.fill(new Slider(0, 1, cfg.masterVolume));
        master.setPrefWidth(160);
        master.setAccessibleText("Soundboard volume");
        master.valueProperty().addListener((o, a, b) -> { cfg.masterVolume = b.doubleValue(); ctx.store.save(); });
        ComboBox<String> out = new ComboBox<>(), route = new ComboBox<>();
        out.setAccessibleText("Play on");
        route.setAccessibleText("Also send to");
        CompletableFuture.supplyAsync(SoundDevices::outputs).thenAccept(l -> Ui.fx(() -> {
            MixerView.fill(out, l, cfg.outputDevice, "Default playback device");
            MixerView.fill(route, l, cfg.micRouteDevice, "Nowhere");
        }));
        out.setOnAction(e -> { cfg.outputDevice = MixerView.val(out); ctx.store.save(); });
        route.setOnAction(e -> { cfg.micRouteDevice = MixerView.val(route); ctx.store.save(); });
        ToggleButton local = Ui.toggleSwitch(cfg.playLocally, "Play on my device", b -> { cfg.playLocally = b; ctx.store.save(); });

        VBox output = Ui.section("Output", null);
        output.getChildren().addAll(
                Ui.row(14, Ui.field("I hear sounds on", out), Ui.field("Friends hear sounds through (virtual cable used as mic in chat)", route)),
                Ui.row(10, Ui.label("Play on my device too", "h3"), local, Ui.grow(), Icons.of(Icons.VOLUME, 16), master, Ui.percent(master, 100)));

        Button newCat = Ui.button("New collection", Icons.FOLDER, () -> {
            TextInputDialog d = new TextInputDialog();
            d.setTitle("New collection");
            d.setHeaderText("Collection name");
            ClipsView.style(d.getDialogPane());
            d.showAndWait().ifPresent(n -> {
                try { sb.addCategory(n); category = n.trim(); refresh(); } catch (Exception e) { win.toast(e.getMessage(), true); }
            });
        }, "ghost");
        HBox head = Ui.row(10, tabs, newCat, Ui.grow(), stopAll, importBtn);

        VBox page = new VBox(16, head, pads, output);
        page.getStyleClass().add("page");
        page.setOnDragOver(e -> { if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY); e.consume(); });
        page.setOnDragDropped(e -> {
            if (e.getDragboard().hasFiles()) importList(e.getDragboard().getFiles());
            e.setDropCompleted(true);
            e.consume();
        });
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        node.setTop(MainWindow.pageHead("Soundboard"));
        node.setCenter(sp);
        timer = new Timeline(new KeyFrame(Duration.millis(250), e -> refreshState()));
        timer.setCycleCount(Timeline.INDEFINITE);
    }

    @Override public String id() { return "soundboard"; }
    @Override public String title() { return "Soundboard"; }
    @Override public String icon() { return Icons.PADS; }
    @Override public Node node() { return node; }
    @Override public void shown() { refresh(); timer.play(); }
    @Override public void hidden() { timer.stop(); }

    private void importFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Add sounds");
        List<String> ext = new ArrayList<>();
        for (String e : SoundboardService.FORMATS) ext.add("*." + e);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio files", ext));
        List<File> files = fc.showOpenMultipleDialog(node.getScene().getWindow());
        if (files != null) importList(files);
    }

    void importList(List<File> files) {
        String cat = category.equals("All") ? "General" : category;
        win.toast("Adding " + files.size() + " sound(s)…", false);
        CompletableFuture.runAsync(() -> {
            int ok = 0;
            for (File f : files) {
                try { ctx.soundboard.importFile(f.toPath(), cat); ok++; }
                catch (Exception e) { String m = e.getMessage(); Ui.fx(() -> win.toast(m, true)); }
            }
            int n = ok;
            Ui.fx(() -> { if (n > 0) win.toast("Added " + n + " sound(s)", false); refresh(); });
        });
    }

    void refresh() {
        Settings.Soundboard cfg = ctx.soundboard.config();
        tabs.getChildren().clear();
        List<String> cats = new ArrayList<>(List.of("All"));
        cats.addAll(cfg.categories);
        if (!cats.contains(category)) category = "All";
        for (String c : cats) {
            long n = c.equals("All") ? cfg.sounds.size() : cfg.sounds.stream().filter(s -> s.category.equals(c)).count();
            ToggleButton t = new ToggleButton(c + " (" + n + ")");
            t.getStyleClass().setAll("chip-tab");
            t.setToggleGroup(tabGroup);
            t.setSelected(c.equals(category));
            t.setOnAction(e -> { category = c; refresh(); });
            if (!c.equals("All") && !c.equals("General")) {
                MenuItem del = new MenuItem("Remove collection (its sounds move to General)");
                del.setOnAction(e -> { ctx.soundboard.removeCategory(c); category = "All"; refresh(); });
                t.setContextMenu(new ContextMenu(del));
            }
            tabs.getChildren().add(t);
        }
        pads.getChildren().clear();
        padById.clear();
        List<Settings.Sound> list = ctx.soundboard.sounds().stream().filter(s -> category.equals("All") || s.category.equals(category)).toList();
        if (list.isEmpty()) {
            pads.getChildren().add(Ui.empty(Icons.PADS, "No sounds yet", "Click “Add sounds” or drop MP3, WAV, OGG, FLAC or M4A files here."));
            return;
        }
        for (Settings.Sound s : list) {
            VBox p = pad(s);
            padById.put(s.id, p);
            pads.getChildren().add(p);
        }
        refreshState();
    }

    void refreshState() {
        for (Map.Entry<String, VBox> e : padById.entrySet()) {
            boolean on = ctx.soundboard.isPlaying(e.getKey());
            if (on && !e.getValue().getStyleClass().contains("pad-playing")) e.getValue().getStyleClass().add("pad-playing");
            if (!on) e.getValue().getStyleClass().remove("pad-playing");
            if (e.getValue().getProperties().get("play") instanceof Button b) {
                b.setGraphic(Icons.of(on ? Icons.STOP : Icons.PLAY, 16));
                b.setAccessibleText(on ? "Stop" : "Play");
            }
        }
    }

    private VBox pad(Settings.Sound s) {
        SoundboardService sb = ctx.soundboard;
        Label name = Ui.label(s.name, "pad-name");
        name.setWrapText(false);
        Button play = Ui.button(null, Icons.PLAY, () -> { sb.toggle(s.id); refreshState(); }, "primary");
        play.setAccessibleText("Play");
        HBox keys = Ui.keycaps(s.hotkey, "keycap");
        Label meta = Ui.label(Ui.duration(sb.durationSec(s)) + (s.loop ? "  ·  loops" : ""), "small", "muted");
        Button more = Ui.iconButton(Icons.MORE, "Sound options", () -> { });
        HBox top = Ui.row(8, play, new VBox(2, name, meta), Ui.grow(), more);
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        Slider vol = Ui.fill(new Slider(0, 1, s.volume));
        vol.setAccessibleText(s.name + " volume");
        HBox.setHgrow(vol, Priority.ALWAYS);
        vol.valueProperty().addListener((o, a, b) -> { s.volume = b.doubleValue(); sb.update(s); });
        Button assign = Ui.button(s.hotkey.isBlank() ? "Assign key" : null, null, () -> { }, "outline");
        if (!s.hotkey.isBlank()) assign.setGraphic(keys);
        assign.setAccessibleText("Shortcut for " + s.name);
        assign.setOnAction(e -> { assign.setGraphic(null); assign.setText("Press keys…"); });
        assign.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!"Press keys…".equals(assign.getText())) return;
            e.consume();
            String combo = e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.ESCAPE ? "" : SettingsView.comboText(e);
            if (combo == null) return;
            s.hotkey = combo;
            sb.update(s);
            String err = ctx.hotkeys.bind("sound:" + s.id, s.hotkey, () -> sb.toggle(s.id));
            if (err != null) win.toast(err, true);
            refresh();
        });
        HBox bottom = Ui.row(8, Icons.of(Icons.VOLUME, 14), vol, Ui.percent(vol, 100));
        assign.setMaxWidth(Double.MAX_VALUE);

        MenuItem loop = new MenuItem(s.loop ? "Stop looping" : "Loop this sound");
        loop.setOnAction(e -> { s.loop = !s.loop; sb.update(s); refresh(); });
        MenuItem again = new MenuItem("Play from the start");
        again.setOnAction(e -> { sb.play(s.id); refreshState(); });
        MenuItem rename = new MenuItem("Rename");
        rename.setOnAction(e -> {
            TextInputDialog d = new TextInputDialog(s.name);
            d.setTitle("Rename sound");
            d.setHeaderText("Sound name");
            ClipsView.style(d.getDialogPane());
            d.showAndWait().ifPresent(n -> { try { sb.rename(s.id, n); refresh(); } catch (Exception ex) { win.toast(ex.getMessage(), true); } });
        });
        MenuItem clearKey = new MenuItem("Remove shortcut");
        clearKey.setDisable(s.hotkey.isBlank());
        clearKey.setOnAction(e -> { s.hotkey = ""; sb.update(s); ctx.hotkeys.unbind("sound:" + s.id); refresh(); });
        ContextMenu menu = new ContextMenu(again, loop, rename, clearKey);
        for (String c : sb.config().categories) {
            if (c.equals(s.category)) continue;
            MenuItem m = new MenuItem("Move to " + c);
            m.setOnAction(e -> { s.category = c; sb.update(s); refresh(); });
            menu.getItems().add(m);
        }
        MenuItem del = new MenuItem("Delete");
        del.setOnAction(e -> {
            try { ctx.hotkeys.unbind("sound:" + s.id); sb.remove(s.id); refresh(); } catch (Exception ex) { win.toast(ex.getMessage(), true); }
        });
        menu.getItems().add(del);
        more.setOnAction(e -> menu.show(more, Side.BOTTOM, 0, 0));

        VBox v = new VBox(12, top, bottom, assign);
        v.getStyleClass().add("pad");
        v.getProperties().put("play", play);
        v.setOnMouseClicked(e -> {
            if (e.getTarget() == v || e.getTarget() instanceof Label) { sb.toggle(s.id); refreshState(); }
        });
        v.setOnContextMenuRequested(e -> menu.show(v, e.getScreenX(), e.getScreenY()));
        return v;
    }
}
