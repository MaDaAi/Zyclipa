package io.zyclipra.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds virtual audio cables: a playback device whose sound comes straight back out of a
 * recording device (e.g. "CABLE Input" → "CABLE Output"). Zyclipra uses one to hand your
 * processed voice and soundboard to chat apps, and uses the same knowledge to refuse setups that
 * would feed your own output back into your microphone (an echo/feedback loop).
 */
public final class VirtualCables {
    private VirtualCables() {}

    /** {@code playback}: where Zyclipra sends sound. {@code recording}: what chat apps pick as their microphone. */
    public record Cable(String playback, String recording, boolean knownFreeDriver) {
        public String label() { return recording + "  ⟵  " + playback; }
    }

    private static final String[] KNOWN = {"vb-audio", "voicemeeter", "virtual audio driver", "virtual cable", "virtual audio cable"};

    /** Name with the direction words removed, so both ends of one cable compare equal. */
    static String base(String device) {
        String s = device.toLowerCase(Locale.ROOT);
        // Only direction words: "Speakers (Realtek)" and "Microphone (Realtek)" must stay different.
        s = s.replaceAll("\\b(input|output|in|out|playback|recording|record)\\b", " ");
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** True when sound played on {@code output} would come back on {@code input}. */
    public static boolean loops(String input, String output) {
        if (input == null || output == null || input.isBlank() || output.isBlank()) return false;
        if (input.equalsIgnoreCase(output)) return true;
        String a = base(input), b = base(output);
        return !a.isEmpty() && a.equals(b);
    }

    public static boolean isVirtual(String device) {
        String s = device.toLowerCase(Locale.ROOT);
        for (String k : KNOWN) if (s.contains(k)) return true;
        return s.startsWith("cable") || s.contains("virtual");
    }

    /** Pairs of devices on this PC that act as a cable. */
    public static List<Cable> find(List<String> outputs, List<String> inputs) {
        List<Cable> out = new ArrayList<>();
        for (String o : outputs) {
            if (!isVirtual(o)) continue;
            for (String i : inputs) {
                if (loops(i, o)) {
                    String low = (o + i).toLowerCase(Locale.ROOT);
                    boolean known = false;
                    for (String k : KNOWN) known |= low.contains(k);
                    out.add(new Cable(o, i, known));
                }
            }
        }
        return out;
    }

    public static List<Cable> find() { return find(SoundDevices.outputs(), SoundDevices.inputs()); }

    /** The cable a given playback device belongs to, if any. */
    public static Optional<Cable> forPlayback(String output) {
        return find().stream().filter(c -> c.playback().equals(output)).findFirst();
    }

    public static final String HOW_TO_INSTALL = """
            No virtual audio cable was found. To let Discord or a game hear your processed voice or your soundboard, install a free one:
            • VB-CABLE (free donationware): vb-audio.com/Cable – run the setup as administrator, then restart your PC.
            • Virtual Audio Driver (open source): github.com/VirtualDrivers/Virtual-Audio-Driver
            Installing a driver needs administrator rights; only install drivers from their official pages. Zyclipra never installs drivers itself.""";
}
