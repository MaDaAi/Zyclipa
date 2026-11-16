package io.zyclipa.clips;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.zyclipa.core.Log;
import io.zyclipa.recording.Ffmpeg;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/** Reads duration, size and track info with ffprobe. */
final class MediaProbe {
    private static final Logger LOG = Log.get(MediaProbe.class);
    static final int VERSION = 2;
    private MediaProbe() {}

    static void fill(Ffmpeg ff, Path file, Clip c) {
        if (ff.ffprobe().isEmpty()) return;
        try {
            Ffmpeg.Result r = ff.probe(List.of("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()), 30);
            if (!r.ok()) return;
            String out = r.output();
            int brace = out.indexOf('{');
            if (brace < 0) return;
            JsonObject o = JsonParser.parseString(out.substring(brace)).getAsJsonObject();
            JsonObject fmt = o.getAsJsonObject("format");
            if (fmt != null && fmt.has("duration")) c.durationSec = fmt.get("duration").getAsDouble();
            JsonArray streams = o.getAsJsonArray("streams");
            int audio = 0;
            java.util.List<String> names = new java.util.ArrayList<>();
            if (streams != null) for (JsonElement e : streams) {
                JsonObject s = e.getAsJsonObject();
                String type = s.has("codec_type") ? s.get("codec_type").getAsString() : "";
                if ("video".equals(type) && c.width == 0) {
                    c.width = s.has("width") ? s.get("width").getAsInt() : 0;
                    c.height = s.has("height") ? s.get("height").getAsInt() : 0;
                    c.videoCodec = s.has("codec_name") ? s.get("codec_name").getAsString() : "";
                    if (s.has("avg_frame_rate")) {
                        String[] fr = s.get("avg_frame_rate").getAsString().split("/");
                        try { double d = fr.length == 2 ? Double.parseDouble(fr[0]) / Math.max(1, Double.parseDouble(fr[1])) : Double.parseDouble(fr[0]); c.fps = Math.round(d); }
                        catch (NumberFormatException ignored) { }
                    }
                } else if ("audio".equals(type)) {
                    audio++;
                    JsonObject tags = s.has("tags") ? s.getAsJsonObject("tags") : null;
                    String title = tags == null ? "" : tags.has("title") ? tags.get("title").getAsString()
                            : tags.has("name") ? tags.get("name").getAsString() : ""; // MP4 keeps track titles as "name"
                    names.add(title.isBlank() ? "Audio track " + audio : title);
                }
            }
            c.audioTracks = audio;
            c.audioTrackNames = names;
            c.probeVersion = VERSION;
        } catch (Exception e) {
            LOG.fine("probe failed for " + file + ": " + e);
        }
    }
}
