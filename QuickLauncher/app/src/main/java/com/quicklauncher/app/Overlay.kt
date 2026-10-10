package com.quicklauncher.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Owns every overlay window: the edge trigger, the full-screen popup layer and the slider panel.
 * Runs inside the accessibility service when enabled (TYPE_ACCESSIBILITY_OVERLAY, no extra permission),
 * otherwise inside [OverlayService] using "Display over other apps".
 */
class OverlayManager(private val ctx: Context, private val winType: Int) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val d get() = ctx.resources.displayMetrics.density
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? = ctx.getSystemService(Vibrator::class.java)

    var state: AppState = Store.state.value
        private set
    var editMode = false
        private set
    private var trigger: TriggerView? = null
    private var trigLp: WindowManager.LayoutParams? = null
    private var layer: LayerView? = null
    private var layerLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val panelClose = Runnable { closePanel() }

    fun start() {
        Store.init(ctx); SysState.init(ctx)
        Floating.onChange = { handler.post { refreshBar() } }
        scope.launch { Store.state.collect { state = it; apply() } }
    }

    fun stop() {
        scope.cancel(); closeLayer(); closePanel(); hideDividers()
        Floating.onChange = {}
        bar?.let { runCatching { wm.removeView(it) } }; bar = null; barLp = null
        trigger?.let { runCatching { wm.removeView(it) } }; trigger = null
    }

    fun onConfigChanged() { apply(); layer?.requestLayout() }

    // ───── geometry ─────
    private fun screen(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return Rect(0, 0, b.width(), b.height())
        }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    private fun dp(v: Float) = (v * d).toInt()

    fun triggerTop(): Float = (trigLp?.y ?: 0).toFloat()
    fun triggerHeightPx(): Int = trigLp?.height ?: 0
    fun triggerCenterY(): Int = (trigLp?.y ?: 0) + (trigLp?.height ?: 0) / 2
    fun layerOpen() = layer != null
    fun hitTrigger(rx: Float, ry: Float): Boolean {
        val lp = trigLp ?: return false
        val sw = screen().width()
        val inX = if (state.settings.isLeft) rx <= lp.width else rx >= sw - lp.width
        return inX && ry >= lp.y && ry <= lp.y + lp.height
    }

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    // ───── trigger ─────
    private fun apply() {
        val s = state.settings
        val landscape = screen().let { it.width() > it.height() }
        if (!s.triggerEnabled || (s.disableInLandscape && landscape)) {
            trigger?.let { runCatching { wm.removeView(it) } }; trigger = null; trigLp = null
        } else {
            val scr = screen()
            val full = dp(s.trigH) >= scr.height() - dp(54f)   // long enough = whole side
            val th = if (full) scr.height() else dp(s.trigH)
            val y = if (full) 0 else (scr.height() * s.trigY / 100f - th / 2f).toInt().coerceIn(dp(36f), max(dp(36f), scr.height() - th - dp(18f)))
            val lp = trigLp ?: WindowManager.LayoutParams(0, 0, winType,
                baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            lp.width = dp(max(s.trigW + 4f, 24f)); lp.height = th; lp.y = y; lp.x = 0
            lp.gravity = Gravity.TOP or (if (s.isLeft) Gravity.START else Gravity.END)
            lp.title = "QuickLauncherTrigger"
            trigLp = lp
            if (trigger == null) {
                trigger = TriggerView(ctx, this)
                runCatching { wm.addView(trigger, lp) }
            } else {
                runCatching { wm.updateViewLayout(trigger, lp) }; trigger?.invalidate()
            }
        }
        layer?.setData(s, state.items, SysState.activeKeys(ctx, state.items))
        refreshBar()
    }

    // ───── floating windows + taskbar strip ─────
    private var bar: android.widget.LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null

    fun openFloating(pkg: String): Boolean {
        val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val ok = Floating.start(ctx, pkg, Floating.bounds(screen(), state.settings, pkg, d))
        if (ok) Floating.add(pkg, label)
        return ok
    }

    private fun gap() = dp(2f)

    private fun rectFor(w: FloatWin): Rect {
        val cfg = w.groupId?.let { id -> state.items.firstOrNull { it.id == id } }?.let { Floating.parse(it.v) }
        val pane = cfg?.panes?.firstOrNull { it.pkg == w.pkg }
        return if (pane != null) Floating.paneRect(screen(), pane, gap()) else Floating.bounds(screen(), state.settings, w.pkg, d)
    }

    private fun focusFloating(w: FloatWin) { Floating.start(ctx, w.pkg, rectFor(w)) }

    /** Opens every app of a floating shortcut at its saved place, then shows the split lines between them. */
    fun openFloatGroup(item: Item): Boolean {
        val cfg = Floating.parse(item.v)
        if (cfg == null || cfg.panes.isEmpty()) { ctx.toast("This floating shortcut has no apps"); return false }
        val scr = screen()
        cfg.panes.forEachIndexed { i, p ->
            val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p.pkg, 0)).toString() }.getOrDefault(p.pkg)
            handler.postDelayed({
                Floating.start(ctx, p.pkg, Floating.paneRect(scr, p, gap()))
                Floating.add(p.pkg, label, item.id)
                if (i == cfg.panes.lastIndex) handler.postDelayed({ showDividers(item.id) }, 500)
            }, i * 420L)
        }
        return true
    }

    // ───── split lines ─────
    private val dividers = ArrayList<DividerView>()
    private var dividerItem: String? = null
    private var dividerDrag = false

    fun hideDividers() {
        dividers.forEach { runCatching { wm.removeView(it) } }; dividers.clear(); dividerItem = null
    }

    fun showDividers(itemId: String) {
        hideDividers()
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        val cfg = Floating.parse(item.v) ?: return
        if (cfg.panes.size < 2) return
        dividerItem = itemId
        val scr = screen(); val thick = dp(22f)
        val hs = cfg.dir == "h"
        for (i in 0 until cfg.panes.size - 1) {
            val a = cfg.panes[i]; val b = cfg.panes[i + 1]
            val v = DividerView(ctx, hs)
            val w = if (hs) thick else ((a.r - a.l).coerceAtLeast(0.1f) * scr.width()).toInt()
            val h = if (hs) ((a.b - a.t) * scr.height()).toInt() else thick
            val lp = WindowManager.LayoutParams(w, h, winType, baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.title = "QuickLauncherDivider"
            if (hs) { lp.x = (a.r * scr.width()).toInt() - thick / 2; lp.y = (a.t * scr.height()).toInt() }
            else { lp.x = (a.l * scr.width()).toInt(); lp.y = (a.b * scr.height()).toInt() - thick / 2 }
            var startRaw = 0f; var startPos = 0
            v.setOnTouchListener { _, e ->
                val cur = Floating.parse(state.items.firstOrNull { it.id == itemId }?.v ?: "") ?: return@setOnTouchListener true
                val pa = cur.panes[i]; val pb = cur.panes[i + 1]
                val lo = if (hs) pa.l + 0.12f * (pb.r - pa.l) else pa.t + 0.12f * (pb.b - pa.t)
                val hi = if (hs) pb.r - 0.12f * (pb.r - pa.l) else pb.b - 0.12f * (pb.b - pa.t)
                val total = if (hs) scr.width() else scr.height()
                fun edgeFrom(raw: Float) = (((startPos + (raw - startRaw)) + thick / 2f) / total).coerceIn(lo, hi)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dividerDrag = true; v.active = true; v.invalidate(); haptic(true)
                        startRaw = if (hs) e.rawX else e.rawY; startPos = if (hs) lp.x else lp.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val edge = edgeFrom(if (hs) e.rawX else e.rawY)
                        if (hs) lp.x = (edge * total).toInt() - thick / 2 else lp.y = (edge * total).toInt() - thick / 2
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dividerDrag = false; v.active = false; v.invalidate()
                        val edge = edgeFrom(if (hs) e.rawX else e.rawY)
                        applyDivider(itemId, i, edge)
                    }
                }
                true
            }
            runCatching { wm.addView(v, lp) }
            dividers.add(v)
        }
    }

    /** Moves the line between pane [i] and [i]+1 to [edge], saves it and re-opens both windows at the new size. */
    private fun applyDivider(itemId: String, i: Int, edge: Float) {
        val item = state.items.firstOrNull { it.id == itemId } ?: return
        val cfg = Floating.parse(item.v) ?: return
        val hs = cfg.dir == "h"
        val panes = cfg.panes.toMutableList()
        val a = panes[i]; val b = panes[i + 1]
        panes[i] = if (hs) a.copy(r = edge) else a.copy(b = edge)
        panes[i + 1] = if (hs) b.copy(l = edge) else b.copy(t = edge)
        val nc = cfg.copy(panes = panes)
        Store.upsert(item.copy(v = Floating.encode(nc)))
        val scr = screen()
        handler.post {
            Floating.start(ctx, panes[i].pkg, Floating.paneRect(scr, panes[i], gap()))
            handler.postDelayed({ Floating.start(ctx, panes[i + 1].pkg, Floating.paneRect(scr, panes[i + 1], gap())) }, 250)
            handler.postDelayed({ showDividers(itemId) }, 700)
        }
    }

    /** Called (debounced) by the accessibility service: remembers where the user dragged / resized floating windows. */
    fun trackWindows(list: List<android.view.accessibility.AccessibilityWindowInfo>) {
        if (Floating.open.isEmpty() || dividerDrag) return
        val scr = screen()
        for (w in list) {
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            val fw = Floating.open.firstOrNull { it.pkg == pkg } ?: continue
            val r = Rect(); w.getBoundsInScreen(r)
            if (r.width() >= scr.width() * 0.97f && r.height() >= scr.height() * 0.9f) continue   // fullscreen: not a floating spot
            val l = r.left.toFloat() / scr.width(); val t = r.top.toFloat() / scr.height()
            val rr = r.right.toFloat() / scr.width(); val bb = r.bottom.toFloat() / scr.height()
            val gap = gap().toFloat()
            val gx = gap / scr.width(); val gy = gap / scr.height()
            val gid = fw.groupId
            if (gid != null) {
                val item = state.items.firstOrNull { it.id == gid } ?: continue
                val cfg = Floating.parse(item.v) ?: continue
                val idx = cfg.panes.indexOfFirst { it.pkg == pkg }
                if (idx < 0) continue
                val old = cfg.panes[idx]
                val np = FloatPane(pkg, l - gx, t - gy, rr + gx, bb + gy)
                if (kotlin.math.abs(np.l - old.l) + kotlin.math.abs(np.t - old.t) + kotlin.math.abs(np.r - old.r) + kotlin.math.abs(np.b - old.b) > 0.03f) {
                    val panes = cfg.panes.toMutableList(); panes[idx] = np
                    Store.upsert(item.copy(v = Floating.encode(cfg.copy(panes = panes))))
                    if (dividerItem == gid) handler.postDelayed({ showDividers(gid) }, 100)
                }
            } else {
                val key = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, l, t, rr, bb)
                val old = state.settings.appBounds[pkg]?.split(",")?.mapNotNull { it.toFloatOrNull() }
                val diff = if (old != null && old.size == 4) kotlin.math.abs(old[0] - l) + kotlin.math.abs(old[1] - t) + kotlin.math.abs(old[2] - rr) + kotlin.math.abs(old[3] - bb) else 1f
                if (diff > 0.03f) Store.updateSettings { it.copy(appBounds = it.appBounds + (pkg to key)) }
            }
        }
    }

    fun refreshBar() {
        val s = state.settings
        if (dividerItem != null && Floating.open.none { it.groupId == dividerItem }) hideDividers()
        if (!s.floatBar || Floating.open.isEmpty()) {
            bar?.let { runCatching { wm.removeView(it) } }; bar = null; barLp = null; return
        }
        val ico = dp(s.floatBarIcon)
        val lv = bar ?: android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xE6101014.toInt()); cornerRadius = 16 * d }
            setPadding(dp(5f), dp(6f), dp(5f), dp(6f))
        }.also { bar = it }
        lv.removeAllViews()
        for (w in Floating.open.toList()) {
            val iv = android.widget.ImageView(ctx)
            runCatching { iv.setImageDrawable(ctx.packageManager.getApplicationIcon(w.pkg)) }
            iv.setOnClickListener { haptic(true); focusFloating(w) }
            iv.setOnLongClickListener { haptic(false); showBarMenu(w); true }
            lv.addView(iv, android.widget.LinearLayout.LayoutParams(ico, ico).apply { setMargins(0, dp(3f), 0, dp(3f)) })
        }
        val lp = barLp ?: WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            winType, baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).also { barLp = it }
        lp.gravity = Gravity.CENTER_VERTICAL or (if (s.isLeft) Gravity.END else Gravity.START)
        lp.title = "QuickLauncherTaskbar"
        if (lv.parent == null) runCatching { wm.addView(lv, lp) } else runCatching { wm.updateViewLayout(lv, lp) }
    }

    private fun showBarMenu(w: FloatWin) {
        val scrim = android.widget.FrameLayout(ctx)
        scrim.setBackgroundColor(0x33000000)
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            winType, baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        lp.title = "QuickLauncherTaskbarMenu"
        val close = { runCatching { wm.removeView(scrim) }; Unit }
        scrim.setOnClickListener { close() }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply { setColor(0xF5121216.toInt()); cornerRadius = 14 * d; setStroke(dp(1f), 0x1FFFFFFF) }
        }
        fun row(label: String, color: Int, click: () -> Unit) {
            box.addView(android.widget.TextView(ctx).apply {
                text = label; setTextColor(color); textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(14f), dp(9f), dp(18f), dp(9f))
                setOnClickListener { close(); click() }
            })
        }
        row(w.label, 0x99FFFFFF.toInt()) {}
        if (w.groupId == null) {
            for ((k, label) in Floating.PRESETS) row("Open $label", 0xFFECECEC.toInt()) {
                Store.updateSettings { it.copy(appSizes = it.appSizes + (w.pkg to k), appBounds = it.appBounds - w.pkg) }
                handler.postDelayed({ focusFloating(w) }, 80)
            }
        } else {
            row("Re-arrange the group", 0xFFECECEC.toInt()) {
                Floating.open.filter { it.groupId == w.groupId }.forEachIndexed { i, fw -> handler.postDelayed({ focusFloating(fw) }, i * 300L) }
                handler.postDelayed({ showDividers(w.groupId) }, 1000)
            }
        }
        row("Remove from taskbar", 0xFFFF7B72.toInt()) { Floating.remove(w.pkg) }
        val blp = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or (if (state.settings.isLeft) Gravity.END else Gravity.START))
        if (state.settings.isLeft) blp.rightMargin = dp(64f) else blp.leftMargin = dp(64f)
        scrim.addView(box, blp)
        runCatching { wm.addView(scrim, lp) }
    }

    fun moveTrigger(topPx: Float) {
        val scr = screen(); val th = dp(state.settings.trigH)
        if (th >= scr.height() - dp(54f)) return
        val y = topPx.toInt().coerceIn(dp(36f), max(dp(36f), scr.height() - th - dp(18f)))
        val pct = ((y + th / 2f) / scr.height() * 100f).coerceIn(10f, 90f)
        Store.updateSettings { it.copy(trigY = pct) }
    }

    // ───── gesture ─────
    fun gestureStart(rx: Float, ry: Float) {
        haptic(true)
        if (layer == null) openLayer(false)
        layer?.pointer(rx, ry)
    }

    fun gestureMove(rx: Float, ry: Float) { layer?.pointer(rx, ry) }

    fun gestureEnd(tap: Boolean) {
        val l = layer ?: return
        if (tap) { enterTouchMode(); l.endGesture(); return }
        val btn = l.buttonUnderPointer()
        val item = l.hoverItem()
        val fold = l.sliderHoverItem()
        if (l.treeOpen() && btn == null) {
            // released over a file opens it; over the panel otherwise keeps the tree open for tapping
            val inside = l.releaseTree()
            l.endGesture()
            if (layer === l) { if (inside) enterTouchMode() else closeLayer() }
            return
        }
        l.endGesture()
        when {
            btn === l.btnSet -> openSettings()
            btn === l.btnEdit -> { enterTouchMode(); toggleEdit() }
            fold != null -> { haptic(false); enterTouchMode(); l.openTree(fold, false) }
            item != null -> { haptic(false); launch(item, false) }
            else -> closeLayer()
        }
    }

    fun gestureCancel() { layer?.endGesture(); if (layer?.persistent != true) closeLayer() }

    // ───── layer ─────
    private fun openLayer(touchable: Boolean) {
        closePanel()
        val lv = LayerView(ctx, this)
        val flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            winType, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.title = "QuickLauncherPopup"
        lv.persistent = touchable
        lv.setData(state.settings, state.items, SysState.activeKeys(ctx, state.items))
        layer = lv; layerLp = lp
        if (runCatching { wm.addView(lv, lp) }.isFailure) { layer = null; layerLp = null }
        trigger?.active = true
    }

    private fun enterTouchMode() {
        val l = layer ?: return; val lp = layerLp ?: return
        l.persistent = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(l, lp) }
    }

    /** The popup window ignores the keyboard by default; it becomes focusable only while the folder search box is open. */
    fun setLayerFocusable(on: Boolean) {
        val l = layer ?: return; val lp = layerLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { wm.updateViewLayout(l, lp) }
    }

    fun closeLayer() {
        layer?.let { runCatching { wm.removeView(it) } }
        layer = null; layerLp = null; editMode = false
        trigger?.active = false; trigger?.invalidate()
    }

    fun toggleEdit() {
        editMode = !editMode
        layer?.setEdit(editMode)
        trigger?.invalidate()
    }

    fun openSettings() {
        closeLayer()
        ctx.startSafe(Intent(ctx, MainActivity::class.java).putExtra("from_pop", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    fun openEditor(item: Item?, slot: Int) {
        closeLayer()
        ctx.startSafe(Intent(ctx, EditorActivity::class.java).putExtra("id", item?.id).putExtra("slot", slot))
    }

    /** Reopens the popup in touch mode (used by the settings "Done" button). */
    fun reopenTouch() { if (layer == null) { openLayer(true) } }

    fun launch(item: Item, fromTouch: Boolean) {
        val sys = item.t == "system"
        val def = if (sys) SYS[item.v] else null
        val keepOpen = fromTouch && def?.toggle == true
        if (keepOpen) {
            Launcher.launch(ctx, item, this)
            handler.postDelayed({ layer?.setActive(SysState.activeKeys(ctx, state.items)) }, 350)
            return
        }
        val delay = if (sys) 160L else 0L
        if (sys) {
            closeLayer()
            handler.postDelayed({ Launcher.launch(ctx, item, this) }, delay)
        } else {
            Launcher.launch(ctx, item, this)   // launch first: our visible overlay allows background starts
            if (!fromTouch || state.settings.autoClose) closeLayer()
        }
    }

    // ───── folder tree actions ─────
    fun openTreeFile(folder: Item, e: FsEntry) {
        haptic(false)
        Fs.openFile(ctx, folder.v, e)   // launch first: our visible overlay allows background starts
        closeLayer()
    }

    fun openTreeFolder(folder: Item, key: String?) {
        haptic(false)
        Launcher.folderAt(ctx, folder.v, key)
        closeLayer()
    }

    fun grantFileAccess() {
        closeLayer()
        Fs.openAccessSettings(ctx)
    }

    fun haptic(tick: Boolean) {
        if (!state.settings.haptic) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29)
                v.vibrate(VibrationEffect.createPredefined(if (tick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK))
            else v.vibrate(VibrationEffect.createOneShot(if (tick) 10L else 24L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ───── slider panel (volume / brightness) ─────
    fun showSlider(title: String, iconCls: String, value: Int, onChange: (Int) -> Unit) {
        closePanel()
        val c = ctx
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(10f), dp(22f), dp(34f))
            background = GradientDrawable().apply {
                setColor(0xFF0D0D0D.toInt())
                val r = 28 * d; cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val grab = View(c).apply { background = GradientDrawable().apply { setColor(0x38FFFFFF); cornerRadius = 2 * d } }
        box.addView(grab, LinearLayout.LayoutParams(dp(38f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14f) })
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val g = Fa.glyph(iconCls)
        val ic = TextView(c).apply {
            text = g?.text ?: ""; typeface = Fa.typeface(c, g?.brands == true); setTextColor(0xFFECECEC.toInt()); textSize = 15f
        }
        val tt = TextView(c).apply { text = title; setTextColor(0xFFECECEC.toInt()); textSize = 14f; setPadding(dp(10f), 0, 0, 0) }
        val pct = TextView(c).apply { text = "$value%"; setTextColor(0xFFFFBF00.toInt()); textSize = 14f; gravity = Gravity.END }
        row.addView(ic); row.addView(tt); row.addView(pct, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        val sb = SeekBar(c).apply {
            max = 100; progress = value
            progressTintList = ColorStateList.valueOf(0xFFFFBF00.toInt()); thumbTintList = ColorStateList.valueOf(0xFFFFBF00.toInt())
            setPadding(dp(8f), dp(14f), dp(8f), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    pct.text = "$p%"; onChange(p)
                    handler.removeCallbacks(panelClose); handler.postDelayed(panelClose, 4000)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false }
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM
        if (runCatching { wm.addView(box, lp) }.isSuccess) {
            panel = box; handler.postDelayed(panelClose, 4000)
        }
    }

    fun closePanel() {
        handler.removeCallbacks(panelClose)
        panel?.let { runCatching { wm.removeView(it) } }; panel = null
    }
}

/** Registry so the settings UI can talk to whichever host currently owns the overlay. */
object Host {
    @Volatile var manager: OverlayManager? = null

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = "${ctx.packageName}/${LauncherAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(me, true) || it.endsWith(".LauncherAccessibilityService") && it.startsWith(ctx.packageName) }
    }

    /** Starts the fallback foreground service when the accessibility service isn't carrying the overlay. */
    fun ensure(ctx: Context) {
        Store.init(ctx)
        if (LauncherAccessibilityService.instance != null) return
        if (!canDrawOverlays(ctx)) return
        if (!Store.state.value.settings.triggerEnabled) return
        runCatching { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) }
    }
}

class LauncherAccessibilityService : AccessibilityService() {
    private var mgr: OverlayManager? = null

    override fun onServiceConnected() {
        instance = this
        stopService(Intent(this, OverlayService::class.java))
        mgr = OverlayManager(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { it.start(); Host.manager = it }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); mgr?.onConfigChanged() }
    private val trackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val track = Runnable { runCatching { mgr?.trackWindows(windows) } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && Floating.open.isNotEmpty()) {
            trackHandler.removeCallbacks(track); trackHandler.postDelayed(track, 700)
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        mgr?.stop(); mgr = null
        if (Host.manager != null) Host.manager = null
        instance = null
        Host.ensure(applicationContext)
        super.onDestroy()
    }

    companion object { @Volatile var instance: LauncherAccessibilityService? = null }
}

class OverlayService : Service() {
    private var mgr: OverlayManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (LauncherAccessibilityService.instance != null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (mgr == null) mgr = OverlayManager(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.start(); Host.manager = it }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ql", "Quick Launcher", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ql").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Quick Launcher is active").setContentText("Swipe from the screen edge").setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); mgr?.onConfigChanged() }

    override fun onDestroy() {
        mgr?.stop(); mgr = null
        if (LauncherAccessibilityService.instance == null) Host.manager = null
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Host.ensure(ctx.applicationContext) }
}
