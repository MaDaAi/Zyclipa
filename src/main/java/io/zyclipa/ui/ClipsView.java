package io.zyclipa.ui;

import io.zyclipa.app.AppContext;
import io.zyclipa.clips.Clip;
import io.zyclipa.cloud.SyncService;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.Settings;
import io.zyclipa.recording.RecordingService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Clips: the home of the app. A capture bar (status, record, instant replay and quality at a
 * glance) sits above a large grid of clips grouped by day. Clicking a clip opens it full size
 * with playback, trim and sharing; the ⋮ menu on each card has everything else.
 */
public final class ClipsView implements View {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    static final String[] REPLAY_LENGTHS = {"00:15", "00:30", "01:00", "02:00", "03:00", "05:00"};
    static final Map<String, Integer> QUALITY = new LinkedHashMap<>();
    static { QUALITY.put("Low", 6000); QUALITY.put("Medium", 12000); QUALITY.put("High", 20000); QUALITY.put("Very high", 40000); }

    private final AppContext ctx;
    private final MainWindow win;
    private final BorderPane node = new BorderPane();
    private final StackPane body = new StackPane();
    private final VBox gallery = new VBox(10);
    private final ScrollPane galleryScroll;
    private final VBox galleryPage;
    private final TextField search = new TextField();
    private final ComboBox<String> filter = new ComboBox<>(FXCollections.observableArrayList("All", "Recordings", "Replays", "Screenshots", "Trimmed", "Favourites"));
    private final ComboBox<String> sort = new ComboBox<>(FXCollections.observableArrayList("Newest", "Oldest", "Largest", "Longest", "Name"));
    private final Label count = Ui.label("ALL CLIPS (0)", "section-title");
    private final Label storage = Ui.label("", "small", "muted");
    private final DoubleProperty cardWidth = new SimpleDoubleProperty(360);

    private final Label status = new Label();
    private final Label gameName = new Label();
    private long gameCheckedAt;
    private final Node statusIcon = Icons.of(Icons.MONITOR, 20);
    private final Button recButton;
    private final Label replayLabel = new Label();
    private final ToggleButton replaySwitch;
    private final Button saveReplay;
    private final HBox replayKeys = new HBox();
    private final Timeline tick;
    private MediaPlayer player;
    private Node captureBar;
    private String openClip;

    public ClipsView(AppContext ctx, MainWindow win) {
        this.ctx = ctx;
        this.win = win;
        Settings.Recording r = ctx.settings.recording;

        // ---------- capture bar ----------
        status.getStyleClass().add("capture-status");
        recButton = Ui.button("Record", Icons.RECORD, () -> ctx.recorder.toggle(), "danger");
        recButton.setAccessibleText("Start or stop recording");
        Button shot = Ui.iconButton(Icons.CAMERA, "Take a screenshot", () -> ctx.screenshots.capture());
        gameName.getStyleClass().add("capture-game");
        gameName.setMinWidth(0);
        gameName.setMaxWidth(220);
        HBox cellStatus = cell(statusIcon, status, gameName, Ui.grow(), shot, recButton);
        gameName.setMinWidth(0);
        HBox.setHgrow(cellStatus, Priority.ALWAYS);

        replaySwitch = Ui.toggleSwitch(ctx.replay.running(), "Instant replay on or off", on -> {
            if (on != ctx.replay.running()) ctx.replay.toggle();
        });

        ComboBox<String> len = flat(REPLAY_LENGTHS, lengthText(r.replaySeconds), "Instant replay length");
        len.setOnAction(e -> {
            String[] p = len.getValue().split(":");
            r.replaySeconds = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            ctx.store.save();
            updateCapture();
            if (ctx.replay.running()) { ctx.replay.stop(); ctx.replay.start(); }
        });
        ComboBox<String> quality = flat(QUALITY.keySet().toArray(String[]::new), qualityName(r.bitrateKbps), "Video quality");
        quality.setOnAction(e -> { r.bitrateKbps = QUALITY.get(quality.getValue()); ctx.store.save(); });
        ComboBox<String> res = flat(new String[]{"Native", "1440p", "1080p", "720p"}, r.outputHeight == 0 ? "Native" : r.outputHeight + "p", "Resolution");
        res.setOnAction(e -> { String v = res.getValue(); r.outputHeight = v.equals("Native") ? 0 : Integer.parseInt(v.replace("p", "")); ctx.store.save(); });
        ComboBox<String> fps = flat(new String[]{"30fps", "60fps", "120fps"}, r.fps + "fps", "Frame rate");
        fps.setOnAction(e -> { r.fps = Integer.parseInt(fps.getValue().replace("fps", "")); ctx.store.save(); });
        HBox cellQuality = cell(len, sep(), quality, sep(), res, sep(), fps);

        saveReplay = Ui.button("Clip it", Icons.CLIP_IT, () -> ctx.clipNow(), "primary");
        saveReplay.setAccessibleText("Save the last moments as a clip");
        saveReplay.setTooltip(new javafx.scene.control.Tooltip("Save the last moments as a clip (or press the keys shown)"));
        HBox cellReplay = cell(replayLabel, replaySwitch, sep(), replayKeys, saveReplay);

        FlowPane bar = new FlowPane(10, 10, cellStatus, cellReplay, cellQuality);
        captureBar = bar;
        bar.getStyleClass().add("capture-bar");
        bar.setMinWidth(0);

        // ---------- gallery header ----------
        Button importBtn = Ui.button("Import", Icons.PLUS, this::importVideo);
        search.setPromptText("Search clips");
        search.setPrefWidth(200);
        search.textProperty().addListener((o, a, b) -> refresh());
        filter.getSelectionModel().select(0);
        sort.getSelectionModel().select(0);
        filter.setOnAction(e -> refresh());
        sort.setOnAction(e -> refresh());
        filter.setAccessibleText("Filter");
        sort.setAccessibleText("Sort");
        filter.setPrefWidth(140);
        sort.setPrefWidth(120);
        HBox searchBox = Ui.row(6, Icons.of(Icons.SEARCH, 16), search);
        searchBox.getStyleClass().add("well");
        searchBox.setPadding(new Insets(0, 0, 0, 10));
        search.setStyle("-fx-border-color: transparent; -fx-background-color: transparent;");
        HBox head = Ui.row(12, count, importBtn, Ui.grow(), searchBox, filter, sort,
                rescanButton());

        galleryPage = new VBox(14, head, storage, gallery);
        galleryPage.getStyleClass().add("page");
        galleryScroll = new ScrollPane(galleryPage);
        galleryScroll.setFitToWidth(true);
        galleryScroll.viewportBoundsProperty().addListener((o, a, b) -> {
            double w = b.getWidth() - 56;
            int cols = w > 1500 ? 4 : w > 820 ? 3 : 2;
            cardWidth.set(Math.floor((w - (cols - 1) * 22) / cols));
        });
        body.getChildren().add(galleryScroll);
        VBox.setVgrow(body, Priority.ALWAYS);
        node.setTop(bar);
        node.setCenter(body);

        tick = new Timeline(new KeyFrame(Duration.millis(500), e -> updateCapture()));
        tick.setCycleCount(Timeline.INDEFINITE);
        tick.play();
        ctx.bus.on(EventBus.CLIPS_CHANGED, x -> Ui.fx(this::refresh));
        ctx.bus.on(EventBus.RECORDING_CHANGED, x -> Ui.fx(this::updateCapture));
        ctx.bus.on(EventBus.REPLAY_CHANGED, x -> Ui.fx(this::updateCapture));
        updateCapture();
    }

    @Override public String id() { return "clips"; }
    @Override public String title() { return "Clips"; }
    @Override public String icon() { return Icons.FILM; }
    @Override public Node node() { return node; }
    @Override public void shown() { refresh(); updateCapture(); }
    @Override public void hidden() { if (player != null) player.pause(); if (editor != null) closeClip(); }

    public String captureStatusText() { return status.getText(); }
    public String replayStatusText() { return replayLabel.getText(); }

    // ---------------- capture bar ----------------

    private static HBox cell(Node... n) {
        for (Node x : n) if (x instanceof javafx.scene.layout.Region r && !(x instanceof javafx.scene.control.ToggleButton)) r.setMinWidth(Region.USE_PREF_SIZE);
        HBox h = new HBox(10, n);
        h.setMinWidth(Region.USE_PREF_SIZE);
        h.getStyleClass().add("capture-cell");
        return h;
    }

    private static Region sep() {
        Region r = new Region();
        r.getStyleClass().add("tab-sep");
        return r;
    }

    private static ComboBox<String> flat(String[] items, String value, String accessible) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(items));
        c.setValue(value);
        c.getStyleClass().add("flat-combo");
        c.setAccessibleText(accessible);
        return c;
    }

    static String lengthText(int sec) { return String.format("%02d:%02d", sec / 60, sec % 60); }

    static String qualityName(int kbps) {
        String best = "Medium";
        for (var e : QUALITY.entrySet()) if (kbps >= e.getValue()) best = e.getKey();
        return best;
    }

    void updateCapture() {
        RecordingService.State st = ctx.recorder.state();
        boolean ff = ctx.capture.ffmpeg().isPresent();
        status.getStyleClass().removeAll("capture-status-rec", "capture-status-off", "capture-status-live");
        if (!ff) {
            status.setText("FFMPEG NOT FOUND – SET IT UP IN SETTINGS");
            status.getStyleClass().add("capture-status-off");
        } else switch (st) {
            case RECORDING -> {
                status.setText("● RECORDING " + Ui.duration(ctx.recorder.elapsedMs() / 1000.0));
                status.getStyleClass().add("capture-status-rec");
            }
            case STARTING -> status.setText("STARTING…");
            case STOPPING -> status.setText("SAVING…");
            default -> {
                status.setText(ctx.replay.running() ? "CAPTURING" : "READY TO CAPTURE");
                if (ctx.replay.running()) status.getStyleClass().add("capture-status-live");
            }
        }
        boolean live = ctx.replay.running() || st == RecordingService.State.RECORDING;
        if (live && System.currentTimeMillis() - gameCheckedAt > 1500) {
            gameCheckedAt = System.currentTimeMillis();
            CompletableFuture.supplyAsync(ctx.capture::sourceName).thenAccept(g -> Ui.fx(() -> gameName.setText(g)));
        }
        if (!live) gameName.setText("");
        boolean rec = st == RecordingService.State.RECORDING;
        recButton.setText(rec ? "Stop" : "Record");
        recButton.setGraphic(Icons.of(rec ? Icons.STOP : Icons.RECORD, 14));
        recButton.setDisable(!ff || st == RecordingService.State.STARTING || st == RecordingService.State.STOPPING);
        boolean on = ctx.replay.running();
        replayLabel.setText(on ? "Replay on · " + ctx.replay.availableSeconds() + "s" : "Replay off");
        replayLabel.setStyle(on ? "-fx-text-fill: -ok;" : "-fx-text-fill: -muted;");
        if (replaySwitch.isSelected() != on) replaySwitch.setSelected(on);
        replaySwitch.setDisable(!ff);
        saveReplay.setDisable(!on);
        replayKeys.getChildren().setAll(Ui.keycaps(ctx.settings.hotkeys.get("saveReplay"), "keycap").getChildren());
        replayKeys.setSpacing(3);
        replayKeys.setAlignment(Pos.CENTER);
    }

    // ---------------- gallery ----------------

    List<Clip> filtered() {
        String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        String k = filter.getValue();
        Comparator<Clip> cmp = switch (sort.getValue()) {
            case "Oldest" -> Comparator.comparingLong(c -> c.createdAt);
            case "Largest" -> Comparator.comparingLong((Clip c) -> c.sizeBytes).reversed();
            case "Longest" -> Comparator.comparingDouble((Clip c) -> c.durationSec).reversed();
            case "Name" -> Comparator.comparing((Clip c) -> c.title.toLowerCase(Locale.ROOT));
            default -> Comparator.comparingLong((Clip c) -> c.createdAt).reversed();
        };
        return ctx.library.all().stream()
                .filter(c -> q.isEmpty() || c.title.toLowerCase(Locale.ROOT).contains(q) || c.source.toLowerCase(Locale.ROOT).contains(q))
                .filter(c -> switch (k) {
                    case "Recordings" -> c.kind.equals("recording") || c.kind.equals("imported");
                    case "Replays" -> c.kind.equals("replay");
                    case "Screenshots" -> c.kind.equals("screenshot");
                    case "Trimmed" -> c.kind.equals("trim");
                    case "Favourites" -> c.favorite;
                    default -> true;
                }).sorted(cmp).toList();
    }

    void refresh() {
        List<Clip> list = filtered();
        count.setText("ALL CLIPS (" + ctx.library.all().size() + ")");
        long free = 0;
        try { Path root = ctx.library.root(); Files.createDirectories(root); free = Files.getFileStore(root).getUsableSpace(); } catch (Exception ignored) { }
        storage.setText(Ui.bytes(ctx.library.totalBytes()) + " of clips · " + Ui.bytes(free) + " free · saved in " + ctx.settings.recording.clipsDir);
        gallery.getChildren().clear();
        if (list.isEmpty()) {
            String hk = ctx.settings.hotkeys.get("toggleRecording");
            gallery.getChildren().add(ctx.library.all().isEmpty()
                    ? Ui.empty(Icons.FILM, "No clips yet", "Press Record (or " + hk + "), or turn on Instant replay and press "
                    + ctx.settings.hotkeys.get("saveReplay") + " after a great moment.")
                    : Ui.empty(Icons.SEARCH, "No clips match", "Try another search or filter."));
            return;
        }
        boolean byDay = sort.getValue().equals("Newest") || sort.getValue().equals("Oldest");
        Map<String, List<Clip>> groups = new LinkedHashMap<>();
        for (Clip c : list) groups.computeIfAbsent(byDay ? dayKey(c.createdAt) : "", x -> new ArrayList<>()).add(c);
        for (var g : groups.entrySet()) {
            if (byDay) {
                LinkedHashSet<String> games = new LinkedHashSet<>();
                for (Clip c : g.getValue()) games.add(c.source);
                gallery.getChildren().add(Ui.row(10, Ui.label(g.getKey().toUpperCase(Locale.ROOT), "date-head"),
                        Ui.label(String.join(" & ", games), "muted")));
            }
            FlowPane fp = new FlowPane(22, 22);
            for (Clip c : g.getValue()) fp.getChildren().add(card(c));
            VBox.setMargin(fp, new Insets(0, 0, 14, 0));
            gallery.getChildren().add(fp);
        }
    }

    static String dayKey(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
        if (d.equals(LocalDate.now())) return "Today";
        if (d.equals(LocalDate.now().minusDays(1))) return "Yesterday";
        return DAY.format(Instant.ofEpochMilli(ms));
    }

    private VBox card(Clip c) {
        StackPane thumb = new StackPane();
        thumb.getStyleClass().add("clip-thumb");
        thumb.prefWidthProperty().bind(cardWidth);
        thumb.prefHeightProperty().bind(cardWidth.multiply(9.0 / 16));
        thumb.minHeightProperty().bind(cardWidth.multiply(9.0 / 16));
        Path t = ctx.library.thumbnail(c);
        if (Files.exists(t)) {
            ImageView iv = new ImageView(new Image(t.toUri().toString(), 640, 360, true, true, true));
            iv.fitWidthProperty().bind(cardWidth);
            iv.fitHeightProperty().bind(cardWidth.multiply(9.0 / 16));
            iv.setPreserveRatio(true);
            thumb.getChildren().add(iv);
        } else thumb.getChildren().add(Icons.of(c.isVideo() ? Icons.FILM : Icons.CAMERA, 36));
        if (c.isVideo() && c.durationSec > 0) {
            Label d = new Label(Ui.duration(c.durationSec), c.kind.equals("trim") ? Icons.of(Icons.SCISSORS, 12) : null);
            d.getStyleClass().add("dur-pill");
            StackPane.setAlignment(d, Pos.TOP_RIGHT);
            StackPane.setMargin(d, new Insets(12));
            thumb.getChildren().add(d);
        }
        Button star = Ui.iconButton(Icons.STAR, c.favorite ? "Remove from favourites" : "Add to favourites", () -> ctx.library.setFavorite(c, !c.favorite));
        star.setStyle(c.favorite ? "-icon-color: #F5A524;" : "-icon-color: -muted;");
        StackPane.setAlignment(star, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(star, new Insets(6));
        thumb.getChildren().add(star);

        Label game = Ui.label(c.source.toUpperCase(Locale.ROOT), "clip-game");
        Label title = Ui.label(c.title, "clip-title");
        title.setWrapText(false);
        Label age = new Label(Ui.ago(c.createdAt));
        age.getStyleClass().add("clip-age");
        VBox text = new VBox(3, game, title, age);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Button share = Ui.iconButton(Icons.SHARE, "Export a copy", () -> export(c));
        Button more = Ui.iconButton(Icons.MORE, "More actions", () -> { });
        ContextMenu menu = menu(c);
        more.setOnAction(e -> menu.show(more, Side.BOTTOM, 0, 0));
        HBox info = new HBox(6, text, share, more);
        info.setAlignment(Pos.CENTER_LEFT);
        info.getStyleClass().add("clip-body");

        VBox card = new VBox(thumb, info);
        card.getStyleClass().add("clip-card");
        card.prefWidthProperty().bind(cardWidth);
        card.maxWidthProperty().bind(cardWidth);
        card.setFocusTraversable(true);
        card.setAccessibleText(c.title);
        card.setOnMouseClicked(e -> { if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) open(c); });
        card.setOnContextMenuRequested(e -> menu.show(card, e.getScreenX(), e.getScreenY()));
        card.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open(c); });
        return card;
    }

    private ContextMenu menu(Clip c) {
        Path f = ctx.library.file(c);
        MenuItem open = new MenuItem("Open");
        open.setOnAction(e -> open(c));
        MenuItem player = new MenuItem("Open in video player");
        player.setOnAction(e -> openExternal(f));
        MenuItem rename = new MenuItem("Rename");
        rename.setOnAction(e -> rename(c));
        MenuItem move = new MenuItem("Move to folder");
        move.setOnAction(e -> move(c));
        MenuItem reveal = new MenuItem("Show in folder");
        reveal.setOnAction(e -> reveal(f));
        String st = ctx.sync.stateOf(c.id);
        MenuItem drive = new MenuItem(switch (st) {
            case "synced" -> "On Google Drive ✓";
            case "queued", "uploading" -> "Uploading to Google Drive…";
            case "failed", "unavailable" -> "Upload to Google Drive again";
            default -> "Upload to Google Drive";
        });
        drive.setDisable(st.equals("synced") || st.equals("queued") || st.equals("uploading"));
        drive.setOnAction(e -> {
            try { if (st.equals("failed") || st.equals("unavailable")) ctx.sync.retry(c.id); else win.toast(ctx.sync.upload(c), false); }
            catch (IllegalStateException ex) { win.toast(ex.getMessage(), true); }
        });
        MenuItem del = new MenuItem("Delete");
        del.setOnAction(e -> delete(c));
        return new ContextMenu(open, player, rename, move, reveal, drive, del);
    }

    // ---------------- single clip ----------------

    public void select(String id) { ctx.library.byId(id).ifPresent(this::open); }

    private void disposePlayer() { if (player != null) { player.dispose(); player = null; } }

    private ClipEditor editor;

    void open(Clip c) {
        disposePlayer();
        if (editor != null) editor.dispose();
        openClip = c.id;
        editor = new ClipEditor(ctx, win, c, filtered(), this::closeClip, this::open);
        Node n = editor.node();
        body.getChildren().setAll(n);
        node.setTop(null); // the editor uses the whole page, like a separate window
        n.requestFocus();
    }

    private void closeClip() {
        disposePlayer();
        if (editor != null) { editor.dispose(); editor = null; }
        node.setTop(captureBar);
        openClip = null;
        body.getChildren().setAll(galleryScroll);
        refresh();
    }

    // ---------------- actions ----------------

    private void rename(Clip c) {
        TextInputDialog d = new TextInputDialog(c.title);
        d.setTitle("Rename clip");
        d.setHeaderText("New name");
        style(d.getDialogPane());
        d.showAndWait().ifPresent(n -> {
            boolean wasOpen = c.id.equals(openClip);
            disposePlayer();
            try { ctx.library.rename(c, n); } catch (Exception e) { win.toast(e.getMessage(), true); }
            if (wasOpen) open(c); else refresh();
        });
    }

    private void move(Clip c) {
        TextInputDialog d = new TextInputDialog(c.folder());
        d.setTitle("Move to folder");
        d.setHeaderText("Folder inside your clips folder (empty = main folder)");
        style(d.getDialogPane());
        d.showAndWait().ifPresent(n -> {
            try { ctx.library.moveToFolder(c, n); refresh(); } catch (Exception e) { win.toast(e.getMessage(), true); }
        });
    }

    private void export(Clip c) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export clip");
        fc.setInitialFileName(c.fileName());
        File f = fc.showSaveDialog(node.getScene().getWindow());
        if (f == null) return;
        CompletableFuture.runAsync(() -> {
            try { ctx.library.export(c, f.toPath()); Ui.fx(() -> win.toast("Exported to " + f, false)); }
            catch (Exception e) { Ui.fx(() -> win.toast("Export failed: " + e.getMessage(), true)); }
        });
    }

    private void importVideo() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import a video or screenshot");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videos and images", "*.mp4", "*.mkv", "*.mov", "*.webm", "*.png", "*.jpg"));
        File f = fc.showOpenDialog(node.getScene().getWindow());
        if (f == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                Path dst = ctx.library.root().resolve("Imported").resolve(f.getName());
                Files.createDirectories(dst.getParent());
                if (Files.exists(dst)) throw new java.io.IOException("A file with that name was already imported.");
                Files.copy(f.toPath(), dst);
                String n = f.getName().toLowerCase(Locale.ROOT);
                ctx.library.add(dst, n.endsWith(".png") || n.endsWith(".jpg") ? "screenshot" : "imported", "Imported");
                Ui.fx(() -> win.toast("Imported " + f.getName(), false));
            } catch (Exception e) { Ui.fx(() -> win.toast("Import failed: " + e.getMessage(), true)); }
        });
    }

    private void delete(Clip c) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Delete \"" + c.title + "\"? It goes to the Recycle Bin when possible.", ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Delete clip");
        style(a.getDialogPane());
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;
        disposePlayer();
        try {
            ctx.library.delete(c);
            if (c.id.equals(openClip)) closeClip(); else refresh();
            win.toast("Deleted " + c.title, false);
        } catch (Exception e) { win.toast("Delete failed: " + e.getMessage(), true); }
    }

    private void trim(Clip c, double from, double to) {
        win.toast("Trimming…", false);
        CompletableFuture.supplyAsync(() -> {
            try { return ctx.library.trim(c, from, to); } catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
        }).whenComplete((n, e) -> Ui.fx(() -> {
            if (e != null) win.toast(e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), true);
            else { win.toast("Saved " + n.fileName(), false); open(n); }
        }));
    }

    private void openExternal(Path f) {
        try { java.awt.Desktop.getDesktop().open(f.toFile()); } catch (Exception e) { win.toast("Couldn't open: " + e.getMessage(), true); }
    }

    private void reveal(Path f) {
        try {
            if (io.zyclipa.core.Platform.isWindows()) new ProcessBuilder("explorer.exe", "/select," + f).start();
            else java.awt.Desktop.getDesktop().open(f.getParent().toFile());
        } catch (Exception e) { win.toast("Couldn't open the folder: " + e.getMessage(), true); }
    }

    static void style(javafx.scene.control.DialogPane p) {
        p.getStylesheets().add(ClipsView.class.getResource("/ui/zyclipa.css").toExternalForm());
    }

    private Button rescanButton() {
        Button b = Ui.button("Rescan", null, () -> ctx.library.scanAsync(), "boxed");
        b.setTooltip(new javafx.scene.control.Tooltip("Look for new files in the clips folder"));
        return b;
    }
}
