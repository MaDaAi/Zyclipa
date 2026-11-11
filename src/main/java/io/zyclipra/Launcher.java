package io.zyclipra;

import io.zyclipra.app.CliOptions;
import io.zyclipra.app.ZyclipraApp;
import javafx.application.Application;

/**
 * Plain entry point. It does not extend {@link Application} so that the shaded jar
 * can start JavaFX from the class path.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        CliOptions.parse(args);
        Application.launch(ZyclipraApp.class, args);
    }
}
