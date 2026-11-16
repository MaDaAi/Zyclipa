package io.zyclipa.ui;

import io.zyclipa.app.AppContext;
import io.zyclipa.clips.Clip;
import io.zyclipa.clips.ClipLibrary;
import io.zyclipa.recording.AudioTracks;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Full-window clip editor modelled on the reference: info bar, player with details panel,
 * transport bar, and a timeline with a trim range (drag the green handles, type exact times or
 * press I / O at the playhead), a picture strip, one lane per audio track with its own mute and
 * volume, and text captions. "Save trim" writes a new clip; the original is never changed.
 */
final class ClipEditor {
    static final String[] LANE_COLORS = {"#2BD9A0", "#3DA9F5", "#F5A524", "#F0479B", "#9D63F7"};
    private static final double LABEL_W = 210, LANE_H = 46, VIDEO_H = 46;

    private final AppContext ctx;
    private final MainWindow win;
    private final Clip clip;
    private final List<Clip> siblings;
    private final Runnable close;
    private final Consumer<Clip> openOther;
    private final BorderPane root = new BorderPane();
    private MediaPlayer player;
    /** One player per sound track, so each track's mute and volume can be heard while editing. */
    private final List<MediaPlayer> sounds = new ArrayList<>();
    /** Darkens the picture outside the 9:16 frame. */
    private final Rectangle dimL = new Rectangle(), dimR = new Rectangle();
    private double in, out, now;
    private final double dur, frame;
    private boolean vertical, muted;
    private final double[] trackVol;
    private final boolean[] trackMuted;
    private final List<Slider> trackSliders = new ArrayList<>();
    private final List<ToggleButton> trackMutes = new ArrayList<>();
    private final Label timeLabel = new Label("0:00 / 0:00");
    private final TextField inField = new TextField(), outField = new TextField();
    private final Label keepLabel = Ui.label("", "small", "muted");
    /**
     * The timeline places its own children. A plain Pane would snap every child back to its preferred
     * size on each layout pass, which made the lanes shrink 8 px per pass, the trim handles vanish and
     * the whole editor jump as the bottom panel changed height. Its height is fixed for the same reason.
     */
    private final Pane timeline = new Pane() {
        @Override protected void layoutChildren() { layoutTimeline(); }
        @Override protected double computePrefHeight(double w) { return timelineHeight(); }
        @Override protected double computeMinHeight(double w) { return timelineHeight(); }
        @Override protected double computeMaxHeight(double w) { return timelineHeight(); }
        @Override protected double computePrefWidth(double h) { return 200; }
        @Override protected double computeMinWidth(double h) { return 0; }
    };
    private double rulerWidth = -1;
    private final Rectangle range = new Rectangle(), shadeL = new Rectangle(), shadeR = new Rectangle();
    private final StackPane handleL = handle(), handleR = handle();
    private final Line playhead = new Line();
    private final Region playKnob = new Region();
    private final List<Node> lanes = new ArrayList<>();
    private final Pane ruler = new Pane();
    private final ImageView strip = new ImageView();
    private Button saveTrim;
    private final List<ClipLibrary.TextOverlay> texts = new ArrayList<>();
    private final VBox textList = new VBox(8);
    private final Label caption = new Label();
    private int soundboardTrack = -1;
    private ToggleButton originalCanvas, tallCanvas;
    private boolean restoring;
    private final javafx.animation.PauseTransition saveLater = new javafx.animation.PauseTransition(Duration.millis(500));
    private final VBox soundboardBox = new VBox(8);

    ClipEditor(AppContext ctx, MainWindow win, Clip clip, List<Clip> siblings, Runnable close, Consumer<Clip> openOther) {
        this.ctx = ctx;
        this.win = win;
        this.clip = clip;
        this.siblings = siblings;
        this.close = close;
        this.openOther = openOther;
        this.dur = Math.max(0.1, clip.durationSec);
        this.frame = 1.0 / (clip.fps > 0 ? clip.fps : 30);
        this.out = dur;
        this.trackVol = new double[Math.max(0, clip.audioTracks)];
        this.trackMuted = new boolean[trackVol.length];
        java.util.Arrays.fill(trackVol, 1.0);
        for (int i = 0; i < clip.audioTrackNames.size(); i++) if (AudioTracks.SOUNDBOARD.equals(clip.audioTrackNames.get(i))) soundboardTrack = i;
        build();
        restoreEdit();
        saveLater.setOnFinished(e -> ctx.library.saveEdit(clip, edited() ? currentEdit() : null));
    }

    Node node() { return root; }

    /** The editor's changes as they are now (what exports, the soundboard and "Save trim" use). */
    private Clip.Edit currentEdit() {
        Clip.Edit e = new Clip.Edit();
        e.in = in;
        e.out = out;
        e.vertical = vertical;
        e.volumes = trackVol.clone();
        e.muted = trackMuted.clone();
        e.texts = new ArrayList<>(texts);
        return e;
    }

    /** Puts back the changes made the last time this clip was edited (kept in Zyclipa, not in the file). */
    private void restoreEdit() {
        Clip.Edit e = clip.edit;
        if (e == null) return;
        restoring = true;
        try {
            in = Math.max(0, Math.min(e.in, dur - 0.5));
            out = Math.max(in + 0.5, Math.min(e.out > 0 ? e.out : dur, dur));
            if (e.vertical && tallCanvas != null) tallCanvas.setSelected(true);
            for (int i = 0; i < trackVol.length; i++) {
                if (i < e.volumes.length && i < trackSliders.size()) trackSliders.get(i).setValue(e.volumes[i]);
                if (i < e.muted.length && e.muted[i]) setTrackMuted(i, true);
            }
            if (e.texts != null) { texts.clear(); texts.addAll(e.texts); rebuildTexts(); }
            updateTrimFields();
            layoutTimeline();
            now = in;
        } finally {
            restoring = false;
        }
        markDirty();
    }

    void dispose() {
        if (player != null) { player.dispose(); player = null; }
        for (MediaPlayer m : sounds) m.dispose();
        sounds.clear();
    }

    // ================================================================ layout

    private void build() {
        Path file = ctx.library.file(clip);
        root.getStyleClass().add("editor");
        root.setTop(infoBar(file));

        StackPane stage = new StackPane();
        stage.getStyleClass().add("editor-stage");
        stage.setMinSize(0, 0);
        stage.setPrefSize(200, 200);
        Rectangle clipRect = new Rectangle();
        clipRect.widthProperty().bind(stage.widthProperty());
        clipRect.heightProperty().bind(stage.heightProperty());
        stage.setClip(clipRect);
        Rectangle verticalFrame = new Rectangle();
        verticalFrame.setFill(Color.TRANSPARENT);
        verticalFrame.setStroke(Theme.web("#2BD9A0"));
        verticalFrame.setStrokeWidth(2);
        verticalFrame.setVisible(false);
        verticalFrame.setMouseTransparent(true);
        caption.getStyleClass().add("caption-preview");
        caption.setVisible(false);
        caption.setMouseTransparent(true);
        for (Rectangle d : new Rectangle[]{dimL, dimR}) {
            d.setFill(Color.rgb(10, 12, 16, 0.6));
            d.setMouseTransparent(true);
            d.visibleProperty().bind(verticalFrame.visibleProperty());
        }
        loadPreview(stage, file, verticalFrame);
        stage.getChildren().addAll(dimL, dimR, verticalFrame, caption);

        ScrollPane sideScroll = new ScrollPane(sidePanel(verticalFrame));
        sideScroll.setFitToWidth(true);
        sideScroll.setPrefWidth(340);
        sideScroll.setMinWidth(300);
        sideScroll.getStyleClass().add("editor-side");
        Button collapse = Ui.iconButton(Icons.BACK, "Hide or show details", () -> { });
        collapse.getGraphic().setRotate(180);
        collapse.setOnAction(e -> {
            boolean show = !sideScroll.isVisible();
            sideScroll.setVisible(show);
            sideScroll.setManaged(show);
            collapse.getGraphic().setRotate(show ? 180 : 0);
        });
        StackPane.setAlignment(collapse, Pos.CENTER_RIGHT);
        stage.getChildren().add(collapse);
        HBox middle = new HBox(stage, sideScroll);
        middle.setMinHeight(0);
        HBox.setHgrow(stage, Priority.ALWAYS);
        root.setCenter(middle);
        if (clip.isVideo()) root.setBottom(bottom());

        root.setOnKeyPressed(e -> {
            if (e.getTarget() instanceof TextField || e.getTarget() instanceof TextArea) return;
            switch (e.getCode()) {
                case ESCAPE -> close.run();
                case SPACE -> togglePlay();
                case I -> setIn(now);
                case O -> setOut(now);
                case LEFT -> seek(now - (e.isShiftDown() ? 1 : frame));
                case RIGHT -> seek(now + (e.isShiftDown() ? 1 : frame));
                case HOME -> seek(in);
                case F -> openFullscreen();
                case END -> seek(out - frame);
                default -> { return; }
            }
            e.consume();
        });
    }

    private Node infoBar(Path file) {
        int idx = indexOf();
        Button prev = Ui.iconButton(Icons.BACK, "Previous clip", () -> openOther.accept(siblings.get(idx - 1)));
        prev.setDisable(idx <= 0);
        Button next = Ui.iconButton(Icons.BACK, "Next clip", () -> openOther.accept(siblings.get(idx + 1)));
        next.getGraphic().setRotate(180);
        next.setDisable(idx < 0 || idx >= siblings.size() - 1);
        Label created = info("Created:", java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.ENGLISH)
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(clip.createdAt)));
        Label quality = info("Video quality:", (clip.height > 0 ? clip.height + "p" : "–") + (clip.fps > 0 ? ", " + (int) clip.fps + " FPS" : ""));
        Label size = info("Size:", Ui.bytes(clip.sizeBytes));
        Label locKey = info("Location:", "");
        Label loc = new Label(file.toString());
        loc.getStyleClass().add("link");
        loc.setOnMouseClicked(e -> reveal(file));
        loc.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        loc.setMinWidth(0);
        HBox.setHgrow(loc, Priority.SOMETIMES);
        Button x = Ui.iconButton(Icons.CLOSE, "Back to all clips (Esc)", close);
        HBox bar = new HBox(14, prev, next, created, sep(), quality, sep(), size, sep(), locKey, loc, Ui.grow(), x);
        bar.getStyleClass().add("editor-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** Picture from a silent copy + one MP3 per sound track, kept in step (see ClipLibrary.previewFiles). */
    private void loadPreview(StackPane stage, Path file, Rectangle verticalFrame) {
        if (!clip.isVideo()) {
            ImageView iv = new ImageView(new Image(file.toUri().toString(), 1600, 900, true, true, true));
            iv.setPreserveRatio(true);
            iv.fitWidthProperty().bind(stage.widthProperty().subtract(40));
            iv.fitHeightProperty().bind(stage.heightProperty().subtract(20));
            stage.getChildren().add(iv);
            return;
        }
        String name = file.toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mp4") && !name.endsWith(".mov")) {
            Path t = ctx.library.thumbnail(clip);
            if (Files.exists(t)) stage.getChildren().add(new ImageView(new Image(t.toUri().toString(), 960, 540, true, true, true)));
            stage.getChildren().add(Ui.label("MKV clips play in your video player (⋮ › Open in video player).", "muted"));
            return;
        }
        // Everything the preview shows lives in its own layer, so an error never removes the overlays around it.
        StackPane screen = new StackPane();
        screen.setMinSize(0, 0);
        stage.getChildren().add(0, screen);
        startPreview(stage, screen, verticalFrame, 0);
    }

    private void startPreview(StackPane stage, StackPane screen, Rectangle verticalFrame, int attempt) {
        if (player != null) { player.dispose(); player = null; }
        for (MediaPlayer m : sounds) m.dispose();
        sounds.clear();
        screen.getChildren().setAll(Ui.label(attempt == 0 ? "Preparing preview…" : "Trying again…", "muted"));
        CompletableFuture.supplyAsync(() -> {
            try { return ctx.library.previewFiles(clip); } catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
        }).whenComplete((preview, err) -> Ui.fx(() -> {
            if (root.getScene() == null && attempt > 0) return; // editor closed meanwhile
            if (err != null) { previewFailed(stage, screen, verticalFrame, attempt, rootMessage(err)); return; }
            try {
                MediaPlayer p = new MediaPlayer(new Media(preview[0].toUri().toString()));
                player = p;
                p.setMute(true);
                for (int i = 1; i < preview.length; i++) sounds.add(new MediaPlayer(new Media(preview[i].toUri().toString())));
                applyVolumes();
                MediaView mv = new MediaView(p);
                mv.setPreserveRatio(true);
                mv.fitWidthProperty().bind(stage.widthProperty().subtract(40));
                mv.fitHeightProperty().bind(stage.heightProperty().subtract(20));
                screen.getChildren().setAll(mv);
                mv.boundsInParentProperty().addListener((o, a, b) -> {
                    double fw = b.getHeight() * 9 / 16, side = Math.max(0, (b.getWidth() - fw) / 2);
                    verticalFrame.setWidth(fw);
                    verticalFrame.setHeight(b.getHeight());
                    for (Rectangle d : new Rectangle[]{dimL, dimR}) { d.setWidth(side); d.setHeight(b.getHeight()); }
                    dimL.setTranslateX(-(fw + side) / 2);
                    dimR.setTranslateX((fw + side) / 2);
                    caption.setMaxWidth(b.getWidth() * 0.9);
                });
                p.currentTimeProperty().addListener((o, a, b) -> {
                    now = b.toSeconds();
                    if (now >= out && p.getStatus() == MediaPlayer.Status.PLAYING) { pause(); seek(in); }
                    if (p.getStatus() == MediaPlayer.Status.PLAYING)
                        for (MediaPlayer m : sounds) if (Math.abs(m.getCurrentTime().toSeconds() - now) > 0.15) m.seek(Duration.seconds(now));
                    updatePlayhead();
                });
                p.setOnReady(() -> { layoutTimeline(); if (now > 0) seek(now); });
                // The media engine sometimes refuses a file it plays fine a moment later, so retry before giving up.
                p.setOnError(() -> { if (player == p) previewFailed(stage, screen, verticalFrame, attempt, p.getError() == null ? "unknown error" : p.getError().getMessage()); });
            } catch (Exception e) {
                previewFailed(stage, screen, verticalFrame, attempt, rootMessage(e));
            }
        }));
    }

    private void previewFailed(StackPane stage, StackPane screen, Rectangle verticalFrame, int attempt, String why) {
        if (attempt < 1 && !why.contains("FFmpeg")) { startPreview(stage, screen, verticalFrame, attempt + 1); return; }
        Button again = Ui.button("Try again", null, () -> startPreview(stage, screen, verticalFrame, attempt + 1), "outline");
        Label msg = Ui.label("The preview couldn't load: " + why, "muted");
        msg.setMaxWidth(560);
        VBox box = new VBox(12, msg, again);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(600);
        screen.getChildren().setAll(box);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private int indexOf() {
        for (int i = 0; i < siblings.size(); i++) if (siblings.get(i).id.equals(clip.id)) return i;
        return -1;
    }

    private static Label info(String k, String v) {
        Label l = new Label(v.isEmpty() ? k : k + "  " + v);
        l.getStyleClass().add("editor-info");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private static Region sep() {
        Region r = new Region();
        r.getStyleClass().add("tab-sep");
        return r;
    }

    // ================================================================ side panel

    private VBox sidePanel(Rectangle verticalFrame) {
        ToggleGroup g = new ToggleGroup();
        ToggleButton original = canvasButton("Original", 34, 20, g), tall = canvasButton("9:16", 16, 28, g);
        originalCanvas = original;
        tallCanvas = tall;
        original.setSelected(true);
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { a.setSelected(true); return; }
            vertical = b == tall;
            verticalFrame.setVisible(vertical);
            markDirty();
        });
        VBox canvas = panelSection("Canvas size", Ui.row(12, original, tall),
                Ui.label("9:16 keeps the middle of the picture – handy for phone videos.", "small", "muted"));

        TextField title = new TextField(clip.title);
        title.setAccessibleText("Clip title");
        Runnable commitTitle = () -> {
            String t = title.getText().trim();
            if (t.equals(clip.title) || t.isEmpty()) { title.setText(clip.title); return; }
            dispose();
            try { ctx.library.rename(clip, t); win.toast("Renamed", false); }
            catch (Exception ex) { win.toast(ex.getMessage(), true); title.setText(clip.title); }
            openOther.accept(clip);
        };
        title.setOnAction(e -> commitTitle.run());
        title.focusedProperty().addListener((o, a, b) -> { if (!b) commitTitle.run(); });
        ToggleButton star = new ToggleButton(null, Icons.of(Icons.STAR, 18));
        star.getStyleClass().setAll("star-toggle");
        star.setSelected(clip.favorite);
        star.setAccessibleText("Favourite");
        star.selectedProperty().addListener((o, a, b) -> ctx.library.setFavorite(clip, b));
        TextArea desc = new TextArea(clip.description);
        desc.setPromptText("Type a description here");
        desc.setWrapText(true);
        desc.setPrefRowCount(3);
        desc.focusedProperty().addListener((o, a, b) -> { if (!b) ctx.library.setDescription(clip, desc.getText()); });
        VBox details = panelSection("Clip details",
                Ui.label("Title", "field-caption"), title,
                Ui.label("Game", "field-caption"), Ui.label(clip.source, "h3"),
                Ui.label("Tag", "field-caption"), star,
                Ui.label("Description", "field-caption"), desc);

        Button addText = Ui.button("Add text", Icons.PLUS, this::addText, "outline");
        VBox textSection = panelSection("Texts", textList, addText,
                Ui.label("Captions are drawn into the saved copy. They start at the playhead; set when each one shows below.", "small", "muted"));

        VBox side = new VBox(canvas, details, textSection);
        if (soundboardTrack >= 0) {
            // Built at full size straight away and only the text changes when the check finishes, so the
            // sections below don't drop down a moment after the editor opens.
            Label status = Ui.label("Checking for soundboard sounds…", "small", "muted");
            ToggleButton keep = Ui.toggleSwitch(true, "Keep soundboard sounds", b -> setTrackMuted(soundboardTrack, !b));
            keep.setDisable(true);
            soundboardBox.getChildren().setAll(status, Ui.row(10, Ui.label("Keep soundboard sounds", "h3"), Ui.grow(), keep));
            side.getChildren().add(1, panelSection("Soundboard", soundboardBox));
            CompletableFuture.supplyAsync(() -> ctx.library.trackPeakDb(clip, soundboardTrack)).thenAccept(db -> Ui.fx(() -> {
                boolean used = db != null && db > -60;
                status.setText(used ? "Soundboard sounds were played in this clip." : "No soundboard sounds were played in this clip.");
                if (used) status.getStyleClass().setAll("label", "small", "warn-text");
                keep.setDisable(!used);
            }));
        }
        side.getChildren().add(panelSection("Google Drive", new DriveSyncBox(ctx, win, clip).node()));
        side.getStyleClass().add("editor-side-box");
        return side;
    }

    private void addText() {
        double s = Math.max(in, Math.min(now, out - 0.5));
        ClipLibrary.TextOverlay t = new ClipLibrary.TextOverlay("Your text", s, Math.min(out, s + 3), "Bottom", 7);
        texts.add(t);
        rebuildTexts();
        markDirty();
    }

    private void rebuildTexts() {
        textList.getChildren().clear();
        for (int i = 0; i < texts.size(); i++) {
            int idx = i;
            ClipLibrary.TextOverlay t = texts.get(i);
            TextField text = new TextField(t.text());
            text.setAccessibleText("Caption text");
            ComboBox<String> pos = new ComboBox<>(FXCollections.observableArrayList("Top", "Middle", "Bottom"));
            pos.setValue(t.position());
            TextField from = new TextField(fmtTime(t.start())), to = new TextField(fmtTime(t.end()));
            from.setPrefWidth(64);
            to.setPrefWidth(64);
            Runnable commit = () -> {
                ClipLibrary.TextOverlay o = texts.get(idx);
                double a = parseTime(from.getText(), o.start()), b = parseTime(to.getText(), o.end());
                if (b <= a) b = Math.min(dur, a + 1);
                texts.set(idx, new ClipLibrary.TextOverlay(text.getText().isBlank() ? " " : text.getText(), a, b, pos.getValue(), o.size()));
                from.setText(fmtTime(a));
                to.setText(fmtTime(b));
                updateCaption();
                markDirty();
            };
            text.textProperty().addListener((o, a, b) -> commit.run());
            pos.setOnAction(e -> commit.run());
            from.setOnAction(e -> commit.run());
            to.setOnAction(e -> commit.run());
            from.focusedProperty().addListener((o, a, b) -> { if (!b) commit.run(); });
            to.focusedProperty().addListener((o, a, b) -> { if (!b) commit.run(); });
            Button del = Ui.iconButton(Icons.TRASH, "Remove this text", () -> { texts.remove(idx); rebuildTexts(); markDirty(); updateCaption(); });
            VBox box = new VBox(6, text, Ui.row(6, pos, from, Ui.label("→", "muted"), to, del));
            box.getStyleClass().add("text-item");
            textList.getChildren().add(box);
        }
        updateCaption();
    }

    private void updateCaption() {
        for (ClipLibrary.TextOverlay t : texts) {
            if (now >= t.start() && now <= t.end()) {
                caption.setText(t.text());
                caption.setVisible(true);
                StackPane.setAlignment(caption, switch (t.position()) { case "Top" -> Pos.TOP_CENTER; case "Middle" -> Pos.CENTER; default -> Pos.BOTTOM_CENTER; });
                StackPane.setMargin(caption, new Insets(40));
                return;
            }
        }
        caption.setVisible(false);
    }

    private static ToggleButton canvasButton(String text, double w, double h, ToggleGroup g) {
        Rectangle r = new Rectangle(w, h);
        r.setFill(Color.TRANSPARENT);
        r.setStroke(Theme.web("#C5CAD3"));
        r.setStrokeWidth(2);
        r.setArcWidth(4);
        r.setArcHeight(4);
        StackPane box = new StackPane(r);
        box.setPrefSize(56, 56);
        box.getStyleClass().add("canvas-box");
        ToggleButton b = new ToggleButton(text, box);
        b.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        b.getStyleClass().setAll("canvas-choice");
        b.setToggleGroup(g);
        return b;
    }

    private static VBox panelSection(String title, Node... content) {
        VBox v = new VBox(8);
        v.getChildren().add(Ui.label(title.toUpperCase(Locale.ROOT), "card-title"));
        v.getChildren().addAll(content);
        v.getStyleClass().add("editor-section");
        return v;
    }

    // ================================================================ transport + timeline

    private Button tbtn(String icon, String tip, Runnable r) {
        Button b = new Button(null, Icons.of(icon, 18));
        b.getStyleClass().setAll("transport");
        b.setTooltip(new Tooltip(tip));
        b.setAccessibleText(tip);
        b.setOnAction(e -> r.run());
        return b;
    }

    /** Play/pause glyph for the round button; the triangle is nudged right so it looks centred in the circle. */
    private static Node playIcon(boolean playing) {
        Node n = Icons.of(playing ? Icons.PAUSE : Icons.PLAY, 16);
        if (!playing) n.setTranslateX(1.5);
        return n;
    }

    private Node bottom() {
        Button shot = tbtn(Icons.SNAPSHOT, "Save this frame as a screenshot", this::grabFrame);
        ToggleButton mute = new ToggleButton(null, Icons.of(Icons.VOLUME, 18));
        mute.getStyleClass().setAll("transport");
        mute.setTooltip(new Tooltip("Mute the preview"));
        mute.selectedProperty().addListener((o, a, b) -> { muted = b; applyVolumes(); mute.setGraphic(Icons.of(b ? Icons.MUTE : Icons.VOLUME, 18)); });
        Button start = tbtn(Icons.SKIP_START, "Go to the start of the trim (Home)", () -> seek(in));
        Button back = tbtn(Icons.FRAME_BACK, "Back one frame (←, Shift+← = 1 s)", () -> seek(now - frame));
        Button play = new Button(null, playIcon(false));
        play.getStyleClass().setAll("transport", "transport-play");
        play.setTooltip(new Tooltip("Play / pause (Space)"));
        play.setOnAction(e -> togglePlay());
        Button fwd = tbtn(Icons.FRAME_FWD, "Forward one frame (→, Shift+→ = 1 s)", () -> seek(now + frame));
        Button end = tbtn(Icons.SKIP_END, "Go to the end of the trim (End)", () -> seek(out - frame));
        Button full = tbtn(Icons.FULLSCREEN, "Full screen (F)", this::openFullscreen);
        Timeline playIcon = new Timeline(new KeyFrame(Duration.millis(200), e -> {
            boolean playing = player != null && player.getStatus() == MediaPlayer.Status.PLAYING;
            if (!Boolean.valueOf(playing).equals(play.getUserData())) { play.setUserData(playing); play.setGraphic(playIcon(playing)); }
            updateCaption();
        }));
        playIcon.setCycleCount(Animation.INDEFINITE);
        playIcon.play();
        root.sceneProperty().addListener((o, a, b) -> { if (b == null) playIcon.stop(); });
        HBox transport = new HBox(4, shot, mute, start, back, play, fwd, end, full);
        transport.setAlignment(Pos.CENTER);

        saveTrim = Ui.button("Save trim", Icons.SCISSORS, this::save);
        saveTrim.setDisable(true);
        // Its text switches between "Save trim" and "Save copy"; a fixed width keeps the play controls from sliding.
        saveTrim.setMinWidth(112);
        saveTrim.setPrefWidth(112);
        Button toSoundboard = Ui.button("Add to soundboard", Icons.SOUND, this::addToSoundboard, "outline");
        toSoundboard.setTooltip(new Tooltip("Turns the trimmed part (with your track volumes) into a soundboard sound"));
        toSoundboard.setDisable(clip.audioTracks == 0 || ctx.capture.ffmpeg().isEmpty());
        Button share = Ui.button("Export", Icons.SHARE, this::export, "primary");
        ContextMenu cm = new ContextMenu(
                EqPanel.item("Open in video player", () -> { try { java.awt.Desktop.getDesktop().open(ctx.library.file(clip).toFile()); } catch (Exception ex) { win.toast(ex.getMessage(), true); } }),
                EqPanel.item("Show in folder", () -> reveal(ctx.library.file(clip))),
                EqPanel.item("Reset all edits", this::resetEdits));
        Button more = Ui.iconButton(Icons.MORE, "More", () -> { });
        more.setOnAction(e -> cm.show(more, Side.TOP, 0, 0));
        HBox right = new HBox(10, toSoundboard, saveTrim, share, more);
        right.setAlignment(Pos.CENTER_RIGHT);
        BorderPane controls = new BorderPane(transport);
        controls.setRight(right);
        controls.setPadding(new Insets(10, 14, 4, LABEL_W + 14));

        // trim row: exact times
        inField.setPrefWidth(80);
        outField.setPrefWidth(80);
        inField.setAccessibleText("Trim start");
        outField.setAccessibleText("Trim end");
        inField.setOnAction(e -> setIn(parseTime(inField.getText(), in)));
        outField.setOnAction(e -> setOut(parseTime(outField.getText(), out)));
        inField.focusedProperty().addListener((o, a, b) -> { if (!b) setIn(parseTime(inField.getText(), in)); });
        outField.focusedProperty().addListener((o, a, b) -> { if (!b) setOut(parseTime(outField.getText(), out)); });
        Button setInBtn = Ui.button("Start here  (I)", null, () -> setIn(now), "boxed");
        Button setOutBtn = Ui.button("End here  (O)", null, () -> setOut(now), "boxed");
        Button reset = Ui.button("Reset", null, () -> { setIn(0); setOut(dur); }, "ghost");
        keepLabel.setMinWidth(150);
        keepLabel.setPrefWidth(150);
        HBox trimRow = Ui.row(8, Icons.of(Icons.SCISSORS, 16), Ui.label("Trim", "h3"), inField, Ui.label("→", "muted"), outField, keepLabel,
                Ui.grow(), setInBtn, setOutBtn, reset);
        trimRow.setPadding(new Insets(4, 14, 8, LABEL_W + 14));

        // left labels
        timeLabel.getStyleClass().add("editor-time");
        VBox labels = new VBox(8);
        labels.setPrefWidth(LABEL_W);
        labels.setMinWidth(LABEL_W);
        HBox timeRow = new HBox(timeLabel);
        timeRow.setPrefHeight(22);
        timeRow.setAlignment(Pos.BOTTOM_LEFT);
        labels.getChildren().add(timeRow);
        labels.getChildren().add(laneLabel(clip.title.length() > 22 ? clip.title.substring(0, 21) + "…" : clip.title, null, -1));
        for (int i = 0; i < clip.audioTracks; i++) {
            String name = i < clip.audioTrackNames.size() ? clip.audioTrackNames.get(i) : "Audio track " + (i + 1);
            labels.getChildren().add(laneLabel(name, LANE_COLORS[i % LANE_COLORS.length], i));
        }
        if (clip.audioTracks == 0) labels.getChildren().add(Ui.label("No sound tracks", "small", "muted"));
        labels.setPadding(new Insets(0, 8, 8, 14));

        // lanes
        timeline.getChildren().add(ruler);
        StackPane videoLane = new StackPane(strip);
        videoLane.getStyleClass().add("lane-video");
        strip.setPreserveRatio(false);
        strip.fitWidthProperty().bind(videoLane.widthProperty());
        strip.fitHeightProperty().bind(videoLane.heightProperty());
        strip.setOpacity(0.55);
        CompletableFuture.supplyAsync(() -> ctx.library.filmstrip(clip, 12)).thenAccept(p -> p.ifPresent(path -> Ui.fx(() -> strip.setImage(new Image(path.toUri().toString())))));
        lanes.add(videoLane);
        timeline.getChildren().add(videoLane);
        for (int i = 0; i < clip.audioTracks; i++) {
            String color = LANE_COLORS[i % LANE_COLORS.length];
            StackPane lane = new StackPane();
            lane.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3;");
            lane.setOpacity(0.85);
            int track = i;
            CompletableFuture.supplyAsync(() -> ctx.library.waveform(clip, track, "E6E8EC")).thenAccept(p -> p.ifPresent(path -> Ui.fx(() -> {
                ImageView w = new ImageView(new Image(path.toUri().toString()));
                w.setPreserveRatio(false);
                w.fitWidthProperty().bind(lane.widthProperty());
                w.fitHeightProperty().bind(lane.heightProperty().subtract(8));
                w.setOpacity(0.8);
                lane.getChildren().add(w);
            })));
            lanes.add(lane);
            timeline.getChildren().add(lane);
        }
        if (clip.audioTracks == 0) {
            Label none = Ui.label("This clip has no sound tracks. New recordings include game audio, chat, microphone and soundboard "
                    + "(Settings › Capture and Sound).", "small", "muted");
            lanes.add(none);
            timeline.getChildren().add(none);
        }
        for (Rectangle r : new Rectangle[]{shadeL, shadeR}) { r.setFill(Color.rgb(8, 10, 14, 0.65)); r.setMouseTransparent(true); }
        range.setFill(Color.TRANSPARENT);
        range.setStroke(Theme.web("#2BD9A0"));
        range.setStrokeWidth(2);
        range.setMouseTransparent(true);
        playhead.setStroke(Color.WHITE);
        playhead.setStrokeWidth(2);
        playhead.setMouseTransparent(true);
        playKnob.getStyleClass().add("playhead-knob");
        playKnob.setMouseTransparent(true);
        timeline.getChildren().addAll(shadeL, shadeR, range, handleL, handleR, playhead, playKnob);
        timeline.setOnMousePressed(e -> { if (!isHandle(e.getTarget())) seek(xToTime(e.getX())); });
        timeline.setOnMouseDragged(e -> { if (!isHandle(e.getTarget())) seek(xToTime(e.getX())); });
        handleL.setOnMouseDragged(e -> setIn(xToTime(handleL.localToParent(e.getX(), 0).getX())));
        handleR.setOnMouseDragged(e -> setOut(xToTime(handleR.localToParent(e.getX(), 0).getX())));
        Tooltip.install(handleL, new Tooltip("Drag to set where the clip starts"));
        Tooltip.install(handleR, new Tooltip("Drag to set where the clip ends"));
        HBox.setHgrow(timeline, Priority.ALWAYS);
        HBox tl = new HBox(labels, timeline);
        tl.setPadding(new Insets(0, 14, 12, 0));
        VBox box = new VBox(controls, trimRow, tl);
        box.getStyleClass().add("editor-bottom");
        updateTrimFields();
        return box;
    }

    /**
     * Full screen shows only the video, with a thin progress bar you can click and the play controls under it.
     * It's a separate black window sharing the same player, so the editor stays exactly as it was. Esc closes it.
     */
    private void openFullscreen() {
        if (player == null) { win.toast("The preview isn't ready yet.", true); return; }
        Stage fs = new Stage(javafx.stage.StageStyle.UNDECORATED);
        fs.initOwner(root.getScene().getWindow());
        MediaView mv = new MediaView(player);
        mv.setPreserveRatio(true);
        StackPane pic = new StackPane(mv);
        pic.getStyleClass().add("fs-picture");
        pic.setMinSize(0, 0);
        mv.fitWidthProperty().bind(pic.widthProperty());
        mv.fitHeightProperty().bind(pic.heightProperty());
        pic.setOnMouseClicked(e -> { if (e.getClickCount() == 1) togglePlay(); else fs.close(); });

        Pane bar = new Pane();
        bar.getStyleClass().add("fs-progress");
        bar.setMinHeight(5);
        bar.setPrefHeight(5);
        bar.setCursor(Cursor.HAND);
        Region played = new Region();
        played.getStyleClass().add("fs-progress-fill");
        played.setMouseTransparent(true);
        bar.getChildren().add(played);
        Runnable paint = () -> played.resizeRelocate(0, 0, bar.getWidth() * Math.max(0, Math.min(1, now / dur)), bar.getHeight());
        bar.setOnMousePressed(e -> { seek(e.getX() / Math.max(1, bar.getWidth()) * dur); paint.run(); });
        bar.setOnMouseDragged(e -> { seek(e.getX() / Math.max(1, bar.getWidth()) * dur); paint.run(); });
        bar.widthProperty().addListener((o, a, b) -> paint.run());

        Button play = new Button(null, playIcon(false));
        play.getStyleClass().setAll("transport", "transport-play");
        play.setOnAction(e -> togglePlay());
        HBox controls = new HBox(4,
                tbtn(Icons.SKIP_START, "Start of the trim", () -> seek(in)),
                tbtn(Icons.FRAME_BACK, "Back one frame (←)", () -> seek(now - frame)),
                play,
                tbtn(Icons.FRAME_FWD, "Forward one frame (→)", () -> seek(now + frame)),
                tbtn(Icons.SKIP_END, "End of the trim", () -> seek(out - frame)),
                tbtn(Icons.FULLSCREEN, "Leave full screen (Esc)", fs::close));
        controls.setAlignment(Pos.CENTER);
        Label time = new Label();
        time.getStyleClass().add("editor-time");
        BorderPane row = new BorderPane(controls);
        row.setLeft(time);
        BorderPane.setAlignment(time, Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 16, 8, 16));
        VBox bottomBar = new VBox(bar, row);
        bottomBar.getStyleClass().add("fs-bottom");

        Timeline tick = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            paint.run();
            time.setText(Ui.duration(now) + " / " + Ui.duration(dur));
            boolean playing = player != null && player.getStatus() == MediaPlayer.Status.PLAYING;
            if (!Boolean.valueOf(playing).equals(play.getUserData())) { play.setUserData(playing); play.setGraphic(playIcon(playing)); }
        }));
        tick.setCycleCount(Animation.INDEFINITE);
        tick.play();

        BorderPane fsRoot = new BorderPane(pic);
        fsRoot.setBottom(bottomBar);
        fsRoot.getStyleClass().add("fs-root");
        javafx.scene.Scene sc = new javafx.scene.Scene(fsRoot, Color.BLACK);
        sc.getStylesheets().addAll(root.getScene().getStylesheets());
        sc.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE, F -> fs.close();
                case SPACE -> togglePlay();
                case LEFT -> seek(now - (e.isShiftDown() ? 1 : frame));
                case RIGHT -> seek(now + (e.isShiftDown() ? 1 : frame));
                default -> { return; }
            }
            e.consume();
        });
        fs.setScene(sc);
        fs.setFullScreenExitHint("");
        fs.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
        fs.setOnHidden(e -> { tick.stop(); mv.setMediaPlayer(null); root.requestFocus(); });
        fs.setFullScreen(true);
        fs.show();
    }

    private boolean isHandle(Object t) {
        return t instanceof Node n && (n == handleL || n == handleR || n.getParent() == handleL || n.getParent() == handleR);
    }

    private static StackPane handle() {
        Region grip = new Region();
        grip.getStyleClass().add("trim-grip");
        StackPane h = new StackPane(grip);
        h.getStyleClass().add("trim-handle");
        h.setCursor(Cursor.H_RESIZE);
        return h;
    }

    private Node laneLabel(String name, String color, int track) {
        Label n = Ui.label(name, "lane-name");
        n.setWrapText(false);
        VBox v = new VBox(2, n);
        v.getStyleClass().add("lane-label");
        double h = track < 0 ? VIDEO_H : LANE_H;
        v.setPrefHeight(h);
        v.setMinHeight(h);
        v.setMaxHeight(h);
        if (track >= 0) {
            n.setStyle("-fx-text-fill: " + color + ";");
            // Fills from 0 and rests on 100 % (unchanged) while dragging past it.
            Slider s = Ui.fill(new Slider(0, 2, 1), 0, 1, "-accent", "-accent");
            s.setAccessibleText(name + " volume in the saved clip");
            HBox.setHgrow(s, Priority.ALWAYS);
            ToggleButton m = new ToggleButton(null, Icons.of(Icons.VOLUME, 14));
            m.getStyleClass().setAll("lane-mute");
            m.setTooltip(new Tooltip("Leave this track out of the saved clip"));
            m.setAccessibleText("Mute " + name);
            Label pct = Ui.label("100%", "small", "muted");
            pct.setMinWidth(38);
            s.valueProperty().addListener((o, a, b) -> {
                trackVol[track] = b.doubleValue();
                applyVolumes();
                pct.setText(Math.round(b.doubleValue() * 100) + "%");
                markDirty();
            });
            m.selectedProperty().addListener((o, a, b) -> setTrackMuted(track, b));
            trackSliders.add(s);
            trackMutes.add(m);
            pct.setWrapText(false);
            // The saved clip can boost a track up to 200 %.
            v.getChildren().add(Ui.row(4, m, s, Ui.editable(pct, 0, 200, x -> s.setValue(x / 100))));
        }
        return v;
    }

    /** What you hear follows each track's mute and volume (the preview can't go above 100 %; the saved clip can). */
    private void applyVolumes() {
        for (int i = 0; i < sounds.size(); i++) {
            boolean off = muted || (i < trackMuted.length && trackMuted[i]);
            sounds.get(i).setVolume(off ? 0 : Math.min(1, i < trackVol.length ? trackVol[i] : 1));
        }
    }

    private void setTrackMuted(int track, boolean mute) {
        trackMuted[track] = mute;
        applyVolumes();
        if (track < trackMutes.size()) {
            ToggleButton m = trackMutes.get(track);
            if (m.isSelected() != mute) m.setSelected(mute);
            m.setGraphic(Icons.of(mute ? Icons.MUTE : Icons.VOLUME, 14));
            trackSliders.get(track).setDisable(mute);
        }
        if (track + 1 < lanes.size()) lanes.get(track + 1).setOpacity(mute ? 0.25 : 0.85);
        markDirty();
    }

    private double tlLeft() { return 8; }
    private double tlWidth() { return Math.max(10, timeline.getWidth() - 16); }
    private double timeToX(double t) { return tlLeft() + t / dur * tlWidth(); }
    private double xToTime(double x) { return Math.max(0, Math.min(dur, (x - tlLeft()) / tlWidth() * dur)); }

    private double timelineHeight() { return 30 + VIDEO_H + (LANE_H + 8) * Math.max(1, clip.audioTracks) + 2; }

    private void layoutTimeline() {
        double w = timeline.getWidth();
        if (w <= 0) return;
        ruler.resizeRelocate(0, 0, w, 22);
        // Rebuild the tick marks only when the width changes; changing them on every pass would ask for another pass.
        if (w != rulerWidth) { rulerWidth = w; buildRuler(w); }
        double y = 30;
        for (int i = 0; i < lanes.size(); i++) {
            double h = i == 0 ? VIDEO_H : LANE_H;
            lanes.get(i).resizeRelocate(tlLeft(), y, tlWidth(), h);
            y += h + 8;
        }
        double top = 30, bottom = y - 8;
        double x1 = timeToX(in), x2 = timeToX(out);
        shadeL.setX(tlLeft()); shadeL.setY(top); shadeL.setWidth(Math.max(0, x1 - tlLeft())); shadeL.setHeight(bottom - top);
        shadeR.setX(x2); shadeR.setY(top); shadeR.setWidth(Math.max(0, tlLeft() + tlWidth() - x2)); shadeR.setHeight(bottom - top);
        range.setX(x1); range.setY(top); range.setWidth(Math.max(1, x2 - x1)); range.setHeight(bottom - top);
        handleL.resizeRelocate(x1 - 7, top, 14, bottom - top);
        handleR.resizeRelocate(x2 - 7, top, 14, bottom - top);
        playhead.setStartY(16);
        playhead.setEndY(bottom + 4);
        updatePlayhead();
    }

    private void buildRuler(double w) {
        ruler.getChildren().clear();
        double step = dur <= 12 ? 1 : dur <= 30 ? 2 : dur <= 70 ? 10 : dur <= 200 ? 30 : 60;
        for (double t = 0; t <= dur + 0.01; t += step) {
            Text tx = new Text(String.format("%02d:%02d", (int) t / 60, (int) t % 60));
            tx.setFill(Theme.web("#C5CAD3"));
            tx.setX(Math.min(timeToX(t), w - 36));
            tx.setY(13);
            Line tick = new Line(timeToX(t), 16, timeToX(t), 22);
            tick.setStroke(Theme.web("#626977"));
            ruler.getChildren().addAll(tx, tick);
            for (int k = 1; k < 5; k++) {
                double mt = t + step * k / 5;
                if (mt > dur) break;
                Line m = new Line(timeToX(mt), 19, timeToX(mt), 22);
                m.setStroke(Theme.web("#3A404B"));
                ruler.getChildren().add(m);
            }
        }
    }

    private void updatePlayhead() {
        double x = timeToX(now);
        playhead.setStartX(x);
        playhead.setEndX(x);
        playKnob.resizeRelocate(x - 6, 12, 12, 12);
        timeLabel.setText(Ui.duration(now) + " / " + Ui.duration(dur));
    }

    private void updateTrimFields() {
        inField.setText(fmtTime(in));
        outField.setText(fmtTime(out));
        keepLabel.setText("keeps " + fmtTime(out - in) + (in > 0.01 || out < dur - 0.01 ? "" : " (whole clip)"));
    }

    private void setIn(double t) {
        in = Math.max(0, Math.min(out - 0.5, t));
        layoutTimeline();
        updateTrimFields();
        markDirty();
        seek(in);
    }

    private void setOut(double t) {
        out = Math.min(dur, Math.max(in + 0.5, t));
        layoutTimeline();
        updateTrimFields();
        markDirty();
        seek(Math.max(in, out - 1));
    }

    static String fmtTime(double s) {
        int m = (int) (s / 60);
        return String.format(Locale.ROOT, "%d:%04.1f", m, s - m * 60);
    }

    /** Accepts "1:23.4", "83.4" or "83". */
    static double parseTime(String text, double fallback) {
        try {
            String t = text.trim().replace(',', '.');
            if (t.contains(":")) {
                String[] p = t.split(":");
                return Integer.parseInt(p[0]) * 60 + Double.parseDouble(p[1]);
            }
            return Double.parseDouble(t);
        } catch (RuntimeException e) { return fallback; }
    }

    private void seek(double t) {
        now = Math.max(0, Math.min(dur, t));
        if (player != null) player.seek(Duration.seconds(now));
        for (MediaPlayer m : sounds) m.seek(Duration.seconds(now));
        updatePlayhead();
        updateCaption();
    }

    private void pause() {
        if (player != null) player.pause();
        for (MediaPlayer m : sounds) m.pause();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.getStatus() == MediaPlayer.Status.PLAYING) pause();
        else {
            if (now < in || now >= out - 0.05) seek(in);
            player.play();
            for (MediaPlayer m : sounds) { m.seek(Duration.seconds(now)); m.play(); }
        }
    }

    private boolean edited() {
        boolean changed = in > 0.01 || out < dur - 0.01 || vertical || !texts.isEmpty();
        for (int i = 0; i < trackVol.length; i++) changed |= Math.abs(trackVol[i] - 1) > 1e-3 || trackMuted[i];
        return changed;
    }

    private void markDirty() {
        // Edits are remembered in Zyclipa as you make them (the file itself is only changed by Save trim / Export).
        if (!restoring) saveLater.playFromStart();
        if (saveTrim == null) return;
        saveTrim.setDisable(!edited() || ctx.capture.ffmpeg().isEmpty());
        saveTrim.setText(in > 0.01 || out < dur - 0.01 ? "Save trim" : "Save copy");
    }

    private void resetEdits() {
        in = 0; out = dur;
        if (originalCanvas != null) originalCanvas.setSelected(true);
        texts.clear();
        rebuildTexts();
        for (int i = 0; i < trackVol.length; i++) { trackSliders.get(i).setValue(1); setTrackMuted(i, false); }
        layoutTimeline();
        updateTrimFields();
        markDirty();
    }

    // ================================================================ actions

    private void save() {
        saveTrim.setDisable(true);
        boolean reencode = vertical || !texts.isEmpty();
        win.toast(reencode ? "Saving a new copy… (re-encoding the picture, this takes a moment)" : "Saving trimmed copy…", false);
        double a = in, b = out;
        boolean v = vertical;
        double[] vols = trackVol.clone();
        for (int i = 0; i < vols.length; i++) if (trackMuted[i]) vols[i] = 0;
        List<ClipLibrary.TextOverlay> t = List.copyOf(texts);
        CompletableFuture.supplyAsync(() -> {
            try { return ctx.library.exportEdit(clip, a, b, v, vols, t, ctx.capture.encoder()); }
            catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
        }).whenComplete((n, e) -> Ui.fx(() -> {
            if (e != null) { win.toast(e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), true); markDirty(); }
            else { win.toast("Saved “" + n.title + "” – the original is unchanged.", false); dispose(); openOther.accept(n); }
        }));
    }

    private void grabFrame() {
        double t = now;
        CompletableFuture.supplyAsync(() -> {
            try { return ctx.library.grabFrame(clip, t); } catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
        }).whenComplete((n, e) -> Ui.fx(() -> win.toast(e != null ? "Couldn't save the frame: " + e.getMessage() : "Frame saved to Screenshots", e != null)));
    }

    private void export() {
        ExportDialog.show(root.getScene().getWindow(), clip, edited(), out - in, ctx.settings.recording.exportsDir, kind -> {
            // Saved straight into the export folder (Settings › Storage), never over an earlier export.
            String base = clip.title.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
            String suffix = switch (kind) { case DISCORD -> " (Discord)"; case Q75 -> " (75%)"; case Q50 -> " (50%)"; case Q25 -> " (25%)"; default -> ""; };
            java.nio.file.Path dir = java.nio.file.Path.of(ctx.settings.recording.exportsDir);
            try { java.nio.file.Files.createDirectories(dir); }
            catch (Exception ex) { win.toast("Can't use the export folder " + dir + " – change it in Settings › Storage.", true); return; }
            java.nio.file.Path target = dir.resolve(base + suffix + "." + kind.ext);
            for (int i = 2; java.nio.file.Files.exists(target); i++) target = dir.resolve(base + suffix + " (" + i + ")." + kind.ext);
            java.io.File f = target.toFile();
            Clip.Edit e = currentEdit();
            win.toast("Exporting " + kind.title.toLowerCase() + "…", false);
            CompletableFuture.runAsync(() -> {
                try {
                    java.nio.file.Path made = ctx.library.render(clip, e, kind, f.toPath(), ctx.capture.encoder());
                    long size = java.nio.file.Files.size(made);
                    Ui.fx(() -> win.toast("Exported " + made.getFileName() + " (" + Ui.bytes(size) + ")", false));
                    Ui.fx(() -> reveal(made));
                } catch (Exception ex) {
                    Ui.fx(() -> win.toast("Export failed: " + ex.getMessage(), true));
                }
            });
        });
    }

    /** The trimmed part with the current track volumes becomes a sound in the "Clips" collection of the soundboard. */
    private void addToSoundboard() {
        Clip.Edit e = currentEdit();
        if (e.out - e.in > 60) { win.toast("Trim it to a minute or less first – soundboard sounds are short.", true); return; }
        win.toast("Adding to the soundboard…", false);
        CompletableFuture.runAsync(() -> {
            java.nio.file.Path dir = null;
            try {
                dir = java.nio.file.Files.createTempDirectory("zyclipa-sound");
                String name = clip.title.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
                java.nio.file.Path wav = dir.resolve((name.isEmpty() ? "Clip sound" : name) + ".wav");
                ctx.library.renderAudioWav(clip, e, wav);
                var sound = ctx.soundboard.importFile(wav, "Clips");
                Ui.fx(() -> win.toast("Added “" + sound.name + "” to the soundboard (Clips).", false));
            } catch (Exception ex) {
                Ui.fx(() -> win.toast("Couldn't add it to the soundboard: " + ex.getMessage(), true));
            } finally {
                if (dir != null) try (var st = java.nio.file.Files.walk(dir)) { st.sorted(java.util.Comparator.reverseOrder()).forEach(q -> q.toFile().delete()); } catch (Exception ignored) { }
            }
        });
    }

    private void reveal(Path f) {
        try {
            if (io.zyclipa.core.Platform.isWindows()) new ProcessBuilder("explorer.exe", "/select," + f).start();
            else new ProcessBuilder("xdg-open", f.getParent().toString()).start();   // Linux: open the folder
        } catch (Exception e) { win.toast("Couldn't open the folder: " + e.getMessage(), true); }
    }

    @SuppressWarnings("unused")
    private static boolean isKey(KeyCode c) { return c != null; }
}
