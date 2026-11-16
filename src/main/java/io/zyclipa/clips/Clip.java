package io.zyclipa.clips;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One video or screenshot in the library, plus what we know about it. */
public final class Clip {
    public String id;            // stable: SHA-1 of the path relative to the clips folder
    public String relPath;       // relative to the clips folder, '/' separated
    public String title;
    public String source = "Desktop";
    public String kind = "recording"; // recording | replay | screenshot | trim | imported
    public long createdAt;       // epoch ms
    public double durationSec;
    public int width;
    public int height;
    public long sizeBytes;
    public int audioTracks;
    public String videoCodec = "";
    public boolean favorite;
    public List<String> tags = new ArrayList<>();
    public String description = "";
    /** Names of the audio tracks, e.g. "Microphone", "Game audio" (from the recording's track titles). */
    public List<String> audioTrackNames = new ArrayList<>();
    public double fps;
    /** Bumped when probing learns new facts, so older index entries are refreshed once. */
    public int probeVersion;
    public String driveFileId = "";
    public long driveSyncedSize;

    public boolean isVideo() { return !"screenshot".equals(kind); }

    public String folder() {
        int i = relPath.lastIndexOf('/');
        return i < 0 ? "" : relPath.substring(0, i);
    }

    public String fileName() {
        return relPath.substring(relPath.lastIndexOf('/') + 1);
    }

    public Path resolve(Path root) { return root.resolve(relPath.replace('/', java.io.File.separatorChar)); }
}
