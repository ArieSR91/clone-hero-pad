package com.clonehero.pad

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.DataInputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Method

/**
 * Dijalankan lewat app_process sebagai shell (adb) atau root:
 * CLASSPATH=<base.apk> app_process /system/bin com.clonehero.pad.Server <uid-app>
 */
object Server {
    private lateinit var manager: Any
    private lateinit var inject: Method

    @JvmStatic
    fun main(args: Array<String>) {
        val appUid = args[0].toInt()
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "input") as IBinder
        manager = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        inject = Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)

        // Jika nama soket sudah dipakai (server lama/versi lain yang masih hidup), matikan lalu ambil alih
        val ss = try { LocalServerSocket("chpad2") } catch (e: Exception) {
            killOldServers()
            try { LocalServerSocket("chpad2") } catch (e2: Exception) { println("Server sudah berjalan"); return }
        }
        killOldServers()
        uhidCreate()
        // TCP localhost + token: untuk server yang dijalankan sebagai shell (adb). Soket abstrak milik domain shell
        // tidak bisa dihubungi aplikasi biasa karena SELinux; TCP 127.0.0.1 bisa. Token mencegah aplikasi lain ikut menyuntik.
        if (args.size > 1) {
            val token = args[1]
            try {
                val tcp = java.net.ServerSocket(TCP_PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))
                Thread {
                    while (true) {
                        val t = try { tcp.accept() } catch (_: Exception) { break }
                        Thread {
                            try {
                                t.soTimeout = 2000
                                val tok = java.io.DataInputStream(t.getInputStream()).readUTF()
                                if (tok != token) { t.close(); return@Thread }
                                t.soTimeout = 0; t.tcpNoDelay = true
                                handle(t.getInputStream(), t.getOutputStream()) { try { t.close() } catch (_: Exception) {} }
                            } catch (_: Exception) { try { t.close() } catch (_: Exception) {} }
                        }.start()
                    }
                }.apply { isDaemon = true }.start()
            } catch (e: Exception) { println("TCP gagal: $e") }
        }
        println("Server siap")
        while (true) {
            val s = ss.accept()
            if (s.peerCredentials.uid != appUid) { s.close(); continue }
            Thread { handle(s.inputStream, s.outputStream) { try { s.close() } catch (_: Exception) {} } }.start()
        }
    }

    const val TCP_PORT = 47831
    private const val PAD = 0x100          // penanda gamepad pada kode di himpunan pressed
    private val axes = FloatArray(6)        // X, Y, Z, RZ, LTRIGGER, RTRIGGER
    private val axisIds = intArrayOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER
    )

    /** Mematikan server versi lama (proses lain dengan nama kelas yang sama) agar tidak mengganggu input. */
    private fun killOldServers() {
        try {
            val me = android.os.Process.myPid()
            // "Serve[r]" agar perintah sh ini sendiri tidak ikut cocok dengan pola
            val cmd = "for p in \$(pgrep -f 'com.clonehero.pad.Serve[r]'); do [ \"\$p\" != \"$me\" ] && kill -9 \$p; done"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            Thread.sleep(200)
        } catch (_: Throwable) {}
    }

    // ---------- Gamepad virtual (UHID) : perangkat HID sungguhan ber-ID Xbox 360 ----------
    private var uhidFd: FileDescriptor? = null
    @Volatile private var uhidOk = false
    private var padBtns = 0                    // bit 0..14 = usage tombol HID 1..15
    private var padDirs = 0                    // bit0 atas, bit1 kanan, bit2 bawah, bit3 kiri
    private val padAx = IntArray(6)            // X, Y, Rx, Ry (-32767..32767), LT, RT (0..255)

    // Report: [15 tombol + 1 pad][hat 4 bit + 4 pad][X][Y][Rx][Ry] (16 bit) [LT][RT] (8 bit)
    private val DESC = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
        0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
        0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0
    ).map { it.toByte() }.toByteArray()

    private fun uhidCreate() {
        try {
            val fd = android.system.Os.open("/dev/uhid", android.system.OsConstants.O_RDWR, 0)
            val b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 16 + DESC.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(11)                                           // UHID_CREATE2
            val name = "Xbox 360 Controller".toByteArray()
            b.put(name); b.position(4 + 128)                       // name[128]
            b.position(4 + 128 + 64 + 64)                          // phys[64], uniq[64]
            b.putShort(DESC.size.toShort())                        // rd_size
            b.putShort(3)                                          // bus = BUS_USB
            b.putInt(0x045e); b.putInt(0x028e); b.putInt(0x0110); b.putInt(0)   // vendor, product, version, country
            b.put(DESC)
            android.system.Os.write(fd, b.array(), 0, b.position())
            uhidFd = fd; uhidOk = true
            Thread {   // kernel mengirim event ke pembuat perangkat; baca agar antrean tidak penuh
                val buf = ByteArray(4380)
                try { while (true) android.system.Os.read(fd, buf, 0, buf.size) } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            Thread.sleep(300)
        } catch (e: Throwable) { uhidOk = false; println("UHID gagal: $e") }
    }

    @Synchronized
    private fun padSend() {
        val fd = uhidFd ?: return
        val d = padDirs
        val u = d and 1 != 0; val r = d and 2 != 0; val dn = d and 4 != 0; val l = d and 8 != 0
        val hat = when { u && r -> 2; r && dn -> 4; dn && l -> 6; l && u -> 8; u -> 1; r -> 3; dn -> 5; l -> 7; else -> 0 }
        val b = ByteBuffer.allocate(4 + 2 + 13).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(12); b.putShort(13)                               // UHID_INPUT2, 13 byte report
        b.putShort((padBtns and 0x7FFF).toShort())
        b.put(hat.toByte())
        for (i in 0 until 4) b.putShort(padAx[i].toShort())
        b.put(padAx[4].toByte()); b.put(padAx[5].toByte())
        try { android.system.Os.write(fd, b.array(), 0, b.position()) } catch (e: Exception) { uhidOk = false }
    }

    /** Kode KeyEvent gamepad -> bit tombol HID (urutan: A,B,C,X,Y,Z,LB,RB,LT,RT,Back,Start,Guide,L3,R3). */
    private fun padBit(code: Int) = when (code) {
        96 -> 0; 97 -> 1; 99 -> 3; 100 -> 4; 102 -> 6; 103 -> 7
        109 -> 10; 108 -> 11; 110 -> 12; 106 -> 13; 107 -> 14
        else -> -1                                                  // LT/RT (104/105) lewat sumbu
    }

    private fun padKey(code: Int, down: Boolean) {
        val dir = when (code) { 19 -> 1; 22 -> 2; 20 -> 4; 21 -> 8; else -> 0 }
        if (dir != 0) padDirs = if (down) padDirs or dir else padDirs and dir.inv()
        else {
            val bit = padBit(code)
            if (bit < 0) return
            padBtns = if (down) padBtns or (1 shl bit) else padBtns and (1 shl bit).inv()
        }
        padSend()
    }

    // ---------- Pemetaan gamepad asli (evdev -> gamepad virtual) ----------
    private class Axis(val min: Int, val max: Int, val rest: Int)
    private class Dev(val path: String, val name: String, val keys: Set<Int>, val axes: Map<Int, Axis>)

    // sumber: "K<kode>" tombol, "A<kode>+" / "A<kode>-" sumbu didorong ke arah + / -
    private val DEF = listOf(
        "A" to "K304", "B" to "K305", "X" to "K307", "Y" to "K308", "LB" to "K310", "RB" to "K311",
        "SELECT" to "K314", "START" to "K315", "GUIDE" to "K316", "L3" to "K317", "R3" to "K318",
        "LT" to "K312", "RT" to "K313",
        "LSX" to "A0+", "LSY" to "A1+", "RSX" to "A3+", "RSY" to "A4+", "LT" to "A2+", "RT" to "A5+",
        "DPAD_RIGHT" to "A16+", "DPAD_LEFT" to "A16-", "DPAD_DOWN" to "A17+", "DPAD_UP" to "A17-",
        "DPAD_UP" to "K544", "DPAD_DOWN" to "K545", "DPAD_LEFT" to "K546", "DPAD_RIGHT" to "K547"
    )
    private val TGT_CODE = mapOf(
        "A" to 96, "B" to 97, "X" to 99, "Y" to 100, "LB" to 102, "RB" to 103, "START" to 108, "SELECT" to 109,
        "GUIDE" to 110, "L3" to 106, "R3" to 107, "DPAD_UP" to 19, "DPAD_RIGHT" to 22, "DPAD_DOWN" to 20, "DPAD_LEFT" to 21
    )
    @Volatile private var userMap: Map<String, String> = emptyMap()   // target -> sumber (dari aplikasi)
    @Volatile private var srcMap: Map<String, String> = emptyMap()    // sumber -> target (setelah digabung default Xbox)
    private val digital = HashMap<String, Boolean>()
    @Volatile private var gen = 0
    @Volatile private var learnOut: java.io.OutputStream? = null

    private fun buildSrcMap(user: Map<String, String>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((t, s) in DEF) if (t !in user.keys) m[s] = t
        for ((t, s) in user) m[s] = t
        return m
    }

    @Synchronized
    private fun remapSet(text: String) {
        val u = LinkedHashMap<String, String>()
        for (p in text.split(";")) { val kv = p.split("="); if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) u[kv[0]] = kv[1] }
        userMap = u; srcMap = buildSrcMap(u)
        startReaders(u.isNotEmpty())
    }

    @Synchronized
    private fun learnStart(o: java.io.OutputStream) {
        learnOut = o
        startReaders(false)
        Thread {
            try { Thread.sleep(15000) } catch (_: Exception) {}
            synchronized(this) { if (learnOut === o) learnDone("") }
        }.apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun learnDone(src: String) {
        val o = learnOut ?: return
        learnOut = null
        try { o.write("SRC:$src\n".toByteArray()); o.flush() } catch (_: Exception) {}
        startReaders(userMap.isNotEmpty())
    }

    private fun scanDevices(): List<Dev> {
        val out = ArrayList<Dev>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getevent", "-p"))
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            var path: String? = null; var name = ""
            var keys = HashSet<Int>(); var axes = HashMap<Int, Axis>(); var sec = ""
            fun flush() { path?.let { out.add(Dev(it, name, keys, axes)) } }
            val head = Regex("""^(\w+) \([0-9a-fA-F]{4}\):(.*)$""")
            val abs = Regex("""([0-9a-fA-F]{4})\s*:\s*value\s+(-?\d+),\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
            for (ln in lines) {
                val t = ln.trim()
                if (t.startsWith("add device")) { flush(); path = t.substringAfter(": ").trim(); name = ""; keys = HashSet(); axes = HashMap(); sec = ""; continue }
                if (t.startsWith("name:")) { name = t.substringAfter("\"").substringBeforeLast("\""); continue }
                if (t.endsWith(":") && !head.matches(t)) { sec = ""; continue }
                var rest = t
                head.find(t)?.let { sec = it.groupValues[1]; rest = it.groupValues[2] }
                if (sec == "KEY") Regex("[0-9a-fA-F]{4}").findAll(rest).forEach { keys.add(it.value.toInt(16)) }
                if (sec == "ABS") abs.find(rest)?.let {
                    val (c, v, mn, mx) = it.destructured
                    axes[c.toInt(16)] = Axis(mn.toInt(), mx.toInt(), v.toInt())
                }
            }
            flush()
        } catch (_: Throwable) {}
        return out.filter { d -> d.keys.any { it in 0x120..0x13f } && d.name != "Xbox 360 Controller" }
    }

    /** EVIOCGRAB lewat libcore (tersembunyi): perangkat asli tidak lagi dikirim ke game, hanya gamepad virtual. */
    private fun tryGrab(fd: FileDescriptor) {
        try {
            val osCls = Class.forName("libcore.io.Os")
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            val m = osCls.methods.first { it.name == "ioctlInt" }
            val cmd = 0x40044590   // EVIOCGRAB
            if (m.parameterTypes.size == 3) {
                val ref = Class.forName("android.system.Int32Ref").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                m.invoke(os, fd, cmd, ref)
            } else m.invoke(os, fd, cmd)
        } catch (_: Throwable) {}
    }

    @Synchronized
    private fun startReaders(grab: Boolean) {
        gen++
        val my = gen
        digital.clear()
        padBtns = 0; padDirs = 0; padAx.fill(0)
        if (uhidOk) padSend()
        if (!uhidOk || (!grab && learnOut == null)) return
        try { Thread.sleep(300) } catch (_: Exception) {}   // beri waktu pembaca lama melepas perangkat
        for (d in scanDevices()) Thread { readLoop(d, grab, my) }.apply { isDaemon = true }.start()
    }

    private fun readLoop(d: Dev, grab: Boolean, my: Int) {
        val sz = if (android.os.Process.is64Bit()) 24 else 16
        val off = sz - 8
        var fd: FileDescriptor? = null
        try {
            fd = android.system.Os.open(d.path, android.system.OsConstants.O_RDONLY, 0)
            if (grab) tryGrab(fd)
            val pfd = android.system.StructPollfd().apply { this.fd = fd; events = android.system.OsConstants.POLLIN.toShort() }
            val buf = ByteArray(sz)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            while (gen == my) {
                pfd.revents = 0
                if (android.system.Os.poll(arrayOf(pfd), 200) <= 0) continue
                if (android.system.Os.read(fd, buf, 0, sz) < sz) continue
                val type = bb.getShort(off).toInt() and 0xFFFF
                val code = bb.getShort(off + 2).toInt() and 0xFFFF
                val value = bb.getInt(off + 4)
                if (gen == my) onRealEvent(d, type, code, value)
            }
        } catch (_: Throwable) {
        } finally { try { fd?.let { android.system.Os.close(it) } } catch (_: Throwable) {} }
    }

    private fun norm(ax: Axis, v: Int): Float =
        if (v >= ax.rest) (v - ax.rest).toFloat() / maxOf(1, ax.max - ax.rest)
        else -(ax.rest - v).toFloat() / maxOf(1, ax.rest - ax.min)

    @Synchronized
    private fun onRealEvent(d: Dev, type: Int, code: Int, value: Int) {
        if (learnOut != null) {
            if (type == 1 && value == 1) learnDone("K$code")
            else if (type == 3) {
                val ax = d.axes[code] ?: return
                val n = norm(ax, value)
                if (n > 0.6f) learnDone("A$code+") else if (n < -0.6f) learnDone("A$code-")
            }
            return
        }
        if (type == 1) {
            if (value == 2) return
            val t = srcMap["K$code"] ?: return
            apply("K$code", t, if (value != 0) 1f else 0f)
        } else if (type == 3) {
            val ax = d.axes[code] ?: return
            val n = norm(ax, value)
            for (dir in listOf("+", "-")) {
                val src = "A$code$dir"
                val t = srcMap[src] ?: continue
                apply(src, t, if (dir == "+") n else -n)
            }
        }
    }

    private fun apply(src: String, tgt: String, v: Float) {
        when (tgt) {
            "LSX" -> padAx[0] = Math.round(v.coerceIn(-1f, 1f) * 32767f)
            "LSY" -> padAx[1] = Math.round(v.coerceIn(-1f, 1f) * 32767f)
            "RSX" -> padAx[2] = Math.round(v.coerceIn(-1f, 1f) * 32767f)
            "RSY" -> padAx[3] = Math.round(v.coerceIn(-1f, 1f) * 32767f)
            "LT" -> padAx[4] = Math.round(v.coerceIn(0f, 1f) * 255f)
            "RT" -> padAx[5] = Math.round(v.coerceIn(0f, 1f) * 255f)
            else -> {
                val code = TGT_CODE[tgt] ?: return
                val down = v > 0.5f
                if ((digital[src] ?: false) == down) return
                digital[src] = down
                padKey(code, down); return
            }
        }
        padSend()
    }

    private fun releasePressed(p: Set<Int>) = p.forEach { key(it and 0xFF, 1, 0, it and PAD != 0) }

    private fun handle(sin: java.io.InputStream, sout: java.io.OutputStream, close: () -> Unit) {
        val pressed = HashSet<Int>()
        try {
            sout.write(if (uhidOk) 1 else 0); sout.flush()   // jabat tangan: status gamepad virtual
            val i = DataInputStream(sin)
            while (true) {
                val code = i.readUnsignedByte()
                val action = i.readUnsignedByte()   // 0 = DOWN, 1 = UP, +0x10 = gamepad
                if (code == 255 && action == 255) {     // perintah berhenti
                    releasePressed(pressed); axisAllZero()
                    Runtime.getRuntime().halt(0)
                }
                if (code == 252) {                      // pemetaan gamepad asli dari aplikasi
                    val b = ByteArray(i.readUnsignedShort()); i.readFully(b)
                    remapSet(String(b)); continue
                }
                if (code == 251) { learnStart(sout); continue }   // belajar tombol gamepad asli
                if (code == 254) {                      // sumbu analog: id, x, y
                    val x = i.readShort() / 32767f; val y = i.readShort() / 32767f
                    axis(action, x, y)
                    continue
                }
                val pad = action and 0x10 != 0
                val act = action and 0x0F
                val k = code or (if (pad) PAD else 0)
                if (act == 0) { if (!pressed.add(k)) continue } else pressed.remove(k)
                key(code, act, meta(pressed), pad)
            }
        } catch (_: Exception) {}
        releasePressed(pressed); axisAllZero()
        close()
    }

    @Synchronized
    private fun axis(id: Int, x: Float, y: Float) {
        if (uhidOk) {
            fun s(v: Float) = Math.round(v.coerceIn(-1f, 1f) * 32767f)
            fun t(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255f)
            when (id) {
                0 -> { padAx[0] = s(x); padAx[1] = s(y) }
                1 -> { padAx[2] = s(x); padAx[3] = s(y) }
                2 -> padAx[4] = t(x)
                3 -> padAx[5] = t(x)
                else -> return
            }
            padSend(); return
        }
        when (id) {
            0 -> { axes[0] = x; axes[1] = y }
            1 -> { axes[2] = x; axes[3] = y }
            2 -> axes[4] = x
            3 -> axes[5] = x
            else -> return
        }
        motion()
    }

    @Synchronized
    private fun axisAllZero() {
        if (uhidOk) { padAx.fill(0); padSend(); return }
        if (axes.all { it == 0f }) return
        axes.fill(0f); motion()
    }

    private fun motion() {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { for (k in axes.indices) setAxisValue(axisIds[k], axes[k]) })
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, -1, 0,
            InputDevice.SOURCE_JOYSTICK, 0)
        inject.invoke(manager, e, 0)
    }

    /** Status modifier dari tombol yang sedang ditahan, supaya event huruf membawa SHIFT_ON dsb. */
    private fun meta(p: Set<Int>): Int {
        var m = 0
        if (KeyEvent.KEYCODE_SHIFT_LEFT in p) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (KeyEvent.KEYCODE_SHIFT_RIGHT in p) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        if (KeyEvent.KEYCODE_CTRL_LEFT in p) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (KeyEvent.KEYCODE_CTRL_RIGHT in p) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        if (KeyEvent.KEYCODE_ALT_LEFT in p) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (KeyEvent.KEYCODE_ALT_RIGHT in p) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        return m
    }

    private fun key(code: Int, action: Int, meta: Int, pad: Boolean = false) {
        if (pad && uhidOk) { padKey(code, action == 0); return }
        val now = SystemClock.uptimeMillis()
        val src = if (!pad) InputDevice.SOURCE_KEYBOARD
            else if (code in 19..22) InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD
            else InputDevice.SOURCE_GAMEPAD
        val e = KeyEvent(now, now, action, code, 0, meta, -1, 0, 0, src)
        inject.invoke(manager, e, 0)
    }
}
