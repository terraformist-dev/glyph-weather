package dev.terraformist.glyphweather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The weather where you are, as last fetched. */
data class WeatherNow(
    val code: Int,
    val tempC: Int,
    val highC: Int?,
    val lowC: Int?,
    val isDayAtFetch: Boolean,
    val sunriseMs: Long,
    val sunsetMs: Long,
    val fetchedAt: Long,
) {
    /** Day or night right now, from today's sunrise and sunset (falls back to what it was when fetched). */
    fun isDay(now: Long): Boolean =
        if (sunriseMs > 0L && sunsetMs > 0L && now - fetchedAt < 18 * 60 * 60_000L) now in sunriseMs until sunsetMs
        else isDayAtFetch

    fun sky(now: Long): Sky = Sky.from(code, isDay(now))
}

/**
 * Weather from Open-Meteo (free, no account), for the phone's approximate location rounded to
 * about a kilometre. Android only gives an app your location while it's open, unless you allow
 * location "all the time"; so the last place we saw is saved, and the Glyph Toy uses that.
 */
object Forecast {
    private const val TAG = "GlyphWeather"
    private const val PREFS = "weather"
    const val REFRESH_MS = 30 * 60_000L
    private const val SHOW_FOR_MS = 12 * 60 * 60_000L

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var fetching = false

    fun hasLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasLocationAllTheTime(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last weather fetched, if it's recent enough to show. */
    fun current(context: Context, now: Long = System.currentTimeMillis()): WeatherNow? {
        val p = prefs(context)
        val at = p.getLong("at", 0L)
        if (at == 0L || now - at > SHOW_FOR_MS) return null
        return WeatherNow(
            code = p.getInt("code", 3),
            tempC = p.getInt("temp", 0),
            highC = if (p.contains("high")) p.getInt("high", 0) else null,
            lowC = if (p.contains("low")) p.getInt("low", 0) else null,
            isDayAtFetch = p.getBoolean("day", true),
            sunriseMs = p.getLong("sunrise", 0L),
            sunsetMs = p.getLong("sunset", 0L),
            fetchedAt = at,
        )
    }

    /**
     * Fetch new weather if what we have is older than [REFRESH_MS] (or always, with [force]).
     * [done] is always called on the main thread, within about 10 seconds.
     */
    fun refresh(context: Context, force: Boolean = false, done: () -> Unit = {}) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val now = System.currentTimeMillis()
        if (fetching || (!force && now - prefs(app).getLong("at", 0L) < REFRESH_MS)) {
            main.post { done() }
            return
        }
        fetching = true
        var called = false
        val finish = {
            if (!called) {
                called = true
                done()
            }
        }
        main.postDelayed({ finish() }, 10_000L)

        findPlace(app) { place ->
            if (place == null) {
                fetching = false
                main.post { finish() }
                return@findPlace
            }
            worker.execute {
                try {
                    fetch(app, place.first, place.second)
                } catch (e: Exception) {
                    Log.w(TAG, "weather fetch failed", e)
                } finally {
                    fetching = false
                    main.post { finish() }
                }
            }
        }
    }

    /** Where to get weather for: a fresh location if Android allows it, else the last place saved. */
    private fun findPlace(context: Context, done: (Pair<Double, Double>?) -> Unit) {
        val p = prefs(context)
        val saved: Pair<Double, Double>? =
            if (p.contains("lat")) p.getFloat("lat", 0f).toDouble() to p.getFloat("lon", 0f).toDouble() else null
        val remember = { loc: Location? ->
            if (loc != null) {
                p.edit().putFloat("lat", loc.latitude.toFloat()).putFloat("lon", loc.longitude.toFloat()).apply()
                done(loc.latitude to loc.longitude)
            } else {
                done(saved)
            }
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasLocation(context)) {
            done(saved)
            return
        }
        try {
            val providers = manager.getProviders(true)
            val known = providers.mapNotNull {
                try {
                    manager.getLastKnownLocation(it)
                } catch (e: SecurityException) {
                    null
                }
            }.maxByOrNull { it.time }
            if (known != null && System.currentTimeMillis() - known.time < 60 * 60_000L) {
                remember(known)
                return
            }
            val provider = when {
                LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                remember(known)
                return
            }
            manager.getCurrentLocation(provider, null, worker) { fresh -> remember(fresh ?: known) }
        } catch (e: Exception) {
            // Not allowed to look right now (for example, in the background): use the saved place.
            done(saved)
        }
    }

    private fun fetch(context: Context, lat: Double, lon: Double) {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                    "&timezone=auto&timeformat=unixtime&forecast_days=1",
                lat,
                lon,
            )
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val daily = json.optJSONObject("daily")
            val edit = prefs(context).edit()
                .putInt("code", current.getInt("weather_code"))
                .putInt("temp", current.getDouble("temperature_2m").roundToInt())
                .putBoolean("day", current.optInt("is_day", 1) == 1)
                .putLong("at", System.currentTimeMillis())
            if (daily != null) {
                val max = daily.optJSONArray("temperature_2m_max")
                val min = daily.optJSONArray("temperature_2m_min")
                val rise = daily.optJSONArray("sunrise")
                val set = daily.optJSONArray("sunset")
                if (max != null && max.length() > 0 && !max.isNull(0)) edit.putInt("high", max.getDouble(0).roundToInt())
                else edit.remove("high")
                if (min != null && min.length() > 0 && !min.isNull(0)) edit.putInt("low", min.getDouble(0).roundToInt())
                else edit.remove("low")
                edit.putLong("sunrise", if (rise != null && rise.length() > 0) rise.optLong(0) * 1000L else 0L)
                edit.putLong("sunset", if (set != null && set.length() > 0) set.optLong(0) * 1000L else 0L)
            }
            edit.apply()
        } finally {
            connection.disconnect()
        }
    }
}
