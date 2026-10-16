# Shaqati (شقتي)

A personal Android app for finding a rental apartment in Riyadh near work. It reads the
listing sites, keeps only the family apartments that meet the requirements, measures the
rush-hour drive to work, and has Claude look at the photos to separate renovated, modern
apartments from old ones.

## What it does

- **Searches five sites**: Aqar (عقار), Haraj (حراج), Property Finder, Deal (ديل) and
  OpenSooq (السوق المفتوح). The same apartment advertised on several sites is shown once,
  matched by REGA licence number or phone, size and location.
- **Applies the requirements** (all editable in Settings): rent up to 50,000 SAR a year
  (monthly rents ×12), one bedroom plus at least one extra room, a living room, two
  bathrooms, unfurnished or semi-furnished, families allowed, morning commute up to 40 min.
- **Sorts results into four tabs**:
  - **Matches**: every requirement is met and rent, rooms and location are confirmed.
  - **To check**: nothing is broken, but the ad leaves out the rent, the rooms or the
    location.
  - **Close**: misses one requirement by a little, for example 10% over budget or 5 minutes
    too far.
  - **All**: every ad around work.
- **Commute**: Google traffic forecast for the next workday at 08:00 (home → work) and
  17:00 (work → home). It is corrected with your real trip from your current home (75 min
  in the morning, 80 in the evening). Without a Google key it uses a distance estimate
  tuned to Riyadh rush hour.
- **Finish check**:
  - Claude Sonnet rates floors, ceiling and lighting, walls and paint, doors and windows,
    bathrooms and kitchen from up to 8 photos.
  - It gives a verdict (modern / renovated / dated / old) with reasons in Arabic and
    English.
  - The ad text adds signals too: "أول ساكن", "مجددة بالكامل", "سبوت لايت", "تحتاج ترميم", …
- **Map** of all results around the work pin: Google Maps with your key, or OpenStreetMap
  without one.
- **Actions** from each apartment: call, WhatsApp, open the original ad, Google Maps route,
  save, hide (with a reason), and private notes.
- **Languages**: Arabic and English, right-to-left aware. There are no notifications;
  background search runs every few hours and results wait in the app.

## Install

1. Open the latest **Shaqati** release on this repository's Releases page.
2. Download `shaqati-<version>.apk` and open it on the phone. Allow installing from the
   browser or file manager if Android asks.
3. Later versions install over the previous one, because every build is signed with the
   same key.

## First start

1. **Work location.** Tap *Set work location*, then move the map so the pin sits on your
   building, or paste a Google Maps link to it. Until then the app uses the centre of
   Al Masif.
2. **Keys (optional, can be added later in Settings):**
   - **Google Maps Platform key**, for traffic-accurate commutes and the Google map.
     1. In Google Cloud Console, create a project.
     2. Enable **Routes API** and **Maps JavaScript API**.
     3. Create an API key.
     4. To restrict it, allow those two APIs and add the website restriction
        `https://appassets.androidplatform.net/*`.
     5. Each apartment location costs 2 route elements (morning and evening), and results
        are cached per location. Normal use stays within Google's monthly free usage.
   - **Anthropic API key**, for photo analysis: create one at console.anthropic.com.
     Apps cannot use a Claude.ai subscription, so an API key is needed.
3. Tap **Start searching**. The first search takes a minute or two; pull down on the list
   to search again.

## Photo analysis cost control

- Only apartments in **Matches** with at least 3 photos are analysed, each one only once.
- Photos are shrunk to about 640 px, and at most 6 are sent (up to 8 in Settings).
- **Batch mode** (on by default) uses the Message Batches API at half price. Results arrive
  within about an hour.
- The long rating rubric is sent as a cached prompt, so it is billed at the cache rate.
- A **monthly limit** (default $5) stops new analysis when reached. The spend so far is
  shown in Settings.

With the defaults one apartment costs about one cent, so $5 covers several hundred
apartments.

## What is not included, and why

- **Bayut** and **Wasalt** block automated access (bot checks), so the app cannot read them.
- **Kite** is an aggregator whose terms do not allow this use from a mobile app.
- **Deal** only shows guests the newest pages of each search. The app reads those gently on
  every refresh, so new Deal ads are picked up over time.

## Privacy

- Everything runs on the phone; there is no server.
- API keys are encrypted with the Android Keystore and only sent to Google or Anthropic.
- Searches, saved apartments and notes stay in the app's private storage.

## For developers

- **`core/`**: pure Kotlin engine.
  - Site adapters, Arabic text signals, filters, duplicate matching and ranking.
  - Google Routes client and the Claude client (official `anthropic-java` SDK).
  - Unit tests run against responses recorded from each site.
- **`app/`**: Jetpack Compose UI, WorkManager background search, encrypted settings and the
  map (WebView).
- **CI** (`.github/workflows/ci.yml`):
  - Core tests, the Android build, unit tests and lint.
  - On demand:
    - `[live]` runs the adapters against the real sites.
    - `[qa]` runs an emulator tour of every screen in both languages plus a live search,
      and commits the screenshots to `qa/`.
- **`probe/`**: the scripts used to learn how each site serves its listings.

Build locally with `./gradlew :core:test :app:assembleDebug`. This needs JDK 17+, and the
Android SDK only for `app`.

### REA (Reverse Engineer Anything) for Claude Code

This repository also registers [REA](https://github.com/morluto/rea) for Claude Code at
project scope:

- `.mcp.json` registers the `rea` MCP server (`npx -y rea-agents@6.5.0 mcp`).
- `.claude/settings.json` pre-approves that server for this project.
- `.claude/skills/reverse-engineer-anything/` is REA's bundled workflow skill (copied from
  `rea-agents@6.5.0`).

Requirements:

- Node.js 22.x (>=22.19), 24.x (>=24.11) or 26+, plus npm.
- On Linux, a procps-compatible `ps` for JavaScript/Electron, Ghidra and JADX analysis.
- Hopper, Ghidra or IDA for native binary decompilation.

Restart Claude Code in this directory, then check with `claude mcp list`.
