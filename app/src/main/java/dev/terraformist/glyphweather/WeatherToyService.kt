package dev.terraformist.glyphweather

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy

/** What the toy is showing. Each long-press moves to the next one. */
enum class Screen { ICON, TEMPERATURE, HIGH_LOW;

    fun next(): Screen = entries[(ordinal + 1) % entries.size]
}

/** Builds the 625-dot picture for a screen. Shared by the Glyph Toy and the app. */
object Screens {
    fun grid(screen: Screen, weather: WeatherNow?, now: Long, animate: Boolean): CharArray = when (screen) {
        Screen.ICON -> if (weather == null) NumberArt.waiting() else {
            val sky = weather.sky(now)
            WeatherArt.picture(sky, if (animate) WeatherArt.frameAt(sky, now) else 0)
        }
        Screen.TEMPERATURE -> NumberArt.temperature(weather?.tempC)
        Screen.HIGH_LOW -> NumberArt.highLow(weather?.highC, weather?.lowC)
    }
}

/**
 * The Glyph Toy: the weather on the 25x25 Glyph Matrix on the back of the phone.
 *
 * Glyph Button (on the back):
 *  - Long-press: weather picture -> temperature -> today's high and low -> back to the picture.
 *  - A quick press switches to the next Glyph Toy (that's the phone's own behaviour).
 *
 * Animation (fog, drizzle, rain, heavy rain, snow, sleet, thunder):
 *  - Plays for a minute after the toy is picked, after a long-press, or while the screen is on.
 *    We keep the processor awake for that minute, otherwise the loop would freeze.
 *  - Always-on (screen off, toy left running): the system wakes us once a minute; we show a
 *    still, slightly dimmer picture and check whether the weather needs refreshing.
 *    Animating non-stop there would keep the phone awake all night.
 *
 * Weather is refreshed at most every half hour, whenever any of the above happens.
 */
class WeatherToyService : Service() {

    private var matrix: GlyphMatrixManager? = null
    private var connected = false
    private var screen = Screen.ICON

    /** Animate until this time even if the screen is off. */
    private var animateUntil = 0L

    /** True when the last thing that woke us was the once-a-minute always-on tick. */
    private var alwaysOn = false

    /** True while new weather is being fetched (we stay awake until it arrives). */
    private var refreshing = false

    private val main = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphWeather:animation").apply {
            setReferenceCounted(false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val screenOn = power.isInteractive
            if (screenOn) alwaysOn = false
            val weather = Forecast.current(applicationContext, now)
            val loop = if (screen == Screen.ICON && weather != null) WeatherArt.loop(weather.sky(now)) else null
            if (loop != null && (screenOn || now < animateUntil)) {
                draw(now, animate = true)
                main.postDelayed(this, loop.frameMs - now % loop.frameMs)
            } else {
                // Still picture: draw once, then let the phone sleep until something wakes us.
                draw(now, animate = false)
                if (screenOn || now < animateUntil) {
                    // Look again in a minute (day turns to night, new weather arrives).
                    main.postDelayed(this, 60_000L)
                } else if (!refreshing) {
                    releaseWakeLock()
                }
            }
        }
    }

    private val buttonEvents = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            when (msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)) {
                GlyphToy.EVENT_CHANGE -> onLongPress()
                GlyphToy.EVENT_AOD -> onAlwaysOnTick()
                GlyphToy.EVENT_ACTION_DOWN, GlyphToy.EVENT_ACTION_UP -> animateFor(ACTIVE_MS, alwaysOnTick = false)
            }
        }
    }

    private val messenger = Messenger(buttonEvents)

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                animateFor(ACTIVE_MS, alwaysOnTick = false)
                refreshWeather()
            }
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder {
        val manager = GlyphMatrixManager.getInstance(applicationContext)
        matrix = manager
        manager.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                manager.register(Glyph.DEVICE_23112)
                connected = true
                screen = Screen.ICON
                animateFor(ACTIVE_MS, alwaysOnTick = false)
                refreshWeather()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                main.removeCallbacks(tick)
                releaseWakeLock()
            }
        })
        if (!receiverRegistered) {
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(tick)
        releaseWakeLock()
        if (receiverRegistered) {
            unregisterReceiver(screenOnReceiver)
            receiverRegistered = false
        }
        connected = false
        matrix?.let {
            try {
                it.turnOff()
            } catch (e: Exception) {
                Log.w(TAG, "turnOff failed", e)
            }
            it.unInit()
        }
        matrix = null
        return false
    }

    private fun onLongPress() {
        screen = screen.next()
        animateFor(ACTIVE_MS, alwaysOnTick = false)
        refreshWeather()
    }

    private fun onAlwaysOnTick() {
        val now = System.currentTimeMillis()
        if (!power.isInteractive && now >= animateUntil) {
            alwaysOn = true
            try {
                wakeLock.acquire(12_000L) // long enough to fetch new weather if it's due
            } catch (e: Exception) {
                Log.w(TAG, "wake lock failed", e)
            }
            restartLoop()
        }
        refreshWeather()
    }

    /** Fetch new weather if it's due, then redraw with it. */
    private fun refreshWeather() {
        refreshing = true
        Forecast.refresh(applicationContext) {
            refreshing = false
            if (connected) restartLoop() else releaseWakeLock()
        }
    }

    /** Animate for [ms], keeping the processor awake just long enough to do it. */
    private fun animateFor(ms: Long, alwaysOnTick: Boolean) {
        val now = System.currentTimeMillis()
        alwaysOn = alwaysOnTick
        animateUntil = maxOf(animateUntil, now + ms)
        try {
            wakeLock.acquire(animateUntil - now + 1_000L)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
        restartLoop()
    }

    private fun restartLoop() {
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed", e)
        }
    }

    private fun draw(now: Long, animate: Boolean) {
        val manager = matrix ?: return
        if (!connected) return
        val weather = Forecast.current(applicationContext, now)
        val levels = WeatherArt.levels(Screens.grid(screen, weather, now, animate))
        val dim = alwaysOn && !power.isInteractive
        // The matrix takes 0..4095 per LED; our levels are 0..255.
        val frame = IntArray(levels.size) { i ->
            val level = if (dim) levels[i] * 6 / 10 else levels[i]
            level * 4095 / 255
        }
        try {
            manager.setMatrixFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    companion object {
        private const val TAG = "GlyphWeather"
        private const val ACTIVE_MS = 60_000L
    }
}
