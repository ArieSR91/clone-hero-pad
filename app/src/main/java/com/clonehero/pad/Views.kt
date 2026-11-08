package com.clonehero.pad

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class KeyButton(ctx: Context, val def: KeyDef) : View(ctx) {
    var editMode = false
        set(v) { field = v; down = false; sx = 0f; sy = 0f; stickPid = -1; dMask = 0; dpadPid = -1; invalidate() }
    var onKey: ((Boolean) -> Unit)? = null
    var onDrag: ((Int, Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    /** Jika diisi (tombol A S J K L), sentuhan diteruskan ke pengendali geser: (pointerId, x layar, y layar, 0=down 1=move 2=up). */
    /** Warna ASJKLH: false = tombol berwarna tampil netral seperti tombol lain. */
    var colorOn = true
        set(v) { field = v; invalidate() }
    /** Dipakai tombol analog: posisi stik -1..1 (x, y). */
    var onStick: ((Float, Float) -> Unit)? = null
    private var sx = 0f
    private var sy = 0f
    private var stickPid = -1
    /** Penguat gerak analog: nilai penuh tercapai sebelum jari menyentuh tepi. */
    var stickGain = 1.3f
    /** true: gerbang persegi (diagonal juga bernilai penuh di kedua sumbu). */
    var stickSquare = true

    var picked = false
        set(v) { field = v; invalidate() }
    var onSelect: (() -> Unit)? = null
    /** D-pad: (maskLama, maskBaru); bit0 atas, bit1 kanan, bit2 bawah, bit3 kiri. */
    var onDpad: ((Int, Int) -> Unit)? = null
    private var dMask = 0
    private var dpadPid = -1
    var onPoint: ((Int, Float, Float, Int) -> Unit)? = null

    fun setDown(v: Boolean) { down = v; invalidate() }
    private val loc = IntArray(2)

    private var down = false
    private var lx = 0f
    private var ly = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val path = Path()

    private fun strokeColor() =
        if (picked) 0xFFFFEB3B.toInt() else if (editMode || down) Color.WHITE else 0x66FFFFFF

    private fun drawDpad(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2
        val a = w / 3f; val m = 3f
        path.reset()
        path.moveTo(cx - a / 2, m); path.lineTo(cx + a / 2, m); path.lineTo(cx + a / 2, cy - a / 2)
        path.lineTo(w - m, cy - a / 2); path.lineTo(w - m, cy + a / 2); path.lineTo(cx + a / 2, cy + a / 2)
        path.lineTo(cx + a / 2, h - m); path.lineTo(cx - a / 2, h - m); path.lineTo(cx - a / 2, cy + a / 2)
        path.lineTo(m, cy + a / 2); path.lineTo(m, cy - a / 2); path.lineTo(cx - a / 2, cy - a / 2); path.close()
        val corner = CornerPathEffect(a * 0.2f)
        fill.pathEffect = corner
        fill.color = def.color; fill.alpha = 200
        c.drawPath(path, fill)
        // arah yang sedang ditekan disorot
        fill.color = Color.WHITE; fill.alpha = 110
        if (dMask and 1 != 0) c.drawRect(cx - a / 2, m, cx + a / 2, cy, fill)
        if (dMask and 2 != 0) c.drawRect(cx, cy - a / 2, w - m, cy + a / 2, fill)
        if (dMask and 4 != 0) c.drawRect(cx - a / 2, cy, cx + a / 2, h - m, fill)
        if (dMask and 8 != 0) c.drawRect(m, cy - a / 2, cx, cy + a / 2, fill)
        fill.pathEffect = null
        stroke.color = strokeColor()
        stroke.pathEffect = if (editMode) ComposePathEffect(DashPathEffect(floatArrayOf(14f, 10f), 0f), corner) else corner
        c.drawPath(path, stroke)
        // segitiga petunjuk arah
        val r = a * 0.2f
        val tri = Path().apply { moveTo(0f, -r); lineTo(r, r * 0.8f); lineTo(-r, r * 0.8f); close() }
        val d = (cy - a / 2 + m) / 2f
        for ((deg, dx, dy) in listOf(Triple(0f, 0f, -(cy - d)), Triple(90f, w / 2 - d, 0f),
            Triple(180f, 0f, cy - d), Triple(270f, -(w / 2 - d), 0f))) {
            c.save(); c.translate(cx + dx, cy + dy); c.rotate(deg); c.drawPath(tri, text); c.restore()
        }
    }

    private fun dpadAt(e: MotionEvent, i: Int) {
        val nx = (e.getX(i) - width / 2f) / (width / 2f); val ny = (e.getY(i) - height / 2f) / (height / 2f)
        var m = 0
        if (ny < -0.3f) m = m or 1
        if (nx > 0.3f) m = m or 2
        if (ny > 0.3f) m = m or 4
        if (nx < -0.3f) m = m or 8
        if (m != dMask) { val old = dMask; dMask = m; onDpad?.invoke(old, m); invalidate() }
    }

    private fun dpadEnd() {
        val old = dMask; dMask = 0; dpadPid = -1
        if (old != 0) onDpad?.invoke(old, 0)
        invalidate()
    }

    private fun drawStick(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 3f
        fill.color = def.color; fill.alpha = 170
        c.drawCircle(cx, cy, r, fill)
        stroke.color = strokeColor()
        stroke.pathEffect = if (editMode) DashPathEffect(floatArrayOf(14f, 10f), 0f) else null
        c.drawCircle(cx, cy, r, stroke)
        fill.color = Color.WHITE; fill.alpha = if (stickPid >= 0) 230 else 150
        var vx = sx; var vy = sy
        val vl = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()
        if (vl > 1f) { vx /= vl; vy /= vl }   // knob tetap di dalam lingkaran
        c.drawCircle(cx + vx * r * 0.55f, cy + vy * r * 0.55f, r * 0.4f, fill)
        text.textSize = r * 0.4f
        c.drawText(def.label, cx + vx * r * 0.55f, cy + vy * r * 0.55f - (text.ascent() + text.descent()) / 2,
            text.also { it.color = 0xFF37474F.toInt() })
        text.color = Color.WHITE
    }

    private fun stickAt(e: MotionEvent, i: Int) {
        val r = minOf(width, height) / 2f * 0.55f
        var dx = (e.getX(i) - width / 2f) / r * stickGain; var dy = (e.getY(i) - height / 2f) / r * stickGain
        if (stickSquare) {
            // gerbang persegi seperti stik asli: jarak jari (lingkaran) dipetakan ke sudut persegi, ARAH tetap.
            // Diagonal penuh (±1, ±1) tercapai pada jarak jari yang sama dengan arah lurus.
            val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (len > 0.0001f) {
                val k = Math.min(len, 1f) / Math.max(Math.abs(dx), Math.abs(dy))
                dx *= k; dy *= k
            }
        } else {
            val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (len > 1f) { dx /= len; dy /= len }
        }
        // zona kecil di sudut gerbang: rasio sumbu kecil/besar 0.86..0.93 dinaikkan mulus ke 1.00 (bukan lompatan),
        // sehingga diagonal penuh (±1, ±1) mudah dicapai tanpa "menempel" di sudut
        run {
            val ax = Math.abs(dx); val ay = Math.abs(dy)
            val mx = Math.max(ax, ay)
            if (mx > 0.98f) {
                val ratio = Math.min(ax, ay) / mx
                if (ratio > 0.86f) {
                    val r2 = if (ratio >= 0.93f) 1f else 0.86f + (ratio - 0.86f) * 2f
                    if (ax >= ay) { dx = Math.signum(dx); dy = Math.signum(dy) * r2 }
                    else { dy = Math.signum(dy); dx = Math.signum(dx) * r2 }
                }
            }
        }
        if (Math.abs(dx) > 0.997f) dx = Math.signum(dx)   // sisa pembulatan -> 1.00
        if (Math.abs(dy) > 0.997f) dy = Math.signum(dy)
        sx = dx; sy = dy; onStick?.invoke(dx, dy); invalidate()
    }

    private fun stickEnd() { stickPid = -1; sx = 0f; sy = 0f; onStick?.invoke(0f, 0f); invalidate() }

    override fun onDraw(c: Canvas) {
        if (def.stick >= 0) { drawStick(c); return }
        if (def.dpad) { drawDpad(c); return }
        rect.set(3f, 3f, width - 3f, height - 3f)
        val rad = if (def.circle) height * 0.5f else height * 0.2f
        fill.color = if (def.colorful && !colorOn) 0xFF607D8B.toInt() else def.color
        fill.alpha = if (down) 255 else 170
        c.drawRoundRect(rect, rad, rad, fill)
        stroke.color = strokeColor()
        stroke.pathEffect = if (editMode) DashPathEffect(floatArrayOf(14f, 10f), 0f) else null
        c.drawRoundRect(rect, rad, rad, stroke)

        val cx = width / 2f
        val cy = height / 2f
        if (def.arrow != null) {
            val r = height * 0.22f
            c.save(); c.rotate(def.arrow, cx, cy)
            path.reset()
            path.moveTo(cx, cy - r); path.lineTo(cx + r, cy + r * 0.8f); path.lineTo(cx - r, cy + r * 0.8f); path.close()
            c.drawPath(path, text)
            c.restore()
        } else {
            text.textSize = height * (if (def.label.length > 1) 0.28f else 0.42f)
            val maxW = width * 0.84f
            val tw = text.measureText(def.label)
            if (tw > maxW) text.textSize = text.textSize * maxW / tw   // teks panjang diperkecil agar muat
            c.drawText(def.label, cx, cy - (text.ascent() + text.descent()) / 2, text)
        }
    }

    private fun pt(e: MotionEvent, i: Int, phase: Int) {
        getLocationOnScreen(loc)
        onPoint?.invoke(e.getPointerId(i), loc[0] + e.getX(i), loc[1] + e.getY(i), phase)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode && def.dpad) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dpadPid = e.getPointerId(0); dpadAt(e, 0) }
                MotionEvent.ACTION_MOVE -> e.findPointerIndex(dpadPid).let { if (it >= 0) dpadAt(e, it) }
                MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == dpadPid) dpadEnd()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dpadEnd()
            }
            return true
        }
        if (!editMode && def.stick >= 0) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { stickPid = e.getPointerId(0); stickAt(e, 0) }
                MotionEvent.ACTION_MOVE -> e.findPointerIndex(stickPid).let { if (it >= 0) stickAt(e, it) }
                MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == stickPid) stickEnd()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stickEnd()
            }
            return true
        }
        if (!editMode && onPoint != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pt(e, e.actionIndex, 0)
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                    getLocationOnScreen(loc)
                    for (h in 0 until e.historySize)   // titik antara agar geseran cepat tidak melewatkan tombol
                        onPoint?.invoke(e.getPointerId(i), loc[0] + e.getHistoricalX(i, h), loc[1] + e.getHistoricalY(i, h), 1)
                    pt(e, i, 1)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pt(e, e.actionIndex, 2)
                MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) pt(e, i, 2)
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.rawX; ly = e.rawY
                if (editMode) onSelect?.invoke()
                if (!editMode) { down = true; onKey?.invoke(true) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (editMode) {
                val dx = (e.rawX - lx).toInt(); val dy = (e.rawY - ly).toInt()
                lx += dx; ly += dy
                if (dx != 0 || dy != 0) onDrag?.invoke(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (editMode) onDragEnd?.invoke()
                else if (down) { down = false; onKey?.invoke(false) }
                invalidate()
            }
        }
        return true
    }
}

class GearView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    /** true saat mode atur posisi aktif: latar gear jadi biru sebagai penanda. */
    var active = false
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f
        p.style = Paint.Style.FILL; p.color = if (active) 0xDD1E88E5.toInt() else 0x99000000.toInt()
        c.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f
        c.drawCircle(cx, cy, r * 0.34f, p)
        for (i in 0 until 8) {
            c.save(); c.rotate(i * 45f, cx, cy)
            c.drawLine(cx, cy - r * 0.46f, cx, cy - r * 0.66f, p)
            c.restore()
        }
    }
}


/** Ikon salin (dua kotak bertumpuk) berwarna hitam. */
class CopyIcon(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.strokeWidth = w * 0.08f
        r.set(w * 0.32f, h * 0.18f, w * 0.80f, h * 0.72f); c.drawRoundRect(r, w * 0.08f, w * 0.08f, p)   // kotak belakang
        r.set(w * 0.18f, h * 0.30f, w * 0.66f, h * 0.84f)                                              // kotak depan
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEAEAEA.toInt() }
        c.drawRoundRect(r, w * 0.08f, w * 0.08f, bg); c.drawRoundRect(r, w * 0.08f, w * 0.08f, p)
    }
}
