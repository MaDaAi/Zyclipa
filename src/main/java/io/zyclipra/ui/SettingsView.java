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
        group(list, "CONNECT", "Google Drive", this::cloud);
        link(list, "Remote Dashboard", this::dashboard);
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

    // ---------------- Google Drive ----------------
    private Node cloud() {
        Settings.Cloud c = ctx.settings.cloud;
        Label state = Ui.label(cloudState(), "h3");
        TextField clientId = new TextField(c.clientId);
        clientId.setPromptText("xxxxxxxx.apps.googleusercontent.com");
        clientId.textProperty().addListener((o, a, b) -> { c.clientId = b.trim(); save(); });
        PasswordField secret = new PasswordField();
        secret.setText(c.clientSecret);
        secret.setPromptText("Only if Google shows one for your Desktop client");
        secret.textProperty().addListener((o, a, b) -> { c.clientSecret = b.trim(); save(); });
        TextField folder = new TextField(c.folderName);
        folder.textProperty().addListener((o, a, b) -> { c.folderName = b; save(); });
        Button connect = Ui.button("Connect Google account", Icons.CLOUD, () -> {
            state.setText("Finish signing in in your browser…");
            ctx.sync.auth().signIn(url -> {
                try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
                catch (Exception e) { Ui.fx(() -> state.setText("Open this address in your browser: " + url)); }
            }).whenComplete((email, e) -> Ui.fx(() -> {
                save();
                if (e != null) win.toast("Google sign-in failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), true);
                else win.toast("Connected" + (email.isBlank() ? "" : " as " + email), false);
                state.setText(cloudState());
            }));
        }, "primary");
        Button upload = Ui.button("Upload my chosen clips now", Icons.SHARE, () -> ctx.sync.syncSelected());
        Button disconnect = Ui.button("Disconnect", null, () -> {
            try { ctx.sync.disconnect(); win.toast("Google account disconnected.", false); } catch (Exception e) { win.toast(e.getMessage(), true); }
            state.setText(cloudState());
        }, "outline");
        VBox remoteList = new VBox(6);
        Button test = Ui.button("Test connection", Icons.REFRESH, () -> {
            state.setText("Checking your Google Drive…");
            remoteList.getChildren().clear();
            ctx.sync.freeSpace().thenCombine(ctx.sync.remote(), (free, files) -> new Object[]{free, files}).whenComplete((res, e) -> Ui.fx(() -> {
                if (e != null) { state.setText("Connection failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())); return; }
                long free = (Long) res[0];
                @SuppressWarnings("unchecked") List<io.zyclipra.cloud.DriveClient.RemoteFile> files = (List<io.zyclipra.cloud.DriveClient.RemoteFile>) res[1];
                state.setText("Connected ✓  " + files.size() + " clip(s) in “" + c.folderName + "”" + (free >= 0 ? " · " + Ui.bytes(free) + " free on Drive" : ""));
                for (var f : files) {
                    javafx.scene.control.ProgressBar pb = new javafx.scene.control.ProgressBar(0);
                    pb.setPrefWidth(120);
                    pb.setVisible(false);
                    Button dl = Ui.button("Download", null, () -> { }, "outline");
                    dl.setOnAction(ev -> {
                        dl.setDisable(true);
                        pb.setVisible(true);
                        ctx.sync.download(f, p -> Ui.fx(() -> pb.setProgress(p))).whenComplete((path, err) -> Ui.fx(() -> {
                            if (err != null) { win.toast("Download failed: " + err.getMessage(), true); dl.setDisable(false); }
                            else win.toast("Downloaded to " + path, false);
                        }));
                    });
                    HBox row = Ui.row(10, Ui.label(f.name(), "h3"), Ui.label(Ui.bytes(f.size()), "small", "muted"), Ui.grow(), pb, dl);
                    row.getStyleClass().add("shortcut-row");
                    remoteList.getChildren().add(row);
                }
            }));
        }, "outline");
        VBox details = new VBox(12,
                Ui.row(12, Ui.field("Google OAuth client ID", clientId)), Ui.row(12, Ui.field("Client secret (optional)", secret), Ui.field("Drive folder name", folder)),
                Ui.row(10, connect, test, upload, Ui.grow(), disconnect), state, remoteList,
                Ui.label("How to get a client ID (free, 5 minutes): open console.cloud.google.com, create a project, turn on the Google Drive API, "
                        + "then create an OAuth client of type “Desktop app” and paste its ID here. Full steps are in the README.", "small", "muted"));
        details.setDisable(!c.enabled);
        return pageBox("Google Drive", "Optional: back up the clips you choose to your own Google Drive.",
                Ui.settingRow("Google Drive sync", "Only clips you pick are uploaded – never settings, logs or sounds. Zyclipra can only see files it created.",
                        sw(c.enabled, "Google Drive sync", v -> { c.enabled = v; details.setDisable(!v); state.setText(cloudState()); })),
                spacer(16), details);
    }

    private String cloudState() {
        Settings.Cloud c = ctx.settings.cloud;
        if (!c.enabled) return "Off – nothing leaves this PC.";
        if (!ctx.sync.connected()) return "Not connected yet.";
        return "Connected" + (c.accountEmail.isBlank() ? "" : " as " + c.accountEmail) + " · " + c.selectedClips.size() + " clip(s) chosen for sync";
    }

    // ---------------- Remote Dashboard ----------------
    private Node dashboard() {
        Settings.Remote r = ctx.settings.remote;
        Label code = Ui.label("", "h1");
        Label state = Ui.label(dashState(), "setting-desc");
        Runnable apply = () -> {
            ctx.dashboard.stop();
            if (r.enabled) {
                if (!ctx.dashboardAuth.hasCode()) code.setText("Pairing code: " + ctx.dashboardAuth.newPairingCode());
                try { ctx.dashboard.start(); } catch (Exception e) { win.toast("The dashboard couldn't start: " + e.getMessage(), true); }
            }
            save();
            state.setText(dashState());
        };
        Spinner<Integer> port = new Spinner<>(1024, 65535, r.port);
        port.setEditable(true);
        port.valueProperty().addListener((o, a, b) -> { r.port = b; save(); });
        Button newCode = Ui.button("Make a new pairing code", Icons.KEY, () -> {
            code.setText("Pairing code: " + ctx.dashboardAuth.newPairingCode());
            save();
            win.toast("New code made. Browsers that were signed in must sign in again.", false);
        }, "outline");
        return pageBox("Remote Dashboard", "Optional: control recording, the mixer and the soundboard from your phone or another PC.",
                Ui.settingRow("Remote dashboard", "Starts a secure (HTTPS) page you sign in to with a pairing code.",
                        Ui.toggleSwitch(r.enabled, "Remote dashboard", v -> { r.enabled = v; apply.run(); })),
                Ui.settingRow("Allow my phone and other devices", "Off: only this PC can open it. On: devices on your Wi-Fi or your Tailscale network. "
                        + "Anything from the open internet is always refused, and Zyclipra never opens ports on your router.",
                        Ui.toggleSwitch(r.lan, "Allow home network", v -> { r.lan = v; apply.run(); })),
                Ui.settingRow("Port", null, port),
                spacer(16), Ui.row(12, newCode), code, state,
                Ui.label("Your browser will warn once because the certificate is made on this PC – check that the fingerprint matches.", "small", "muted"),
                spacer(16), Ui.label("Away from home", "settings-lead"),
                Ui.label("Use a private network app instead of opening ports on your router – your dashboard then never faces the internet:\n"
                        + "1.  Install Tailscale (free for personal use) on this PC and on your phone, and sign in to the same account on both.\n"
                        + "2.  Turn on “Allow my phone and other devices” above.\n"
                        + "3.  On your phone, open the address marked “via Tailscale” above and sign in with the pairing code.", "setting-desc"),
                Ui.row(12, Ui.button("Get Tailscale", null, () -> {
                    try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://tailscale.com/download")); }
                    catch (Exception e) { win.toast("Open tailscale.com/download in your browser.", false); }
                }, "outline")));
    }

    private String dashState() {
        if (!ctx.dashboard.running()) return "Off.";
        return "Open " + String.join("  or  ", ctx.dashboard.urls()) + "\nCertificate fingerprint: " + ctx.dashboard.fingerprint();
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
