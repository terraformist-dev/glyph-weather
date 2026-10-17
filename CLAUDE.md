# Glyph Weather (repo glyph-weather): notes for Claude

Owner has no coding experience and works only from a phone. Explain changes in plain English,
never ask them to run commands, and always finish by pushing to `main` and confirming the
GitHub Actions build went green (the build publishes `glyph-weather.apk` as the latest release).

## Project

Android app (Kotlin, no AndroidX, no Compose, UI built in code), package `dev.terraformist.glyphweather`,
minSdk 34, target Phone (3). Builds only in GitHub Actions (`.github/workflows/build.yml`);
the sandbox cannot reach Google's Maven or Gradle servers. Set up the same way as the owner's
Dotchi app (repo `terraformist-dev/dotchi`): borrow proven patterns from there.

The debug build is signed with `app/glyphweather.keystore` (committed on purpose) so updates
install over the old app. Don't change the key or applicationId.

## What it does

A Glyph Toy showing the current weather. Long-press (`GlyphToy.EVENT_CHANGE`) cycles
ICON -> TEMPERATURE -> HIGH_LOW -> ICON (`Screen`). Reset to ICON when the toy is picked.
The app screen shows the same picture (tap it to cycle) plus location setup.

## Artwork is signed off: don't redesign it

`Art.kt` holds the approved icon set v2 grids ('.' off, '-' dim ~35% = level 90, '#' full).
`WeatherArt.kt` builds the loops for FOG, DRIZZLE, RAIN, HEAVY_RAIN, SNOW, SLEET, THUNDER exactly as
reviewed (frame counts/speeds: fog 10@240ms, drizzle 6@200, rain 8@120, heavy 4@80, snow 12@200,
sleet 24@140, thunder 12@120). Only change art when the owner asks. The owner reviewed it on the
claude.ai canvas "Glyph Weather Icons". Weather code mapping is in `Sky.from`.

## Glyph Matrix SDK 2.0 (verified in Dotchi)

- `GlyphMatrixManager.getInstance(ctx)`, `init(Callback)`, `register(Glyph.DEVICE_23112)`,
  `setMatrixFrame(IntArray)`, `turnOff()`, `unInit()`. Frames: 625 ints, 0..4095 per LED.
- `GlyphToy` events: `EVENT_CHANGE` (long press), `EVENT_AOD` (once a minute when always-on),
  `EVENT_ACTION_DOWN`/`EVENT_ACTION_UP`. A quick press switches toys, so it can't be used.
- Toy service lives only while selected; return `Messenger.binder` from `onBind`.

## Power and data

Animate for 60 s after pick / long-press / screen-on (partial wake lock), otherwise one still
frame. Always-on ticks draw a still frame at 60% brightness and refresh weather if due.
Weather: Open-Meteo, no key, refresh at most every 30 min (`Forecast.REFRESH_MS`), shown up to
12 h old. Location: coarse; the last place is saved so the toy works without background
location; "all the time" is optional. Day/night uses today's sunrise/sunset.
