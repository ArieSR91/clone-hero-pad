package com.clonehero.pad

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Klien: mengirim ke Server (proses shell/root) yang menyuntikkan input sungguhan.
 * Protokol: [kode, aksi]. aksi: 0=DOWN 1=UP, +0x10 = sumber gamepad.
 * kode 254 = sumbu analog: [254, id, x16, y16] (id 0=stik kiri, 1=stik kanan, 2=LT, 3=RT; x,y = -32767..32767).
 * kode 252 = pemetaan gamepad asli [252,0,lenHi,lenLo,teks]; kode 251 = mulai belajar tombol.
 */
object Injector {
    private const val PAD = 0x1000
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closer: (() -> Unit)? = null
    @Volatile private var out: OutputStream? = null
    private val pressed = HashSet<Int>()          // kode, atau kode or PAD untuk gamepad
    private val axesOn = HashSet<Int>()
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile private var realMapText = ""
    @Volatile private var learnCb: ((String) -> Unit)? = null

    private fun writeMap() {
        val o = out ?: return
        val b = realMapText.toByteArray()
        try {
            o.write(byteArrayOf(252.toByte(), 0, (b.size shr 8).toByte(), b.size.toByte())); o.write(b); o.flush()
        } catch (e: Exception) { drop() }
    }

    /** Pemetaan gamepad asli, format "TARGET=SUMBER;..." ; kosong = pakai gamepad Xbox apa adanya. */
    fun setRealMap(text: String) = io.execute { realMapText = text; writeMap() }

    /** Minta server menangkap tombol/sumbu berikutnya dari gamepad asli. */
    fun learn(cb: (String) -> Unit) = io.execute {
        if (out == null) connectNow()
        learnCb = cb
        writeRaw(251, 0)
    }
    fun cancelLearn() { learnCb = null }

    val connected: Boolean get() = out != null
    /** true jika Server berhasil membuat gamepad virtual (UHID). */
    @Volatile var padOk = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /** Token untuk jalur TCP (server yang dijalankan sebagai shell); diisi dari Prefs. */
    @Volatile var token: String? = null
    fun init(c: android.content.Context) { token = Prefs.token(c) }

    private class Conn(val ins: java.io.InputStream, val os: OutputStream, val close: () -> Unit, val timeout: (Int) -> Unit)

    /** Soket abstrak dulu (server root); bila ditolak (SELinux untuk server shell), pakai TCP 127.0.0.1 + token. */
    private fun open(): Conn? {
        try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress("chpad2", LocalSocketAddress.Namespace.ABSTRACT))
            return Conn(s.inputStream, s.outputStream, { try { s.close() } catch (_: Exception) {} }, { s.soTimeout = it })
        } catch (_: Exception) {}
        val tok = token ?: return null
        return try {
            val t = java.net.Socket()
            t.connect(java.net.InetSocketAddress("127.0.0.1", Server.TCP_PORT), 800)
            t.tcpNoDelay = true
            val os = t.getOutputStream()
            java.io.DataOutputStream(os).apply { writeUTF(tok); flush() }
            Conn(t.getInputStream(), os, { try { t.close() } catch (_: Exception) {} }, { t.soTimeout = it })
        } catch (_: Exception) { null }
    }

    private fun connectNow() {
        try {
            val c = open() ?: run { out = null; return }
            c.timeout(1500)
            val hs = try { c.ins.read() } catch (_: Exception) { -1 }
            if (hs < 0) { c.close(); out = null; return }   // tidak ada jabat tangan: bukan server kita / token salah
            padOk = hs == 1
            c.timeout(0)
            closer = c.close; out = c.os
            Thread {   // balasan server: "SRC:<sumber>" hasil belajar tombol
                try {
                    c.ins.bufferedReader().forEachLine { l ->
                        if (l.startsWith("SRC:")) { val src = l.substring(4); main.post { learnCb?.invoke(src); learnCb = null } }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            if (realMapText.isNotEmpty()) writeMap()
        } catch (e: Exception) { out = null }
    }

    private fun drop() {
        try { closer?.invoke() } catch (_: Exception) {}
        closer = null; out = null; pressed.clear(); axesOn.clear(); changed()
    }

    private fun writeRaw(a: Int, b: Int) {
        val o = out ?: return
        try { o.write(byteArrayOf(a.toByte(), b.toByte())); o.flush() } catch (e: Exception) { drop() }
    }

    private fun write(code: Int, down: Boolean, pad: Boolean = false) =
        writeRaw(code, (if (down) 0 else 1) or (if (pad) 0x10 else 0))

    private fun writeAxis(id: Int, x: Float, y: Float) {
        val o = out ?: return
        try {
            val xi = Math.round(x.coerceIn(-1f, 1f) * 32767f); val yi = Math.round(y.coerceIn(-1f, 1f) * 32767f)
            o.write(byteArrayOf(254.toByte(), id.toByte(), (xi shr 8).toByte(), xi.toByte(), (yi shr 8).toByte(), yi.toByte()))
            o.flush()
        } catch (e: Exception) { drop() }
    }

    fun connectAsync(done: (() -> Unit)? = null) = io.execute {
        val was = connected
        if (!was) connectNow()
        if (connected != was) changed()
        main.post { done?.invoke() }
    }

    fun send(code: Int, down: Boolean) = io.execute {
        if (out == null) { connectNow(); if (out != null) changed() }
        if (down) pressed.add(code) else pressed.remove(code)
        write(code, down)
    }

    /** Tombol gamepad (A/B/X/Y, bumper, D-pad, dst). */
    fun sendPad(code: Int, down: Boolean) = io.execute {
        if (out == null) { connectNow(); if (out != null) changed() }
        val k = code or PAD
        if (down) pressed.add(k) else pressed.remove(k)
        write(code, down, true)
    }

    /** Analog gamepad: id 0 stik kiri, 1 stik kanan, 2 LT, 3 RT. */
    fun sendAxis(id: Int, x: Float, y: Float) = io.execute {
        if (out == null) { connectNow(); if (out != null) changed() }
        if (x == 0f && y == 0f) axesOn.remove(id) else axesOn.add(id)
        writeAxis(id, x, y)
    }

    fun releaseAll() = io.execute {
        pressed.toList().forEach { write(it and 0xFFF, false, it and PAD != 0) }
        pressed.clear()
        axesOn.toList().forEach { writeAxis(it, 0f, 0f) }
        axesOn.clear()
    }

    /** Meminta Server (proses shell/root) berhenti total, lalu memutus koneksi. */
    fun stopServer(done: (() -> Unit)? = null) = io.execute {
        if (out == null) connectNow()
        writeRaw(255, 255)
        drop()
        main.post { done?.invoke() }
    }

    /** Mengetik teks ke game: tiap karakter diubah jadi event tombol (termasuk SHIFT untuk huruf besar). */
    fun typeText(text: String, enter: Boolean) = io.execute {
        if (out == null) { connectNow(); if (out != null) changed() }
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        fun step(code: Int, down: Boolean) {
            if (code !in 1..253) return
            if (down) pressed.add(code) else pressed.remove(code)
            write(code, down)
            Thread.sleep(if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) 60 else 30)
        }
        for (ch in text) {
            if (ch in 'A'..'Z') {   // huruf besar: SHIFT ditahan eksplisit selama huruf ditekan
                val k = 29 + (ch - 'A')
                step(KeyEvent.KEYCODE_SHIFT_LEFT, true); Thread.sleep(40)
                step(k, true); Thread.sleep(20); step(k, false)
                Thread.sleep(20); step(KeyEvent.KEYCODE_SHIFT_LEFT, false)
                continue
            }
            val evs = map.getEvents(charArrayOf(ch)) ?: continue   // karakter tak dikenal dilewati
            for (e in evs) step(e.keyCode, e.action == KeyEvent.ACTION_DOWN)
        }
        if (enter) { step(KeyEvent.KEYCODE_ENTER, true); step(KeyEvent.KEYCODE_ENTER, false) }
    }
}
