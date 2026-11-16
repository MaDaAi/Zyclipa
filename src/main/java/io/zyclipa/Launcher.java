package io.zyclipa;

import io.zyclipa.app.CliOptions;
import io.zyclipa.app.ZyclipaApp;
import javafx.application.Application;

/**
 * Plain entry point. It does not extend {@link Application} so that the shaded jar
 * can start JavaFX from the class path.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        CliOptions.parse(args);
        Application.launch(ZyclipaApp.class, args);
    }
}
