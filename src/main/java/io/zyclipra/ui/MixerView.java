package io.zyclipra.ui;

import io.zyclipra.app.AppContext;
import io.zyclipra.audio.AudioPlatform;
import io.zyclipra.audio.MixerService;
import io.zyclipra.audio.SoundDevices;
import io.zyclipra.audio.dsp.Equalizer;
import io.zyclipra.core.Settings;
import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
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
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Audio Mixer. "Mixer" shows every channel side by side with its volume, live level, mute and
 * the apps in it (click an app to move it). The Game/Chat/Media/Aux tabs give one channel's
 * apps more room, and Mic holds the equalizer and voice effects.
 */
public final class MixerView implements View {
    static final Map<String, String> COLORS = Map.of("master", "#6C78F7", "game", "#2BD9A0", "chat", "#3DA9F5",
            "media", "#F0479B", "aux", "#9D63F7", "mic", "#F5A524");
    static final Map<String, String> ICONS = Map.of("master", Icons.MIXER, "game", Icons.GAMEPAD, "chat", Icons.CHAT,
            "media", Icons.MEDIA, "aux", Icons.AUX, "mic", Icons.MIC);
    static final List<String> COLUMN_ORDER = List.of("master", "game", "chat", "media", "aux", "mic");
    static final List<String> APP_CHANNELS = List.of("game", "chat", "media", "aux");
    static final String[] BAND_COLORS = {"#9D63F7", "#6C78F7", "#F0479B", "#F04452", "#FF7A45", "#F5A524", "#B8E62E", "#2BD9A0", "#3DA9F5"};

    static final Map<String, double[]> BUILTIN = new LinkedHashMap<>();
    static {
        BUILTIN.put("Flat", new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0});
        BUILTIN.put("Deep voice", new double[]{-12, 3, 4, 3, 0, -2.5, -1, 0, 0});
        BUILTIN.put("Clear voice", new double[]{-9, -4, -1, 0, 1, 2.5, 3.5, 2, 0});
        BUILTIN.put("Bass boost", new double[]{6, 5, 3.5, 1.5, 0, 0, 0, 0, 0});
        BUILTIN.put("Bright", new double[]{0, 0, 0, 0, 0, 1, 3, 5, 6});
    }

    private final AppContext ctx;
    private final MainWindow win;
    private final BorderPane node = new BorderPane();
    private final StackPane tabBody = new StackPane();
    private final ToggleGroup tabs = new ToggleGroup();
    private String tab = "mixer";
    private final Map<String, java.util.function.DoubleConsumer> meters = new LinkedHashMap<>();
    private final Map<String, javafx.beans.property.DoubleProperty> sliders = new LinkedHashMap<>();
    private final Map<String, ToggleButton> mutes = new LinkedHashMap<>();
    private final Map<String, FlowPane> chipWells = new LinkedHashMap<>();
    private final Map<String, Label> subLabels = new LinkedHashMap<>();
    private final Map<String, Label> appCounts = new LinkedHashMap<>();
    private final Map<String, LevelMeter> appMeters = new LinkedHashMap<>();
    private List<MixerService.App> apps = List.of();
    private final ComboBox<String> profiles = new ComboBox<>();
    private final Canvas eq = new Canvas(900, 300);
    private final LevelMeter micIn = new LevelMeter(false, 8), micOut = new LevelMeter(false, 8);
    private final Label micState = Ui.label("", "small", "muted");
    private final Label cableInfo = Ui.label("Looking for a virtual audio cable…", "small", "muted");
    private final Timeline timer;
    private boolean syncing;
    private int dragBand = -1, selectedBand = -1;
    private final Label bandInfo = Ui.label("", "small", "muted");
    private Slider balance;

    public MixerView(AppContext ctx, MainWindow win) {
        this.ctx = ctx;
        this.win = win;

        HBox strip = new HBox(6);
        strip.getStyleClass().add("tab-strip");
        strip.getChildren().add(tabButton("mixer", "Mixer"));
        javafx.scene.layout.Region sep = new javafx.scene.layout.Region();
        sep.getStyleClass().add("tab-sep");
        HBox.setMargin(sep, new Insets(0, 10, 0, 10));
        strip.getChildren().add(sep);
        for (String id : List.of("game", "chat", "media", "aux", "mic")) strip.getChildren().add(tabButton(id, MixerService.CHANNEL_NAMES.get(id).replace("Microphone", "Mic")));

        profiles.setAccessibleText("Mixer profile");
        reloadProfiles();
        profiles.setOnAction(e -> {
            if (syncing || profiles.getValue() == null) return;
            try { ctx.mixer.loadProfile(profiles.getValue()); syncChannels(); win.toast("Profile “" + profiles.getValue() + "” loaded", false); }
            catch (Exception ex) { win.toast(ex.getMessage(), true); }
        });
        Button more = Ui.iconButton(Icons.MORE, "Profile options", () -> { }, "boxed");
        MenuItem save = new MenuItem("Save current levels as profile…");
        save.setOnAction(e -> saveProfile());
        MenuItem del = new MenuItem("Delete this profile");
        del.setOnAction(e -> {
            String p = profiles.getValue();
            if (p == null || p.equals("Default")) { win.toast("The Default profile can't be deleted.", true); return; }
            ctx.mixer.deleteProfile(p);
            reloadProfiles();
        });
        MenuItem adopt = new MenuItem("Use the volumes Windows has now");
        adopt.setOnAction(e -> { ctx.mixer.adoptEndpointLevels(); syncChannels(); });
        ContextMenu menu = new ContextMenu(save, del, adopt);
        more.setOnAction(e -> menu.show(more, Side.BOTTOM, 0, 0));
        HBox tabRow = Ui.row(10, strip, Ui.grow(), Ui.label("Profile", "muted"), profiles, more);
        tabRow.setPadding(new Insets(18, 28, 6, 28));

        node.setTop(new VBox(MainWindow.pageHead("Audio Mixer"), tabRow));
        node.setCenter(tabBody);

        timer = new Timeline(new KeyFrame(Duration.millis(80), e -> tick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        ctx.mixer.onChange(() -> Ui.fx(this::refreshApps));
        showTab("mixer");
    }

    @Override public String id() { return "mixer"; }
    @Override public String title() { return "Audio Mixer"; }
    @Override public String icon() { return Icons.MIXER; }
    @Override public Node node() { return node; }
    @Override public void shown() { syncChannels(); refreshApps(); timer.play(); }
    @Override public void hidden() { timer.stop(); }

    private ToggleButton tabButton(String id, String text) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().setAll("tab-btn");
        b.setToggleGroup(tabs);
        b.setUserData(id);
        b.setSelected(id.equals("mixer"));
        if (!id.equals("mixer")) b.setStyle("-accent: " + COLORS.get(id) + ";");
        b.setOnAction(e -> { if (!b.isSelected()) b.setSelected(true); showTab(id); });
        return b;
    }

    public void showTab(String id) {
        tab = id;
        for (var t : tabs.getToggles()) if (id.equals(t.getUserData())) t.setSelected(true);
        meters.clear(); sliders.clear(); mutes.clear(); chipWells.clear(); subLabels.clear(); appMeters.clear(); appCounts.clear();
        Node content = switch (id) {
            case "mixer" -> mixerTab();
            case "mic" -> micTab();
            default -> {
                currentChannel = new ChannelTab(ctx, win, id);
                currentChannel.onRebuild(() -> showTab(id));
                Node n = currentChannel.build();
                FlowPane holder = new FlowPane();
                holder.getChildren().add(currentChannel.appsBox());
                currentChannel.appsBox().prefWidthProperty().bind(holder.widthProperty());
                chipWells.put("list:" + id, holder);
                VBox.setVgrow(holder, Priority.ALWAYS);
                yield n;
            }
        };
        if (id.equals("mixer") || id.equals("mic")) currentChannel = null;
        if (content instanceof ScrollPane already) tabBody.getChildren().setAll(already);
        else {
            ScrollPane sp = new ScrollPane(content);
            sp.setFitToWidth(true);
            tabBody.getChildren().setAll(sp);
        }
        refreshApps();
        if (id.equals("mic")) drawEq();
    }

    // ---------------- Mixer tab ----------------

    private Node mixerTab() {
        // A grid so the balance box can sit exactly under the Game and Chat columns, its middle on the line between them.
        GridPane cols = new GridPane();
        cols.setHgap(2);
        for (int i = 0; i < COLUMN_ORDER.size(); i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints(130, 178, 260);
            cc.setHgrow(Priority.ALWAYS);
            cols.getColumnConstraints().add(cc);
            VBox col = column(COLUMN_ORDER.get(i));
            col.setMaxHeight(Double.MAX_VALUE);
            cols.add(col, i, 0);
        }
        double game = ctx.mixer.channel("game").volume, chat = ctx.mixer.channel("chat").volume;
        balance = new Slider(-1, 1, game >= chat ? -(1 - chat) : 1 - game);
        balance.getStyleClass().add("balance-slider");
        balance.setAccessibleText("Balance between game and chat");
        HBox.setHgrow(balance, Priority.ALWAYS);
        balance.setMaxWidth(Double.MAX_VALUE);
        // Bar grows from the middle towards the side you favour; the handle rests at the even split while dragging.
        Ui.fill(balance, 0, 0, COLORS.get("game"), COLORS.get("chat"));
        balance.valueProperty().addListener((o, a, b) -> {
            if (syncing) return;
            double v = balance.getValue(); // not b: the centre detent may have just moved it
            setChannel("game", v <= 0 ? 1 : 1 - v);
            setChannel("chat", v >= 0 ? 1 : 1 + v);
        });
        balance.setOnMouseClicked(e -> { if (e.getClickCount() == 2) balance.setValue(0); });
        balance.setTooltip(new javafx.scene.control.Tooltip("Slide towards Game or Chat to turn the other one down. Double-click to centre."));
        HBox bal = new HBox(12, colored(Icons.GAMEPAD, "game", 16), balance, colored(Icons.CHAT, "chat", 16));
        bal.setAlignment(Pos.CENTER);
        VBox balBox = new VBox(6, Ui.label("GAME / CHAT BALANCE", "balance-title"), bal);
        balBox.getStyleClass().add("balance-box");
        balBox.setAlignment(Pos.CENTER);
        cols.add(balBox, 1, 1, 2, 1);
        GridPane.setMargin(balBox, new Insets(4, 0, 0, 0));
        Label note = Ui.label(ctx.mixer.available()
                ? "Drag an app onto another channel (or click it) to move it. Click a channel's device name to pick where it plays (or which mic it uses)."
                : "Windows audio control isn't available on this system, so these sliders can't change app volumes.", "small", "muted");
        VBox page = new VBox(0, cols, new VBox(note));
        VBox.setMargin(note.getParent(), new Insets(16, 0, 0, 0));
        page.getStyleClass().add("page");
        return page;
    }

    /** Device under each column's name: click it to choose the output (Game…Aux) or the microphone (Mic). */
    private Node deviceButton(String id) {
        Label sub = Ui.label("", "small", "muted");
        sub.setWrapText(false);
        sub.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        sub.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sub, Priority.ALWAYS);
        subLabels.put(id, sub);
        HBox row = new HBox(6, sub);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mix-col-sub");
        if (id.equals("master")) {
            Ui.tip(row, "Master follows your Windows default speakers or headphones.");
            return row;
        }
        Node chevron = Icons.of(Icons.CHEVRON_DOWN, 12);
        row.getChildren().add(chevron);
        row.getStyleClass().add("mix-col-device");
        row.setFocusTraversable(true);
        row.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        row.setAccessibleText(id.equals("mic") ? "Choose microphone" : "Choose where " + MixerService.CHANNEL_NAMES.get(id) + " plays");
        row.setOnMouseClicked(e -> showDevices(id, row));
        row.setOnKeyPressed(e -> { if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) showDevices(id, row); });
        return row;
    }

    private String chosenDevice(String id) {
        return id.equals("mic") ? ctx.settings.mic.inputDevice : ctx.mixer.fx(id).outputDevice;
    }

    private void showDevices(String id, Node anchor) {
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);
        VBox box = devicePanel(id, popup::hide);
        box.getStylesheets().addAll(anchor.getScene().getStylesheets());
        popup.getContent().add(box);
        javafx.geometry.Bounds b = anchor.localToScreen(anchor.getLayoutBounds());
        if (b != null) popup.show(anchor, b.getMinX(), b.getMaxY() + 2);
    }

    /** The device list shown under a channel's name (public so the screenshot check can draw it too). */
    public VBox devicePanel(String id, Runnable close) {
        boolean mic = id.equals("mic");
        VBox box = new VBox(4);
        box.getStyleClass().add("device-popup");
        box.setPrefWidth(340);
        box.getChildren().add(Ui.label((mic ? "MIC INPUT" : MixerService.CHANNEL_NAMES.get(id).toUpperCase(Locale.ROOT) + " OUTPUT"), "device-popup-title"));
        VBox list = new VBox(2, Ui.label("Looking for devices…", "small", "muted"));
        box.getChildren().add(list);
        CompletableFuture.supplyAsync(() -> mic ? SoundDevices.inputs() : SoundDevices.outputs()).thenAccept(devs -> Ui.fx(() -> {
            list.getChildren().clear();
            String current = chosenDevice(id);
            List<String> names = new ArrayList<>();
            names.add("");
            // "Primary Sound Driver" entries are Windows' aliases for the default device, already listed first.
            devs.stream().filter(d -> !d.startsWith("Primary Sound")).forEach(names::add);
            if (!current.isBlank() && !devs.contains(current)) names.add(current);
            for (String d : names) {
                String text = d.isEmpty() ? (mic ? "Windows default microphone" : "Windows default playback device")
                        : devs.contains(d) ? d : d + " (disconnected)";
                Label item = new Label(text, Icons.of(Icons.GRIP, 12));
                item.getStyleClass().add("device-item");
                if (d.equals(current)) item.getStyleClass().add("device-item-selected");
                item.setMaxWidth(Double.MAX_VALUE);
                item.setTooltip(new javafx.scene.control.Tooltip(text));
                item.setOnMouseClicked(e -> { pickDevice(id, d); close.run(); });
                list.getChildren().add(item);
            }
        }));
        Label note;
        if (!mic) {
            Settings.ChannelFx fx = ctx.mixer.fx(id);
            note = Ui.label(fx.sourceDeviceId.isBlank() || !fx.active()
                    ? "Windows only lets an app's sound go somewhere else once it passes through Zyclipra: in the "
                      + MixerService.CHANNEL_NAMES.get(id) + " tab pick a sound source and turn on an effect, then it plays here."
                    : "● " + MixerService.CHANNEL_NAMES.get(id) + " is processed by Zyclipra and plays on the device picked here.", "small", "muted");
        } else {
            note = Ui.label("Zyclipra's voice effects listen to this microphone.", "small", "muted");
        }
        note.setMaxWidth(316);
        box.getChildren().add(note);
        Button manager = Ui.button("Windows sound settings", null, () -> {
            close.run();
            try { new ProcessBuilder("control.exe", "mmsys.cpl").start(); } catch (Exception ex) { win.toast("Couldn't open sound settings: " + ex.getMessage(), true); }
        }, "primary");
        manager.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(manager, new Insets(8, 0, 0, 0));
        box.getChildren().add(manager);
        return box;
    }

    private void pickDevice(String id, String device) {
        if (id.equals("mic")) {
            ctx.settings.mic.inputDevice = device;
            ctx.store.save();
            if (ctx.mic.running()) ctx.mic.restart();
        } else {
            ctx.mixer.fx(id).outputDevice = device;
            ctx.mixer.fxChanged(id);
        }
        refreshApps();
        win.toast(MixerService.CHANNEL_NAMES.get(id) + " now uses " + (device.isEmpty() ? "the Windows default" : device), false);
    }

    private Node colored(String icon, String channel, double size) {
        Node n = Icons.of(icon, size);
        n.setStyle("-icon-color: " + COLORS.get(channel) + ";");
        return n;
    }

    private VBox column(String id) {
        Settings.Channel c = ctx.mixer.channel(id);
        Label name = new Label(MixerService.CHANNEL_NAMES.get(id).replace("Microphone", "Mic").toUpperCase(Locale.ROOT), colored(ICONS.get(id), id, 18));
        name.getStyleClass().add("mix-col-name");
        name.setStyle("-fx-text-fill: " + COLORS.get(id) + ";");
        name.setGraphicTextGap(8);
        HBox head = new HBox(name);
        head.getStyleClass().add("mix-col-head");
        if (!id.equals("master")) {
            // Click a channel's name to open its page (Game, Chat, Media, Aux or Mic).
            head.getStyleClass().add("mix-col-head-link");
            head.setOnMouseClicked(e -> showTab(id));
            Ui.tip(head, "Open the " + MixerService.CHANNEL_NAMES.get(id).replace("Microphone", "Mic") + " page");
            head.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            head.setAccessibleText("Open the " + MixerService.CHANNEL_NAMES.get(id) + " page");
        }
        Node subRow = deviceButton(id);

        // One bar per channel: the live level fills the track and the volume handle rides on it.
        MeterFader s = new MeterFader(c.volume);
        s.setPrefHeight(250);
        s.setAccessibleText(MixerService.CHANNEL_NAMES.get(id) + " volume");
        Label pct = Ui.label(Math.round(c.volume * 100) + "%", "h3");
        pct.setWrapText(false);
        pct.setAlignment(Pos.CENTER);
        javafx.scene.layout.StackPane pctBox = Ui.editable(pct, 0, 100, v -> s.setValue(v / 100));
        pctBox.setAlignment(Pos.CENTER);
        pctBox.setMaxWidth(60);
        s.valueProperty().addListener((o, a, b) -> {
            pct.setText(Math.round(b.doubleValue() * 100) + "%");
            if (!syncing) ctx.mixer.setChannel(id, b.doubleValue(), mutes.get(id).isSelected());
        });
        HBox bars = new HBox(s);
        bars.setAlignment(Pos.CENTER);
        ToggleButton mute = muteButton(c.muted, MixerService.CHANNEL_NAMES.get(id), b -> { if (!syncing) ctx.mixer.setChannel(id, s.getValue(), b); });

        FlowPane well = new FlowPane(6, 6);
        well.getStyleClass().add("apps-well");
        well.setPrefWrapLength(140);
        well.setMinWidth(0);
        well.setMaxWidth(Double.MAX_VALUE);
        chipWells.put(id, well);
        Label appsLabel = Ui.label(id.equals("master") ? "Everything you hear" : id.equals("mic") ? "Your microphone" : "Apps", "small", "muted");
        appCounts.put(id, appsLabel);
        VBox bodyBox = new VBox(12, bars, pctBox, mute, appsLabel, well);
        VBox.setVgrow(bodyBox, Priority.ALWAYS);
        bodyBox.getStyleClass().add("mix-col-body");
        if (id.equals("master") || id.equals("mic")) { well.setVisible(false); well.setManaged(false); }

        VBox col = new VBox(head, subRow, bodyBox);
        col.getStyleClass().add("mix-col");
        col.setMinWidth(130);
        col.setPrefWidth(178);
        col.setMaxWidth(260);
        if (APP_CHANNELS.contains(id)) acceptDrops(col, id);
        meters.put(id, s::set);
        sliders.put(id, s.valueProperty());
        mutes.put(id, mute);
        return col;
    }

    static final String DRAG_PREFIX = "zyclipra-app:";

    /** Lets an app chip be dropped onto a channel column (or a channel tab's app list). */
    private void acceptDrops(Node target, String channel) {
        target.setOnDragOver(e -> {
            String v = e.getDragboard().hasString() ? e.getDragboard().getString() : "";
            if (v.startsWith(DRAG_PREFIX)) e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            e.consume();
        });
        target.setOnDragEntered(e -> {
            if (e.getDragboard().hasString() && e.getDragboard().getString().startsWith(DRAG_PREFIX))
                target.setStyle("-fx-border-color: " + COLORS.get(channel) + "; -fx-border-width: 2;");
        });
        target.setOnDragExited(e -> target.setStyle(""));
        target.setOnDragDropped(e -> {
            String v = e.getDragboard().hasString() ? e.getDragboard().getString() : "";
            boolean ok = v.startsWith(DRAG_PREFIX);
            if (ok) {
                String process = v.substring(DRAG_PREFIX.length());
                ctx.mixer.setAppChannel(process, channel);
                apps = List.of();
                refreshApps();
                win.toast("Moved " + process + " to " + MixerService.CHANNEL_NAMES.get(channel), false);
            }
            target.setStyle("");
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    private ToggleButton muteButton(boolean muted, String what, java.util.function.Consumer<Boolean> change) {
        ToggleButton mute = new ToggleButton(null, Icons.of(muted ? Icons.MUTE : Icons.VOLUME, 15));
        mute.getStyleClass().setAll("mute-btn");
        mute.setSelected(muted);
        mute.setAccessibleText("Mute " + what);
        mute.setTooltip(new javafx.scene.control.Tooltip("Mute " + what));
        mute.selectedProperty().addListener((o, a, b) -> { mute.setGraphic(Icons.of(b ? Icons.MUTE : Icons.VOLUME, 15)); change.accept(b); });
        return mute;
    }

    private void setChannel(String id, double v) {
        ctx.mixer.setChannel(id, v, ctx.mixer.channel(id).muted);
        var s = sliders.get(id);
        if (s != null) { syncing = true; s.set(v); syncing = false; }
    }

    private Label chip(MixerService.App a) {
        Label l = new Label(a.label().toUpperCase(Locale.ROOT));
        l.getStyleClass().add("app-chip");
        l.setMaxWidth(140);
        l.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        l.setStyle("-fx-background-color: " + COLORS.get(a.channel()) + ";" + (a.muted() ? "-fx-opacity: 0.5;" : ""));
        l.setTooltip(new javafx.scene.control.Tooltip(a.label() + " · " + a.device() + (a.active() ? " · playing" : " · quiet") + "\nClick to move to another channel"));
        ContextMenu m = new ContextMenu();
        for (String ch : APP_CHANNELS) {
            if (ch.equals(a.channel())) continue;
            MenuItem it = new MenuItem("Move to " + MixerService.CHANNEL_NAMES.get(ch));
            it.setOnAction(e -> { ctx.mixer.setAppChannel(a.process(), ch); apps = List.of(); refreshApps(); });
            m.getItems().add(it);
        }
        l.setOnMouseClicked(e -> { if (e.isStillSincePress()) m.show(l, Side.BOTTOM, 0, 2); });
        // Hold and drag the chip onto another channel column to move the app there.
        l.setOnDragDetected(e -> {
            javafx.scene.input.Dragboard db = l.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(DRAG_PREFIX + a.process());
            db.setContent(cc);
            db.setDragView(l.snapshot(null, null));
            e.consume();
        });
        l.setCursor(javafx.scene.Cursor.OPEN_HAND);
        return l;
    }

    // ---------------- single channel tab ----------------

    private Node channelTab(String id) {
        Settings.Channel c = ctx.mixer.channel(id);
        Slider s = Ui.fill(new Slider(0, 1, c.volume));
        HBox.setHgrow(s, Priority.ALWAYS);
        s.setAccessibleText(MixerService.CHANNEL_NAMES.get(id) + " volume");
        Label pct = Ui.label(Math.round(c.volume * 100) + "%", "h3");
        pct.setMinWidth(50);
        ToggleButton mute = muteButton(c.muted, MixerService.CHANNEL_NAMES.get(id), b -> ctx.mixer.setChannel(id, s.getValue(), b));
        s.valueProperty().addListener((o, a, b) -> { pct.setText(Math.round(b.doubleValue() * 100) + "%"); ctx.mixer.setChannel(id, b.doubleValue(), mute.isSelected()); });
        LevelMeter m = new LevelMeter(false, 8);
        m.setMaxWidth(Double.MAX_VALUE);
        meters.put(id, m::set);
        sliders.put(id, s.valueProperty());
        mutes.put(id, mute);
        VBox vol = Ui.section(MixerService.CHANNEL_NAMES.get(id) + " volume", null);
        vol.getChildren().addAll(Ui.row(14, colored(ICONS.get(id), id, 22), s, pct, mute), m);

        VBox appsCard = Ui.section("Apps in " + MixerService.CHANNEL_NAMES.get(id), null);
        FlowPane holder = new FlowPane();
        chipWells.put("list:" + id, holder);
        appsCard.getChildren().add(holder);
        appsCard.getChildren().add(Ui.label("Each app keeps its own volume inside the channel. Use “Move to” to send it to another channel.", "small", "muted"));
        VBox page = new VBox(16, vol, appsCard);
        page.getStyleClass().add("page");
        return page;
    }

    private Node appRow(MixerService.App a) {
        Label name = Ui.label(a.label(), "h3");
        name.setPrefWidth(180);
        Label dev = Ui.label((a.active() ? "playing" : "quiet") + " · " + a.device(), "small", "muted");
        dev.setWrapText(false);
        dev.setPrefWidth(260);
        LevelMeter m = new LevelMeter(false, 6);
        m.setPrefWidth(120);
        appMeters.put(a.process(), m);
        Slider v = Ui.fill(new Slider(0, 1, a.volume()));
        v.setPrefWidth(200);
        v.setAccessibleText(a.label() + " volume");
        ToggleButton mute = muteButton(a.muted(), a.label(), b -> ctx.mixer.setApp(a.process(), v.getValue(), b));
        v.valueProperty().addListener((o, x, y) -> ctx.mixer.setApp(a.process(), y.doubleValue(), mute.isSelected()));
        ComboBox<String> move = new ComboBox<>();
        for (String ch : APP_CHANNELS) move.getItems().add(MixerService.CHANNEL_NAMES.get(ch));
        move.setValue(MixerService.CHANNEL_NAMES.get(a.channel()));
        move.setAccessibleText("Channel for " + a.label());
        move.setOnAction(e -> { ctx.mixer.setAppChannel(a.process(), move.getValue().toLowerCase(Locale.ROOT)); apps = List.of(); refreshApps(); });
        HBox row = new HBox(12, name, dev, Ui.grow(), m, v, Ui.percent(v, 100), mute, move);
        row.getStyleClass().add("app-row");
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    void refreshApps() {
        List<MixerService.App> now = ctx.mixer.apps();
        List<String> key = now.stream().map(a -> a.process() + a.channel() + a.active() + a.muted()).toList();
        List<String> old = apps.stream().map(a -> a.process() + a.channel() + a.active() + a.muted()).toList();
        boolean changed = !key.equals(old);
        apps = now;
        for (String id : COLUMN_ORDER) {
            Label sub = subLabels.get(id);
            if (sub == null) continue;
            long n = now.stream().filter(a -> a.channel().equals(id)).count();
            String chosen = id.equals("master") ? "" : chosenDevice(id);
            sub.setText(chosen.isBlank() ? defaultDevice(!id.equals("mic")) : chosen);
            Label count = appCounts.get(id);
            if (count != null && APP_CHANNELS.contains(id)) count.setText(n == 0 ? "Apps" : n + (n == 1 ? " app" : " apps"));
        }
        if (!changed && !chipWells.isEmpty() && chipWells.values().stream().anyMatch(w -> !w.getChildren().isEmpty())) return;
        for (var e : chipWells.entrySet()) {
            e.getValue().getChildren().clear();
            if (e.getKey().startsWith("list:")) {
                String ch = e.getKey().substring(5);
                VBox rows = new VBox(6);
                rows.prefWidthProperty().bind(((FlowPane) e.getValue()).widthProperty());
                for (MixerService.App a : now) if (a.channel().equals(ch)) rows.getChildren().add(appRow(a));
                if (rows.getChildren().isEmpty()) rows.getChildren().add(Ui.label("No apps in this channel right now.", "muted"));
                e.getValue().getChildren().add(rows);
            } else {
                for (MixerService.App a : now) if (a.channel().equals(e.getKey())) e.getValue().getChildren().add(chip(a));
            }
        }
    }

    private String endpointsCacheOut = "", endpointsCacheIn = "";
    private long endpointsAt;

    private String defaultDevice(boolean output) {
        if (System.currentTimeMillis() - endpointsAt > 5000) {
            endpointsAt = System.currentTimeMillis();
            CompletableFuture.supplyAsync(() -> ctx.mixer.platform().endpoints()).thenAccept(l -> {
                for (AudioPlatform.Endpoint e : l) if (e.isDefault()) { if (e.output()) endpointsCacheOut = e.name(); else endpointsCacheIn = e.name(); }
            });
        }
        String n = output ? endpointsCacheOut : endpointsCacheIn;
        if (n.isBlank()) return output ? "Default output" : "Default mic";
        return n.length() > 22 ? n.substring(0, 21) + "…" : n;
    }

    private ChannelTab currentChannel;

    private void tick() {
        if (currentChannel != null) currentChannel.tick();
        Map<String, Float> lv = ctx.mixer.levels();
        for (var e : meters.entrySet()) e.getValue().accept(ctx.mixer.channel(e.getKey()).muted ? 0 : lv.getOrDefault(e.getKey(), 0f));
        for (MixerService.App a : apps) {
            LevelMeter m = appMeters.get(a.process());
            if (m != null) m.set(ctx.mixer.appPeak(a));
        }
        if (tab.equals("mic")) {
            micIn.set(ctx.mic.inputPeak());
            micOut.set(ctx.mic.outputPeak());
            if (!ctx.mic.warning().isBlank()) { micState.setText(ctx.mic.warning()); return; }
            micState.setText(!ctx.mic.running() ? (ctx.mic.error().isBlank() ? "Voice effects are off – turn them on to use everything below." : ctx.mic.error())
                    : ctx.mic.gateOpen() ? "Listening · you're coming through" : "Listening · gate closed (quiet)");
        }
    }

    private void syncChannels() {
        syncing = true;
        for (var e : sliders.entrySet()) {
            Settings.Channel c = ctx.mixer.channel(e.getKey());
            e.getValue().set(c.volume);
            mutes.get(e.getKey()).setSelected(c.muted);
        }
        syncing = false;
        reloadProfiles();
    }

    private void reloadProfiles() {
        syncing = true;
        profiles.getItems().setAll(ctx.mixer.profiles());
        profiles.getSelectionModel().select(ctx.mixer.activeProfile());
        syncing = false;
    }

    private void saveProfile() {
        TextInputDialog d = new TextInputDialog(ctx.mixer.activeProfile());
        d.setTitle("Save mixer profile");
        d.setHeaderText("Profile name");
        ClipsView.style(d.getDialogPane());
        d.showAndWait().ifPresent(n -> {
            try { ctx.mixer.saveProfile(n); reloadProfiles(); win.toast("Saved profile “" + n + "”", false); }
            catch (Exception ex) { win.toast(ex.getMessage(), true); }
        });
    }

    // ---------------- Mic tab ----------------

    /**
     * Mic page, laid out like the other channel pages: a top bar (voice effects on/off, live levels, where your
     * voice goes, Set up, Reset), then one card per effect. A switched-off card shows only its title.
     */
    private Node micTab() {
        Settings.Mic m = ctx.settings.mic;

        // ---- top bar
        StackPane icon = new StackPane(Icons.of(Icons.MIC, 26));
        icon.setStyle("-fx-background-color: " + COLORS.get("mic") + "; -fx-background-radius: 3; -icon-color: white;");
        icon.setMinSize(60, 60);
        icon.setMaxSize(60, 60);
        ToggleButton on = Ui.toggleSwitch(m.enabled, "Voice effects", b -> { m.enabled = b; ctx.store.save(); if (b) ctx.mic.start(); else ctx.mic.stop(); });
        Label title = Ui.label("Voice effects", "h3");
        micState.setWrapText(true);
        VBox nameBox = new VBox(4, Ui.row(10, title, on), micState);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox.setHgrow(micIn, Priority.ALWAYS);
        HBox.setHgrow(micOut, Priority.ALWAYS);
        micIn.setMaxWidth(Double.MAX_VALUE);
        micOut.setMaxWidth(Double.MAX_VALUE);
        Label inL = Ui.label("Mic in", "small", "muted"), outL = Ui.label("Voice out", "small", "muted");
        inL.setMinWidth(60);
        outL.setMinWidth(60);
        VBox levels = new VBox(8, Ui.row(8, inL, micIn), Ui.row(8, outL, micOut));
        levels.setPrefWidth(260);
        levels.setAlignment(Pos.CENTER);
        Button setup = Ui.button("Set up", Icons.SETTINGS, this::micSetup, "outline");
        Button resetAll = Ui.button("Reset to defaults", Icons.REPLAY, this::resetMic, "boxed");
        resetAll.setTooltip(new javafx.scene.control.Tooltip("Puts mic boost, the equalizer, noise cancellation, gate, compressor and limiter back to how they started. Your devices are kept."));
        VBox buttons = new VBox(6, setup, resetAll);
        setup.setMaxWidth(Double.MAX_VALUE);
        resetAll.setMaxWidth(Double.MAX_VALUE);
        HBox top = new HBox(16, icon, nameBox, levels, buttons);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("preset-card");
        top.setPadding(new Insets(12, 14, 12, 12));

        // ---- cards
        VBox boost = Ui.section("Mic boost", null);
        boost.getChildren().addAll(Ui.slider("Gain", -20, 30, m.gainDb, " dB", 1, v -> { m.gainDb = v; ctx.store.save(); }),
                Ui.label("Makes your voice louder or quieter before the effects below.", "small", "muted"));

        Settings.Equalizer e = ctx.settings.equalizer;
        ComboBox<String> preset = new ComboBox<>();
        reloadPresets(preset);
        preset.setAccessibleText("Equalizer preset");
        preset.setOnAction(ev -> { if (!syncing && preset.getValue() != null) { applyPreset(preset.getValue()); reloadPresets(preset); } });
        Button eqMore = Ui.iconButton(Icons.MORE, "Equalizer options", () -> { }, "boxed");
        MenuItem save = new MenuItem("Save as preset…");
        save.setOnAction(ev -> savePreset(preset));
        MenuItem reset = new MenuItem("Reset to flat");
        reset.setOnAction(ev -> { applyPreset("Flat"); reloadPresets(preset); });
        MenuItem delete = new MenuItem("Delete this preset");
        delete.setOnAction(ev -> {
            String p = preset.getValue();
            if (p == null || BUILTIN.containsKey(p)) { win.toast("Built-in presets can't be deleted.", true); return; }
            e.presets.remove(p); e.preset = "Flat"; ctx.store.save(); reloadPresets(preset);
        });
        ToggleButton sbEq = Ui.toggleSwitch(ctx.settings.soundboard.useEq, "Use on soundboard", b -> { ctx.settings.soundboard.useEq = b; ctx.store.save(); });
        ContextMenu em = new ContextMenu(save, reset, delete);
        eqMore.setOnAction(ev -> em.show(eqMore, Side.BOTTOM, 0, 0));
        ctx.settings.mic.eq = true;
        micEq = new EqPanel(e, () -> { ctx.store.save(); ctx.mic.eqChanged(); }, preset, eqMore);
        VBox eqCard = micEq.node();
        eqCard.getChildren().add(Ui.row(10, Ui.grow(), Ui.label("Also use on soundboard", "small", "muted"), sbEq));

        VBox noise = Ui.section("Noise cancellation", Ui.toggleSwitch(m.noiseSuppression, "Noise cancellation", b -> { m.noiseSuppression = b; ctx.store.save(); }));
        noise.getChildren().addAll(Ui.slider("Min ⟶ Max", 0, 40, m.noiseReductionDb, " dB", 0, v -> { m.noiseReductionDb = v; ctx.store.save(); }),
                Ui.label("Removes steady background noise like fans and hum.", "small", "muted"));
        VBox gate = Ui.section("Noise gate", Ui.toggleSwitch(m.gate, "Noise gate", b -> { m.gate = b; ctx.store.save(); }));
        gate.getChildren().addAll(Ui.slider("Threshold", -90, 0, m.gateThresholdDb, " dB", 0, v -> { m.gateThresholdDb = v; ctx.store.save(); }),
                Ui.label("Silences your mic when you're not talking.", "small", "muted"));
        VBox comp = Ui.section("Compressor", Ui.toggleSwitch(m.compressor, "Compressor", b -> { m.compressor = b; ctx.store.save(); }));
        comp.getChildren().addAll(Ui.slider("Threshold", -60, 0, m.compThresholdDb, " dB", 0, v -> { m.compThresholdDb = v; ctx.store.save(); }),
                Ui.slider("Strength", 1, 20, m.compRatio, ":1", 1, v -> { m.compRatio = v; ctx.store.save(); }));
        VBox lim = Ui.section("Limiter", Ui.toggleSwitch(m.limiter, "Limiter", b -> { m.limiter = b; ctx.store.save(); }));
        lim.getChildren().addAll(Ui.slider("Ceiling", -20, 0, m.limiterCeilingDb, " dB", 1, v -> { m.limiterCeilingDb = v; ctx.store.save(); }),
                Ui.label("Stops shouting from clipping.", "small", "muted"));

        VBox page = new VBox(16, Ui.row(6, new javafx.scene.shape.Circle(4, Color.web(m.enabled ? "#2BD9A0" : "#626977")), Ui.label("VOICE", "tiny-caps")),
                top, eqCard, noise, ChannelTab.twoColumns(gate, comp), ChannelTab.twoColumns(boost, lim));
        VBox.setMargin(page.getChildren().get(0), new Insets(0, 0, -10, 0));
        page.getStyleClass().add("page");
        return page;
    }

    /** Which microphone, where the processed voice goes, and "hear myself" – kept out of the way in a dialog. */
    private void micSetup() {
        Settings.Mic m = ctx.settings.mic;
        ComboBox<String> input = new ComboBox<>(), route = new ComboBox<>(), monitor = new ComboBox<>();
        for (ComboBox<String> c : List.of(input, route, monitor)) c.setMaxWidth(Double.MAX_VALUE);
        CompletableFuture.supplyAsync(() -> List.of(SoundDevices.inputs(), SoundDevices.outputs())).thenAccept(l -> Ui.fx(() -> {
            List<String> ins = l.get(0).stream().filter(d -> !d.startsWith("Primary Sound")).toList();
            List<String> outs = l.get(1).stream().filter(d -> !d.startsWith("Primary Sound")).toList();
            fill(input, ins, m.inputDevice, "Windows default microphone");
            fill(route, outs, m.routeDevice, "Don't send it anywhere");
            fill(monitor, outs, m.monitorDevice, "Windows default playback device");
        }));
        ToggleButton hear = Ui.toggleSwitch(m.monitor, "Hear myself", b -> { });
        cableInfo.setWrapText(true);
        cableInfo.setMaxWidth(520);
        CompletableFuture.supplyAsync(io.zyclipra.audio.VirtualCables::find).thenAccept(list -> Ui.fx(() -> {
            List<io.zyclipra.audio.VirtualCables.Cable> known = list.stream().filter(io.zyclipra.audio.VirtualCables.Cable::knownFreeDriver).toList();
            if (known.isEmpty()) { cableInfo.setText(io.zyclipra.audio.VirtualCables.HOW_TO_INSTALL); cableInfo.getStyleClass().setAll("label", "small", "warn-text"); }
            else {
                var c = known.get(0);
                cableInfo.setText("To use your processed voice in Discord or a game: send it to “" + c.playback()
                        + "” above, then pick “" + c.recording() + "” as the microphone in that app. It adds about 0.1 s of delay.");
                cableInfo.getStyleClass().setAll("label", "small", "muted");
            }
        }));
        VBox body = new VBox(14,
                Ui.field("Microphone", input),
                Ui.field("Send my processed voice to (for Discord, games…)", route),
                cableInfo,
                Ui.row(12, Ui.label("Hear myself", "h3"), hear),
                Ui.field("Play “hear myself” on", monitor));
        body.setPrefWidth(540);
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle("Mic set-up");
        d.setHeaderText("Microphone set-up");
        d.getDialogPane().setContent(body);
        d.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        ClipsView.style(d.getDialogPane());
        if (d.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;
        boolean restart = false;
        if (input.getValue() != null) { String v = val(input); restart |= !v.equals(m.inputDevice); m.inputDevice = v; }
        if (monitor.getValue() != null) { String v = val(monitor); restart |= !v.equals(m.monitorDevice); m.monitorDevice = v; }
        if (route.getValue() != null) { m.routeDevice = val(route); ctx.mic.setRouteDevice(m.routeDevice); }
        m.monitor = hear.isSelected();
        ctx.store.save();
        if (restart && ctx.mic.running()) ctx.mic.restart();
        refreshApps();
    }

    private void resetMic() {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Mic boost, the equalizer and all voice effects go back to how they started. Your microphone and output choices are kept.",
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        a.setTitle("Reset to defaults");
        a.setHeaderText("Reset the Mic page to defaults?");
        ClipsView.style(a.getDialogPane());
        if (a.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;
        Settings.Mic m = ctx.settings.mic, d = new Settings.Mic();
        m.gainDb = d.gainDb; m.gate = d.gate; m.gateThresholdDb = d.gateThresholdDb;
        m.compressor = d.compressor; m.compThresholdDb = d.compThresholdDb; m.compRatio = d.compRatio;
        m.limiter = d.limiter; m.limiterCeilingDb = d.limiterCeilingDb;
        m.noiseSuppression = d.noiseSuppression; m.noiseReductionDb = d.noiseReductionDb; m.monitor = d.monitor;
        EqPanel.apply(ctx.settings.equalizer, Settings.defaultBands(), "Flat");
        ctx.settings.equalizer.enabled = false;
        ctx.store.save();
        ctx.mic.eqChanged();
        win.toast("The Mic page is back to its defaults", false);
        showTab("mic");
    }

    private EqPanel micEq;

    private void eqChanged() {
        ctx.store.save();
        ctx.mic.eqChanged();
        drawEq();
    }

    void drawEq() { if (micEq != null) micEq.refresh(); }

    private void reloadPresets(ComboBox<String> c) {
        syncing = true;
        List<String> names = new ArrayList<>(BUILTIN.keySet());
        names.addAll(ctx.settings.equalizer.presets.keySet());
        if (ctx.settings.equalizer.preset.equals("Custom")) names.add("Custom");
        c.getItems().setAll(names);
        c.getSelectionModel().select(ctx.settings.equalizer.preset);
        syncing = false;
    }

    private void applyPreset(String name) {
        Settings.Equalizer e = ctx.settings.equalizer;
        List<Settings.EqBand> src;
        if (BUILTIN.containsKey(name)) {
            src = Settings.defaultBands();
            double[] g = BUILTIN.get(name);
            for (int i = 0; i < 9; i++) src.get(i).gainDb = g[i];
        } else if (e.presets.containsKey(name)) src = e.presets.get(name);
        else return;
        for (int i = 0; i < 9 && i < src.size(); i++) {
            Settings.EqBand d = e.bands.get(i), x = src.get(i);
            d.freq = x.freq; d.gainDb = x.gainDb; d.q = x.q; d.type = x.type;
        }
        e.preset = name;
        eqChanged();
    }

    private void savePreset(ComboBox<String> c) {
        TextInputDialog d = new TextInputDialog("My preset");
        d.setTitle("Save equalizer preset");
        d.setHeaderText("Preset name");
        ClipsView.style(d.getDialogPane());
        d.showAndWait().ifPresent(n -> {
            String name = n.trim();
            if (name.isEmpty() || name.length() > 40 || BUILTIN.containsKey(name) || name.equals("Custom")) { win.toast("Pick another preset name.", true); return; }
            List<Settings.EqBand> copy = new ArrayList<>();
            for (Settings.EqBand b : ctx.settings.equalizer.bands) copy.add(new Settings.EqBand(b.freq, b.gainDb, b.q, b.type));
            ctx.settings.equalizer.presets.put(name, copy);
            ctx.settings.equalizer.preset = name;
            ctx.store.save();
            reloadPresets(c);
        });
    }

    // ---------------- helpers shared with other screens ----------------

    static VBox labelled(String label, Node n) { return Ui.field(label, n); }

    static void fill(ComboBox<String> c, List<String> items, String current, String defaultLabel) {
        List<String> l = new ArrayList<>();
        l.add(defaultLabel);
        l.addAll(items);
        if (!current.isBlank() && !items.contains(current)) l.add(current + " (disconnected)");
        c.getItems().setAll(l);
        c.setUserData(defaultLabel);
        c.getSelectionModel().select(current.isBlank() ? defaultLabel : items.contains(current) ? current : current + " (disconnected)");
        c.setPrefWidth(300);
    }

    static String val(ComboBox<String> c) {
        String v = c.getValue();
        if (v == null || v.equals(c.getUserData())) return "";
        return v.endsWith(" (disconnected)") ? v.substring(0, v.length() - 15) : v;
    }
}
