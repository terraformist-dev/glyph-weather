package dev.terraformist.glyphweather

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app screen: the same picture the Glyph Matrix shows, the numbers in words, and the
 * one-time location setup. Tap the picture to step through the screens like a long-press.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var screen = Screen.ICON
    private var refreshing = false
    private var askedForLocation = false

    private lateinit var picture: ImageView
    private lateinit var headline: TextView
    private lateinit var details: TextView
    private lateinit var updated: TextView
    private lateinit var refreshButton: TextView
    private lateinit var locationNote: TextView
    private lateinit var locationButton: TextView

    private val loop = object : Runnable {
        override fun run() {
            render()
            val now = System.currentTimeMillis()
            val weather = Forecast.current(this@MainActivity, now)
            val l = if (screen == Screen.ICON && weather != null) WeatherArt.loop(weather.sky(now)) else null
            handler.postDelayed(this, l?.let { it.frameMs - now % it.frameMs } ?: 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildLayout())
        picture.setOnClickListener {
            screen = screen.next()
            render()
        }
        refreshButton.setOnClickListener { refresh(force = true) }
        locationButton.setOnClickListener { askForLocation() }
    }

    override fun onResume() {
        super.onResume()
        if (!Forecast.hasLocation(this) && !askedForLocation) {
            askedForLocation = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), ASK_LOCATION)
        }
        // Opening the app is the moment Android lets us see where you are, so always look now.
        refresh(force = true)
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(loop)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh(force = true)
    }

    private fun askForLocation() {
        when {
            !Forecast.hasLocation(this) ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), ASK_LOCATION)
            !Forecast.hasLocationAllTheTime(this) ->
                // Android shows its own page with "Allow all the time".
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), ASK_LOCATION)
            else -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun refresh(force: Boolean) {
        if (refreshing) return
        refreshing = true
        render()
        Forecast.refresh(this, force) {
            refreshing = false
            render()
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val weather = Forecast.current(this, now)
        picture.setImageBitmap(DotPainter.paint(Screens.grid(screen, weather, now, animate = true), 40))

        if (weather == null) {
            headline.text = if (refreshing) "Looking outside…" else "No weather yet"
            details.text = ""
            updated.text = if (Forecast.hasLocation(this)) "Check you're online, then tap Refresh."
            else "Allow location so Glyph Weather knows where you are."
        } else {
            headline.text = "${weather.tempC}°  ${weather.sky(now).label}"
            val high = weather.highC?.let { "$it°" } ?: "–"
            val low = weather.lowC?.let { "$it°" } ?: "–"
            details.text = "High $high · Low $low"
            val time = DateTimeFormatter.ofPattern("HH:mm")
                .format(Instant.ofEpochMilli(weather.fetchedAt).atZone(ZoneId.systemDefault()))
            updated.text = if (refreshing) "Updating…" else "Updated $time"
        }

        val allTheTime = Forecast.hasLocationAllTheTime(this)
        locationNote.text = when {
            !Forecast.hasLocation(this) ->
                "Glyph Weather needs your approximate location to know whose weather to show. Only a rough position (about a kilometre) is sent to Open-Meteo, a free weather service."
            !allTheTime ->
                "The Glyph Toy uses the place you were when you last opened this app. To have it follow you when you travel, allow location all the time."
            else -> "Location is allowed all the time, so the Glyph Toy follows you when you travel."
        }
        locationButton.text = when {
            !Forecast.hasLocation(this) -> "ALLOW LOCATION"
            !allTheTime -> "ALLOW ALL THE TIME"
            else -> "LOCATION SETTINGS"
        }
    }

    // ---- Layout (built in code: black, white dot pictures, quiet grey text) ----

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int, mono: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", Typeface.NORMAL)
        gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(0f, 1.2f)
    }

    private fun button(): TextView = text(14f, Color.WHITE, mono = true).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), 0xFF5A5A5A.toInt())
            setColor(Color.BLACK)
        }
        isClickable = true
        isFocusable = true
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(40))
            setBackgroundColor(Color.BLACK)
        }

        column.addView(text(13f, 0xFF9A9A9A.toInt(), mono = true).apply {
            text = "GLYPH WEATHER"
            letterSpacing = 0.15f
        })
        column.addView(gap(28))

        picture = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(280))
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF111111.toInt())
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Weather picture. Tap to see the temperature, then the high and low."
        }
        column.addView(picture)
        column.addView(gap(28))

        headline = text(30f, Color.WHITE)
        column.addView(headline)
        column.addView(gap(6))
        details = text(17f, 0xFFCFCFCF.toInt())
        column.addView(details)
        column.addView(gap(6))
        updated = text(13f, 0xFF9A9A9A.toInt(), mono = true)
        column.addView(updated)
        column.addView(gap(20))

        refreshButton = button().apply { text = "REFRESH" }
        column.addView(refreshButton, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(gap(36))

        column.addView(text(15f, Color.WHITE).apply {
            text = "On the back of your phone"
        })
        column.addView(gap(8))
        column.addView(text(14f, 0xFFB0B0B0.toInt()).apply {
            text = "Add it in Settings › Glyph Interface › Glyph Toys.\n\n" +
                "Long-press the Glyph Button for the temperature, again for today's high and low, " +
                "and once more to go back to the picture.\n\nTap the picture above to try it here."
        })
        column.addView(gap(32))

        locationNote = text(14f, 0xFFB0B0B0.toInt())
        column.addView(locationNote)
        column.addView(gap(14))
        locationButton = button()
        column.addView(locationButton, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(gap(32))

        column.addView(text(12f, 0xFF8A8A8A.toInt(), mono = true).apply {
            text = "Weather data by Open-Meteo.com"
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(column)
        }
    }

    companion object {
        private const val ASK_LOCATION = 1
    }
}
