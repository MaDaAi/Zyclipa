package io.zyclipa.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Shown before Zyclipa starts anything (no recording, dashboard or Google Drive) the first time it runs and after
 * every update or change to the legal terms. The full documents are readable right here (the same ones as on the
 * website); the person must tick "I agree" and press Accept, or Quit. Acceptance is kept in legal-acceptance.json
 * with the terms version, app version and time.
 */
public final class TermsGate {
    /** Change this whenever the EULA, Terms of Use or Privacy Policy change, so everyone is asked again. */
    public static final String TERMS_VERSION = "2026-11-16";

    private TermsGate() { }

    static Path file(Path dataRoot) { return dataRoot.resolve("legal-acceptance.json"); }

    /** True when this app version and the current terms were already accepted on this PC. */
    public static boolean accepted(Path dataRoot, String appVersion) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file(dataRoot), StandardCharsets.UTF_8)).getAsJsonObject();
            return TERMS_VERSION.equals(o.get("terms").getAsString()) && appVersion.equals(o.get("app").getAsString());
        } catch (Exception e) {
            return false;   // missing or damaged: ask again
        }
    }

    static void record(Path dataRoot, String appVersion) throws java.io.IOException {
        JsonObject o = new JsonObject();
        o.addProperty("terms", TERMS_VERSION);
        o.addProperty("app", appVersion);
        o.addProperty("acceptedAt", Instant.now().toString());
        Files.createDirectories(dataRoot);
        Path tmp = file(dataRoot).resolveSibling("legal-acceptance.json.tmp");
        Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file(dataRoot), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static javafx.scene.Node summary(String appVersion, boolean updated) {
        VBox box = new VBox(12);
        box.getStyleClass().add("legal");
        box.getChildren().addAll(
                Ui.label(updated ? "Zyclipa was updated to " + appVersion : "Welcome to Zyclipa", "legal-h1"),
                Ui.label("Please read the agreements on the left. In short:", "legal-p"));
        for (String p : new String[]{
                "Zyclipa records your screen, game audio and microphone only when you start recording or instant replay.",
                "Your clips and settings stay on this PC. Google Drive upload and the remote dashboard are off until you turn them on.",
                "Zyclipa sends nothing to its developer. It checks GitHub for updates, which you can turn off in Settings.",
                "You're responsible for recording legally – some places require everyone's consent to record voices.",
                "Zyclipa is free and provided “as is”, without warranty."}) {
            box.getChildren().add(Ui.label("•  " + p, "legal-p", "legal-li"));
        }
        box.getChildren().add(Ui.label("Questions: notefirasupport@gmail.com · Made in Ontario, Canada", "legal-muted"));
        return box;
    }

    /** Shows the agreement on {@code stage}; runs {@code onAccept} after Accept, or exits on Quit / close. */
    public static void show(Stage stage, Path dataRoot, String appVersion, boolean updated, Runnable onAccept) {
        BorderPane docs = LegalDocs.viewer("Summary", summary(appVersion, updated), "Summary");

        ImageView logo = new ImageView();
        try (var in = TermsGate.class.getResourceAsStream("/ui/logo-64.png")) { if (in != null) logo.setImage(new Image(in, 28, 28, true, true)); }
        catch (Exception ignored) { }
        Label title = new Label(updated ? "Zyclipa was updated – please accept the terms" : "Before you start, please accept the terms");
        title.getStyleClass().add("h3");
        HBox head = new HBox(10, logo, title);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(14, 20, 14, 20));
        head.getStyleClass().add("legal-head");

        CheckBox agree = new CheckBox("I have read and agree to the End User Licence Agreement and the Terms of Use, and I have read the Privacy Policy.");
        agree.setWrapText(true);
        agree.setMaxWidth(560);
        Label error = Ui.label("", "small", "danger-text");
        Button accept = Ui.button("Accept and continue", null, () -> { }, "primary");
        accept.setDisable(true);
        accept.setDefaultButton(true);
        agree.selectedProperty().addListener((o, a, b) -> accept.setDisable(!b));
        Button quit = Ui.button("Quit", null, () -> System.exit(0), "outline");
        quit.setCancelButton(true);
        accept.setOnAction(e -> {
            try {
                record(dataRoot, appVersion);
                onAccept.run();
            } catch (Exception ex) {
                error.setText("Couldn't save your answer: " + ex.getMessage());
            }
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(12, new VBox(4, agree, error), grow, quit, accept);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(14, 20, 14, 20));
        bar.getStyleClass().add("legal-bar");

        BorderPane root = new BorderPane(docs, head, null, bar, null);
        root.getStyleClass().add("legal-window");
        Theme.apply(root, "system");
        Scene scene = new Scene(root, 1040, 720);
        scene.getStylesheets().add(TermsGate.class.getResource("/ui/zyclipa.css").toExternalForm());
        stage.setTitle("Zyclipa – terms");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> System.exit(0));
        stage.show();
        agree.requestFocus();
    }

}
