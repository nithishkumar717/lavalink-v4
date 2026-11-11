# YUNA Lavalink node

This builds a customized Lavalink server from the official source and adds
YUNA's recommendation logic as an authenticated Lavalink API endpoint. Lavalink
handles audio loading, decoding, voice transmission, and ranking. YUNA's bot
supplies candidates and listening context, then plays the selected result.

## YUNA ranking endpoint

The server implements the active logic in YUNA V7's `core/ml/ranking_model.py`,
`core/ml/feature_extractor.py`, `core/ml/embedding_engine.py`, and
`core/ml/contextual_bandit.py`: engineered feature scoring, language gating,
recent-track similarity, artist fatigue, duplicate filtering, and exploration.
No trained `yuna_ranker.booster` file was present in the inspected YUNA checkout,
so this port implements the weighted fallback scoring path, which is the
available ranker behavior in that checkout.

The intended flow is:

1. YUNA's direct YouTube guest search resolves candidate metadata.
2. YUNA posts candidates and session signals to `POST /v4/yuna/rank` on this
   Lavalink node. The normal Lavalink password is required in `Authorization`.
3. Lavalink returns the sorted candidates and a contextual-bandit selection.
4. The current YUNA autoplay code uses the selected candidate in its existing
   direct playback path. The ranking integration is in place; switching
   `utils/audio/playback_manager.py` and the Discord voice lifecycle to
   Lavalink's player API is a separate playback migration.

Example request:

```json
{
  "candidates": [
    {
      "yt_video_id": "dQw4w9WgXcQ",
      "title": "Example Official Audio",
      "uploader": "Example Music - Topic",
      "duration": 212,
      "view_count": 1000000,
      "like_count": 25000,
      "language": "ta",
      "language_confirmed": true,
      "mood": "energetic",
      "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
  ],
  "session": {
    "recent_tracks": [],
    "recent_artists": [],
    "active_language": "ta",
    "language_established": true,
    "target_mood": "energetic",
    "played_track_ids": []
  },
  "exploreRate": 0.1,
  "temperature": 12.0
}
```

The response contains `ranked` and `selected`. Candidate metadata uses YUNA's
existing snake_case field names where applicable. Requests are limited to 200
candidates.

## Deploy on the YUNA VPS

1. Install Docker Engine and Docker Compose on Ubuntu.
2. Point a DNS name such as `lavalink.example.com` to the VPS public IP and allow
   inbound TCP ports 80 and 443 in the VPS firewall.
3. Copy `.env.example` to `.env`; set `LAVALINK_DOMAIN` to your DNS name and
   replace the password with a unique random secret.
4. Create writable mount folders: `mkdir -p plugins logs && sudo chown -R 322:322 plugins logs`.
5. Build and start the customized node with `docker compose up -d --build`.
6. Check startup with `docker compose logs -f lavalink caddy`.
7. Set `LAVALINK_URI=http://127.0.0.1:2333` and
   `LAVALINK_SERVER_PASSWORD` in YUNA's environment to match this node.

Members connect using the domain, port `443`, TLS enabled, and the shared
`LAVALINK_SERVER_PASSWORD`. Caddy obtains and renews the TLS certificate. The
Lavalink port remains local to the VPS; public traffic reaches it through Caddy.
This is a shared credential with no per-member quota or revocation. Rotate the
password to revoke access for everyone who has the old value.

The container has a 2 GB memory ceiling and a 1.5 GB Java heap, leaving room
for YUNA and Ubuntu on the 3 GB VPS. The 1.5 CPU limit leaves some headroom for
the bot and system services on the 2-vCPU host.

## YouTube guest behavior

The node config includes the official YouTube source plus LavaSrc and LavaSearch
for Spotify, Apple Music, Deezer, Yandex Music, VK Music, Tidal, Qobuz, JioSaavn,
YouTube Music, and yt-dlp, as well as Lavalink's SoundCloud, Bandcamp, Twitch,
Vimeo, and Nico sources. `http` and `local` are disabled for this public node.

YouTube playback uses OAuth and the remote cipher endpoint configured in
`application.yml`. Set `YOUTUBE_OAUTH_REFRESH_TOKEN` in `.env`; keep it private.
The cipher endpoint is a public third-party service with its own availability
and rate limits. For a fully self-hosted deployment, run a private yt-cipher
service and point `plugins.youtube.remoteCipher.url` at it.

Fill the relevant `LAVASRC_*` values in `.env` before using providers that need
credentials, then set that provider's `LAVASRC_ENABLE_*` flag to `true`.
Apple Music needs an API token; Spotify needs a client ID and secret; Deezer
needs its master key and ARL; Yandex, VK, Tidal, and Qobuz each need their
tokens; JioSaavn needs its decryption key. Credential-dependent providers
default to off so a missing token does not prevent the node from starting.
Spotify and Apple Music are metadata mirrors: the configured providers resolve
them to playable YouTube or Deezer tracks instead of streaming from Spotify or
Apple Music directly.

YUNA's direct guest search remains the candidate provider for its custom rank
endpoint. YUNA's current direct audio playback path is still in use until its
separate player migration to Lavalink is made.

## Security and secrets

Do not commit `.env`. The API port only listens on loopback, the HTTP source is
disabled, and request payload logging is off. If YUNA runs in a separate
container, attach both containers to a private Docker network and use the
Lavalink service name instead of publishing the API publicly.
