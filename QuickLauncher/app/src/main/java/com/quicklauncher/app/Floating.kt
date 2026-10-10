package com.quicklauncher.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

data class FloatWin(val pkg: String, val label: String, val groupId: String? = null)

/** One window of a floating group; l/t/r/b are fractions (0..1) of the screen. */
@Serializable
data class FloatPane(val pkg: String, val l: Float, val t: Float, val r: Float, val b: Float)

/** A floating shortcut: 1-3 apps, side by side ("h") or stacked ("v"). */
@Serializable
data class FloatCfg(val dir: String = "h", val panes: List<FloatPane> = emptyList())

/** Floating (freeform) app windows: which ones are open, where they go and how big they are. */
object Floating {
    val open = ArrayList<FloatWin>()
    var onChange: () -> Unit = {}
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val main = Handler(Looper.getMainLooper())
    private var hackAt = 0L

    val PRESETS = linkedMapOf(
        "small" to "Small", "medium" to "Medium", "large" to "Large",
        "full" to "Full", "left" to "Left half", "right" to "Right half",
    )

    fun parse(v: String): FloatCfg? = runCatching { json.decodeFromString<FloatCfg>(v) }.getOrNull()
    fun encode(c: FloatCfg): String = json.encodeToString(c)

    /** Builds the panes for [pkgs] inside a group of w% x h% of the screen placed at x%/y% of the free space. */
    fun layout(pkgs: List<String>, dir: String, wPct: Float, hPct: Float, xPct: Float, yPct: Float): FloatCfg {
        val w = wPct / 100f; val h = hPct / 100f
        val l = (1f - w) * xPct / 100f; val t = (1f - h) * yPct / 100f
        val n = max(1, pkgs.size)
        return FloatCfg(dir, pkgs.mapIndexed { i, p ->
            if (dir == "h") FloatPane(p, l + w * i / n, t, l + w * (i + 1) / n, t + h)
            else FloatPane(p, l, t + h * i / n, l + w, t + h * (i + 1) / n)
        })
    }

    fun paneRect(scr: Rect, p: FloatPane, gap: Int): Rect {
        val l = (scr.width() * p.l).toInt() + gap; val t = (scr.height() * p.t).toInt() + gap
        val r = (scr.width() * p.r).toInt() - gap; val b = (scr.height() * p.b).toInt() - gap
        return Rect(l, t, max(l + 120, r), max(t + 120, b))
    }

    fun add(pkg: String, label: String, groupId: String? = null) {
        open.removeAll { it.pkg == pkg }
        open.add(FloatWin(pkg, label, groupId)); onChange()
    }
    fun remove(pkg: String) { open.removeAll { it.pkg == pkg }; onChange() }

    /** Rectangle for a single app: its remembered spot, else its size preset / the default size, cascaded. */
    fun bounds(scr: Rect, s: Settings, pkg: String, density: Float): Rect {
        s.appBounds[pkg]?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }?.let { f ->
            return paneRect(scr, FloatPane(pkg, f[0], f[1], f[2], f[3]), 0)
        }
        var wp = s.floatW; var hp = s.floatH; var side = ""
        when (val key = s.appSizes[pkg]) {
            "small" -> { wp = 40f; hp = 40f }
            "medium" -> { wp = 60f; hp = 55f }
            "large" -> { wp = 80f; hp = 75f }
            "full" -> { wp = 100f; hp = 92f }
            "left", "right" -> { wp = 50f; hp = 100f; side = key ?: "" }
        }
        val sw = scr.width(); val sh = scr.height()
        val w = (sw * wp / 100f).toInt(); val h = (sh * hp / 100f).toInt()
        if (side == "left") return Rect(0, 0, w, h)
        if (side == "right") return Rect(sw - w, 0, sw, h)
        val idx = max(0, open.indexOfFirst { it.pkg == pkg }.let { if (it < 0) open.size else it })
        val off = ((idx % 6) - 2) * (28 * density).toInt()
        val l = ((sw - w) / 2 + off).coerceIn(0, max(0, sw - w))
        val t = ((sh - h) / 2 + off).coerceIn(0, max(0, sh - h))
        return Rect(l, t, l + w, t + h)
    }

    /** Opens a tiny freeform window first so the system is in freeform mode before the real window opens. */
    private fun primeFreeform(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - hackAt < 6000) return
        hackAt = now
        runCatching {
            val o = ActivityOptions.makeBasic()
            o.setLaunchBounds(Rect(0, 0, 2, 2))
            runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 5) }
            ctx.startActivity(Intent(ctx, FreeformHackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), o.toBundle())
        }
    }

    fun start(ctx: Context, pkg: String, bounds: Rect): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { ctx.toast("App not installed"); return false }
        primeFreeform(ctx)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val go = {
            val o = ActivityOptions.makeBasic()
            o.setLaunchBounds(bounds)
            // 5 = WINDOWING_MODE_FREEFORM (hidden API; ignored when the device doesn't allow it)
            runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 5) }
            try { ctx.startActivity(i, o.toBundle()) } catch (e: Exception) { ctx.toast("Couldn't open floating window") }
        }
        main.postDelayed(go, 160)
        return true
    }
}

/** Invisible 2x2 freeform window; see [Floating.primeFreeform]. */
class FreeformHackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 5000)
    }
}

/** The draggable line between two floating windows. */
class DividerView(ctx: Context, private val horizontalSplit: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    var active = false
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        p.color = if (active) 0xFFB3202B.toInt() else 0xCCFFFFFF.toInt()
        if (horizontalSplit) { val lw = 3 * d; rf.set((w - lw) / 2, 0f, (w + lw) / 2, h) }
        else { val lh = 3 * d; rf.set(0f, (h - lh) / 2, w, (h + lh) / 2) }
        c.drawRoundRect(rf, 2 * d, 2 * d, p)
        // grip in the middle
        p.color = if (active) Color.WHITE else 0xFF202024.toInt()
        val gw = if (horizontalSplit) 12 * d else 36 * d; val gh = if (horizontalSplit) 36 * d else 12 * d
        rf.set((w - gw) / 2, (h - gh) / 2, (w + gw) / 2, (h + gh) / 2)
        c.drawRoundRect(rf, 6 * d, 6 * d, p)
        p.color = if (active) 0xFFB3202B.toInt() else 0xFFECECEC.toInt()
        val iw = if (horizontalSplit) 2 * d else 18 * d; val ih = if (horizontalSplit) 18 * d else 2 * d
        rf.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
        c.drawRoundRect(rf, d, d, p)
    }
}
