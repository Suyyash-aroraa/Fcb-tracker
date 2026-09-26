# FCB Tracker

An Android app and home-screen widget for FC Barcelona: fixtures and results in every competition, live scores, match stats and lineups, the squad, the LALIGA table, news, and where to watch each match in India.

Everything runs on the phone. The app downloads and parses each source itself; there is no server.

```
android/                                Kotlin app (Jetpack Compose) + Glance home-screen widget
.claude/skills/UIUXmasterclass-skill/   design skill the UI was built with
```

## Install

Download the APK from the [latest release](../../releases), open it on your phone and allow installing from that source. Then long-press the home screen, choose **Widgets** and add **FC Barcelona**.

## What it shows

- **Widget** (resizable): the next match with countdown and India channel; the live score and clock with Barça's scorers; or the full-time result for a few hours after the whistle. The larger size adds the last result, form, league position and the following fixture. Tap it to open the app.
- **Overview**: next or live match, last result with scorers, form, goal and assist leaders, the table around Barça, upcoming fixtures and the latest news.
- **Matches**: fixtures and results, filterable by competition, with India TV channels.
- **Match centre**: a preview (league position, form, every meeting since 2020), the timeline, mirrored team stats, and lineups on a pitch.
- **Squad**: official player photos, position groups, sorting by goals, assists, appearances or age, and a sheet with each player's season stats.
- **Table** and **News** (club site, Google News and ESPN).

Channel names open the broadcaster's own site (FanCode, Sony LIV, JioTV...).

## Sources

| Source | What it provides |
|---|---|
| ESPN public JSON | Fixtures and results in every competition, live scores, match events, team stats, lineups, squad stats, the LALIGA table, news, past seasons for head-to-head |
| fcbarcelona.com | Official player photos and profile links, club news |
| Google News RSS | Headlines from many publishers |
| LiveSoccerTV | Where to watch in India, per fixture |
| FanCode | India fallback for LALIGA (and the Copa del Rey / Supercopa when it carries them) |

## Refreshing

Every 30 minutes in the background, and about every minute while a match is in its live window (75 minutes before kick-off until it ends). The latest data is saved on the phone, so the widget still shows it offline. Opening the app always refreshes.

## Build

Needs JDK 17+ and the Android SDK (`sdk.dir` in `android/local.properties`).

```bash
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed with keystore.properties if present, else the debug key
./gradlew testDebugUnitTest -Proborazzi.test.record=true
```

The tests use live data only: `LiveSourcesTest` runs the data layer against the real sources (every match involves Barcelona, goal events add up to the score, India TV found, squad with official photos, news, head-to-head), and `ScreensTest` renders every screen in both themes and three widget sizes under Robolectric into `app/build/screens/`.

Release signing reads `android/keystore.properties` (not committed):

```
storeFile=fcb-release.jks
storePassword=...
keyAlias=fcb
keyPassword=...
```

## Design

The UI follows `.claude/skills/UIUXmasterclass-skill`, which combines [taste-skill](https://github.com/leonxlnx/taste-skill)'s anti-slop rules with the [ui-ux-pro-max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) design search engine (both MIT, vendored with their licenses). Big Shoulders, Geist and Geist Mono are bundled (OFL, see `android/LICENSE-fonts.txt`); icons are Phosphor (MIT). Blaugrana appears as a stripe and gold is the single accent, with light and dark themes.
