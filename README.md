# LyricsLite 1.1 (Fabric, Minecraft 1.21.11, Android-friendly)

Synced lyrics (LRCLIB) above your hotbar. Optional Spotify auto-sync. Original mod.

## Build without a PC
1. Create a free GitHub repo and upload everything in this folder (keep the .github folder).
2. Repo > Actions > "build" (runs on upload). Download artifact "lyricslite-jar"; use lyricslite-1.1.0.jar.
3. Put it + Fabric API in your Fabric 1.21.11 profile's mods folder (Java 21).

## Spotify setup (once)
1. Open developer.spotify.com/dashboard and create an app.
2. Add this Redirect URI exactly:  http://127.0.0.1:8789/callback
3. Copy the app's Client ID.
4. In Minecraft:  /lyrics spotify setup <ClientID>
5. /lyrics spotify login  -> tap the green link (or tap the yellow one to copy it and paste in your browser), approve.
6. Play music in the Spotify app. Lyrics follow the song automatically (play/pause/seek/skip).
Next launches reconnect on their own. /lyrics spotify off to disable.

## Manual mode (no Spotify)
/lyrics play Artist - Title | start | seek <s> | offset <ms> | pause | resume | stop
Use /lyrics offset <ms> to nudge timing in Spotify mode too (negative = later).
