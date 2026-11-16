package io.zyclipa.clips;

import com.google.gson.reflect.TypeToken;
import io.zyclipa.core.EventBus;
import io.zyclipa.core.Json;
import io.zyclipa.core.Log;
import io.zyclipa.recording.Ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The clip library: an index (library.json in the data folder) over the files in the clips
 * folder. Files are the truth; the index only adds metadata and is rebuilt from disk on scan.
 */
public final class ClipLibrary {
    private static final Logger LOG = Log.get(ClipLibrary.class);
    private static final Set<String> VIDEO = Set.of("mp4", "mkv", "mov", "webm");
    private static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg");
    private static final Pattern BAD_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final Supplier<Path> root;
    private final Path indexFile;
    private final Path thumbs;
    private final Supplier<Optional<Ffmpeg>> ffmpeg;
    private final EventBus bus;
    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-library");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public ClipLibrary(Supplier<Path> root, Path indexFile, Path thumbs, Supplier<Optional<Ffmpeg>> ffmpeg, EventBus bus) {
        this.root = root;
        this.indexFile = indexFile;
        this.thumbs = thumbs;
        this.ffmpeg = ffmpeg;
        this.bus = bus;
        loadIndex();
    }

    public Path root() { return root.get(); }

    private void loadIndex() {
        if (!Files.exists(indexFile)) return;
        try {
            List<Clip> list = Json.GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8),
                    new TypeToken<List<Clip>>() { }.getType());
            if (list != null) for (Clip c : list) if (c != null && c.id != null && c.relPath != null) clips.put(c.id, c);
        } catch (Exception e) {
            LOG.warning("Clip index unreadable, rebuilding: " + e);
        }
    }

    private synchronized void saveIndex() {
        try { Json.writeAtomic(indexFile, new ArrayList<>(clips.values())); } catch (IOException e) { LOG.warning("Saving clip index: " + e); }
    }

    public synchronized List<Clip> all() {
        List<Clip> l = new ArrayList<>(clips.values());
        l.sort(Comparator.comparingLong((Clip c) -> c.createdAt).reversed());
        return l;
    }

    public synchronized Optional<Clip> byId(String id) { return Optional.ofNullable(clips.get(id)); }

    public Path file(Clip c) { return c.resolve(root()); }

    public Path thumbnail(Clip c) { return thumbs.resolve(c.id + ".jpg"); }

    /** Rescans the clips folder (two levels: root and its sub-folders). Runs in the background. */
    public void scanAsync() { worker.submit(this::scan); }

    public void scan() {
        Path r = root();
        try { Files.createDirectories(r); } catch (IOException e) { LOG.warning("Clips folder: " + e); return; }
        Map<String, Clip> found = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(r, 2)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String ext = ext(p);
                if (!VIDEO.contains(ext) && !IMAGE.contains(ext)) continue;
                String rel = r.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".")) continue;
                String id = idFor(rel);
                Clip c;
                synchronized (this) { c = clips.get(id); }
                if (c == null) {
                    c = new Clip();
                    c.id = id;
                    c.relPath = rel;
                    c.title = stripExt(p.getFileName().toString());
                    c.kind = IMAGE.contains(ext) ? "screenshot" : "imported";
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    c.createdAt = a.creationTime().toMillis();
                }
                c.sizeBytes = Files.size(p);
                found.put(id, c);
            }
        } catch (IOException e) {
            LOG.warning("Scanning clips: " + e);
        }
        synchronized (this) {
            clips.clear();
            clips.putAll(found);
        }
        for (Clip c : found.values()) enrich(c, false);
        saveIndex();
        bus.emit(EventBus.CLIPS_CHANGED, null);
    }

    /** Registers a freshly written file (recording, replay, screenshot, trim). */
    public Clip add(Path file, String kind, String source) {
        String rel = root().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
        Clip c = new Clip();
        c.id = idFor(rel);
        c.relPath = rel;
        c.title = stripExt(file.getFileName().toString());
        c.kind = kind;
        c.source = source == null || source.isBlank() ? "Desktop" : source;
        c.createdAt = System.currentTimeMillis();
        try { c.sizeBytes = Files.size(file); } catch (IOException ignored) { }
        synchronized (this) { clips.put(c.id, c); }
        enrich(c, true);
        saveIndex();
        bus.emit(EventBus.CLIPS_CHANGED, c);
        return c;
    }

    /** Reads duration/size with ffprobe and makes a thumbnail if missing. */
    private void enrich(Clip c, boolean force) {
        Optional<Ffmpeg> ff = ffmpeg.get();
        Path f = file(c);
        if (ff.isEmpty() || !Files.exists(f)) return;
        if (c.isVideo() && (force || c.durationSec <= 0 || c.probeVersion < MediaProbe.VERSION)) MediaProbe.fill(ff.get(), f, c);
        if (!c.isVideo() && c.width == 0) MediaProbe.fill(ff.get(), f, c);
        Path t = thumbnail(c);
        if (force || !Files.exists(t)) Thumbnails.make(ff.get(), f, t, c.isVideo() ? Math.min(1.0, c.durationSec / 3) : 0);
    }

    public Clip rename(Clip c, String newTitle) throws IOException {
        String name = newTitle == null ? "" : newTitle.trim();
        if (name.isEmpty() || name.length() > 120 || BAD_NAME.matcher(name).find() || name.startsWith("."))
            throw new IOException("That name can't be used. Avoid \\ / : * ? \" < > | and keep it under 120 characters.");
        Path src = file(c);
        Path dst = src.resolveSibling(name + "." + ext(src));
        if (Files.exists(dst)) throw new IOException("A clip called \"" + name + "\" already exists here.");
        Files.move(src, dst);
        Path oldThumb = thumbnail(c);
        synchronized (this) {
            clips.remove(c.id);
            c.relPath = root().relativize(dst).toString().replace('\\', '/');
            c.id = idFor(c.relPath);
            c.title = name;
            clips.put(c.id, c);
        }
        if (Files.exists(oldThumb)) Files.move(oldThumb, thumbnail(c), StandardCopyOption.REPLACE_EXISTING);
        saveIndex();
        bus.emit(EventBus.CLIPS_CHANGED, c);
        return c;
    }

    /** Sends the file to the recycle bin when the OS supports it, otherwise deletes it. */
    public void delete(Clip c) throws IOException {
        Path f = file(c);
        boolean trashed = false;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH))
                trashed = java.awt.Desktop.getDesktop().moveToTrash(f.toFile());
        } catch (Exception ignored) { }
        if (!trashed) Files.deleteIfExists(f);
        Files.deleteIfExists(thumbnail(c));
        synchronized (this) { clips.remove(c.id); }
        saveIndex();
        bus.emit(EventBus.CLIPS_CHANGED, null);
    }

    public void export(Clip c, Path target) throws IOException {
        Path src = file(c);
        if (target.toAbsolutePath().normalize().equals(src.toAbsolutePath().normalize())) return;
        long free = Files.getFileStore(target.toAbsolutePath().getParent()).getUsableSpace();
        if (free < Files.size(src)) throw new IOException("Not enough free space to export this clip.");
        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void setFavorite(Clip c, boolean fav) {
        c.favorite = fav;
        saveIndex();
        bus.emit(EventBus.CLIPS_CHANGED, c);
    }

    public void moveToFolder(Clip c, String folder) throws IOException {
        String f = folder == null ? "" : folder.trim();
        if (BAD_NAME.matcher(f).find() || f.startsWith(".")) throw new IOException("Folder names can't contain \\ / : * ? \" < > |");
        Path dir = f.isEmpty() ? root() : root().resolve(f);
        Files.createDirectories(dir);
        Path src = file(c);
        Path dst = dir.resolve(src.getFileName());
        if (Files.exists(dst)) throw new IOException("A file with that name is already in " + (f.isEmpty() ? "the main folder" : f));
        Files.move(src, dst);
        Path oldThumb = thumbnail(c);
        synchronized (this) {
            clips.remove(c.id);
            c.relPath = root().relativize(dst).toString().replace('\\', '/');
            c.id = idFor(c.relPath);
            clips.put(c.id, c);
        }
        if (Files.exists(oldThumb)) Files.move(oldThumb, thumbnail(c), StandardCopyOption.REPLACE_EXISTING);
        saveIndex();
        bus.emit(EventBus.CLIPS_CHANGED, c);
    }

    /** Lossless cut (stream copy, so cut points snap to keyframes). Creates a new clip. */
    /**
     * Saves an edited copy: trimmed to [start, end], optionally cropped to vertical 9:16, with each
     * audio track's volume applied (0 = remove the track). With no crop and all volumes at 1 the
     * cut is a lossless stream copy; otherwise video/audio are re-encoded with {@code encoder}.
     */
    public Clip exportEdit(Clip c, double startSec, double endSec, boolean vertical, double[] trackVolumes, String encoder)
            throws IOException, InterruptedException {
        return exportEdit(c, startSec, endSec, vertical, trackVolumes, List.of(), encoder);
    }

    /** A caption burned into the video. Times are in seconds of the original clip. */
    public record TextOverlay(String text, double start, double end, String position, int size) { }

    public Clip exportEdit(Clip c, double startSec, double endSec, boolean vertical, double[] trackVolumes, List<TextOverlay> texts, String encoder)
            throws IOException, InterruptedException {
        boolean volumesChanged = false;
        if (trackVolumes != null) for (double v : trackVolumes) volumesChanged |= Math.abs(v - 1) > 1e-3;
        boolean hasText = texts != null && !texts.isEmpty();
        if (!vertical && !volumesChanged && !hasText) return trim(c, startSec, endSec);
        Ffmpeg ff = ffmpeg.get().orElseThrow(() -> new IOException("Saving edits needs FFmpeg."));
        if (!(startSec >= 0) || !(endSec > startSec)) throw new IOException("The end must be after the start.");
        Path src = file(c);
        String base = stripExt(src.getFileName().toString()) + (vertical ? "_vertical" : "_edit");
        Path dst = src.resolveSibling(base + "." + ext(src));
        for (int i = 2; Files.exists(dst); i++) dst = src.resolveSibling(base + i + "." + ext(src));
        List<String> a = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-ss", fmt(startSec), "-to", fmt(endSec), "-i", src.toString()));
        StringBuilder fc = new StringBuilder();
        List<String> maps = new ArrayList<>();
        boolean reencode = vertical || hasText;
        if (reencode) {
            StringBuilder v = new StringBuilder("[0:v]");
            if (vertical) v.append("crop=trunc(ih*9/16/2)*2:ih,");
            if (hasText) {
                Path font = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts", "arialbd.ttf");
                int n = 0;
                for (TextOverlay t : texts) {
                    // The text goes through a file so no characters need escaping in the filter.
                    Path tf = thumbs.resolve(c.id + "_text" + (n++) + ".txt");
                    Files.createDirectories(thumbs);
                    Files.writeString(tf, t.text().replace("\r", ""), java.nio.charset.StandardCharsets.UTF_8);
                    String y = switch (t.position()) { case "Top" -> "h*0.06"; case "Middle" -> "(h-text_h)/2"; default -> "h-text_h-h*0.08"; };
                    v.append("drawtext=fontfile='").append(ffPath(font)).append("':textfile='").append(ffPath(tf)).append("':fontsize=h*")
                            .append(fmt(Math.max(2, Math.min(15, t.size())) / 100.0)).append(":fontcolor=white:borderw=3:bordercolor=black@0.7:x=(w-text_w)/2:y=")
                            .append(y).append(":enable='between(t,").append(fmt(Math.max(0, t.start() - startSec))).append(",")
                            .append(fmt(Math.max(0, t.end() - startSec))).append(")',");
                }
            }
            v.append("format=yuv420p[v];");
            fc.append(v);
            maps.addAll(List.of("-map", "[v]"));
        } else maps.addAll(List.of("-map", "0:v"));
        int tracks = trackVolumes == null ? c.audioTracks : Math.min(c.audioTracks, trackVolumes.length);
        int outIdx = 0;
        for (int i = 0; i < tracks; i++) {
            double v = trackVolumes == null ? 1 : trackVolumes[i];
            if (v <= 0.001) continue; // track removed
            fc.append("[0:a:").append(i).append("]volume=").append(fmt(v)).append("[a").append(i).append("];");
            maps.addAll(List.of("-map", "[a" + i + "]"));
            if (i < c.audioTrackNames.size()) maps.addAll(List.of("-metadata:s:a:" + outIdx, "title=" + c.audioTrackNames.get(i)));
            outIdx++;
        }
        if (fc.length() > 0) { fc.setLength(fc.length() - 1); a.addAll(List.of("-filter_complex", fc.toString())); }
        a.addAll(maps);
        a.addAll(reencode ? List.of("-c:v", encoder == null ? "libx264" : encoder, "-b:v", "12000k") : List.of("-c:v", "copy"));
        if (outIdx > 0) a.addAll(List.of("-c:a", "aac", "-b:a", "160k"));
        if (dst.toString().toLowerCase(Locale.ROOT).endsWith(".mp4")) a.addAll(List.of("-movflags", "+faststart"));
        a.add(dst.toString());
        Ffmpeg.Result r = ff.run(a, 900);
        if (!r.ok() || !Files.exists(dst)) throw new IOException("Saving the edit failed: " + r.output());
        Clip n = add(dst, "trim", c.source);
        n.description = c.description;
        saveIndex();
        return n;
    }

    /** "C:\x\y.txt" -> "C\:/x/y.txt", the form FFmpeg filter options need. */
    static String ffPath(Path p) {
        return p.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:").replace("'", "");
    }

    /** Loudest point of one audio track in dB (−91 = silence), or null when unknown. */
    public Double trackPeakDb(Clip c, int track) {
        Optional<Ffmpeg> ff = ffmpeg.get();
        if (ff.isEmpty() || track >= c.audioTracks) return null;
        try {
            Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-i", file(c).toString(), "-map", "0:a:" + track, "-af", "volumedetect", "-f", "null", "-"), 120);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("max_volume: (-?[0-9.]+) dB").matcher(r.output());
            return m.find() ? Double.parseDouble(m.group(1)) : null;
        } catch (Exception e) { return null; }
    }

    /** Thumbnails along the clip for the editor's video lane (cached). */
    public Optional<Path> filmstrip(Clip c, int frames) {
        Optional<Ffmpeg> ff = ffmpeg.get();
        if (ff.isEmpty() || c.durationSec <= 0) return Optional.empty();
        Path out = thumbs.resolve(c.id + "_strip" + frames + ".jpg");
        if (Files.exists(out)) return Optional.of(out);
        try {
            Files.createDirectories(thumbs);
            Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", file(c).toString(),
                    "-vf", "fps=" + fmt(frames / Math.max(0.5, c.durationSec)) + ",scale=160:90,tile=" + frames + "x1",
                    "-frames:v", "1", "-q:v", "5", out.toString()), 120);
            return r.ok() && Files.exists(out) ? Optional.of(out) : Optional.empty();
        } catch (Exception e) { return Optional.empty(); }
    }

    /** Saves the video frame at {@code atSec} as a PNG screenshot next to the other screenshots. */
    public Clip grabFrame(Clip c, double atSec) throws IOException, InterruptedException {
        Ffmpeg ff = ffmpeg.get().orElseThrow(() -> new IOException("Saving a frame needs FFmpeg."));
        Path dir = root().resolve("Screenshots");
        Files.createDirectories(dir);
        Path dst = dir.resolve(stripExt(file(c).getFileName().toString()) + "_frame_" + Math.round(atSec * 10) / 10.0 + ".png");
        Ffmpeg.Result r = ff.run(List.of("-hide_banner", "-loglevel", "error", "-y", "-ss", fmt(Math.max(0, atSec)), "-i", file(c).toString(),
                "-frames:v", "1", dst.toString()), 60);
        if (!r.ok() || !Files.exists(dst)) throw new IOException("Couldn't save the frame: " + r.output());
        return add(dst, "screenshot", c.source);
    }

    /** Waveform picture of one audio track (cached), for the editor's timeline. */
    public Optional<Path> waveform(Clip c, int track, String hexColor) {
        Optional<Ffmpeg> ff = ffmpeg.get();
        if (ff.isEmpty() || track >= c.audioTracks) return Optional.empty();
        Path out = thumbs.resolve(c.id + "_wave" + track + ".png");
        if (Files.exists(out)) return Optional.of(out);
        try {
            Files.createDirectories(thumbs);
            Ffmpeg.Result r = ff.get().run(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", file(c).toString(),
                    "-filter_complex", "[0:a:" + track + "]aformat=channel_layouts=mono,showwavespic=s=1600x90:colors=0x" + hexColor.replace("#", "") + "[w]",
                    "-map", "[w]", "-frames:v", "1", out.toString()), 60);
            return r.ok() && Files.exists(out) ? Optional.of(out) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Files the built-in player can handle. Its decoder can't play AAC audio on every Windows
     * setup, so clips with sound are previewed as a silent copy of the picture (stream copy, no
     * quality loss) plus an MP3 mix of all audio tracks, which the editor plays in sync.
     * Returns {video, audio-or-null}. Cached until the clip file changes.
     */
    /**
     * Files the editor plays: [0] a silent copy of the picture, then one MP3 per sound track (game, chat,
     * mic, soundboard…), so each track's mute and volume can be heard while editing. Cached until the clip changes.
     */
    public Path[] previewFiles(Clip c) throws IOException, InterruptedException {
        Path src = file(c);
        if (c.audioTracks <= 0) return new Path[]{src};
        Ffmpeg ff = ffmpeg.get().orElseThrow(() -> new IOException("Previewing clips with sound needs FFmpeg."));
        Files.createDirectories(thumbs);
        Path[] out = new Path[1 + c.audioTracks];
        out[0] = thumbs.resolve(c.id + "_pv.mp4");
        for (int i = 0; i < c.audioTracks; i++) out[i + 1] = thumbs.resolve(c.id + "_pa" + i + ".mp3");
        boolean fresh = true;
        for (Path p : out) fresh &= Files.exists(p) && Files.size(p) > 0 && Files.getLastModifiedTime(p).compareTo(Files.getLastModifiedTime(src)) >= 0;
        if (!fresh) {
            Ffmpeg.Result r1 = ff.run(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", src.toString(), "-map", "0:v:0", "-c", "copy", "-an",
                    "-movflags", "+faststart", out[0].toString()), 300);
            List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", src.toString()));
            for (int i = 0; i < c.audioTracks; i++)
                args.addAll(List.of("-map", "0:a:" + i, "-c:a", "libmp3lame", "-b:a", "160k", "-ar", "44100", out[i + 1].toString()));
            Ffmpeg.Result r2 = ff.run(args, 300);
            if (!r1.ok() || !r2.ok()) throw new IOException("Couldn't prepare the preview: " + r1.output() + r2.output());
        }
        return out;
    }

    public void setDescription(Clip c, String text) {
        c.description = text == null ? "" : text.length() > 2000 ? text.substring(0, 2000) : text;
        saveIndex();
    }

    public Clip trim(Clip c, double startSec, double endSec) throws IOException, InterruptedException {
        Ffmpeg ff = ffmpeg.get().orElseThrow(() -> new IOException("Trimming needs FFmpeg."));
        if (!(startSec >= 0) || !(endSec > startSec)) throw new IOException("The end must be after the start.");
        Path src = file(c);
        String base = stripExt(src.getFileName().toString()) + "_trim";
        Path dst = src.resolveSibling(base + "." + ext(src));
        for (int i = 2; Files.exists(dst); i++) dst = src.resolveSibling(base + i + "." + ext(src));
        Ffmpeg.Result r = ff.run(List.of("-hide_banner", "-loglevel", "error", "-y", "-ss", fmt(startSec), "-to", fmt(endSec),
                "-i", src.toString(), "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero", dst.toString()), 300);
        if (!r.ok() || !Files.exists(dst)) throw new IOException("Trim failed: " + r.output());
        Clip n = add(dst, "trim", c.source);
        return n;
    }

    public synchronized long totalBytes() { return clips.values().stream().mapToLong(c -> c.sizeBytes).sum(); }

    public synchronized List<String> folders() {
        return clips.values().stream().map(Clip::folder).distinct().sorted().toList();
    }

    public void persist() { saveIndex(); }

    static String fmt(double s) { return String.format(Locale.ROOT, "%.3f", s); }

    static String idFor(String rel) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(rel.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 10);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i <= 0 ? n : n.substring(0, i);
    }

    public void close() { worker.shutdownNow(); }
}
