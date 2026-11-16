package io.zyclipa.ui;

import io.zyclipa.clips.Clip;
import io.zyclipa.clips.ClipLibrary.Export;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Consumer;

/** Picks how to export a clip: original, under 10 MB for Discord, ¼–full quality, or audio only. */
final class ExportDialog {
    private ExportDialog() { }

    static void show(Window owner, Clip clip, boolean edited, double seconds, String folder, Consumer<Export> go) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("Export");
        ToggleGroup g = new ToggleGroup();
        VBox list = new VBox(6);
        double full = clip.durationSec > 0 ? clip.sizeBytes * Math.min(1, seconds / clip.durationSec) : clip.sizeBytes;
        for (Export k : Export.values()) {
            String est = switch (k) {
                case ORIGINAL -> Ui.bytes(clip.sizeBytes);
                case DISCORD -> "under 10 MB";
                case FULL -> "about " + Ui.bytes((long) full);
                case Q75 -> "about " + Ui.bytes((long) (full * 0.75));
                case Q50 -> "about " + Ui.bytes((long) (full * 0.5));
                case Q25 -> "about " + Ui.bytes((long) (full * 0.25));
                case AUDIO -> "about " + Ui.bytes((long) (seconds * 192_000 / 8));
            };
            Label name = Ui.label(k.title, "h3");
            Label desc = Ui.label(k.description, "small", "muted");
            Label size = Ui.label(est, "small", "muted");
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(10, new VBox(2, name, desc), grow, size);
            row.setAlignment(Pos.CENTER_LEFT);
            ToggleButton t = new ToggleButton();
            t.setGraphic(row);
            t.setUserData(k);
            t.setToggleGroup(g);
            t.getStyleClass().setAll("export-choice");
            t.setMaxWidth(Double.MAX_VALUE);
            if (k == Export.ORIGINAL && edited) desc.setText(k.description + " Your edits are left out.");
            if (k == Export.DISCORD) t.setSelected(true);
            list.getChildren().add(t);
        }
        g.selectedToggleProperty().addListener((o, a, b) -> { if (b == null && a != null) a.setSelected(true); });
        Label note = Ui.label(edited ? "Uses your edits: the trimmed part, 9:16, captions and track volumes (mixed into one track so it plays everywhere)."
                : "Shared videos get one mixed audio track so they play everywhere.", "small", "muted");
        Button cancel = Ui.button("Cancel", null, st::close, "outline");
        Button ok = Ui.button("Export", null, () -> { st.close(); go.accept((Export) g.getSelectedToggle().getUserData()); }, "primary");
        ok.setDefaultButton(true);
        cancel.setCancelButton(true);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label where = Ui.label("Saved to " + folder + " (change it in Settings › Storage).", "small", "muted");
        VBox box = new VBox(12, Ui.label("Export “" + clip.title + "”", "h1"), list, note, where, new HBox(10, grow, cancel, ok));
        box.setPadding(new Insets(20));
        box.getStyleClass().add("dialog-root");
        if (Theme.light()) box.getStyleClass().add("light");
        Scene sc = new Scene(box, 560, 630);
        sc.getStylesheets().add(ExportDialog.class.getResource("/ui/zyclipa.css").toExternalForm());
        st.setScene(sc);
        st.show();
    }
}
