package io.zyclipra.ui;

import io.zyclipra.app.AppContext;
import io.zyclipra.core.Settings;
import io.zyclipra.hotkeys.Hotkey;
import io.zyclipra.platform.Desktop;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Settings: a short list of pages on the left, one page at a time on the right. */
public final class SettingsView implements View {
    private final AppContext ctx;
    private final MainWindow win;
    private final BorderPane node = new BorderPane();
    private final StackPane pageHolder = new StackPane();
    private final ToggleGroup nav = new ToggleGroup();
    private final Map<String, Supplier<Node>> pages = new LinkedHashMap<>();
    private final Map<String, ToggleButton> links = new LinkedHashMap<>();
    private String page = "General";

    public SettingsView(AppContext ctx, MainWindow win) {
        this.ctx = ctx;
        this.win = win;
        VBox list = new VBox();
        list.getStyleClass().add("settings-nav");
        list.getChildren().add(Ui.label("SETTINGS", "section-title"));
        group(list, "GENERAL", "General", this::general);
        group(list, "CLIPS", "Capture and Sound", this::capture);
        link(list, "Clip Settings", this::clipSettings);
        link(list, "Shortcuts", this::shortcuts);
        group(list, "CONNECT", "Cloud & Sync", this::cloud);
        link(list, "Hosting", this::dashboard);
        group(list, "", "About and Privacy", this::about);
        ScrollPane navScroll = new ScrollPane(list);
        navScroll.setFitToWidth(true);
        navScroll.setFitToHeight(true);
        navScroll.setMinWidth(250);
        node.setLeft(navScroll);
        node.setCenter(pageHolder);
    }

    @Override public String id() { return "settings"; }
    @Override public String title() { return "Settings"; }
    @Override public String icon() { return Icons.SETTINGS; }
    @Override public Node node() { return node; }
    @Override public void shown() { open(page); }

    private void group(VBox list, String title, String first, Supplier<Node> p) {
        if (!title.isEmpty()) list.getChildren().add(Ui.label(title, "settings-group"));
        else list.getChildren().add(Ui.label(" ", "settings-group"));
        link(list, first, p);
    }

    private void link(VBox list, String name, Supplier<Node> p) {
        ToggleButton b = new ToggleButton(name);
        b.getStyleClass().setAll("settings-link");
        b.setToggleGroup(nav);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> open(name));
        pages.put(name, p);
        links.put(name, b);
        list.getChildren().add(b);
    }

    public void open(String name) {
        page = name;
        links.get(name).setSelected(true);
        VBox body = new VBox();
        body.getStyleClass().add("settings-body");
        body.getChildren().add(pages.get(name).get());
        ScrollPane sp = new ScrollPane(body);
        sp.setFitToWidth(true);
        pageHolder.getChildren().setAll(sp);
    }

    private void save() { ctx.store.save(); }

    private static VBox pageBox(String title, String lead, Node... rows) {
        VBox v = new VBox(Ui.label(title.toUpperCase(Locale.ROOT), "settings-h"));
        if (lead != null) v.getChildren().add(Ui.label(lead, "settings-lead"));
        v.getChildren().add(MainWindow.rule());
        v.getChildren().addAll(rows);
        return v;
    }

    private static Label sub(String t) {
        Label l = Ui.label(t.toUpperCase(Locale.ROOT), "settings-h");
        l.setStyle("-fx-font-size: 16px; -fx-padding: 28 0 4 0;");
        return l;
    }

    private ToggleButton sw(boolean on, String what, java.util.function.Consumer<Boolean> set) {
        return Ui.toggleSwitch(on, what, b -> { set.accept(b); save(); });
    }

    private static ComboBox<String> combo(List<String> items, String value) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(new ArrayList<>(items)));
        c.setValue(value);
        return c;
    }

    // ---------------- General ----------------
    private Node general() {
        Settings.General g = ctx.settings.general;
        FlowPane swatches = new FlowPane(8, 8);
        for (String hex : List.of("#5B6CF9", "#F04452", "#2BD9A0", "#3DA9F5", "#FF7A45", "#F5A524", "#9D63F7")) {
            Button b = new Button();
            b.setStyle("-fx-background-color: " + hex + "; -fx-background-radius: 999; -fx-min-width: 28; -fx-min-height: 28; -fx-max-width: 28; -fx-max-height: 28;"
                    + (hex.equalsIgnoreCase(g.accent) ? "-fx-border-color: white; -fx-border-radius: 999; -fx-border-width: 2;" : ""));
            b.setAccessibleText("Accent colour " + hex);
            b.setOnAction(e -> { g.accent = hex; win.applyAccent(hex); save(); open("General"); });
            swatches.getChildren().add(b);
        }
        Settings.Recording r = ctx.settings.recording;
        return pageBox("General", "Preferences that apply to all of Zyclipra.",
                Ui.settingRow("Accent colour", "The colour of buttons, switches and highlights.", swatches),
                Ui.settingRow("Instant replay at start-up", "Start keeping the last few minutes ready to clip as soon as Zyclipra opens.",
                        sw(r.replayEnabled, "Instant replay at start-up", v -> r.replayEnabled = v)),
                Ui.settingRow("Recording indicator", "Show a small “REC” badge in the corner of your screen while recording, and a flash when a clip is saved.",
                        sw(g.showOverlay, "Recording indicator", v -> g.showOverlay = v)),
                Ui.settingRow("Reduce motion", "Turn off fade animations.", sw(g.reduceMotion, "Reduce motion", v -> g.reduceMotion = v)));
    }

    // ---------------- Capture and Sound ----------------
    private Node capture() {
        Settings.Recording r = ctx.settings.recording;
        Settings.General g = ctx.settings.general;
        ComboBox<String> mode = combo(List.of("My whole screen", "One window (game)"), r.captureMode.equals("window") ? "One window (game)" : "My whole screen");
        ComboBox<String> display = new ComboBox<>();
        ComboBox<String> window = new ComboBox<>();
        window.setEditable(true);
        window.setValue(r.windowTitle);
        window.setPromptText("Pick the game window");
        CompletableFuture.supplyAsync(() -> List.of(ctx.desktop.displays(), ctx.desktop.windows())).thenAccept(l -> Ui.fx(() -> {
            @SuppressWarnings("unchecked") List<Desktop.Display> ds = (List<Desktop.Display>) l.get(0);
            @SuppressWarnings("unchecked") List<Desktop.Window> ws = (List<Desktop.Window>) l.get(1);
            display.getItems().setAll(ds.stream().map(Desktop.Display::label).toList());
            display.getSelectionModel().select(Math.min(r.displayIndex, ds.size() - 1));
            window.getItems().setAll(ws.stream().map(Desktop.Window::title).distinct().toList());
        }));
        display.setOnAction(e -> { if (display.getSelectionModel().getSelectedIndex() >= 0) { r.displayIndex = display.getSelectionModel().getSelectedIndex(); save(); } });
        window.valueProperty().addListener((o, a, b) -> { r.windowTitle = b == null ? "" : b.trim(); save(); });
        Runnable showWhich = () -> { boolean w = r.captureMode.equals("window"); window.setVisible(w); window.setManaged(w); display.setVisible(!w); display.setManaged(!w); };
        mode.setOnAction(e -> { r.captureMode = mode.getValue().startsWith("One") ? "window" : "display"; save(); showWhich.run(); });
        showWhich.run();

        VBox audio = new VBox(10);
        CompletableFuture.supplyAsync(() -> ctx.capture.ffmpeg().map(f -> f.listAudioDevices()).orElse(List.of())).thenAccept(list -> Ui.fx(() -> {
            if (list.isEmpty()) { audio.getChildren().add(Ui.label("No recording devices found (FFmpeg is needed).", "muted")); return; }
            for (String dev : list) {
                CheckBox c = new CheckBox(dev);
                c.setSelected(r.audioInputs.contains(dev));
                c.selectedProperty().addListener((o, a, b) -> {
                    if (b && !r.audioInputs.contains(dev)) r.audioInputs.add(dev);
                    if (!b) r.audioInputs.remove(dev);
                    save();
                });
                audio.getChildren().add(c);
            }
        }));

        TextField ff = new TextField(g.ffmpegPath);
        ff.setPromptText(ctx.capture.ffmpeg().map(f -> "Found automatically: " + f.path()).orElse("Not found – choose ffmpeg.exe"));
        ff.setEditable(false);
        HBox.setHgrow(ff, Priority.ALWAYS);
        Button pick = Ui.button("Choose…", null, () -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Find ffmpeg.exe");
            File f = fc.showOpenDialog(node.getScene().getWindow());
            if (f == null) return;
            g.ffmpegPath = f.getAbsolutePath();
            save();
            ctx.capture.refresh();
            open("Capture and Sound");
        }, "outline");

        HBox target = Ui.row(12, mode, display, window);
        return pageBox("Capture and Sound", "Choose what gets recorded and which sounds are captured.",
                sub("Gameplay capture"),
                Ui.settingRow("What to record", "Your whole screen works with every game. Picking one window records only that game.", null),
                target,
                sub("Audio"),
                Ui.settingRow("Record game audio", "Everything playing on your PC except Zyclipra itself, on its own track.",
                        sw(r.recordGameAudio, "Record game audio", v -> r.recordGameAudio = v)),
                Ui.settingRow("Record chat audio", "Your voice-chat output on its own track (when chat uses a different output device than games).",
                        sw(r.recordChatAudio, "Record chat audio", v -> r.recordChatAudio = v)),
                Ui.settingRow("Record voice through mic while gaming", "Your microphone on its own track. Mute it any time with " + ctx.settings.hotkeys.get("toggleMicMute") + ".",
                        sw(r.recordMic, "Record microphone", v -> r.recordMic = v)),
                Ui.settingRow("Record the soundboard", "Soundboard sounds on their own track, so you can remove them from a clip later.",
                        sw(r.recordSoundboard, "Record soundboard", v -> r.recordSoundboard = v)),
                Ui.settingRow("Extra recording devices", "Optional: add more inputs as extra tracks (for example a second microphone).", null),
                audio,
                Ui.settingRow("Separate tracks", "Keep each sound source on its own audio track so you can edit them apart later.",
                        sw(r.separateTracks, "Separate tracks", v -> r.separateTracks = v)),
                sub("Recorder"),
                Ui.settingRow("FFmpeg", "Zyclipra uses the free FFmpeg program to record. It's usually found automatically.", null),
                Ui.row(10, ff, pick));
    }

    // ---------------- Clip Settings ----------------
    private Node clipSettings() {
        Settings.Recording r = ctx.settings.recording;
        ComboBox<String> dur = combo(List.of(ClipsView.REPLAY_LENGTHS), ClipsView.lengthText(r.replaySeconds));
        ComboBox<String> q = combo(new ArrayList<>(ClipsView.QUALITY.keySet()), ClipsView.qualityName(r.bitrateKbps));
        ComboBox<String> res = combo(List.of("Native", "1440p", "1080p", "720p", "480p"), r.outputHeight == 0 ? "Native" : r.outputHeight + "p");
        ComboBox<String> fps = combo(List.of("30", "60", "120", "144"), String.valueOf(r.fps));
        ComboBox<String> fmt = combo(List.of("MP4", "MKV"), r.container.toUpperCase(Locale.ROOT));
        ComboBox<String> enc = combo(List.of("auto", "h264_nvenc", "h264_qsv", "h264_amf", "libx264"), r.encoder);
        Label estimate = new Label();
        Label encInfo = Ui.label("Checking your graphics card…", "small", "muted");
        Runnable upd = () -> {
            long mb = (long) r.bitrateKbps * r.replaySeconds / 8 / 1024;
            estimate.setText("Estimated clip size: " + mb + " MB per clip (" + ClipsView.lengthText(r.replaySeconds) + " at " + ClipsView.qualityName(r.bitrateKbps) + " quality)");
        };
        upd.run();
        dur.setOnAction(e -> { String[] p = dur.getValue().split(":"); r.replaySeconds = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); save(); upd.run(); });
        q.setOnAction(e -> { r.bitrateKbps = ClipsView.QUALITY.get(q.getValue()); save(); upd.run(); });
        res.setOnAction(e -> { String v = res.getValue(); r.outputHeight = v.equals("Native") ? 0 : Integer.parseInt(v.replace("p", "")); save(); });
        fps.setOnAction(e -> { r.fps = Integer.parseInt(fps.getValue()); save(); });
        fmt.setOnAction(e -> { r.container = fmt.getValue().toLowerCase(Locale.ROOT); save(); });
        enc.setOnAction(e -> { r.encoder = enc.getValue(); save(); });
        CompletableFuture.supplyAsync(() -> ctx.capture.ffmpeg().isPresent() ? ctx.capture.encoders() : List.<String>of()).thenAccept(l -> Ui.fx(() ->
                encInfo.setText(l.isEmpty() ? "FFmpeg not found." : "Using " + ctx.capture.encoder()
                        + (ctx.capture.encoder().equals("libx264") ? " (processor – no supported graphics card encoder found)" : " (graphics card – lighter on your games)"))));

        // disk usage bar
        Path root = Path.of(r.clipsDir);
        long total = 0, free = 0;
        try { Files.createDirectories(root); FileStore fs = Files.getFileStore(root); total = fs.getTotalSpace(); free = fs.getUsableSpace(); } catch (Exception ignored) { }
        long clips = ctx.library.totalBytes();
        long used = Math.max(0, total - free);
        // A plain Pane sized by its parent: the coloured parts are laid out from the pane's width but
        // never feed back into it (an HBox here grew a little on every layout pass).
        javafx.scene.layout.Pane barBox = new javafx.scene.layout.Pane();
        barBox.setMinWidth(0);
        barBox.setPrefWidth(0);
        barBox.setMaxWidth(Double.MAX_VALUE);
        barBox.setMinHeight(8);
        barBox.setPrefHeight(8);
        Rectangle cR = new Rectangle(0, 8), oR = new Rectangle(0, 8), fR = new Rectangle(0, 8);
        cR.setStyle("-fx-fill: -ok;");
        oR.setStyle("-fx-fill: #3DA9F5;");
        fR.getStyleClass().add("disk-bar-bg");
        for (Rectangle x : new Rectangle[]{cR, oR, fR}) x.setManaged(false);
        barBox.getChildren().addAll(fR, oR, cR);
        long tt = Math.max(1, total);
        barBox.widthProperty().addListener((o, a, b) -> {
            double w = b.doubleValue();
            double cw = Math.max(clips > 0 ? 2 : 0, w * clips / tt), ow = w * Math.max(0, used - clips) / tt;
            cR.setWidth(cw);
            oR.setX(cw);
            oR.setWidth(ow);
            fR.setWidth(w);
        });
        long perClip = Math.max(1, (long) r.bitrateKbps * r.replaySeconds / 8 * 1024);
        Rectangle k1 = new Rectangle(9, 9), k2 = new Rectangle(9, 9);
        k1.setStyle("-fx-fill: -ok;");
        k2.setStyle("-fx-fill: #3DA9F5;");
        HBox legend = Ui.row(8, k1, Ui.label("Clips: " + Ui.bytes(clips) + " (" + ctx.library.all().size() + ")", "small"),
                k2, Ui.label("Rest of PC: " + Ui.bytes(Math.max(0, used - clips)), "small"), Ui.grow(),
                Ui.label("Room for about " + (free / perClip) + " more clips", "small"));
        Label path = Ui.label(r.clipsDir, "accent-text");
        Button change = Ui.button("CHANGE LOCATION", null, () -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Where should clips be saved?");
            File cur = new File(r.clipsDir);
            if (cur.isDirectory()) dc.setInitialDirectory(cur);
            File f = dc.showDialog(node.getScene().getWindow());
            if (f == null) return;
            if (!Files.isWritable(f.toPath())) { win.toast("Zyclipra can't save into that folder.", true); return; }
            r.clipsDir = f.getAbsolutePath();
            save();
            ctx.library.scanAsync();
            open("Clip Settings");
        }, "outline");
        VBox loc = new VBox(4, Ui.label("Storage location", "h2"), path);
        HBox locRow = Ui.row(10, loc, Ui.grow(), change);
        locRow.getStyleClass().add("card");

        return pageBox("Clip Settings", "Adjust settings for new clips, see how big they'll be, and choose where they're saved.",
                sub("Clip settings"),
                Ui.label("Higher settings look better; lower settings are lighter on your PC and use less space.", "setting-desc"),
                spacer(10),
                Ui.row(12, Ui.field("Instant replay length", dur), Ui.field("Video quality", q), Ui.field("Resolution", res), Ui.field("FPS", fps)),
                spacer(8),
                Ui.row(12, Ui.field("File type", fmt), Ui.field("Encoder", enc)),
                spacer(8), estimate, encInfo,
                sub("Hard drive space"),
                Ui.row(10, Ui.label(Ui.bytes(free) + " free of " + Ui.bytes(total), "h3"), Ui.grow(),
                        Ui.label(total == 0 ? "" : (100 * used / tt) + "% used", "muted")),
                barBox, legend, spacer(14), locRow);
    }

    private static javafx.scene.layout.Region spacer(double h) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinHeight(h);
        return r;
    }

    // ---------------- Shortcuts ----------------
    private Node shortcuts() {
        VBox rows = new VBox(10);
        for (var e : AppContext.HOTKEY_LABELS.entrySet()) {
            String id = e.getKey();
            String cur = ctx.settings.hotkeys.getOrDefault(id, "");
            Button key = new Button();
            key.getStyleClass().add("outline");
            key.setAccessibleText("Shortcut for " + e.getValue());
            Runnable show = () -> {
                String v = ctx.settings.hotkeys.getOrDefault(id, "");
                if (v.isBlank()) { key.setText("Assign Key"); key.setGraphic(null); }
                else { key.setText(null); key.setGraphic(Ui.keycaps(v, "keycap-dark")); }
            };
            show.run();
            Label err = Ui.label(ctx.hotkeyErrors().getOrDefault(id, ""), "small", "danger-text");
            key.setOnAction(ev -> { key.setGraphic(null); key.setText("Press keys…"); });
            key.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
                if (!"Press keys…".equals(key.getText())) return;
                ev.consume();
                String combo = ev.getCode() == KeyCode.BACK_SPACE || ev.getCode() == KeyCode.DELETE || ev.getCode() == KeyCode.ESCAPE ? "" : comboText(ev);
                if (combo == null) return;
                ctx.settings.hotkeys.put(id, combo);
                save();
                err.setText(ctx.bindHotkeys().getOrDefault(id, ""));
                show.run();
            });
            HBox row = new HBox(10, Ui.label(e.getValue(), "h3"), err, Ui.grow(), key);
            row.getStyleClass().add("shortcut-row");
            rows.getChildren().add(row);
        }
        String note = ctx.hotkeys.supported() ? "Shortcuts work everywhere, even in a game. Click a shortcut, then press the new keys (Backspace clears it). Use Ctrl, Alt or Shift plus one key."
                : "Shortcuts that work in games aren't supported on this system yet.";
        return pageBox("Shortcuts", null, sub("Clips, audio and soundboard"), Ui.label(note, "setting-desc"), spacer(14), rows,
                spacer(14), Ui.label("Inside Zyclipra: Ctrl+1 Clips · Ctrl+2 Audio Mixer · Ctrl+3 Soundboard · Ctrl+4 Settings", "small", "muted"));
    }

    /** Text like "Ctrl+Alt+F9" for a key press; null while only modifiers are held. */
    static String comboText(KeyEvent e) {
        KeyCode c = e.getCode();
        if (c.isModifierKey() || c == KeyCode.UNDEFINED) return null;
        StringBuilder b = new StringBuilder();
        if (e.isControlDown()) b.append("Ctrl+");
        if (e.isAltDown()) b.append("Alt+");
        if (e.isShiftDown()) b.append("Shift+");
        if (e.isMetaDown()) b.append("Win+");
        String name = switch (c) {
            case PAGE_UP -> "PageUp";
            case PAGE_DOWN -> "PageDown";
            case PRINTSCREEN -> "PrintScreen";
            case SCROLL_LOCK -> "ScrollLock";
            case BACK_QUOTE -> "BackQuote";
            case OPEN_BRACKET -> "OpenBracket";
            case CLOSE_BRACKET -> "CloseBracket";
            default -> {
                String n = c.getName().replace(" ", "");
                if (c.isKeypadKey()) n = "NumPad" + n.replace("Numpad", "");
                yield n;
            }
        };
        b.append(name);
        String s = b.toString();
        return Hotkey.parse(s).map(Hotkey::text).orElse(s);
    }

    // ---------------- Cloud & Sync (Google Drive) ----------------
    private Node cloud() {
        Settings.Cloud c = ctx.settings.cloud;
        io.zyclipra.cloud.SyncService sync = ctx.sync;
        boolean connected = sync.connected();

        // --- account
        VBox account = Ui.section("Google account", null);
        if (connected) {
            HBox who = new HBox(14);
            who.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            java.nio.file.Path pic = sync.auth().avatar();
            if (pic != null && java.nio.file.Files.isRegularFile(pic)) {
                javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(new javafx.scene.image.Image(pic.toUri().toString(), 48, 48, true, true));
                javafx.scene.shape.Circle mask = new javafx.scene.shape.Circle(24, 24, 24);
                iv.setClip(mask);
                who.getChildren().add(iv);
            }
            who.getChildren().add(new VBox(2, Ui.label(c.accountName.isBlank() ? "Connected" : c.accountName, "h3"),
                    Ui.label(c.accountEmail, "small", "muted"), Ui.label("● Connected to Google Drive", "small", "ok-text")));
            Button disconnect = Ui.button("Disconnect", null, () -> {
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        "Zyclipra stops uploading and forgets the Google sign-in on this PC. Nothing is deleted: clips on this PC and copies on Google Drive stay where they are.",
                        javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
                a.setTitle("Disconnect Google Drive");
                a.setHeaderText("Disconnect " + (c.accountEmail.isBlank() ? "Google Drive" : c.accountEmail) + "?");
                ClipsView.style(a.getDialogPane());
                if (a.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try { sync.disconnect(); Ui.fx(() -> { win.toast("Google Drive disconnected.", false); open("Cloud & Sync"); }); }
                    catch (Exception e) { Ui.fx(() -> win.toast("Disconnect: " + e.getMessage(), true)); }
                });
            }, "outline");
            account.getChildren().add(Ui.row(12, who, Ui.grow(), disconnect));
        } else {
            Label st = Ui.label(c.needsReauth
                    ? "Google asked Zyclipra to sign in again (the saved sign-in was revoked or expired" + (c.accountEmail.isBlank() ? "" : " for " + c.accountEmail) + "). Uploads are paused until you reconnect."
                    : "Not connected. Everything works without Google Drive – it's only an optional backup.", "setting-desc");
            if (c.needsReauth) st.getStyleClass().setAll("label", "warn-text");
            Button connect = Ui.button(c.needsReauth ? "Sign in with Google again" : "Sign in with Google", Icons.CLOUD, () -> { }, "primary");
            connect.getStyleClass().add("google-signin");
            connect.setOnAction(e -> {
                connect.setDisable(true);
                st.setText("Finish signing in in the browser window that just opened… (Zyclipra waits up to 5 minutes)");
                sync.auth().signIn(url -> DriveSyncBox.browse(url)).whenComplete((email, err) -> Ui.fx(() -> {
                    save();
                    if (err != null) {
                        connect.setDisable(false);
                        String m = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
                        st.setText("Google sign-in didn't finish: " + m);
                        win.toast("Google sign-in failed: " + m, true);
                    } else {
                        win.toast("Connected" + (email.isBlank() ? "" : " as " + email), false);
                        open("Cloud & Sync");
                    }
                }));
            });
            boolean can = sync.auth().available();
            connect.setDisable(!can);
            account.getChildren().addAll(st, Ui.row(12, connect, can
                    ? Ui.label("Opens Google in your browser – choose your account and allow access to the files Zyclipra creates.", "small", "muted")
                    : Ui.label("This copy of Zyclipra was built without Google sign-in.", "small", "warn-text")));
        }

        // --- what gets uploaded
        VBox prefs = Ui.section("Automatic upload", sw(c.autoUpload, "Automatic upload", v -> { c.autoUpload = v; save(); }));
        prefs.getChildren().addAll(
                Ui.label("New clips are copied to Google Drive as soon as they're saved – including ones you clip from the dashboard. "
                        + "Older clips stay local unless you upload them yourself (clip menu › Upload to Google Drive).", "small", "muted"),
                Ui.settingRow("Clips", "Instant replays and trimmed clips", sw(c.uploadClips, "Upload clips", v -> { c.uploadClips = v; save(); })),
                Ui.settingRow("Recordings", "Recordings you started and stopped", sw(c.uploadRecordings, "Upload recordings", v -> { c.uploadRecordings = v; save(); })),
                Ui.settingRow("Screenshots", null, sw(c.uploadScreenshots, "Upload screenshots", v -> { c.uploadScreenshots = v; save(); })));

        ToggleButton keep = sw(c.keepLocal, "Keep local copies", v -> { });
        keep.selectedProperty().addListener((o, a, b) -> {
            if (b == c.keepLocal) return;
            if (!b) {
                javafx.scene.control.Alert al = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                        "After each upload is verified (same size and checksum as on this PC), the clip on this PC goes to the Recycle Bin to save space. "
                                + "It's then only on Google Drive (you can download it back from this page). Clips that aren't uploaded are never touched.",
                        javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
                al.setTitle("Free up space after upload");
                al.setHeaderText("Remove clips from this PC after they're on Google Drive?");
                ClipsView.style(al.getDialogPane());
                if (al.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) { keep.setSelected(true); return; }
            }
            c.keepLocal = b;
            save();
        });
        VBox storage = Ui.section("Where your clips are", null);
        storage.getChildren().addAll(
                Ui.settingRow("On this PC (your main library)", ctx.settings.recording.clipsDir + "\nRecording, instant replay and screenshots always work here, even offline.", Ui.grow()),
                Ui.settingRow("On Google Drive (backup copies)", "My Drive › " + c.folderName + " › Clips, Recordings, Screenshots",
                        Ui.button("Open in Google Drive", Icons.CLOUD, () -> DriveSyncBox.browse(sync.folderUrl()), "outline")),
                Ui.settingRow("Keep clips on this PC after uploading", "On (recommended): uploading never removes anything from this PC.", keep));

        // --- status and queue (live)
        VBox status = Ui.section("Sync status", null);
        VBox queue = new VBox(6);
        Label summary = Ui.label("", "setting-desc");
        Runnable fill = () -> fillQueue(queue, summary);
        fill.run();
        Button retryAll = Ui.button("Retry failed uploads", Icons.REPLAY, () -> { int n = sync.retry(null); win.toast(n == 0 ? "Nothing to retry." : "Retrying " + n + ".", false); fill.run(); }, "outline");
        Button check = Ui.button("Check cloud copies", Icons.REFRESH, () -> java.util.concurrent.CompletableFuture.supplyAsync(sync::verifyCloudCopies)
                .thenAccept(n -> Ui.fx(() -> { win.toast(n == 0 ? "All synced clips are still on Google Drive." : n + " clip(s) are no longer on Google Drive.", n > 0); fill.run(); })), "outline");
        check.setDisable(!connected);
        status.getChildren().addAll(summary, Ui.row(10, retryAll, check), queue);
        Runnable[] unsub = {() -> { }};
        javafx.animation.PauseTransition throttle = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
        throttle.setOnFinished(e -> fill.run());
        status.sceneProperty().addListener((o, a, b) -> {
            unsub[0].run();
            if (b != null) unsub[0] = sync.onChange(i -> Ui.fx(throttle::playFromStart));
        });

        return pageBox("Cloud & Sync", "Optional: keep copies of your clips on your own Google Drive. Zyclipra can only see files it created there.",
                account, spacer(14), prefs, spacer(14), storage, spacer(14), status);
    }

    private void fillQueue(VBox queue, Label summary) {
        io.zyclipra.cloud.SyncService sync = ctx.sync;
        com.google.gson.JsonObject st = sync.statusJson();
        com.google.gson.JsonObject counts = st.getAsJsonObject("counts");
        java.util.function.Function<String, Integer> n = k -> counts.has(k) ? counts.get(k).getAsInt() : 0;
        long last = ctx.settings.cloud.lastSyncAt;
        summary.setText(n.apply("synced") + " synced · " + (n.apply("queued") + n.apply("uploading")) + " waiting · " + n.apply("failed") + " failed"
                + (n.apply("unavailable") > 0 ? " · " + n.apply("unavailable") + " missing from Drive" : "")
                + "\nLast successful upload: " + (last == 0 ? "never" : java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.ENGLISH)
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(last)))
                + (sync.lastProblem().isBlank() ? "" : "\nLast problem: " + sync.lastProblem()));
        queue.getChildren().clear();
        for (io.zyclipra.cloud.SyncService.Item i : sync.items()) {
            boolean cloudOnly = io.zyclipra.cloud.SyncService.SYNCED.equals(i.state) && ctx.library.byId(i.clipId).isEmpty();
            if (io.zyclipra.cloud.SyncService.SYNCED.equals(i.state) && !cloudOnly) continue;
            String label = switch (i.state) {
                case "queued" -> "Queued" + (i.error.isBlank() ? "" : " – " + i.error);
                case "uploading" -> "Uploading " + Ui.bytes(i.done) + " / " + Ui.bytes(Math.max(1, i.size));
                case "failed" -> "Failed – " + i.error;
                case "unavailable" -> "Cloud file unavailable – deleted from Google Drive";
                default -> "On Google Drive only" + (i.localRemoved ? " (moved off this PC after upload)" : "");
            };
            Label l = Ui.label(label, "small", i.state.equals("failed") ? "danger-text" : i.state.equals("unavailable") ? "warn-text" : "muted");
            HBox row = Ui.row(10, new VBox(2, Ui.label(i.title, "h3"), l), Ui.grow());
            if (i.state.equals("uploading")) {
                javafx.scene.control.ProgressBar pb = new javafx.scene.control.ProgressBar(i.done / (double) Math.max(1, i.size));
                pb.setPrefWidth(160);
                row.getChildren().add(pb);
            }
            if (i.state.equals("failed") || i.state.equals("unavailable"))
                row.getChildren().add(Ui.button(i.state.equals("failed") ? "Retry" : "Upload again", Icons.REPLAY, () -> { sync.retry(i.clipId); fillQueue(queue, summary); }, "outline"));
            if (i.state.equals("queued") || i.state.equals("uploading") || i.state.equals("failed"))
                row.getChildren().add(Ui.button("Cancel", null, () -> { try { sync.cancel(i.clipId); } catch (IllegalStateException ignored) { } fillQueue(queue, summary); }, "ghost"));
            if (cloudOnly) {
                javafx.scene.control.ProgressBar pb = new javafx.scene.control.ProgressBar(0);
                pb.setPrefWidth(120);
                pb.setVisible(false);
                Button dl = Ui.button("Download to this PC", null, () -> { }, "outline");
                dl.setOnAction(e -> {
                    dl.setDisable(true);
                    pb.setVisible(true);
                    sync.download(i.clipId, p -> Ui.fx(() -> pb.setProgress(p))).whenComplete((path, err) -> Ui.fx(() -> {
                        if (err != null) { win.toast("Download failed: " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()), true); dl.setDisable(false); }
                        else { win.toast("Downloaded to " + path, false); fillQueue(queue, summary); }
                    }));
                });
                row.getChildren().addAll(pb, dl);
            }
            row.getStyleClass().add("shortcut-row");
            queue.getChildren().add(row);
        }
        if (queue.getChildren().isEmpty()) queue.getChildren().add(Ui.label("Nothing waiting. Clips you upload appear here while they upload.", "small", "muted"));
    }

    // ---------------- Hosting (remote dashboard) ----------------
    private Node dashboard() {
        Settings.Remote r = ctx.settings.remote;
        io.zyclipra.remote.DashboardServer server = ctx.dashboard;
        io.zyclipra.remote.DashboardAuth auth = ctx.dashboardAuth;

        // --- status
        VBox status = Ui.section("Dashboard status", null);
        boolean running = server.running();
        Label state = Ui.label(running ? "● Running" : "● Stopped", "h3");
        state.getStyleClass().add(running ? "ok-text" : "muted");
        status.getChildren().add(state);
        if (!server.lastError().isBlank() && !running) status.getChildren().add(Ui.label(server.lastError(), "small", "danger-text"));
        if (running) {
            for (io.zyclipra.remote.DashboardServer.Url u : server.addresses()) {
                Button copy = Ui.button("Copy", null, () -> {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(u.url());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                    win.toast("Address copied", false);
                }, "outline");
                Button openB = Ui.button("Open", null, () -> DriveSyncBox.browse(u.url()), "outline");
                HBox row = Ui.row(10, new VBox(2, Ui.label(u.kind(), "small", "muted"), Ui.label(u.url(), "h3")), Ui.grow(), copy, openB);
                row.getStyleClass().add("shortcut-row");
                status.getChildren().add(row);
            }
            status.getChildren().add(Ui.label("Encrypted (HTTPS). The certificate is made on this PC, so browsers warn once – check the fingerprint matches:\n"
                    + server.fingerprint(), "small", "muted"));
        }
        Spinner<Integer> port = new Spinner<>(1024, 65535, r.port);
        port.setEditable(true);
        port.valueProperty().addListener((o, a, b) -> { r.port = b; save(); });
        Runnable startNow = () -> {
            try { server.restart(); r.enabled = true; save(); win.toast("Dashboard running", false); }
            catch (Exception e) { r.enabled = false; save(); win.toast("The dashboard couldn't start: " + e.getMessage(), true); }
            open("Hosting");
        };
        Button start = Ui.button(running ? "Restart" : "Start dashboard", Icons.PLAY, startNow::run, "primary");
        start.setDisable(!auth.configured());
        Button stop = Ui.button("Stop", Icons.STOP, () -> { server.stop(); r.enabled = false; save(); open("Hosting"); }, "outline");
        stop.setDisable(!running);
        status.getChildren().addAll(Ui.row(12, Ui.label("Port", "h3"), port, Ui.label(running && port.getValue() != server.port() ? "Restart to use the new port" : "", "small", "muted"),
                Ui.grow(), start, stop));
        if (!auth.configured()) status.getChildren().add(Ui.label("Create the dashboard sign-in below before starting it.", "small", "warn-text"));

        // --- sign-in (account & security)
        VBox security = Ui.section("Dashboard sign-in", null);
        if (!auth.configured()) {
            TextField user = new TextField();
            user.setPromptText("username");
            PasswordField pw = new PasswordField(), pw2 = new PasswordField();
            pw.setPromptText("at least " + auth.minPasswordLength() + " characters");
            pw2.setPromptText("type it again");
            Label err = Ui.label("", "small", "danger-text");
            Button create = Ui.button("Create sign-in", Icons.KEY, () -> {
                try {
                    auth.setCredentials(user.getText().trim(), pw.getText(), pw2.getText());
                    win.toast("Dashboard sign-in created. You can start the dashboard now.", false);
                    open("Hosting");
                } catch (IllegalArgumentException e) { err.setText(e.getMessage()); }
            }, "primary");
            security.getChildren().addAll(
                    Ui.label("There's no default account. Choose the username and password the dashboard will ask for. "
                            + "The password is stored only as a secure hash (Argon2id).", "small", "muted"),
                    Ui.row(12, Ui.field("Username", user), Ui.field("Password", pw), Ui.field("Confirm password", pw2)), err, Ui.row(12, create));
        } else {
            PasswordField cur = new PasswordField(), np = new PasswordField(), np2 = new PasswordField();
            cur.setPromptText("current password");
            np.setPromptText("new password");
            np2.setPromptText("new password again");
            Label err = Ui.label("", "small", "danger-text");
            Button change = Ui.button("Change password", Icons.KEY, () -> {
                try { auth.changePassword(null, cur.getText(), np.getText(), np2.getText()); win.toast("Password changed. All browsers must sign in again.", false); open("Hosting"); }
                catch (RuntimeException e) { err.setText(e.getMessage()); }
            }, "outline");
            TextField newUser = new TextField(auth.username());
            PasswordField cur2 = new PasswordField();
            cur2.setPromptText("current password");
            Button rename = Ui.button("Change username", null, () -> {
                try { auth.changeUsername(cur2.getText(), newUser.getText().trim()); win.toast("Username changed.", false); open("Hosting"); }
                catch (RuntimeException e) { err.setText(e.getMessage()); }
            }, "outline");
            VBox sessions = new VBox(6);
            for (io.zyclipra.remote.DashboardAuth.SessionInfo s : auth.sessions()) {
                String when = java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.ENGLISH).withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.ofEpochMilli(s.lastSeen()));
                HBox row = Ui.row(10, new VBox(2, Ui.label(s.address() + " · last used " + when, "h3"), Ui.label(s.agent(), "small", "muted")), Ui.grow(),
                        Ui.button("Sign out", null, () -> { auth.revoke(s.id()); open("Hosting"); }, "outline"));
                row.getStyleClass().add("shortcut-row");
                sessions.getChildren().add(row);
            }
            if (sessions.getChildren().isEmpty()) sessions.getChildren().add(Ui.label("No browser is signed in right now.", "small", "muted"));
            security.getChildren().addAll(
                    Ui.label("Signed-in user: " + auth.username() + " · sessions end after 12 hours unused or 7 days at most", "setting-desc"),
                    Ui.row(12, Ui.field("Current password", cur), Ui.field("New password", np), Ui.field("Confirm", np2), change),
                    Ui.row(12, Ui.field("New username", newUser), Ui.field("Current password", cur2), rename), err,
                    spacer(6), Ui.row(10, Ui.label("Active sessions", "h3"), Ui.grow(),
                            Ui.button("Sign out everywhere", null, () -> { auth.revokeAll(); win.toast("Every browser was signed out.", false); open("Hosting"); }, "outline")),
                    sessions);
        }

        // --- who can connect
        boolean pub = "public".equals(r.mode);
        VBox access = Ui.section("Who can open the dashboard", null);
        Runnable restartIfRunning = () -> {
            save();
            if (server.running()) { try { server.restart(); } catch (Exception e) { win.toast("Restart failed: " + e.getMessage(), true); } }
            open("Hosting");
        };
        List<String> ts = io.zyclipra.remote.DashboardServer.tailscaleAddresses();
        ToggleButton lanSw = sw(r.lan, "Home network", v -> { r.lan = v; restartIfRunning.run(); });
        ToggleButton vpnSw = sw(r.vpn, "Private VPN", v -> { r.vpn = v; restartIfRunning.run(); });
        lanSw.setDisable(pub);
        vpnSw.setDisable(pub);
        access.getChildren().addAll(
                Ui.settingRow("This PC", "Always allowed (https://127.0.0.1).", Ui.label("On", "small", "ok-text")),
                Ui.settingRow("Devices on my home network", "Phones and PCs on the same Wi-Fi or router. Off by default.", lanSw),
                Ui.settingRow("Private VPN – Tailscale (recommended for away from home)",
                        (ts.isEmpty() ? "Tailscale isn't running on this PC. " : "Tailscale found: " + String.join(", ", ts) + ". ")
                                + "Your phone and PC join one private network; the dashboard never faces the internet.", vpnSw));
        if (ts.isEmpty()) access.getChildren().add(Ui.row(10, Ui.label("1. Install Tailscale on this PC and your phone  2. Sign in to the same account on both  3. Turn on the switch above",
                "small", "muted"), Ui.grow(), Ui.button("Get Tailscale", null, () -> DriveSyncBox.browse("https://tailscale.com/download"), "outline")));
        access.getChildren().add(Ui.label("Any other address is refused. Zyclipra never opens router ports, never uses UPnP or NAT-PMP and never changes the firewall.", "small", "muted"));

        // --- advanced: public
        VBox adv = new VBox(10);
        TextField url = new TextField(r.publicUrl);
        url.setPromptText("https://clips.example.com");
        PasswordField confirmPw = new PasswordField();
        confirmPw.setPromptText("your dashboard password");
        Label advState = Ui.label(pub ? "● Public access is ON through " + r.publicUrl : "Off (recommended).", "h3");
        advState.getStyleClass().add(pub ? "warn-text" : "muted");
        Label advErr = Ui.label("", "small", "danger-text");
        Button turnOn = Ui.button("Check address and turn on", null, () -> { }, "outline");
        turnOn.setOnAction(e -> {
            advErr.setText("");
            if (!server.running()) { advErr.setText("Start the dashboard first – the check goes through your reverse proxy to it."); return; }
            if (!auth.passwordMatches(confirmPw.getText())) { advErr.setText("That isn't the dashboard password."); return; }
            if (confirmPw.getText().length() < 12) { advErr.setText("Public access needs a dashboard password of at least 12 characters. Change it above first."); return; }
            String norm;
            try { norm = io.zyclipra.remote.PublicAccess.normalise(url.getText()); } catch (IllegalArgumentException ex) { advErr.setText(ex.getMessage()); return; }
            turnOn.setDisable(true);
            advErr.setText("Checking " + norm + " …");
            java.util.concurrent.CompletableFuture.supplyAsync(() -> io.zyclipra.remote.PublicAccess.verify(norm, server.instanceId())).thenAccept(problem -> Ui.fx(() -> {
                turnOn.setDisable(false);
                if (problem != null) { advErr.setText(problem); return; }
                r.publicUrl = norm;
                r.publicVerifiedAt = System.currentTimeMillis();
                r.mode = "public";
                r.lan = false;
                r.vpn = false;
                win.toast("Public access is on. Keep your password strong and Zyclipra updated.", false);
                restartIfRunning.run();
            }));
        });
        Button turnOff = Ui.button("Turn public access off", null, () -> { r.mode = "local"; r.publicVerifiedAt = 0; restartIfRunning.run(); }, "outline");
        turnOff.setDisable(!pub);
        Button export = Ui.button("Save dashboard certificate (.pem)", null, () -> {
            if (server.certificatePem().isBlank()) { win.toast("Start the dashboard once first.", true); return; }
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("zyclipra-dashboard.pem");
            java.io.File f = fc.showSaveDialog(node.getScene().getWindow());
            if (f == null) return;
            try { java.nio.file.Files.writeString(f.toPath(), server.certificatePem()); win.toast("Saved " + f, false); }
            catch (Exception ex) { win.toast(ex.getMessage(), true); }
        }, "outline");
        adv.getChildren().addAll(
                Ui.label("⚠ Not recommended. This puts your recording controls and clips on the internet, where anyone can try to sign in. "
                        + "Private VPN (above) gives the same away-from-home access without that risk. Only continue if you know how to run a server.", "warn-text"),
                Ui.label("You do all of this yourself – Zyclipra never changes your router, firewall or DNS:\n"
                        + "1.  Get a domain name that points to your home IP address (dynamic DNS is fine).\n"
                        + "2.  Run an HTTPS reverse proxy on this PC (for example Caddy) with a real certificate, forwarding to https://localhost:" + r.port + ".\n"
                        + "    Have it trust this dashboard's own certificate (save it with the button below) and keep the Host header.\n"
                        + "3.  Forward port 443 on your router to this PC and allow it in Windows Firewall.\n"
                        + "4.  Enter the address and your dashboard password here. Zyclipra checks that the address reaches this app with a trusted certificate before turning it on.\n"
                        + "While public access is on, the dashboard only listens on this PC (for the proxy), and home-network/VPN access are switched off.", "small", "muted"),
                advState, Ui.row(12, Ui.field("Public address", url), Ui.field("Dashboard password", confirmPw)), advErr, Ui.row(10, turnOn, turnOff, Ui.grow(), export));
        javafx.scene.control.TitledPane advanced = new javafx.scene.control.TitledPane("Advanced: public internet through your own reverse proxy", adv);
        advanced.setExpanded(pub);
        advanced.getStyleClass().add("plain-titled");

        VBox external = Ui.section("External hosting", null);
        external.getChildren().add(Ui.label("Hosting the dashboard on a website (instead of on this PC) isn't available in this version. It would need a secure, "
                + "authenticated relay between that website and this PC, and Zyclipra won't expose its recording controls without one. "
                + "Use Private VPN for access away from home.", "small", "muted"));

        return pageBox("Hosting", "The dashboard lets you clip, record and manage your clips from your phone or another PC. It runs on this PC and is off until you start it.",
                status, spacer(14), security, spacer(14), access, spacer(14), advanced, spacer(14), external);
    }

    // ---------------- About ----------------
    private Node about() {
        Button logs = Ui.button("Open logs folder", Icons.FOLDER, () -> {
            try { java.awt.Desktop.getDesktop().open(ctx.paths.logs().toFile()); } catch (Exception e) { win.toast(e.getMessage(), true); }
        }, "outline");
        Button data = Ui.button("Open settings folder", Icons.FOLDER, () -> {
            try { java.awt.Desktop.getDesktop().open(ctx.paths.root().toFile()); } catch (Exception e) { win.toast(e.getMessage(), true); }
        }, "outline");
        return pageBox("About and Privacy", "Zyclipra " + AppContext.VERSION + " – free and open source (MIT licence).",
                Ui.settingRow("No account, no ads, no tracking", "Everything stays on this PC unless you turn on Google Drive sync or the remote dashboard.", null),
                Ui.settingRow("Your microphone and screen", "The mic is only used while Voice effects are on; the screen only while recording or while Instant replay is on.", null),
                Ui.settingRow("Files", ctx.paths.root().toString(), Ui.row(8, logs, data)));
    }
}
