package io.zyclipa.ui;

import javafx.scene.Node;

/** A main section. Timers run only while it is visible, keeping idle CPU low. */
public interface View {
    String id();
    String title();
    String icon();
    Node node();
    default void shown() { }
    default void hidden() { }
}
