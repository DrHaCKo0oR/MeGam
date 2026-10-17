# Shaqati (شقتي) — plan

A personal Android app that collects rental apartments in Riyadh from every
major listing app, keeps only the ones that fit, checks the photos for a
renovated/modern finish, and measures the real rush-hour drive to work.

## Your requirements (locked in)

| Topic | Requirement |
| --- | --- |
| Work | Salam R&D, Al Masif (المصيف), Riyadh — pin adjustable in Settings |
| Morning trip | Leave home 08:00 Sun–Thu, by car |
| Evening trip | Leave work 17:00 Sun–Thu |
| Commute | Up to 40 min (shown for both trips; morning trip is the filter) |
| Calibration | Today: Al Murabba compound (كمبوند المربع) → work ≈ 75 min at 08:00, ≈ 24 min weekend afternoon |
| Budget | ≤ SAR 50,000 per year (monthly rent × 12) |
| Layout | ≥ 1 bedroom + ≥ 1 extra room (2+ rooms besides the living room), ≥ 1 living room (صالة), kitchen (empty or fitted), ≥ 2 bathrooms |
| Furnishing | Unfurnished or semi-furnished; fully furnished hidden |
| Floor | Any |
| Finish | Renovated / modern look: new floor, gypsum ceiling + modern lighting, fresh paint, modern doors, bathrooms and kitchen |
| AI | Claude Sonnet 5.5, with strict cost controls |
| Maps | Google Maps inside the app; Google traffic predictions for the commute |
| Alerts | None |
| Repo | Public is fine — no keys or personal locations are committed |

## What went wrong before, and what changes

| Old app (Codex) | New app |
| --- | --- |
| Needed the home computer on and on the same Wi-Fi | Everything runs inside the phone app |
| Commute was straight-line km; traffic never connected | Google traffic forecast for 08:00 / 17:00 on a normal workday, calibrated against your real trip |
| "Modern" judgement was often wrong | Evidence-based checklist on the photos (floor, ceiling/lighting, paint, doors, bathroom, kitchen) + ad text signals, with the reasons shown |
| Hard to use | One clean feed, big photos, clear badges, one-tap call/WhatsApp |

## Architecture

```
app/   Android (Kotlin, Jetpack Compose, WorkManager, DataStore, Keystore-encrypted keys),
       plus a hidden in-app browser for sites with bot checks and a screen to pass a check
core/  Pure Kotlin engine shared by the app and tests
       ├─ sources/   one adapter per site → common Listing model
       ├─ text/      Arabic normalization + renovation/furnishing/family signals
       ├─ filter/    hard requirements with reasons
       ├─ rank/      match score
       ├─ dedupe/    same apartment across sites
       ├─ commute/   Google Routes (traffic) + approximate fallback + calibration
       └─ ai/        Sonnet photo assessment, batching, budget
tools/ command line for the optional GitHub photo check (Claude Code on the subscription)
probe/ GitHub Actions probe of the live sites (the build sandbox cannot reach them)
```

Everything that was built, tested and fixed is listed in [`DETAILS.md`](DETAILS.md).

### Fetching
Each site is read the way its own website reads it:

| Site | How | Notes |
| --- | --- | --- |
| Aqar | GraphQL `findListings` with a polygon around work | Every field (furnishing, families, age, licence) in one query |
| Haraj | GraphQL posts by tag (Riyadh + nearby districts) | Price/rooms/map pin from the REGA licence block in the text |
| Property Finder | Search-page data (`__NEXT_DATA__`), yearly and monthly prices | Ad pages add the full photo set; WebView fallback if refused |
| Deal | Guest API: newest pages, then the first page of each nearby district, paced | Guests may read 4 pages per query; the app stops at the first refusal |
| OpenSooq | Search-page data, ad pages for photos and pin | Small Riyadh inventory |
| Bayut | Search and ad pages read in the app's browser; the page state (`window.state`) holds the Algolia hits | hCaptcha check for servers; the app shows the site so the person can pass it once |
| Wasalt | List and ad pages read in the app's browser; `__NEXT_DATA__` holds `searchResult` | Cloudflare check for servers; same check screen |

Results are merged into one model, duplicates across sites are linked (REGA
licence, or phone + size + location), and ads that disappear are marked as gone.
Listings without a confirmed rent, room count or location are kept apart in a
"To check" tab instead of being shown as matches.

### Modern / renovated detection
1. Ad text: Arabic and English renovation signals (مجددة، ترميم، أول ساكن، تشطيب
   حديث، إضاءة مخفية، سبوت، جبس بورد، بورسلان…) and negative signals.
2. Photos (main judge): Sonnet 5.5 rates a fixed checklist — floor, ceiling and
   lighting, walls/paint, doors/windows, bathroom, kitchen — and returns a 0–100
   score, a verdict (modern / renovated / dated / old / unclear) and evidence per
   photo. Shown in the app.
3. Hiding an apartment asks for a reason (too expensive, too far, old finish,
   layout), so the feed only keeps what you would still consider.

### Keeping AI cost low
- AI only runs on listings that already pass budget, layout, furnishing and
  commute filters.
- Up to 6 photos per listing, downscaled to ~640 px (≈ 400 tokens each).
- Every listing is assessed once; duplicates across sites reuse the result.
- Message Batches API (50% cheaper) for background assessment.
- A monthly spending cap in Settings; the app stops when it is reached.

Expected cost: about $0.006–0.012 per listing assessed, i.e. a few dollars a
month, when the app uses an Anthropic API key.

On the owner's Claude subscription instead: Anthropic lets only its own apps sign
in with a subscription, so the app hands photos to the Claude app (Ask Claude),
and the photo-check workflow runs the official Claude Code CLI in GitHub Actions
with the owner's `claude setup-token` token. It rates up to 10 new matches every
6 hours with the same rubric and schema, and the app downloads the verdicts.

### Commute
Google Routes API with traffic-aware routing for the next Sunday–Thursday at
08:00 (home-side → work) and 17:00 (work → home-side). Results are cached per
location. A calibration trip (Al Murabba → work) compares Google's forecast
with your real 75 minutes and applies the correction. Without a Google key the
app shows an approximate estimate (road distance + Riyadh rush-hour factor),
clearly labelled.

## Build & test strategy
- `core` is compiled and unit-tested on every change (fixtures recorded from the
  live sites by the probe).
- The Android app is built in GitHub Actions; UI tests run on an emulator and
  save screenshots for visual review.
- Live checks run each source against the real sites from CI.
- The APK is published as a GitHub Release.
