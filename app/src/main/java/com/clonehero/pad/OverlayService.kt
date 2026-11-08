package com.clonehero.pad

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val items = LinkedHashMap<String, Pair<KeyButton, WindowManager.LayoutParams>>()
    private var gear: GearView? = null
    private var panel: View? = null
    private var status: TextView? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var gridOpen = false
    private var pick: CatalogKey = CATALOG.first()
    private var padMode = false
    private var profOpen = false
    private var enterAfter = true
    private var editMode = false
    private var built = false
    private var hidden = false
    private var panelSlideSeek: SeekBar? = null
    // geser antar tombol A S J K L
    private val slideIds = setOf("A", "S", "J", "K", "L")
    private val slideHeld = HashMap<Int, String>()
    private val slideCount = HashMap<String, Int>()
    private val slideLoc = IntArray(2)
    private val stickPrev = HashMap<String, Int>()
    private var serverSeen = false
    private val hidListener: () -> Unit = {
        updateStatus()
        // server dimatikan (tombol Matikan server / proses dihentikan) -> overlay ikut berhenti
        if (Injector.connected) serverSeen = true
        else if (serverSeen) {
            Toast.makeText(this, getString(R.string.toast_server_dead), Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        // tiap tombol adalah jendela sendiri; tanpa flag ini jari ke-2 di jendela lain diabaikan sistem
        WindowManager.LayoutParams.FLAG_SPLIT_TOUCH

    override fun onBind(i: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun sizePx() = (dp(64) * Prefs.sizePct(this) / 100f).toInt()

    private fun lp(w: Int, h: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        Injector.setRealMap(Prefs.realMapText(this))
        Injector.connectAsync()
        Prefs.ensureGamepadProfile(this)
        if (!built) build()
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ch", "Overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "ch")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.notif_active))
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true).build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onCreate() {
        Injector.init(this)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun createKey(k: KeyDef) {
        val b = KeyButton(this, k)
        val p = lp(10, 10, 0, 0)
        b.alpha = groupOpacity(k.id) / 100f
        b.editMode = editMode
        b.colorOn = Prefs.colorOn(this)
        b.onKey = { down ->
            val m = if (k.pad) Prefs.padMap(this)[k.label] else null   // pemetaan gamepad -> keyboard (menu utama)
            if (m != null) Injector.send(m, down)
            else {
                if (k.pad) Injector.sendPad(k.code, down) else Injector.send(k.code, down)
                if (k.trig >= 0) Injector.sendAxis(k.trig, if (down) 1f else 0f, 0f)
            }
        }
        b.stickGain = Prefs.stickGain(this) / 100f
        b.stickSquare = true   // selalu gerbang persegi (seperti stik asli)
        if (k.stick >= 0) b.onStick = { x, y ->
            val map = Prefs.padMap(this)
            val pre = if (k.stick == 0) "LS" else "RS"
            val codes = listOf("UP", "RIGHT", "DOWN", "LEFT").map { map["${pre}_$it"] }
            if (codes.all { it == null }) Injector.sendAxis(k.stick, x, y)
            else {   // dipetakan ke keyboard: stik jadi 4 arah digital
                val mask = (if (y < -0.5f) 1 else 0) or (if (x > 0.5f) 2 else 0) or
                    (if (y > 0.5f) 4 else 0) or (if (x < -0.5f) 8 else 0)
                val d = (stickPrev[k.id] ?: 0) xor mask
                stickPrev[k.id] = mask
                for (i in 0..3) if (d and (1 shl i) != 0) codes[i]?.let { Injector.send(it, mask and (1 shl i) != 0) }
            }
        }
        if (k.dpad) b.onDpad = { old, new ->
            val d = old xor new
            val map = Prefs.padMap(this)
            fun dir(bit: Int, code: Int, name: String) {
                if (d and bit == 0) return
                val m = map[name]
                if (m != null) Injector.send(m, new and bit != 0) else Injector.sendPad(code, new and bit != 0)
            }
            dir(1, 19, "DPAD_UP"); dir(2, 22, "DPAD_RIGHT"); dir(4, 20, "DPAD_DOWN"); dir(8, 21, "DPAD_LEFT")
        }
        b.onSelect = { selectKey(k.id) }
        if (k.id in slideIds && Prefs.slideOn(this)) b.onPoint = { pid, x, y, ph -> slideTouch(pid, x, y, ph) }
        if (hidden) b.visibility = View.GONE
        b.onDrag = { dx, dy ->
            val (w, h) = screen()
            p.x = (p.x + dx).coerceIn(0, max(0, w - p.width))
            p.y = (p.y + dy).coerceIn(0, max(0, h - p.height))
            wm.updateViewLayout(b, p)
            if (selectedId == k.id) placeSizeBar()
        }
        b.onDragEnd = { Prefs.setPos(this, screen().let { it.first > it.second }, k.id, p.x, p.y) }
        wm.addView(b, p)
        items[k.id] = b to p
    }

    private fun slideTouch(pid: Int, x: Float, y: Float, phase: Int) {
        val cur = slideHeld[pid]
        if (phase == 2) { slideHeld.remove(pid); cur?.let { slideRelease(it) }; return }
        // hanya tombol yang sedang disentuh jari ini yang aktif; tombol sebelumnya dilepas
        var under: String? = null
        for (id in slideIds) {
            val b = items[id]?.first ?: continue
            b.getLocationOnScreen(slideLoc)
            if (x >= slideLoc[0] && x < slideLoc[0] + b.width && y >= slideLoc[1] && y < slideLoc[1] + b.height) { under = id; break }
        }
        if (under == cur) return
        // tekan yang baru dulu, baru lepas yang lama (tidak ada celah antar tombol)
        if (under != null) {
            slideHeld[pid] = under
            val n = (slideCount[under] ?: 0) + 1
            slideCount[under] = n
            if (n == 1) items[under]?.first?.let { it.setDown(true); Injector.send(it.def.code, true) }
        } else slideHeld.remove(pid)
        cur?.let { slideRelease(it) }
    }

    private fun slideRelease(id: String) {
        val n = (slideCount[id] ?: 0) - 1
        if (n > 0) { slideCount[id] = n; return }
        slideCount.remove(id)
        items[id]?.first?.let { it.setDown(false); Injector.send(it.def.code, false) }
    }

    private fun slideReset() {
        slideHeld.clear(); slideCount.clear(); stickPrev.clear()
        slideIds.forEach { items[it]?.first?.setDown(false) }
    }

    private fun setSlideEnabled(on: Boolean) {
        Prefs.setSlideOn(this, on)
        Injector.releaseAll(); slideReset()
        slideIds.forEach { id ->
            items[id]?.first?.onPoint = if (on) { pid, x, y, ph -> slideTouch(pid, x, y, ph) } else null
        }
    }

    private fun toggleHidden() {
        hidden = !hidden
        Injector.releaseAll(); slideReset()
        if (hidden) { hidePanel(); hideSizeBar() }
        items.values.forEach { it.first.visibility = if (hidden) View.GONE else View.VISIBLE }
        gear?.alpha = if (hidden) 0.4f else 0.75f
        Toast.makeText(this, if (hidden) getString(R.string.toast_hidden) else getString(R.string.toast_shown), Toast.LENGTH_SHORT).show()
    }

    private fun build() {
        built = true
        for (k in allKeys(this)) createKey(k)
        applyAll(true)

        addGear()
        Injector.addListener(hidListener)
    }

    private fun addGear() {
        val g = GearView(this)
        g.alpha = if (hidden) 0.4f else 0.75f
        g.active = editMode
        g.setOnClickListener { if (panel == null) showPanel() else hidePanel() }
        g.setOnLongClickListener { toggleHidden(); true }
        wm.addView(g, lp(dp(36), dp(36), dp(12), dp(12)))
        gear = g
    }

    /** Membangun ulang semua tombol dari profil yang aktif. */
    private fun reloadProfile() {
        Injector.releaseAll(); slideReset(); hideSizeBar()
        items.values.forEach { try { wm.removeView(it.first) } catch (_: Exception) {} }
        items.clear()
        for (k in allKeys(this)) createKey(k)
        applyAll(true)
        gear?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        addGear()   // gear dipasang ulang agar tetap di atas tombol
    }

    private fun switchProfile(id: String) {
        Prefs.setActive(this, id)
        reloadProfile(); rebuildPanel()
    }

    private fun defaults(s: Int, w: Int, h: Int): Map<String, Pair<Int, Int>> {
        val m = HashMap<String, Pair<Int, Int>>()
        val gap = (s * 1.15f).toInt()
        val land = w > h
        val fy = if (land) h - (s * 1.6f).toInt() else h - (s * 5f).toInt()
        listOf("A", "S", "J", "K", "L").forEachIndexed { i, id -> m[id] = (w / 2 - s / 2 + (i - 2) * gap) to fy }
        val rowY = h - (s * 1.6f).toInt()
        val cx = (s * 1.8f).toInt()
        m["UP"] = (cx - s / 2) to (rowY - gap)
        m["LEFT"] = (cx - gap - s / 2) to rowY
        m["DOWN"] = (cx - s / 2) to rowY
        m["RIGHT"] = (cx + gap - s / 2) to rowY
        m["SPACE"] = (w - (s * 2.5f).toInt()) to rowY
        val topY = h - (s * 2.8f).toInt()
        val hx = w - (s * 1.3f).toInt()
        val ex = hx - (s * 1.55f).toInt()
        m["H"] = hx to topY
        m["ENTER"] = ex to topY
        m["TAB"] = (ex - (s * 1.55f).toInt()) to topY
        m["ESC"] = (ex - (s * 1.55f).toInt()) to rowY

        // ---- tata letak profil "Gamepad" (mirip controller Xbox) ----
        fun u(f: Float) = (s * f).toInt()
        val y0 = u(0.9f)
        m["g_LT"] = u(0.5f) to y0
        m["g_LB"] = u(0.5f) to (y0 + u(0.9f))
        m["g_RT"] = (w - u(2.2f)) to y0
        m["g_RB"] = (w - u(2.2f)) to (y0 + u(0.9f))
        m["g_SELECT"] = (w / 2 - u(2.0f)) to y0
        m["g_GUIDE"] = (w / 2 - u(0.65f)) to y0
        m["g_START"] = (w / 2 + u(0.7f)) to y0
        m["g_ANALOG KIRI"] = u(0.7f) to (y0 + u(2.0f))
        m["g_DPAD"] = u(3.0f) to (h - u(2.55f))
        val abx = w - u(2.0f); val aby = y0 + u(3.3f)
        m["g_Y"] = (abx - u(0.5f)) to (aby - u(1.5f))
        m["g_A"] = (abx - u(0.5f)) to (aby + u(0.5f))
        m["g_X"] = (abx - u(1.5f)) to (aby - u(0.5f))
        m["g_B"] = (abx + u(0.5f)) to (aby - u(0.5f))
        m["g_ANALOG KANAN"] = (w - u(6.0f)) to (h - u(2.4f))
        m["g_L3"] = u(0.5f) to (h - u(1.3f))
        m["g_R3"] = (w - u(1.4f)) to (h - u(1.2f))
        return m
    }

    private fun applyAll(useSaved: Boolean) {
        val (w, h) = screen()
        val s = sizePx()
        val land = w > h
        val def = defaults(s, w, h)
        val customIds = Prefs.customKeys(this).map { it.id }
        val gap = (s * 1.15f).toInt()
        for ((id, bp) in items) {
            val (b, p) = bp
            val sc = Prefs.keyScale(this, id) / 100f
            p.width = (s * b.def.wMul * sc).toInt(); p.height = (s * b.def.hMul * sc).toInt()
            val fallback = def[id] ?: customIds.indexOf(id).coerceAtLeast(0).let { i ->
                (s / 2 + (i % 4) * (s * 2.5f).toInt()) to ((h * 0.3f).toInt() + (i / 4) * gap)
            }
            val pos = (if (useSaved) Prefs.getPos(this, land, id) else null) ?: fallback
            p.x = pos.first.coerceIn(0, max(0, w - p.width))
            p.y = pos.second.coerceIn(0, max(0, h - p.height))
            wm.updateViewLayout(b, p)
        }
        placeSizeBar()
    }

    // ---------- Slider ukuran per tombol (muncul di atas tombol saat mode Atur posisi) ----------
    private var selectedId: String? = null
    private var sizeBar: View? = null
    private var sizeLp: WindowManager.LayoutParams? = null
    private var sizeSeek: SeekBar? = null
    private var sizeLabel: TextView? = null

    private var selectStamp = 0
    private var opSeek: SeekBar? = null
    private var opLabel: TextView? = null

    /** Opacity (persen) tombol: nilai khusus tombol bila ada, jika tidak ikut slider umum / ASJKL. */
    private fun groupOpacity(id: String): Int =
        Prefs.keyOpacity(this, id) ?: if (id in slideIds) Prefs.slideOpacity(this) else Prefs.opacity(this)

    private fun selectKey(id: String) {
        if (!editMode) return
        selectedId = id; selectStamp++
        items.forEach { (k, v) -> v.first.picked = (k == id) }
        if (sizeBar == null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply { setColor(0xF0202124.toInt()); cornerRadius = dp(12).toFloat() }
            }
            // satu baris = label + slider; dua baris (Ukuran, Opacity) dalam satu kotak
            fun addLine(max: Int, onChange: (Int, Boolean, TextView) -> Unit): Pair<TextView, SeekBar> {
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val t = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }
                val sb = SeekBar(this).apply {
                    this.max = max
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) = onChange(p, user, t)
                        override fun onStartTrackingTouch(s: SeekBar) {}
                        override fun onStopTrackingTouch(s: SeekBar) {}
                    })
                }
                r.addView(t, LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT))
                r.addView(sb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(r)
                return t to sb
            }
            val (t1, s1) = addLine(270) { p, user, t ->
                t.text = getString(R.string.size_pct, p + 30)
                val sid = selectedId
                if (user && sid != null) { Prefs.setKeyScale(this@OverlayService, sid, p + 30); resizeKey(sid) }
            }
            val (t2, s2) = addLine(80) { p, user, t ->
                t.text = getString(R.string.opacity_pct, p + 20)
                val sid = selectedId
                if (user && sid != null) {
                    Prefs.setKeyOpacity(this@OverlayService, sid, p + 20)
                    items[sid]?.first?.alpha = (p + 20) / 100f
                }
            }
            val p = lp(dp(260), dp(84), 0, 0)
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            // sentuhan di luar slider: sembunyikan, kecuali yang menyentuh tombol (memicu selectKey -> stempel berubah)
            row.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    val st = selectStamp
                    Handler(Looper.getMainLooper()).postDelayed({ if (selectStamp == st) hideSizeBar() }, 80)
                }
                false
            }
            wm.addView(row, p)
            sizeBar = row; sizeLp = p; sizeSeek = s1; sizeLabel = t1; opSeek = s2; opLabel = t2
        }
        val pct = Prefs.keyScale(this, id)
        sizeSeek?.progress = pct - 30
        sizeLabel?.text = getString(R.string.size_pct, pct)
        val op = groupOpacity(id).coerceIn(20, 100)
        opSeek?.progress = op - 20
        opLabel?.text = getString(R.string.opacity_pct, op)
        placeSizeBar()
    }

    private fun placeSizeBar() {
        val id = selectedId ?: return
        val p = items[id]?.second ?: return
        val bar = sizeBar ?: return
        val bp = sizeLp ?: return
        val (w, h) = screen()
        bp.x = (p.x + p.width / 2 - bp.width / 2).coerceIn(0, max(0, w - bp.width))
        val above = p.y - bp.height - dp(6)
        bp.y = if (above >= 0) above else min(p.y + p.height + dp(6), max(0, h - bp.height))
        wm.updateViewLayout(bar, bp)
    }

    private fun hideSizeBar() {
        sizeBar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        sizeBar = null; sizeLp = null; sizeSeek = null; sizeLabel = null; opSeek = null; opLabel = null; selectedId = null
        items.values.forEach { it.first.picked = false }
    }

    private fun resizeKey(id: String) {
        val (b, p) = items[id] ?: return
        val (w, h) = screen()
        val sc = Prefs.keyScale(this, id) / 100f
        val cx = p.x + p.width / 2; val cy = p.y + p.height / 2
        p.width = (sizePx() * b.def.wMul * sc).toInt(); p.height = (sizePx() * b.def.hMul * sc).toInt()
        p.x = (cx - p.width / 2).coerceIn(0, max(0, w - p.width))
        p.y = (cy - p.height / 2).coerceIn(0, max(0, h - p.height))
        wm.updateViewLayout(b, p)
        Prefs.setPos(this, w > h, id, p.x, p.y)
        placeSizeBar()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        Handler(Looper.getMainLooper()).postDelayed({ if (built) applyAll(true) }, 200)
    }

    private fun setEdit(on: Boolean) {
        editMode = on
        gear?.active = on
        if (!on) hideSizeBar()
        gear?.invalidate()
        Injector.releaseAll()
        slideReset()
        items.values.forEach { it.first.editMode = on }
    }

    private fun updateStatus() {
        status?.text = if (Injector.connected) getString(R.string.status_on) else getString(R.string.status_off)
        }

    /** ScrollView yang tingginya dibatasi agar panel tidak melebihi layar. */
    private class MaxHeightScroll(ctx: Context, val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }

    private fun chip(t: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minWidth = dp(48)
        background = GradientDrawable().apply {
            setColor(if (selected) 0xFF1E88E5.toInt() else 0xFF3C4043.toInt()); cornerRadius = dp(8).toFloat()
        }
        setOnClickListener { onClick() }
    }

    /** Jendela panel hanya fokus saat mengetik; selain itu tidak boleh mencuri fokus dari game. */
    private fun focusPanel(on: Boolean) {
        val p = panelLp ?: return
        val v = panel ?: return
        p.flags = if (on) flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else flags
        wm.updateViewLayout(v, p)
    }

    private fun rebuildPanel() {
        val v = panel ?: return
        val y = (v as? ScrollView)?.scrollY ?: 0       // posisi gulir dipertahankan
        try { wm.removeView(v) } catch (_: Exception) {}
        panel = null
        showPanel()
        (panel as? ScrollView)?.let { sv ->
            sv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    sv.scrollTo(0, y)
                }
            })
        }
    }

    private fun addKey(c: CatalogKey) {
        val k = CustomKey("c${System.currentTimeMillis()}", c.name, c.code, c.arrow, c.pad, c.stick, c.trig, c.dpad)
        Prefs.addCustom(this, k)
        createKey(k.toDef()); applyAll(true)
        Toast.makeText(this, getString(R.string.toast_key_added, k.name), Toast.LENGTH_SHORT).show()
        rebuildPanel()
    }

    private fun showPanel() {
        val white = Color.WHITE
        fun label(t: String, sp: Float = 13f) = TextView(this).apply { text = t; setTextColor(white); textSize = sp }
        fun title(t: String) = label(t, 14f).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(4))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        status = label("", 12f).also { it.setOnClickListener { Injector.connectAsync() }; root.addView(it) }
        updateStatus()

        root.addView(Switch(this).apply {
            text = getString(R.string.sw_edit); setTextColor(white); textSize = 13f
            isChecked = editMode
            setOnCheckedChangeListener { _, c -> setEdit(c) }
        })

        root.addView(Switch(this).apply {
            text = getString(R.string.sw_slide); setTextColor(white); textSize = 13f
            isChecked = Prefs.slideOn(this@OverlayService)
            setOnCheckedChangeListener { _, c -> setSlideEnabled(c) }
        })

        root.addView(Switch(this).apply {
            text = getString(R.string.sw_color); setTextColor(white); textSize = 13f
            isChecked = Prefs.colorOn(this@OverlayService)
            setOnCheckedChangeListener { _, c ->
                Prefs.setColorOn(this@OverlayService, c)
                items.values.forEach { it.first.colorOn = c }
            }
        })

        fun seekRow(title: String, min: Int, max: Int, cur: Int, onChange: (Int) -> Unit): SeekBar {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val t = label("$title $cur%")
            val sb = SeekBar(this).apply {
                this.max = max - min; progress = cur - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                        t.text = "$title ${p + min}%"
                        if (user) onChange(p + min)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            row.addView(t, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(sb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
            return sb
        }
        seekRow(getString(R.string.sk_opacity), 20, 100, Prefs.opacity(this)) { v ->
            // slider umum: semua tombol termasuk ASJKL (nilai ASJKL ikut disamakan)
            Prefs.setOpacity(this, v); Prefs.setSlideOpacity(this, v)
            items.forEach { (id, bp) -> if (Prefs.keyOpacity(this, id) == null) bp.first.alpha = v / 100f }
            panelSlideSeek?.progress = v - 20
        }
        panelSlideSeek = seekRow(getString(R.string.sk_opacity_asjkl), 20, 100, Prefs.slideOpacity(this)) { v ->
            Prefs.setSlideOpacity(this, v)
            slideIds.forEach { id -> if (Prefs.keyOpacity(this, id) == null) items[id]?.first?.alpha = v / 100f }
        }
        seekRow(getString(R.string.sk_size), 50, 200, Prefs.sizePct(this)) { v ->
            Prefs.setSizePct(this, v); applyAll(true)
        }
        seekRow(getString(R.string.sk_gain), 100, 250, Prefs.stickGain(this)) { v ->
            Prefs.setStickGain(this, v); items.values.forEach { it.first.stickGain = v / 100f }
        }

        // ---------- Tambah tombol ----------
        root.addView(title(getString(R.string.title_add_key)))
        val catalog = if (padMode) PAD_CATALOG else CATALOG
        val modeRow = LinearLayout(this)
        val mlp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), dp(4)) } }
        modeRow.addView(chip("Keyboard", !padMode) {
            if (padMode) { padMode = false; gridOpen = false; rebuildPanel() }
        }, mlp())
        modeRow.addView(chip("Gamepad", padMode) {
            if (!padMode) { padMode = true; gridOpen = false; rebuildPanel() }
        }, mlp())
        root.addView(modeRow)
        root.addView(chip(getString(R.string.choose_key, if (gridOpen) "▲" else "▼"), false) {
            gridOpen = !gridOpen; rebuildPanel()
        })
        if (gridOpen) {
            val grid = GridLayout(this).apply { columnCount = if (padMode) 3 else 5 }
            for (c in catalog) {
                grid.addView(chip(c.name, false) { addKey(c) }, GridLayout.LayoutParams().apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    width = 0
                })
            }
            root.addView(grid)
        }

        val customs = Prefs.customKeys(this)
        if (customs.isNotEmpty()) {
            root.addView(label(getString(R.string.lbl_added_keys), 12f).apply { setPadding(0, dp(8), 0, dp(2)) })
            val row = GridLayout(this).apply { columnCount = 4 }
            for (k in customs) {
                row.addView(chip("✕ ${k.name}", false) {
                    if (selectedId == k.id) hideSizeBar()
                    items.remove(k.id)?.let { try { wm.removeView(it.first) } catch (_: Exception) {} }
                    Prefs.removeCustom(this, k.id)
                    rebuildPanel()
                }, GridLayout.LayoutParams().apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            root.addView(row)
        }

        // ---------- Input teks ----------
        root.addView(chip(getString(R.string.chip_text_input), true) { hidePanel(); showTextDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---------- Profil kontrol ----------
        root.addView(title(getString(R.string.title_profiles)))
        val activeId = Prefs.activeId(this)
        val activeName = Prefs.profiles(this).firstOrNull { it.first == activeId }?.second ?: ""
        root.addView(chip(getString(R.string.profile_sel, activeName, if (profOpen) "▲" else "▼"), false) {
            profOpen = !profOpen; rebuildPanel()
        })
        if (profOpen) {
            val pg = GridLayout(this).apply { columnCount = 3 }
            for ((id, name) in Prefs.profiles(this)) {
                pg.addView(chip(name, id == activeId) { profOpen = false; if (id != activeId) switchProfile(id) else rebuildPanel() },
                    GridLayout.LayoutParams().apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); width = 0
                    })
            }
            root.addView(pg)
        }
        val pn = EditText(this).apply {
            hint = getString(R.string.hint_profile)
            setTextColor(white); setHintTextColor(0x99FFFFFF.toInt()); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    focusPanel(true); v.requestFocus()
                    v.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
                }
                false
            }
        }
        fun pnHide() {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pn.windowToken, 0)
            pn.clearFocus(); focusPanel(false)
        }
        pn.setOnEditorActionListener { _, _, _ -> pnHide(); true }
        root.addView(pn)
        fun nameOrWarn(): String? {
            val n = pn.text.toString().trim()
            if (n.isEmpty()) Toast.makeText(this, getString(R.string.toast_profile_name), Toast.LENGTH_SHORT).show()
            return n.ifEmpty { null }
        }
        val pr = GridLayout(this).apply { columnCount = 2 }
        fun act(t: String, f: () -> Unit) = pr.addView(chip(t, false) { f() }, GridLayout.LayoutParams().apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); width = 0
        })
        act(getString(R.string.act_copy_profile)) {
            nameOrWarn()?.let { pnHide(); Prefs.createProfile(this, it, true); reloadProfile(); rebuildPanel() }
        }
        act(getString(R.string.act_empty_profile)) {
            nameOrWarn()?.let { pnHide(); Prefs.createProfile(this, it, false); reloadProfile(); rebuildPanel() }
        }
        act(getString(R.string.act_rename_profile)) {
            nameOrWarn()?.let { pnHide(); Prefs.renameProfile(this, activeId, it); rebuildPanel() }
        }
        if (activeId != "default") act(getString(R.string.act_delete_profile)) {
            pnHide(); Prefs.deleteProfile(this, activeId); reloadProfile(); rebuildPanel()
        }
        root.addView(pr)

        // ---------- Tombol bawah ----------
        val btns = LinearLayout(this)
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; minHeight = 0; minimumHeight = dp(40)
            setOnClickListener { onClick() }
        }
        val lpb = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        btns.addView(btn(getString(R.string.btn_reset_pos)) {
            Prefs.clearPos(this, screen().let { it.first > it.second }); applyAll(false)
        }, lpb)
        btns.addView(btn(getString(R.string.done)) { hidePanel() }, lpb)
        btns.addView(btn(getString(R.string.btn_exit)) { stopSelf() }, lpb)
        root.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val (w, h) = screen()
        val sv = MaxHeightScroll(this, h - dp(72)).apply {
            background = GradientDrawable().apply { setColor(0xF0202124.toInt()); cornerRadius = dp(16).toFloat() }
            addView(root)
        }
        val p = lp(min(dp(400), w - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT, dp(12), dp(56))
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.addView(sv, p)
        panel = sv; panelLp = p
    }

    private var textBox: View? = null
    private var textBoxLp: WindowManager.LayoutParams? = null

    private fun hideTextDialog() {
        textBox?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        textBox = null; textBoxLp = null
    }

    /** Menu input teks di tengah layar: kolom teks, centang Enter di akhir, Kirim. */
    private fun showTextDialog() {
        if (textBox != null) return
        val white = Color.WHITE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.chip_text_input); setTextColor(white); textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(6))
        })
        val et = EditText(this).apply {
            setText(Prefs.lastText(this@OverlayService))
            hint = getString(R.string.hint_text)
            setTextColor(white); setHintTextColor(0x99FFFFFF.toInt()); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        }
        fun focus(on: Boolean) {
            val p = textBoxLp ?: return
            val v = textBox ?: return
            p.flags = if (on) flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else flags
            wm.updateViewLayout(v, p)
        }
        fun hideIme() {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus(); focus(false)
        }
        et.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                focus(true); v.requestFocus()
                v.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
            }
            false
        }
        et.setOnEditorActionListener { _, _, _ -> hideIme(); true }
        root.addView(et)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        lateinit var enterChip: TextView
        fun enterText() = if (enterAfter) getString(R.string.chk_enter_on) else getString(R.string.chk_enter_off)
        enterChip = chip(enterText(), false) { enterAfter = !enterAfter; enterChip.text = enterText() }
        row.addView(enterChip)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(chip(getString(R.string.close), false) { hideIme(); hideTextDialog() })
        row.addView(chip(getString(R.string.send), true) {
            val t = et.text.toString()
            Prefs.setLastText(this, t)
            hideIme()
            // beri waktu game mengambil fokus kembali sebelum teks disuntikkan
            Handler(Looper.getMainLooper()).postDelayed({ Injector.typeText(t, enterAfter) }, 350)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.background = GradientDrawable().apply { setColor(0xF0202124.toInt()); cornerRadius = dp(16).toFloat() }
        val (w, _) = screen()
        val p = lp(min(dp(380), w - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT, 0, 0)
        p.gravity = Gravity.CENTER
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.addView(root, p)
        textBox = root; textBoxLp = p
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; status = null; panelLp = null; gridOpen = false; profOpen = false
    }

    override fun onDestroy() {
        hideTextDialog(); hideSizeBar()
        Injector.removeListener(hidListener)
        Injector.releaseAll()
        hidePanel()
        items.values.forEach { try { wm.removeView(it.first) } catch (_: Exception) {} }
        items.clear()
        gear?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        built = false
        super.onDestroy()
    }
}
