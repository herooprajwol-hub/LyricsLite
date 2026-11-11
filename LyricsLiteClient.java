package dev.lyricslite;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * LyricsLite: synced lyrics on your HUD, with optional Spotify auto-sync.
 *
 * Spotify:
 *  /lyrics spotify setup <clientId>   save your Spotify app's Client ID (once)
 *  /lyrics spotify login              connect your account
 *  /lyrics spotify on | off
 * Manual:
 *  /lyrics play <artist - title>, start, seek <s>, offset <ms>, pause, resume, stop
 */
public class LyricsLiteClient implements ClientModInitializer {
    private static volatile Lrc.Result current;
    private static volatile long baseMs;
    private static volatile long baseNano;
    private static volatile boolean playing;
    private static volatile long offsetMs;
    private static volatile String spotifyKey = "";

    private static long position() {
        return playing ? baseMs + (System.nanoTime() - baseNano) / 1_000_000L : baseMs;
    }

    private static void seek(long ms) {
        baseMs = Math.max(0, ms);
        baseNano = System.nanoTime();
    }

    static void say(String s) {
        sayText(Text.literal("[Lyrics] " + s));
    }

    static void sayText(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(t, false);
        });
    }

    /** Called from the Spotify poller roughly every 2 seconds. */
    static void onSpotify(String key, String title, String artist, long progressMs, boolean isPlaying) {
        if (!key.equals(spotifyKey)) {
            spotifyKey = key;
            current = null;
            Lrc.searchTrack(title, artist).whenComplete((res, err) -> {
                if (!key.equals(spotifyKey)) return; // song changed again meanwhile
                if (err != null) { say("Lyrics error: " + err.getMessage()); return; }
                if (res == null) { say("No synced lyrics for " + artist + " - " + title); return; }
                current = res;
            });
        }
        long cur = position();
        if (isPlaying != playing || Math.abs(cur - progressMs) > 300) {
            baseMs = progressMs;
            baseNano = System.nanoTime();
            playing = isPlaying;
        }
    }

    static void spotifyIdle() {
        if (!spotifyKey.isEmpty()) {
            spotifyKey = "";
            current = null;
            playing = false;
        }
    }

    @Override
    public void onInitializeClient() {
        Spotify.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("lyrics")
                .then(literal("spotify")
                    .then(literal("setup").then(argument("clientId", StringArgumentType.word()).executes(ctx -> {
                        Spotify.setClientId(StringArgumentType.getString(ctx, "clientId"));
                        say("Client ID saved. Now run /lyrics spotify login");
                        return 1;
                    })))
                    .then(literal("login").executes(ctx -> {
                        if (!Spotify.hasClientId()) {
                            say("First run /lyrics spotify setup <clientId> (see README).");
                            return 0;
                        }
                        String url = Spotify.startLogin();
                        sayText(Text.literal("[Lyrics] Tap to open Spotify login").styled(s ->
                                s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))).formatted(Formatting.GREEN, Formatting.UNDERLINE));
                        sayText(Text.literal("[Lyrics] If that doesn't open, tap here to copy the link, then paste it in your phone browser").styled(s ->
                                s.withClickEvent(new ClickEvent.CopyToClipboard(url))).formatted(Formatting.YELLOW, Formatting.UNDERLINE));
                        return 1;
                    }))
                    .then(literal("on").executes(ctx -> { Spotify.setEnabled(true); say("Spotify sync on"); return 1; }))
                    .then(literal("off").executes(ctx -> {
                        Spotify.setEnabled(false); spotifyKey = ""; current = null; say("Spotify sync off"); return 1;
                    })))
                .then(literal("play").then(argument("song", StringArgumentType.greedyString()).executes(ctx -> {
                    String q = StringArgumentType.getString(ctx, "song");
                    Spotify.setEnabled(false);
                    spotifyKey = "";
                    say("Searching: " + q);
                    Lrc.search(q).whenComplete((res, err) -> {
                        if (err != null) { say("Error: " + err.getMessage()); return; }
                        if (res == null) { say("No synced lyrics found. Try \"artist - title\"."); return; }
                        current = res;
                        seek(0);
                        playing = true;
                        say("Playing " + res.artist() + " - " + res.title());
                    });
                    return 1;
                })))
                .then(literal("start").executes(ctx -> { seek(0); playing = true; return 1; }))
                .then(literal("seek").then(argument("seconds", IntegerArgumentType.integer(0)).executes(ctx -> {
                    seek(IntegerArgumentType.getInteger(ctx, "seconds") * 1000L);
                    return 1;
                })))
                .then(literal("offset").then(argument("ms", IntegerArgumentType.integer(-20000, 20000)).executes(ctx -> {
                    offsetMs = IntegerArgumentType.getInteger(ctx, "ms");
                    return 1;
                })))
                .then(literal("pause").executes(ctx -> { baseMs = position(); playing = false; return 1; }))
                .then(literal("resume").executes(ctx -> { baseNano = System.nanoTime(); playing = true; return 1; }))
                .then(literal("stop").executes(ctx -> { current = null; playing = false; return 1; }))
            ));

        HudElementRegistry.attachElementAfter(
            VanillaHudElements.HOTBAR,
            Identifier.of("lyricslite", "lyrics"),
            LyricsLiteClient::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        Lrc.Result r = current;
        if (r == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;

        long pos = position() + offsetMs;
        List<Lrc.Line> lines = r.lines();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() <= pos) idx = i; else break;
        }

        int cx = ctx.getScaledWindowWidth() / 2;
        int y = ctx.getScaledWindowHeight() - 90;
        draw(ctx, mc, lines, idx - 1, cx, y - 12, 0xFF9A9A9A);
        draw(ctx, mc, lines, idx, cx, y, 0xFFFFFFFF);
        draw(ctx, mc, lines, idx + 1, cx, y + 12, 0xFF9A9A9A);
    }

    private static void draw(DrawContext ctx, MinecraftClient mc, List<Lrc.Line> lines, int i, int cx, int y, int color) {
        if (i < 0 || i >= lines.size()) return;
        String t = lines.get(i).text();
        if (t.isEmpty()) t = "\u266A";
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(t), cx, y, color);
    }
}
