package io.zyclipa.ui;

import io.zyclipa.app.AppContext;
import io.zyclipa.clips.Clip;
import io.zyclipa.cloud.SyncService;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.concurrent.CompletableFuture;

/**
 * One clip's Google Drive state with the actions that make sense for it: Local only → Upload;
 * Queued/Uploading → progress and Cancel; Failed → the reason and Retry; Synced → Open in Drive and
 * Delete cloud copy (asks first); Cloud file unavailable → Upload again. Updates live while visible.
 */
final class DriveSyncBox {
    private final AppContext ctx;
    private final MainWindow win;
    private final Clip clip;
    private final VBox box = new VBox(8);
    private final Label state = Ui.label("", "small");
    private final ProgressBar bar = new ProgressBar(0);
    private final FlowPane buttons = new FlowPane(8, 8);
    private Runnable unsubscribe = () -> { };

    DriveSyncBox(AppContext ctx, MainWindow win, Clip clip) {
        this.ctx = ctx;
        this.win = win;
        this.clip = clip;
        bar.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(state, bar, buttons);
        box.sceneProperty().addListener((o, a, b) -> {
            unsubscribe.run();
            if (b != null) unsubscribe = ctx.sync.onChange(i -> { if (i.clipId.equals(clip.id)) Ui.fx(this::refresh); });
        });
        refresh();
    }

    VBox node() { return box; }

    private void refresh() {
        String s = ctx.sync.stateOf(clip.id);
        SyncService.Item item = ctx.sync.item(clip.id).orElse(null);
        boolean connected = ctx.sync.connected();
        buttons.getChildren().clear();
        bar.setVisible(false);
        bar.setManaged(false);
        String text;
        String style = "muted";
        switch (s) {
            case SyncService.QUEUED -> {
                text = "Queued" + (item != null && !item.error.isBlank() ? " – " + item.error : connected ? " – uploads shortly." : " – connect Google Drive to upload.");
                buttons.getChildren().add(Ui.button("Cancel upload", null, this::cancel, "outline"));
            }
            case SyncService.UPLOADING -> {
                long done = item == null ? 0 : item.done, total = item == null ? 0 : Math.max(1, item.size);
                text = "Uploading… " + Ui.bytes(done) + " of " + Ui.bytes(total);
                bar.setProgress(done / (double) total);
                bar.setVisible(true);
                bar.setManaged(true);
                buttons.getChildren().add(Ui.button("Cancel upload", null, this::cancel, "outline"));
            }
            case SyncService.SYNCED -> {
                text = "✓ Synced to Google Drive" + (ctx.settings.cloud.folderName.isBlank() ? "" : " (" + ctx.settings.cloud.folderName + "/" + SyncService.categoryOf(clip) + ")");
                style = "ok-text";
                buttons.getChildren().addAll(
                        Ui.button("Open in Google Drive", Icons.CLOUD, () -> browse(ctx.sync.folderUrl()), "outline"),
                        Ui.button("Delete cloud copy", Icons.TRASH, this::deleteCloud, "outline"));
            }
            case SyncService.FAILED -> {
                text = "Upload failed: " + (item == null ? "" : item.error);
                style = "danger-text";
                buttons.getChildren().add(Ui.button("Retry", Icons.REPLAY, () -> { ctx.sync.retry(clip.id); refresh(); }, "primary"));
                buttons.getChildren().add(Ui.button("Remove from queue", null, this::cancel, "outline"));
            }
            case SyncService.UNAVAILABLE -> {
                text = "Cloud file unavailable – it was deleted from Google Drive. The clip on this PC is fine.";
                style = "warn-text";
                buttons.getChildren().add(Ui.button("Upload again", Icons.CLOUD, () -> { ctx.sync.retry(clip.id); refresh(); }, "primary"));
            }
            default -> {
                text = connected ? "Local only – not on Google Drive." : "Local only. Connect Google Drive in Settings › Cloud & Sync to back clips up.";
                Button up = Ui.button("Upload to Google Drive", Icons.CLOUD, () -> {
                    try { win.toast(ctx.sync.upload(clip), false); } catch (IllegalStateException e) { win.toast(e.getMessage(), true); }
                    refresh();
                }, "primary");
                buttons.getChildren().add(up);
            }
        }
        state.setText(text);
        state.getStyleClass().setAll("label", "small", style);
    }

    private void cancel() {
        try { win.toast(ctx.sync.cancel(clip.id), false); } catch (IllegalStateException e) { win.toast(e.getMessage(), true); }
        refresh();
    }

    private void deleteCloud() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "The copy of “" + clip.title + "” on Google Drive will be deleted. "
                + "The clip on this PC is not affected. This can't be undone from Zyclipa.", ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("Delete cloud copy");
        a.setHeaderText("Delete the Google Drive copy?");
        ClipsView.style(a.getDialogPane());
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        CompletableFuture.runAsync(() -> {
            try { ctx.sync.deleteCloudCopy(clip.id); Ui.fx(() -> { win.toast("Cloud copy deleted.", false); refresh(); }); }
            catch (Exception e) { Ui.fx(() -> win.toast("Couldn't delete the cloud copy: " + e.getMessage(), true)); }
        });
    }

    static void browse(String url) {
        try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); } catch (Exception ignored) { }
    }
}
