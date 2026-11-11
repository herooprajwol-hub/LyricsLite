package dev.lyricslite;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Spotify "currently playing" sync using OAuth PKCE (no client secret).
 * Login redirects to http://127.0.0.1:8789/callback, which this mod listens on
 * for a moment. On Android the game and the browser run on the same phone, so it works.
 */
public final class Spotify {
    private static final String REDIRECT = "http://127.0.0.1:8789/callback";
    private static final String SCOPE = "user-read-currently-playing user-read-playback-state";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lyricslite.json");
    private static final SecureRandom RNG = new SecureRandom();

    private static String clientId = "";
    private static String refreshToken = "";
    private static String accessToken = "";
    private static long accessExpiryMs;
    private static String verifier = "";
    private static String state = "";
    private static volatile boolean enabled;
    private static volatile long backoffUntil;
    private static ScheduledExecutorService pool;

    private Spotify() {}

    // ---------- config ----------
    public static void load() {
        try {
            if (Files.exists(FILE)) {
                JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                if (o.has("clientId")) clientId = o.get("clientId").getAsString();
                if (o.has("refreshToken")) refreshToken = o.get("refreshToken").getAsString();
            }
        } catch (Exception ignored) {}
        if (!clientId.isEmpty() && !refreshToken.isEmpty()) setEnabled(true);
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("clientId", clientId);
            o.addProperty("refreshToken", refreshToken);
            Files.writeString(FILE, new Gson().toJson(o));
        } catch (Exception ignored) {}
    }

    public static void setClientId(String id) {
        clientId = id.trim();
        save();
    }

    public static boolean hasClientId() { return !clientId.isEmpty(); }
    public static boolean isEnabled() { return enabled; }

    public static synchronized void setEnabled(boolean on) {
        enabled = on;
        if (on && pool == null) {
            pool = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lyricslite-spotify");
                t.setDaemon(true);
                return t;
            });
            pool.scheduleWithFixedDelay(Spotify::poll, 1, 2, TimeUnit.SECONDS);
        }
    }

    // ---------- login ----------
    /** Returns the URL the user must open, and starts listening for the redirect. */
    public static String startLogin() {
        verifier = randomUrlSafe(64);
        state = randomUrlSafe(16);
        String url = "https://accounts.spotify.com/authorize"
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(REDIRECT)
                + "&scope=" + enc(SCOPE)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + challenge(verifier)
                + "&state=" + enc(state);
        Thread t = new Thread(Spotify::listenForCallback, "lyricslite-oauth");
        t.setDaemon(true);
        t.start();
        return url;
    }

    private static void listenForCallback() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8789));
            ss.setSoTimeout(180_000);
            while (true) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String line = br.readLine();
                    if (line == null) continue;
                    String[] parts = line.split(" ");
                    String target = parts.length > 1 ? parts[1] : "";
                    if (!target.startsWith("/callback")) {
                        reply(s, 404, "Not found");
                        continue;
                    }
                    Map<String, String> q = query(target);
                    boolean ok = state.equals(q.get("state")) && q.containsKey("code");
                    reply(s, 200, ok ? "Logged in to Spotify. You can go back to Minecraft."
                                     : "Login failed or was cancelled. Go back to Minecraft and try again.");
                    if (ok) finishLogin(q.get("code"));
                    else LyricsLiteClient.say("Spotify login failed: " + q.getOrDefault("error", "bad response"));
                    return;
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            LyricsLiteClient.say("Spotify login timed out. Run /lyrics spotify login again.");
        } catch (Exception e) {
            LyricsLiteClient.say("Spotify login error: " + e.getMessage());
        }
    }

    private static void finishLogin(String code) {
        try {
            Map<String, String> f = new HashMap<>();
            f.put("grant_type", "authorization_code");
            f.put("code", code);
            f.put("redirect_uri", REDIRECT);
            f.put("client_id", clientId);
            f.put("code_verifier", verifier);
            applyToken(tokenRequest(f));
            save();
            setEnabled(true);
            LyricsLiteClient.say("Spotify connected. Play a song in the Spotify app.");
        } catch (Exception e) {
            LyricsLiteClient.say("Spotify token error: " + e.getMessage());
        }
    }

    // ---------- tokens ----------
    private static JsonObject tokenRequest(Map<String, String> form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form))).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + " " + r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static void applyToken(JsonObject o) {
        accessToken = o.get("access_token").getAsString();
        accessExpiryMs = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L;
        if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) refreshToken = o.get("refresh_token").getAsString();
    }

    private static boolean ensureToken() {
        if (!accessToken.isEmpty() && System.currentTimeMillis() < accessExpiryMs - 30_000) return true;
        if (refreshToken.isEmpty() || clientId.isEmpty()) return false;
        try {
            Map<String, String> f = new HashMap<>();
            f.put("grant_type", "refresh_token");
            f.put("refresh_token", refreshToken);
            f.put("client_id", clientId);
            applyToken(tokenRequest(f));
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- polling ----------
    private static void poll() {
        if (!enabled || System.currentTimeMillis() < backoffUntil) return;
        try {
            if (!ensureToken()) {
                enabled = false;
                LyricsLiteClient.say("Spotify session expired. Run /lyrics spotify login");
                return;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + accessToken).GET().build();
            long t0 = System.nanoTime();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            long halfRttMs = (System.nanoTime() - t0) / 2_000_000L;
            int code = r.statusCode();
            if (code == 429) {
                long secs = r.headers().firstValue("Retry-After").map(Long::parseLong).orElse(5L);
                backoffUntil = System.currentTimeMillis() + secs * 1000L;
                return;
            }
            if (code == 401) { accessToken = ""; return; }
            if (code != 200) { LyricsLiteClient.spotifyIdle(); return; } // 204 = nothing playing
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (!o.has("item") || o.get("item").isJsonNull()) { LyricsLiteClient.spotifyIdle(); return; }
            JsonObject item = o.getAsJsonObject("item");
            String title = item.get("name").getAsString();
            String artist = item.has("artists") && item.getAsJsonArray("artists").size() > 0
                    ? item.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString() : "";
            boolean playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
            long progress = o.get("progress_ms").getAsLong() + (playing ? halfRttMs : 0);
            LyricsLiteClient.onSpotify(artist + "|" + title, title, artist, progress, playing);
        } catch (Exception ignored) {
            // transient network error; try again next tick
        }
    }

    // ---------- helpers ----------
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String formEncode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static Map<String, String> query(String target) {
        Map<String, String> out = new HashMap<>();
        int i = target.indexOf('?');
        if (i < 0) return out;
        for (String kv : target.substring(i + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) out.put(URLDecoder.decode(kv.substring(0, e), StandardCharsets.UTF_8),
                               URLDecoder.decode(kv.substring(e + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void reply(Socket s, int code, String msg) throws Exception {
        String body = "<html><head><meta name=viewport content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;padding:24px'><h3>LyricsLite</h3><p>" + msg + "</p></body></html>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        s.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().write(b);
        s.getOutputStream().flush();
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String challenge(String v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
