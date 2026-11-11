package dev.lyricslite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches time-synced (LRC) lyrics from LRCLIB and parses them. */
public final class Lrc {
    public record Line(long timeMs, String text) {}
    public record Result(String title, String artist, List<Line> lines) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    private Lrc() {}

    /** Free-text search ("artist - title"). Completes with null if nothing has synced lyrics. */
    public static CompletableFuture<Result> search(String query) {
        return fetch("https://lrclib.net/api/search?q=" + enc(query));
    }

    /** Search by exact track + artist, falling back to a free-text search. */
    public static CompletableFuture<Result> searchTrack(String track, String artist) {
        return fetch("https://lrclib.net/api/search?track_name=" + enc(track) + "&artist_name=" + enc(artist))
                .thenCompose(res -> res != null ? CompletableFuture.completedFuture(res) : search(artist + " " + track));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static CompletableFuture<Result> fetch(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "LyricsLite/1.1 (Minecraft Fabric mod)")
                .GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            if (r.statusCode() != 200) throw new RuntimeException("LRCLIB returned HTTP " + r.statusCode());
            JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) continue;
                List<Line> lines = parse(o.get("syncedLyrics").getAsString());
                if (lines.isEmpty()) continue;
                return new Result(str(o, "trackName"), str(o, "artistName"), lines);
            }
            return null;
        });
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = TS.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find(end) && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String f = m.group(3);
                if (f != null) {
                    ms = Long.parseLong(f);
                    if (f.length() == 1) ms *= 100;
                    else if (f.length() == 2) ms *= 10;
                }
                times.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        out.sort(Comparator.comparingLong(Line::timeMs));
        return out;
    }
}
