package io.zyclipa.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The legal documents inside the app. They are the same files as on the website (bundled in /web and kept in sync by
 * packaging/sync-legal.py), shown as native text so they work offline and look like the rest of Zyclipa.
 */
public final class LegalDocs {
    /** Page title → bundled file. */
    public static final Map<String, String> DOCS = new LinkedHashMap<>();
    static {
        DOCS.put("End User Licence Agreement", "eula.html");
        DOCS.put("Terms of Use", "terms.html");
        DOCS.put("Privacy Policy", "privacy.html");
        DOCS.put("Cookie Policy", "cookies.html");
        DOCS.put("Refund Policy", "refunds.html");
        DOCS.put("Third-party notices", "third-party-notices.txt");
    }

    private static final Pattern BLOCK = Pattern.compile("<(h1|h2|p|li|pre|tr)(\\s[^>]*)?>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[hd][^>]*>(.*?)</t[hd]>", Pattern.DOTALL);

    private LegalDocs() { }

    static String read(String file) {
        try (InputStream in = LegalDocs.class.getResourceAsStream("/web/" + file)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String text(String html) {
        String t = html.replaceAll("<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").strip();
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /** One document as native nodes (scrolls inside the caller's ScrollPane). */
    public static Node render(String file) {
        if (file.endsWith(".txt")) {
            TextArea t = new TextArea(read(file));
            t.setEditable(false);
            t.setWrapText(false);
            t.getStyleClass().add("legal-mono");
            VBox.setVgrow(t, Priority.ALWAYS);
            return t;
        }
        String html = read(file);
        int a = html.indexOf("<main"), b = html.lastIndexOf("</main>");
        if (a >= 0 && b > a) html = html.substring(a, b);
        VBox box = new VBox(8);
        box.getStyleClass().add("legal");
        Matcher m = BLOCK.matcher(html);
        GridPane table = null;
        int row = 0;
        while (m.find()) {
            String tag = m.group(1), attrs = m.group(2) == null ? "" : m.group(2), inner = m.group(3);
            if (tag.equals("p") && (inner.contains("← Zyclipa") || attrs.contains("muted small"))) continue;   // back link and footer links
            if (tag.equals("tr")) {
                if (table == null) { table = new GridPane(); table.setHgap(14); table.setVgap(6); box.getChildren().add(table); row = 0; }
                Matcher c = CELL.matcher(inner);
                int col = 0;
                boolean head = inner.contains("<th");
                while (c.find()) {
                    Label l = Ui.label(text(c.group(1)), head ? "legal-th" : "legal-td");
                    l.setMaxWidth(260);
                    table.add(l, col++, row);
                }
                row++;
                continue;
            }
            table = null;
            String t = text(inner);
            if (t.isEmpty()) continue;
            Label l = switch (tag) {
                case "h1" -> Ui.label(t, "legal-h1");
                case "h2" -> Ui.label(t, "legal-h2");
                case "li" -> Ui.label("•  " + t, "legal-p", "legal-li");
                case "pre" -> Ui.label(text(inner.replace("\n", "<br>")), "legal-pre");
                default -> Ui.label(t, attrs.contains("muted") ? "legal-muted" : "legal-p");
            };
            box.getChildren().add(l);
        }
        return box;
    }

    /** A sidebar of documents plus the reading pane; {@code extra} is shown first (e.g. a summary), when given. */
    public static BorderPane viewer(String extraTitle, Node extra, String first) {
        BorderPane pane = new BorderPane();
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("legal-scroll");
        VBox nav = new VBox(4);
        nav.getStyleClass().add("legal-nav");
        nav.setPadding(new Insets(12));
        ToggleGroup g = new ToggleGroup();
        Map<String, Runnable> open = new LinkedHashMap<>();
        if (extra != null) open.put(extraTitle, () -> show(scroll, extra));
        DOCS.forEach((title, file) -> open.put(title, () -> show(scroll, render(file))));
        ToggleButton firstButton = null;
        for (var e : open.entrySet()) {
            ToggleButton t = new ToggleButton(e.getKey());
            t.setToggleGroup(g);
            t.getStyleClass().add("legal-tab");
            t.setMaxWidth(Double.MAX_VALUE);
            t.setOnAction(ev -> { t.setSelected(true); e.getValue().run(); });
            nav.getChildren().add(t);
            if (firstButton == null || e.getKey().equals(first)) firstButton = t;
        }
        pane.setLeft(nav);
        pane.setCenter(scroll);
        if (firstButton != null) firstButton.fire();
        return pane;
    }

    private static void show(ScrollPane scroll, Node content) {
        VBox wrap = new VBox(content);
        wrap.setPadding(new Insets(20, 28, 28, 28));
        VBox.setVgrow(content, Priority.ALWAYS);
        if (content instanceof TextArea) { wrap.setFillWidth(true); scroll.setFitToHeight(true); } else scroll.setFitToHeight(false);
        scroll.setContent(wrap);
        scroll.setVvalue(0);
    }

    /** Opens the documents in their own window (from Settings › About and Privacy). */
    public static void openWindow(javafx.stage.Window owner, String first) {
        javafx.stage.Stage st = new javafx.stage.Stage();
        st.initOwner(owner);
        st.setTitle("Zyclipa – legal");
        BorderPane root = viewer(null, null, first);
        root.getStyleClass().add("legal-window");
        if (Theme.light()) root.getStyleClass().add("light");
        javafx.scene.Scene sc = new javafx.scene.Scene(root, 980, 700);
        sc.getStylesheets().add(LegalDocs.class.getResource("/ui/zyclipa.css").toExternalForm());
        st.setScene(sc);
        st.show();
    }
}
