package io.zyclipa.ui;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/** Original simple line-art icons drawn as SVG paths on a 24x24 grid. */
public final class Icons {
    private Icons() {}

    public static final String HOME = "M3 11 L12 3 L21 11 V21 H14 V15 H10 V21 H3 Z";
    public static final String CLIPS = "M3 5 H17 V19 H3 Z M17 9.5 L22 6.5 V17.5 L17 14.5 Z";
    public static final String MIXER = "M5 3 V21 M12 3 V21 M19 3 V21 M2.5 15 H7.5 M9.5 8 H14.5 M16.5 13 H21.5";
    public static final String SOUND = "M4 9 H8 L13 5 V19 L8 15 H4 Z M16 9 Q18.5 12 16 15 M18.5 6.5 Q23 12 18.5 17.5";
    /** 8-tooth gear with a centre hole – the same icon as Notefira's settings (drawn as an outline). */
    public static final String SETTINGS = "M10.08 4.85 L10.5 2.52 A9.6 9.6 0 0 1 13.5 2.52 L13.92 4.85 A7.4 7.4 0 0 1 15.7 5.59 L17.64 4.23 A9.6 9.6 0 0 1 19.77 6.36 L18.41 8.3 A7.4 7.4 0 0 1 19.15 10.08 L21.48 10.5 A9.6 9.6 0 0 1 21.48 13.5 L19.15 13.92 A7.4 7.4 0 0 1 18.41 15.7 L19.77 17.64 A9.6 9.6 0 0 1 17.64 19.77 L15.7 18.41 A7.4 7.4 0 0 1 13.92 19.15 L13.5 21.48 A9.6 9.6 0 0 1 10.5 21.48 L10.08 19.15 A7.4 7.4 0 0 1 8.3 18.41 L6.36 19.77 A9.6 9.6 0 0 1 4.23 17.64 L5.59 15.7 A7.4 7.4 0 0 1 4.85 13.92 L2.52 13.5 A9.6 9.6 0 0 1 2.52 10.5 L4.85 10.08 A7.4 7.4 0 0 1 5.59 8.3 L4.23 6.36 A9.6 9.6 0 0 1 6.36 4.23 L8.3 5.59 A7.4 7.4 0 0 1 10.08 4.85 Z M15.2 12 A3.2 3.2 0 1 1 8.8 12 A3.2 3.2 0 0 1 15.2 12 Z";
    public static final String RECORD = "M12 5 A7 7 0 1 0 12.01 5 Z";
    public static final String STOP = "M6 6 H18 V18 H6 Z";
    public static final String CHEVRON_DOWN = "M6 9 L12 15 L18 9";
    public static final String GRIP = "M8 5 H10 V7 H8 Z M14 5 H16 V7 H14 Z M8 11 H10 V13 H8 Z M14 11 H16 V13 H14 Z M8 17 H10 V19 H8 Z M14 17 H16 V19 H14 Z";
    public static final String PLAY = "M7 4 L20 12 L7 20 Z";
    public static final String PAUSE = "M6 4 H10 V20 H6 Z M14 4 H18 V20 H14 Z";
    public static final String REPLAY = "M12 4 A8 8 0 1 1 4.5 9.5 M4 4 V10 H10";
    public static final String CAMERA = "M3 7 H7 L9 4.5 H15 L17 7 H21 V19 H3 Z M12 9.5 A3.5 3.5 0 1 0 12.01 9.5 Z";
    public static final String MIC = "M9 3 H15 V13 H9 Z M5.5 11 Q5.5 18 12 18 Q18.5 18 18.5 11 M12 18 V21.5 M8.5 21.5 H15.5";
    public static final String MUTE = "M4 9 H8 L13 5 V19 L8 15 H4 Z M16 9 L22 15 M22 9 L16 15";
    public static final String VOLUME = "M4 9 H8 L13 5 V19 L8 15 H4 Z M16 9 Q18.5 12 16 15";
    public static final String TRASH = "M4 7 H20 M9 7 V4 H15 V7 M6 7 L7 21 H17 L18 7";
    public static final String EDIT = "M4 20 L4.5 16 L16 4.5 L19.5 8 L8 19.5 Z";
    public static final String EXPORT = "M12 3 V15 M7 8 L12 3 L17 8 M4 14 V20 H20 V14";
    public static final String FOLDER = "M3 6 H10 L12 8 H21 V19 H3 Z";
    public static final String PLUS = "M12 4 V20 M4 12 H20";
    public static final String CLOUD = "M7 18 H17.5 A4.5 4.5 0 0 0 17 9 A6 6 0 0 0 5.5 11 A3.5 3.5 0 0 0 7 18 Z";
    public static final String STAR = "M12 3 L14.6 9 L21 9.5 L16 13.7 L17.6 20 L12 16.6 L6.4 20 L8 13.7 L3 9.5 L9.4 9 Z";
    public static final String SCISSORS = "M6 6 A2.5 2.5 0 1 0 6.01 6 Z M6 18 A2.5 2.5 0 1 0 6.01 18 Z M8 7.5 L20 17 M8 16.5 L20 7";
    public static final String LOOP = "M4 12 V9 Q4 6 7 6 H18 M15 3 L18 6 L15 9 M20 12 V15 Q20 18 17 18 H6 M9 21 L6 18 L9 15";
    public static final String SEARCH = "M10.5 4 A6.5 6.5 0 1 0 10.51 4 Z M15.5 15.5 L21 21";
    public static final String GLOBE = "M12 3 A9 9 0 1 0 12.01 3 Z M3 12 H21 M12 3 Q16 7.5 12 21 Q8 7.5 12 3";
    public static final String KEY = "M8 10 A4 4 0 1 0 8.01 10 Z M11.5 12 H21 M18 12 V15.5 M15 12 V14.5";
    /** Clip it: frame corners with a play mark (same drawing as the dashboards). */
    public static final String CLIP_IT = "M4 8 V5.5 A1.5 1.5 0 0 1 5.5 4 H8 M16 4 H18.5 A1.5 1.5 0 0 1 20 5.5 V8 M20 16 V18.5 A1.5 1.5 0 0 1 18.5 20 H16 M8 20 H5.5 A1.5 1.5 0 0 1 4 18.5 V16 M10 9 L15 12 L10 15 Z";
    public static final String REFRESH = "M20 12 A8 8 0 1 1 17.5 6.2 M20 3 V8 H15";

    public static final String GAMEPAD = "M7 7 H17 Q21 7 21.5 12 L22 16 Q22 19 19.5 19 Q18 19 16.5 16.5 H7.5 Q6 19 4.5 19 Q2 19 2 16 L2.5 12 Q3 7 7 7 Z M7 10 V14 M5 12 H9 M15.5 11 V11.1 M17.5 13 V13.1";
    public static final String CHAT = "M4 4 H20 V16 H10 L5 20 V16 H4 Z M8 9 V11 M11 7.5 V12.5 M14 8.5 V11.5 M17 9.5 V10.5";
    public static final String MEDIA = "M3 5 H21 V19 H3 Z M10 9 L15 12 L10 15 Z";
    /** Headphones: Aux is mostly used for music. */
    public static final String AUX = "M3 14 V12 A9 9 0 0 1 21 12 V14 H19 V12 A7 7 0 0 0 5 12 V14 Z M3 14 H8 V21 H5 A2 2 0 0 1 3 19 Z M21 14 H16 V21 H19 A2 2 0 0 0 21 19 Z";
    public static final String MORE = "M12 5 V5.1 M12 12 V12.1 M12 19 V19.1";
    public static final String SHARE = "M17 4 A2.5 2.5 0 1 0 17.01 4 Z M6 9.5 A2.5 2.5 0 1 0 6.01 9.5 Z M17 15 A2.5 2.5 0 1 0 17.01 15 Z M8.2 11 L14.8 7.5 M8.2 13 L14.8 16.5";
    public static final String GRID = "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z";
    public static final String LIST = "M4 6 H20 M4 12 H20 M4 18 H20";
    public static final String MONITOR = "M3 4 H21 V16 H3 Z M12 16 V20 M8 20 H16";
    public static final String FILM = "M4 4 H20 V20 H4 Z M8 4 V20 M16 4 V20 M4 8 H8 M4 12 H8 M4 16 H8 M16 8 H20 M16 12 H20 M16 16 H20";
    public static final String CLOSE = "M6 6 L18 18 M18 6 L6 18";
    public static final String BACK = "M15 5 L8 12 L15 19";
    public static final String PADS = "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14.5 15 L19.5 17 L14.5 19 Z";

    // transport (filled shapes)
    public static final String SKIP_START = "M4 5 H7 V19 H4 Z M20 5 L13.5 12 L20 19 Z M13.5 5 L7 12 L13.5 19 Z";
    public static final String FRAME_BACK = "M16 5 L7 12 L16 19 Z M17.5 5 H20.5 V19 H17.5 Z";
    public static final String FRAME_FWD = "M8 5 L17 12 L8 19 Z M3.5 5 H6.5 V19 H3.5 Z";
    public static final String SKIP_END = "M17 5 H20 V19 H17 Z M4 5 L10.5 12 L4 19 Z M10.5 5 L17 12 L10.5 19 Z";
    public static final String FULLSCREEN = "M4 9 V4 H9 M15 4 H20 V9 M20 15 V20 H15 M9 20 H4 V15";
    public static final String TEXT = "M5 6 V4 H19 V6 M12 4 V20 M9 20 H15";
    public static final String SNAPSHOT = "M3 7 H7 L9 4.5 H15 L17 7 H21 V19 H3 Z M12 9.5 A3.5 3.5 0 1 0 12.01 9.5 Z";

    public static Node of(String path, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        boolean stroke = path.contains("Q") || !path.trim().endsWith("Z") || path.equals(MIXER) || path.equals(GRID) || path.equals(FILM) || path.equals(MONITOR) || path.equals(SETTINGS) || path.equals(CLIP_IT);
        p.getStyleClass().add("icon");
        p.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
        if (stroke) p.getStyleClass().add("icon-stroke");
        double scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        javafx.scene.Group g = new javafx.scene.Group(p);
        javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(g);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
