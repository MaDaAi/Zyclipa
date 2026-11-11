package io.zyclipra.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Tiny publish/subscribe hub so modules talk without knowing each other. */
public final class EventBus {
    private static final Logger LOG = Log.get(EventBus.class);
    private final Map<String, List<Consumer<Object>>> subs = new ConcurrentHashMap<>();

    public void on(String topic, Consumer<Object> c) {
        subs.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(c);
    }

    public void emit(String topic, Object payload) {
        for (Consumer<Object> c : subs.getOrDefault(topic, List.of())) {
            try { c.accept(payload); } catch (RuntimeException e) { LOG.warning(topic + " listener failed: " + e); }
        }
    }

    public static final String RECORDING_CHANGED = "recording.changed";
    public static final String REPLAY_CHANGED = "replay.changed";
    public static final String CLIPS_CHANGED = "clips.changed";
    public static final String SOUNDS_CHANGED = "sounds.changed";
    public static final String NOTIFY = "notify";
    public static final String ERROR = "error";
}
