# YouTube Data API setup (free)

Arnav Music uses **only public data** from the YouTube Data API v3 with an API key, and the **official embedded player** for playback. No OAuth scopes are requested — there is no YouTube account access at all, which keeps it simple and private.

## 1. Google Cloud project
1. <https://console.cloud.google.com> → select the project Firebase created (or create one). No billing account is needed for the YouTube Data API.
2. **APIs & Services → Library** → search **YouTube Data API v3** → **Enable**.

## 2. Create a restricted API key
1. **APIs & Services → Credentials → Create credentials → API key**.
2. **Edit key**:
   - *Application restrictions* → **Android apps** → add package `com.arnav.music` with your SHA-1 (see [FIREBASE_SETUP.md](FIREBASE_SETUP.md#2-register-the-android-app) for the public community key's SHA-1).
     The app sends `X-Android-Package` and `X-Android-Cert` headers so this restriction works.
   - *API restrictions* → **Restrict key** → only **YouTube Data API v3**.
3. Save. Copy the key.

## 3. Give the key to the app
Pick one:
- **In the app**: Settings → Sources → paste the key → Save. It's encrypted on-device (Android Keystore) and never synced.
- **Local build**: `local.properties` → `YOUTUBE_API_KEY=...`
- **CI build**: repository secret `YOUTUBE_API_KEY`.

## 4. Quota
The default quota is **10,000 units/day** per project (check *APIs & Services → YouTube Data API v3 → Quotas*).

| Call | Cost | Used for |
|---|---|---|
| `search.list` | 100 | Search (one call for songs+artists+playlists) |
| `videos.list` | 1 | Durations, embeddability, trending chart |
| `playlistItems.list` | 1 | Opening YouTube playlists |

How Arnav Music protects it: normalised cache keys (`"Daft PUNK!!"` = `"daft punk"`), 650 ms debounce, minimum query length, in-flight de-duplication, day-level reuse of cached pages, stale-while-revalidate, conserve mode at 80 %, exponential backoff on 5xx, no retries on quota errors, and an on-screen usage dashboard (Settings → Usage & quotas). Set **Settings → Sources → Daily unit budget** to match your console quota.

## 5. OAuth consent screen
Only needed for **Google sign-in** (Firebase Auth), not for YouTube:
1. **APIs & Services → OAuth consent screen** → External → app name *Arnav Music*, support email, developer email.
2. Scopes: none beyond the defaults (`openid`, `email`, `profile`).
3. Publishing status can stay *Testing* (add your test users) or be published — no verification is required for these basic scopes.

## Policy reminders
- Keep the player visible (≥ 200×200 px) with YouTube's controls; never overlay or hide it.
- No background playback, downloads, audio extraction or ad blocking.
- Show YouTube attribution where YouTube data appears (the app does this with a badge).
- Read the [YouTube API Services Terms](https://developers.google.com/youtube/terms/api-services-terms-of-service) and [Developer Policies](https://developers.google.com/youtube/terms/developer-policies).
