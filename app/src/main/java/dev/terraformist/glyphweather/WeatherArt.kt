package dev.terraformist.glyphweather

/** The twelve weather pictures. Open-Meteo (WMO) weather codes map onto these in [Sky.from]. */
enum class Sky(val label: String) {
    CLEAR_DAY("Clear"),
    CLEAR_NIGHT("Clear"),
    PARTLY_DAY("Partly cloudy"),
    PARTLY_NIGHT("Partly cloudy"),
    CLOUDY("Cloudy"),
    FOG("Fog"),
    DRIZZLE("Drizzle"),
    RAIN("Rain"),
    HEAVY_RAIN("Heavy rain"),
    SNOW("Snow"),
    SLEET("Sleet"),
    THUNDER("Thunderstorm");

    companion object {
        fun from(code: Int, isDay: Boolean): Sky = when (code) {
            0 -> if (isDay) CLEAR_DAY else CLEAR_NIGHT
            1, 2 -> if (isDay) PARTLY_DAY else PARTLY_NIGHT
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57, 66, 67 -> SLEET
            61, 63, 80, 81 -> RAIN
            65, 82 -> HEAVY_RAIN
            in 71..77, 85, 86 -> SNOW
            95, 96, 99 -> THUNDER
            else -> CLOUDY
        }
    }
}

/** One fog bar: row [y], from column [a] to [b], drifting in direction [dir]. */
private class Bar(val y: Int, val a: Int, val b: Int, val ch: Char, val dir: Int)

/** A looping animation: every frame is a 625-char grid (see [Art]). */
class Loop(val frameMs: Long, val frames: List<CharArray>)

/**
 * Draws the weather pictures, including the short loops for 06-12.
 * Ported one-for-one from the approved review canvas, so the matrix shows exactly what was signed off.
 */
object WeatherArt {
    const val N = 25
    private const val BAND = 15 // rain, snow etc. fall from this row down (just under the cloud)

    private fun mod(a: Int, n: Int): Int = ((a % n) + n) % n

    private fun set(g: CharArray, x: Int, y: Int, ch: Char) {
        if (x in 0 until N && y in 0 until N && g[y * N + x] != ' ') g[y * N + x] = ch
    }

    /** Streaks falling along a slant: two rows down for every column left. */
    private fun streaks(
        g: CharArray, t: Int, spacing: Int, lane: Int, len: Int, period: Int,
        speed: Int, stagger: Int, ch: Char, lo: Int = 8, hi: Int = 20,
    ) {
        for (y in BAND until N) {
            for (x in 0 until N) {
                val s = x + y / 2
                if (mod(s - lane, spacing) != 0) continue
                val xTop = s - BAND / 2 // where this streak starts under the cloud
                if (xTop < lo || xTop > hi) continue
                val idx = Math.floorDiv(s - lane, spacing)
                val p = mod(y - t * speed - idx * stagger, period)
                if (p < len) set(g, x, y, ch)
            }
        }
    }

    private val BOLT = listOf(14 to 15, 13 to 16, 12 to 17, 13 to 17, 14 to 17, 13 to 18, 12 to 19, 11 to 20)

    private val loops: Map<Sky, Loop> by lazy { buildLoops() }

    private fun buildLoops(): Map<Sky, Loop> {
        val m = HashMap<Sky, Loop>()

        // 06 Fog: bars sway gently, alternate rows in opposite directions.
        run {
            val bars = listOf(
                Bar(8, 5, 17, '#', 1), Bar(11, 4, 16, '-', -1), Bar(14, 7, 20, '#', 1),
                Bar(17, 5, 15, '-', -1), Bar(20, 8, 17, '#', 1),
            )
            val sway = intArrayOf(0, 1, 2, 2, 1, 0, -1, -2, -2, -1)
            val frames = sway.map { off ->
                val g = Art.grid("blank")
                for (bar in bars) for (x in bar.a..bar.b) set(g, x + bar.dir * off, bar.y, bar.ch)
                g
            }
            m[Sky.FOG] = Loop(240, frames)
        }

        // 07 Drizzle: sparse dim dots falling straight down.
        run {
            val cols = listOf(7 to 0, 10 to 3, 13 to 1, 16 to 4, 19 to 2)
            val frames = (0 until 6).map { t ->
                val g = Art.grid("precip_cloud")
                for ((x, ph) in cols) for (y in BAND until N) if (mod(y - t - ph, 6) == 0) set(g, x, y, '-')
                g
            }
            m[Sky.DRIZZLE] = Loop(200, frames)
        }

        // 08 Rain.
        m[Sky.RAIN] = Loop(120, (0 until 8).map { t ->
            val g = Art.grid("precip_cloud")
            streaks(g, t, spacing = 4, lane = 0, len = 3, period = 8, speed = 1, stagger = 3, ch = '#')
            g
        })

        // 09 Heavy rain: denser, longer and faster.
        m[Sky.HEAVY_RAIN] = Loop(80, (0 until 4).map { t ->
            val g = Art.grid("precip_cloud")
            streaks(g, t, spacing = 3, lane = 0, len = 4, period = 8, speed = 2, stagger = 4, ch = '#', lo = 7, hi = 20)
            g
        })

        // 10 Snow: three flakes drift down and sway.
        run {
            val flakes = listOf(Triple(8, 0, 1), Triple(16, 4, -1), Triple(12, 8, 1))
            val sway = intArrayOf(0, 0, 0, 1, 1, 1, 0, 0, 0, -1, -1, -1)
            val plus = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
            val frames = (0 until 12).map { t ->
                val g = Art.grid("precip_cloud")
                for ((x0, ph, dir) in flakes) {
                    val cy = 13 + mod(ph + t, 12)
                    val cx = x0 + dir * sway[mod(ph + t, 12)]
                    for ((dx, dy) in plus) if (cy + dy >= BAND) set(g, cx + dx, cy + dy, '#')
                }
                g
            }
            m[Sky.SNOW] = Loop(200, frames)
        }

        // 11 Sleet: short streaks alternating with ice pellets.
        m[Sky.SLEET] = Loop(140, (0 until 24).map { t ->
            val g = Art.grid("precip_cloud")
            streaks(g, t, spacing = 8, lane = 0, len = 3, period = 8, speed = 1, stagger = 3, ch = '#')
            streaks(g, t, spacing = 8, lane = 4, len = 1, period = 6, speed = 1, stagger = 2, ch = '#')
            g
        })

        // 12 Thunderstorm: dim rain, and the bolt flashes twice at the start of each loop.
        run {
            val flash = booleanArrayOf(true, true, false, true, false, false, false, false, false, false, false, false)
            val frames = flash.indices.map { t ->
                val g = Art.grid("precip_cloud")
                streaks(g, t, spacing = 4, lane = 2, len = 2, period = 8, speed = 1, stagger = 3, ch = '-')
                if (flash[t]) {
                    for ((bx, by) in BOLT) {
                        for (a in -1..1) for (b in -1..1) if (by + b >= BAND) set(g, bx + a, by + b, '.')
                    }
                    for ((bx, by) in BOLT) set(g, bx, by, '#')
                }
                g
            }
            m[Sky.THUNDER] = Loop(120, frames)
        }
        return m
    }

    /** The loop for this sky, or null if its picture is still. */
    fun loop(sky: Sky): Loop? = loops[sky]

    /** The picture for [sky]; for animated ones, [frame] picks which frame (it wraps). */
    fun picture(sky: Sky, frame: Int = 0): CharArray {
        loops[sky]?.let { return it.frames[mod(frame, it.frames.size)] }
        return Art.grid(
            when (sky) {
                Sky.CLEAR_DAY -> "clear_day"
                Sky.CLEAR_NIGHT -> "clear_night"
                Sky.PARTLY_DAY -> "partly_cloudy_day"
                Sky.PARTLY_NIGHT -> "partly_cloudy_night"
                else -> "cloudy"
            }
        )
    }

    /** Which frame to show at [nowMs] (0 for still pictures). */
    fun frameAt(sky: Sky, nowMs: Long): Int {
        val l = loops[sky] ?: return 0
        return ((nowMs / l.frameMs) % l.frames.size).toInt()
    }

    /** Brightness 0..255 for each of the 625 dots: '#' full, '-' about 35%, anything else off. */
    fun levels(g: CharArray): IntArray = IntArray(g.size) {
        when (g[it]) {
            '#' -> 255
            '-' -> 90
            else -> 0
        }
    }
}

/** The temperature and high/low screens. */
object NumberArt {
    private const val N = 25

    // 5x7 digits for the temperature screen.
    private val BIG: Map<Char, List<String>> = mapOf(
        '0' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
        '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '-' to listOf("000", "000", "000", "111", "000", "000", "000"),
        'o' to listOf("010", "101", "010", "000", "000", "000", "000"),
    )

    // 3x5 digits plus up/down chevrons for the high/low screen.
    private val SMALL: Map<Char, List<String>> = mapOf(
        '0' to listOf("111", "101", "101", "101", "111"),
        '1' to listOf("010", "110", "010", "010", "111"),
        '2' to listOf("111", "001", "111", "100", "111"),
        '3' to listOf("111", "001", "111", "001", "111"),
        '4' to listOf("101", "101", "111", "001", "001"),
        '5' to listOf("111", "100", "111", "001", "111"),
        '6' to listOf("111", "100", "111", "101", "111"),
        '7' to listOf("111", "001", "010", "010", "010"),
        '8' to listOf("111", "101", "111", "101", "111"),
        '9' to listOf("111", "101", "111", "001", "111"),
        '-' to listOf("000", "000", "111", "000", "000"),
        'o' to listOf("11", "11", "00", "00", "00"),
        '^' to listOf("00000", "00100", "01010", "10001", "00000"),
        'v' to listOf("00000", "10001", "01010", "00100", "00000"),
    )

    private fun width(s: String, font: Map<Char, List<String>>): Int =
        s.sumOf { font.getValue(it)[0].length } + (s.length - 1)

    /** Draws [s] starting at [left] (or centred), with each glyph's brightness from [chars]. */
    private fun draw(
        g: CharArray, s: String, font: Map<Char, List<String>>, top: Int,
        left: Int? = null, chars: List<Char>? = null,
    ) {
        var x = left ?: ((N - width(s, font) + 1) / 2)
        s.forEachIndexed { i, c ->
            val glyph = font.getValue(c)
            val ch = chars?.get(i) ?: '#'
            for (r in glyph.indices) for (col in glyph[r].indices) {
                if (glyph[r][col] == '1') {
                    val px = x + col
                    val py = top + r
                    if (px in 0 until N && py in 0 until N && g[py * N + px] != ' ') g[py * N + px] = ch
                }
            }
            x += glyph[0].length + 1
        }
    }

    /** "10°", "-3°"; dim "--" when there's no reading yet. */
    fun temperature(tempC: Int?): CharArray {
        val g = Art.grid("blank")
        if (tempC == null) {
            draw(g, "--", BIG, 9, chars = listOf('-', '-'))
        } else {
            draw(g, "${tempC.coerceIn(-99, 99)}o", BIG, 9)
        }
        return g
    }

    /** Today's high (top) and low (bottom): dim arrows, bright numbers. */
    fun highLow(high: Int?, low: Int?): CharArray {
        val g = Art.grid("blank")
        val top = "^" + (high?.coerceIn(-99, 99)?.let { "${it}o" } ?: "--")
        val bottom = "v" + (low?.coerceIn(-99, 99)?.let { "${it}o" } ?: "--")
        val w = maxOf(width(top, SMALL), width(bottom, SMALL))
        val left = (N - w + 1) / 2
        fun shades(s: String, haveNumber: Boolean) = s.mapIndexed { i, _ -> if (i == 0 || !haveNumber) '-' else '#' }
        draw(g, top, SMALL, 6, left, shades(top, high != null))
        draw(g, bottom, SMALL, 14, left, shades(bottom, low != null))
        return g
    }

    /** The icon screen before any weather has arrived. */
    fun waiting(): CharArray = temperature(null)
}
