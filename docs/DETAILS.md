# Shaqati (شقتي) — everything that was built

The full record of what the app does, how each part works, how it was tested, and what is
still open. The short version of the plan is in [`PLAN.md`](PLAN.md); the user guide is the
[`README`](../README.md).

**Current version:** 1.0.35 —
[download](https://github.com/DrHaCKo0oR/MeGam/releases/tag/shaqati-1.0.35). It installs over
earlier versions (every build is signed with the same key).

---

## 1. Your requirements and where each one lives

| Requirement | How the app applies it |
| --- | --- |
| Rent up to 50,000 SAR a year | Monthly rents are multiplied by 12 and half-yearly by 2. If an ad gives no period, the amount decides it: up to 9,000 counts as monthly, 14,000 or more as yearly. A "yearly" price of 9,000 or less is treated as monthly, and a "monthly" price of 20,000 or more as yearly. |
| A bedroom plus at least one extra room | At least 2 rooms besides the living room. A majlis (guest room) counts as the extra room. |
| A living room (صالة) | At least 1 living room. |
| 2 bathrooms | At least 2. If the ad leaves the count out, the apartment waits in **To check**; the photo check can confirm it from the photos. |
| Unfurnished or semi-furnished | Furnished apartments are left out. |
| Families | Singles-only ads are left out. |
| Commute up to 40 minutes | The 08:00 drive from home to work is the filter. The 17:00 drive back is shown too. |
| Modern / renovated look | Photo check plus text signals (section 5). |
| Google Maps | Google Maps with your key; OpenStreetMap without one. |
| Arabic and English | The whole app, right-to-left aware, with Western digits in both languages. |
| No notifications | Background search every few hours; results wait in the app. |

Every requirement can be changed in **Settings → Requirements**. Ads older than 75 days do not
count as matches.

## 2. Sites

| Site | How it is read | Live result (emulator, 17 Oct) |
| --- | --- | --- |
| **Aqar** (عقار) | The site's own GraphQL query (`findListings`) for rentals inside a circle around work. One query brings furnishing, families, building age and the licence. | 718 ads |
| **Haraj** (حراج) | GraphQL posts by tag: Riyadh plus the districts near work. Price, rooms and the map pin come from the REGA licence block in the text. "Wanted" ads are dropped. | 127 ads |
| **Property Finder** | The data inside the search pages (`__NEXT_DATA__`), yearly prices then monthly ones. Ad pages add every photo. When the site's firewall refuses, the page is read in the app's browser. | 69 ads |
| **Deal** (ديل) | The guest API: the newest pages, then the first page of each nearby district, slowly. Guests may read 4 pages per query; the app stops at the first refusal. | 61 ads |
| **OpenSooq** | The data inside the Riyadh "apartments for rent" pages; ad pages add photos and the pin. | 3 ads |
| **Bayut** (بيوت) | Search and ad pages read **in a browser inside the app**, page by page. Bayut puts its results in the page (`window.state` → Algolia hits): price and period, rooms, baths, area, furnishing, district, map pin, photos. The ad page adds the description, every photo, whether the pin is exact, amenities and the REGA ad licence. Yearly prices up to the budget, then monthly up to a twelfth of it, newest first. | Not verified live (see below) |
| **Wasalt** (وصلت) | List and ad pages read **in a browser inside the app**. Wasalt puts its results in the page (`__NEXT_DATA__` → `searchResult`): price and period, bedrooms, living rooms, bathrooms, area, building age, furnishing, district, pin (and whether it is approximate), photos, REGA ad licence. Paging follows the site's own code (`max_price`, pages counted from 0). | Not verified live |
| **Kite** | Not included: a paid API whose terms forbid use from mobile apps. | — |

**Bayut and Wasalt bot checks.** Both sites always show a check to servers: Bayut an
hCaptcha "I am human", Wasalt a Cloudflare "Verify you are human". Every machine available
for testing (GitHub, the emulator) is a server, so they were blocked there, and reading real
listings could only be tested with saved copies of their pages (from the Internet Archive).
On your phone the sites may load directly. If one asks for a check:

1. The feed shows "Bayut asked for a check…" with **Open Bayut** (also in Settings → Sites).
2. The check screen shows the site inside the app; complete the check there.
3. When the apartments appear, tap **Search now**. The cookies from the check are shared with
   the app's hidden browser, so the search can read the site.

**How the same apartment is matched across sites.** Only strong evidence links two ads: the
same REGA advertising licence, or the same advertiser phone with the same layout, price and
location. The most complete ad leads the group; the others show as "also on".

**Ads that disappear** are marked gone; they are removed 30 days later (or after 120 days
unseen), unless saved or noted.

## 3. Feed, score and tabs

**Tabs:**
- **Matches**: every requirement met, and rent, rooms, bathrooms and location confirmed.
- **To check**: nothing broken, but the ad leaves something out (rent, rooms, bathrooms or
  location).
- **Almost**: exactly one requirement missed by a little: rent up to 10% over, commute up to
  10 minutes over, or one bathroom or room short.
- **All**: every ad around work.

**Match score (0–100)**, shown on every card:

| Part | Weight | Best → worst |
| --- | --- | --- |
| Finish (photos, or the ad text when there is no photo check) | 45% | modern → old |
| Commute (60% morning, 40% evening) | 22% | 12 min → 55 min |
| Price | 15% | 30,000 → 51,000 a year |
| Space (area, kitchen, private entrance) | 10% | |
| Freshness | 8% | 1 day → 45 days old |

Sort by best, newest, cheapest, closest or most modern.

## 4. Commute

- **With a Google Maps key:** Google Routes traffic forecast for the next workday (Sunday to
  Thursday) at **08:00** home → work and **17:00** work → home. Results are cached per
  location, and each apartment costs 2 route lookups.
- **Calibration:** your real trip from Al Murabba to work (75 minutes at 08:00, 80 at 17:00)
  is compared with Google's forecast for the same trip, and the difference is applied to every
  apartment.
- **Without a key:** an estimate from road distance at Riyadh rush-hour speed (14 km/h,
  roads 1.3 × the straight line, plus 5 minutes), clearly labelled as approximate.
- A website-restricted key works: the app sends the `https://appassets.androidplatform.net/`
  referrer.

## 5. Modern / renovated check

**From the ad text.** Arabic and English signals such as "أول ساكن", "مجددة بالكامل",
"تشطيب حديث", "سبوت لايت", "جبس بورد", "بورسلان" count up; "تحتاج ترميم" and similar
count down.

**From the photos (the main judge).** Claude rates up to 8 photos on a fixed checklist:
- floors
- ceiling and lighting
- walls and paint
- doors and windows
- bathrooms
- kitchen (an empty kitchen is fine; only its finish is judged)

It returns:
- a score from 0 to 100
- a verdict: modern, renovated, dated, old, or unclear when the photos do not show the inside
- reasons in Arabic and English, and notes on specific photos
- how many bedrooms and bathrooms the photos show

Renders, stock photos and other properties are flagged.

**Three ways to run the photo check:**

| Way | Uses | Automatic? |
| --- | --- | --- |
| **Ask Claude about the photos** (button on each apartment) | Your Claude subscription, in the Claude app | No: one tap per apartment, and the answer appears in the Claude app |
| **Anthropic API key** (Settings → Keys) | Billed to the key: about 1 cent per apartment, with a monthly cap (default $5). Batch mode costs half and answers within about an hour. | Yes, inside the app |
| **GitHub photo check** (optional) | Your subscription, through the official Claude Code in GitHub Actions | Yes, every 6 hours, after a one-time setup (`claude setup-token`, a repository secret, and merging the pull request) |

**Why there is no "log in with Claude" inside the app.** Anthropic does not let other apps
offer Claude login or use subscription tokens, including the one from `claude setup-token`.
Only Anthropic's own apps (the Claude app and Claude Code) may, and Anthropic blocks apps that
do it anyway, which can put the account at risk. So the app never asks for your Claude
login.

## 6. The app

- **Apartments (feed):** tabs, sort, pull to refresh, the "site asked for a check" banner,
  and cards with photo, price per year and month, district, rooms, living rooms, bathrooms,
  area, morning commute, site and age.
- **Apartment page:** photos (full screen, swipe), price, open the ad, route in Google Maps,
  call, WhatsApp, save, hide with a reason, private notes, finish check, **Ask Claude**, text
  signals, commute at 08:00 and 17:00, requirements met or missing, and the same apartment on
  other sites.
- **Map:** every result around the work pin, colour-coded; tap a pin to see the card.
- **Saved** and **Hidden** lists.
- **Settings:**
  - work location (drag the map or paste a Google Maps link) and search radius (default 9 km)
  - requirements and commute times
  - Google and Anthropic keys, encrypted with the Android Keystore
  - photo check options and spending
  - Claude subscription section
  - sites, each with its own switch and status
  - background search off, or every 3, 6, 12 or 24 hours (default 6)
  - language (phone, Arabic or English)
  - clear data
- **Check screen:** shows Bayut or Wasalt inside the app so you can pass its check.

Pages from Bayut, Wasalt and (when needed) Property Finder are read in a hidden browser that
skips images. It reads a page as soon as its HTML has arrived; a large Bayut page would
otherwise take too long. A page without the site's data counts as a check.

## 7. Testing

- **Unit tests: 73.**
  - They run against responses recorded from every site; Bayut and Wasalt use pages from the
    Internet Archive. Phone numbers are masked, and keys found in pages are removed.
  - They also cover bot-check pages, the requirements, prices, duplicates, the score, the
    commute and calibration, the Claude client (against a local fake), and reading Claude
    Code's answers.
- **Emulator QA (Android 15), on every `[qa]` commit:**
  - Every screen in English and Arabic.
  - A Bayut ad and a Wasalt ad with their photos.
  - Ask Claude opening the share sheet with 6 photos.
  - The check banner, the settings button and the check screen.
  - The map, with checks that it really has a size.
  - A real first run: onboarding, then a search of every site. The last run found 977 ads and
    100 matches in 108 seconds.
- **Live checks (`[live]`):** every adapter against the real sites from GitHub.
- **Photo check dry run:** in GitHub Actions it picked 10 real matching apartments, downloaded
  and converted their photos, and Claude Code accepted every option; it stopped only at
  "Not logged in".
- **Photo hosts:** current Bayut and Wasalt photo links return images to ordinary clients.

## 8. Problems found and fixed

| Problem | Cause | Fix |
| --- | --- | --- |
| Blank map (1.0.21) | The map's WebView was added with wrap-content size, so the page was laid out with zero height | It now fills its space, the page has a fallback, and QA checks the map's height (fixed in 1.0.27) |
| "API KEY REQUIRED" on map tiles | CARTO's free tiles started requiring a key | OpenStreetMap tiles |
| Mixed Arabic and Western digits | Arabic locale formatting | Western digits everywhere, with direction marks for coordinates |
| QA missed a crash once | A system "not responding" dialog covered the app, and the job passed even when tests failed | The dialog is dismissed, there are in-app back buttons, and the job fails unless every test passes |
| Archived Bayut page blocked by GitHub | It contained a third-party map token | Keys and tokens are removed from saved pages before they are committed |
| Bayut's check not recognised | Its check page is large and answers "200 OK" | Check pages are recognised by title, and a Bayut or Wasalt page without data counts as a check |

## 9. Releases

| Version | What changed |
| --- | --- |
| 1.0.21 | First full release (the map was blank) |
| 1.0.27 | Working map, OpenStreetMap tiles, Western digits, Arabic photo notes |
| 1.0.35 | Bayut and Wasalt read in the app's browser, the check screen, Ask Claude, the Claude subscription section, and the optional GitHub photo check |

## 10. Still open

- **Bayut and Wasalt on a real phone:** not yet seen with live listings. The first search on
  your phone will show whether they load directly or ask for the one-time check.
- **Automatic photo checks:** need either an API key or the GitHub setup.
- **Traffic-accurate commutes:** need a Google Maps Platform key (Routes API and Maps
  JavaScript API).
- **Kite:** not included (terms).

## 11. Privacy

- Everything runs on the phone; there is no server.
- Keys are encrypted with the Android Keystore and only sent to Google or Anthropic.
- The repository is public but holds no keys, tokens or your exact locations. The optional
  GitHub photo check publishes only verdicts (ad id, ratings, notes, photo links).

## 12. Repository map

```
app/     Android app (Kotlin, Jetpack Compose, WorkManager, DataStore)
core/    the engine: sites, Arabic text, requirements, duplicates, score, commute, Claude
tools/   command line for the GitHub photo check (queue, collect)
probe/   scripts that learned how each site serves its listings, and their results
qa/      screenshots and logs from the last emulator run
docs/    PLAN.md and this file
.github/workflows/
         ci.yml           tests, build, lint, emulator QA, live checks, releases
         probe.yml        site probes
         photo-check.yml  optional photo check on your Claude subscription
```
