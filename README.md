# Glyph Weather

A weather glyph toy for the Nothing Phone (3). Inspired by the Nothing Weather app.

- It shows a dot picture of the current weather. Fog, drizzle, rain, heavy rain, snow, sleet and thunder have short animated loops.
- **Long-press** the Glyph Button to see the temperature. Long-press again for today's high and low, and once more to go back to the picture.

## Getting the app onto your phone

1. On your phone, open **Releases → Latest** on this repo's GitHub page.
2. Tap **glyph-weather.apk** to download it.
3. Open the download and tap **Install** (allow your browser to install apps the first time).
4. Open **Glyph Weather** once and allow location.
5. Go to **Settings › Glyph Interface › Glyph Toys** and add **Glyph Weather**.

## How it works

| File | What it does |
|---|---|
| `Art.kt` | The approved icon artwork (icon set v2) as 25×25 dot grids. |
| `WeatherArt.kt` | The twelve weather pictures, their animated loops, and the temperature / high-low screens. |
| `Forecast.kt` | Fetches weather from Open-Meteo for your approximate location, at most every 30 minutes. |
| `WeatherToyService.kt` | The Glyph Toy: long-press cycle, animation, always-on. |
| `MainActivity.kt` | The app screen: location setup, a preview of the matrix, and instructions. |
| `DotPainter.kt` | Paints a dot grid as round dots for the app. |

Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
The Glyph Matrix SDK (`app/libs/glyph-matrix-sdk-2.0.aar`) is from
[Nothing-Developer-Programme/GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) (MIT).
