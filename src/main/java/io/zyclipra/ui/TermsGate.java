package io.zyclipra.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
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
 * Shown before Zyclipra starts anything (no recording, dashboard or Google Drive) the first time it runs and after
 * every update or change to the legal terms. The person must tick "I agree" and press Accept; Quit closes the app.
 * Acceptance is kept in its own small file (legal-acceptance.json) with the terms version, app version and time.
 */
public final class TermsGate {
    /** Change this whenever the EULA, Terms of Use or Privacy Policy change, so everyone is asked again. */
    public static final String TERMS_VERSION = "2026-11-16";
    static final String SITE = "https://madaai.github.io/zyclipra-dashboard/";

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

    /** Shows the agreement on {@code stage}; runs {@code onAccept} after Accept, or exits on Quit / close. */
    public static void show(Stage stage, Path dataRoot, String appVersion, boolean updated, Runnable onAccept) {
        Label title = new Label(updated ? "Zyclipra was updated" : "Welcome to Zyclipra");
        title.getStyleClass().add("h1");
        Label intro = Ui.label(updated
                ? "Before you continue, please read and accept the terms for version " + appVersion + "."
                : "Before you start, please read and accept these terms.", "body");
        Label points = Ui.label("""
                •  Zyclipra records your screen, game audio and microphone only when you start recording or instant replay.
                •  Your clips and settings stay on this PC. Google Drive upload and the remote dashboard are off until you turn them on.
                •  Zyclipra sends nothing to its developer. It checks GitHub for updates (you can turn that off in Settings).
                •  You're responsible for recording legally – some places require everyone's consent to record voices.
                •  Zyclipra is free and provided “as is”, without warranty.""", "body");
        points.setWrapText(true);
        HBox links = new HBox(14, link("End User Licence Agreement", "eula.html"), link("Terms of Use", "terms.html"),
                link("Privacy Policy", "privacy.html"), link("Third-party notices", "third-party-notices.txt"));
        CheckBox agree = new CheckBox("I have read and agree to the EULA and Terms of Use, and I have read the Privacy Policy.");
        agree.setWrapText(true);
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
        HBox buttons = new HBox(10, grow, quit, accept);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        Label contact = Ui.label("Questions: notefirasupport@gmail.com · Made in Ontario, Canada", "small", "muted");
        VBox box = new VBox(16, title, intro, points, links, agree, error, buttons, contact);
        box.setPadding(new Insets(32));
        box.setMaxWidth(760);
        box.getStyleClass().add("card");
        VBox root = new VBox(box);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));
        Scene scene = new Scene(root, 860, 600);
        scene.getStylesheets().add(TermsGate.class.getResource("/ui/zyclipra.css").toExternalForm());
        stage.setTitle("Zyclipra – terms");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> System.exit(0));
        stage.show();
        agree.requestFocus();
    }

    private static Hyperlink link(String text, String page) {
        Hyperlink h = new Hyperlink(text);
        h.setOnAction(e -> DriveSyncBox.browse(SITE + page));
        return h;
    }
}
