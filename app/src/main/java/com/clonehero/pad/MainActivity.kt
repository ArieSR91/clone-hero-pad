package com.clonehero.pad

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var padTest: TextView

    override fun onGenericMotionEvent(e: android.view.MotionEvent): Boolean {
        if (e.source and android.view.InputDevice.SOURCE_JOYSTICK != 0 && e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            fun a(x: Int) = "%.2f".format(e.getAxisValue(x))
            padTest.text = getString(R.string.gptest_axes,
                a(android.view.MotionEvent.AXIS_X), a(android.view.MotionEvent.AXIS_Y),
                a(android.view.MotionEvent.AXIS_Z), a(android.view.MotionEvent.AXIS_RZ),
                a(android.view.MotionEvent.AXIS_LTRIGGER), a(android.view.MotionEvent.AXIS_RTRIGGER),
                a(android.view.MotionEvent.AXIS_HAT_X), a(android.view.MotionEvent.AXIS_HAT_Y))
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (e.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD) {
            padTest.text = getString(R.string.gptest_key, android.view.KeyEvent.keyCodeToString(e.keyCode), getString(if (e.action == 0) R.string.gptest_down else R.string.gptest_up))
            return true
        }
        return super.dispatchKeyEvent(e)
    }
    private val listener: () -> Unit = { refresh() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private val apk get() = applicationInfo.sourceDir
    private val inner get() = "CLASSPATH=$apk nohup app_process /system/bin com.clonehero.pad.Server ${applicationInfo.uid} ${Prefs.token(this)} >/dev/null 2>&1 &"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Injector.init(this)
        showCrashIfAny()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply { text = "Clone Hero Pad"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Clone Hero Pad by Arie-SR91"
            setPadding(0, dp(8), 0, dp(4))
        })
        status = TextView(this).apply { setPadding(0, dp(0), 0, dp(16)); textSize = 16f }
        root.addView(status)
        root.addView(btn(getString(R.string.btn_overlay_perm)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        root.addView(btn(getString(R.string.btn_root)) {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", inner)); Thread.sleep(1500)
                } catch (e: Exception) { runOnUiThread { toast(getString(R.string.toast_no_root)) } }
                Injector.connectAsync { refresh() }
            }.start()
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.lbl_adb)
            setPadding(0, dp(12), 0, dp(4))
        })
        val cmdText = "adb shell \"$inner\""
        val box = FrameLayout(this).apply { setBackgroundColor(0x11000000) }
        box.addView(TextView(this).apply {
            text = cmdText; setTextIsSelectable(true); textSize = 12f
            setPadding(dp(8), dp(8), dp(40), dp(8))   // ruang untuk ikon salin di kanan atas
        })
        box.addView(CopyIcon(this).apply {
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("cmd", cmdText))
                toast(getString(R.string.toast_cmd_copied))
            }
        }, FrameLayout.LayoutParams(dp(32), dp(32), android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
        root.addView(box)
        root.addView(btn(getString(R.string.btn_pairing)) { startPairing() })
        root.addView(btn(getString(R.string.btn_padmap)) { showPadMap() })
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        root.addView(btn(getString(R.string.btn_start)) {
            if (!Settings.canDrawOverlays(this)) toast(getString(R.string.toast_grant_overlay))
            else ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        })
        root.addView(btn(getString(R.string.btn_stop)) { stopService(Intent(this, OverlayService::class.java)) })
        root.addView(btn(getString(R.string.btn_kill)) { Injector.stopServer { refresh(); toast(getString(R.string.toast_server_off)) } })
        root.addView(TextView(this).apply { text = getString(R.string.lbl_gptest); textSize = 12f; setPadding(0, dp(12), 0, 0) })
        padTest = TextView(this).apply { textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE; text = "-" }
        root.addView(padTest)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Baris pemetaan -> target di Server untuk "Input Gamepad" (null = tidak tersedia untuk baris ini). */
    private fun realTarget(id: String) = when (id) {
        "LS_RIGHT" -> "LSX"; "LS_DOWN" -> "LSY"; "RS_RIGHT" -> "RSX"; "RS_DOWN" -> "RSY"
        "LS_UP", "LS_LEFT", "RS_UP", "RS_LEFT" -> null
        else -> id
    }

    private fun srcText(src: String?) = when {
        src == null -> ""
        src.startsWith("K") -> getString(R.string.src_button, src.substring(1))
        else -> getString(R.string.src_axis, "${src.substring(1, src.length - 1)}${src.last()}")
    }

    /** Membuka Opsi pengembang (Debug nirkabel) dan mulai mencari kode pairing; kode dimasukkan lewat notifikasi. */
    /** Menampilkan detail galat dari penutupan paksa sebelumnya (supaya bisa dilaporkan). */
    private fun showCrashIfAny() {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.exists()) return
        val t = f.readText().take(4000); f.delete()
        val tv = TextView(this).apply { text = t; textSize = 11f; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.crash_title))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(getString(R.string.copy)) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", t))
            }.setNegativeButton(getString(R.string.close), null).show()
    }

    private fun startPairing() {
        ContextCompat.startForegroundService(this, Intent(this, PairingService::class.java))
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
        }
        try {
            startActivity(i)
            toast(getString(R.string.toast_pair_wireless))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {}
            toast(getString(R.string.toast_pair_devopts))
        }
    }

    private fun showPadMap() {
        val ids = listOf("A", "B", "X", "Y", "LB", "RB", "LT", "RT", "START", "SELECT", "GUIDE", "L3", "R3",
            "DPAD_UP", "DPAD_RIGHT", "DPAD_DOWN", "DPAD_LEFT", "LS_UP", "LS_RIGHT", "LS_DOWN", "LS_LEFT",
            "RS_UP", "RS_RIGHT", "RS_DOWN", "RS_LEFT")
        fun dirName(d: String) = getString(when (d) { "UP" -> R.string.dir_up; "RIGHT" -> R.string.dir_right; "DOWN" -> R.string.dir_down; else -> R.string.dir_left })
        fun label(id: String) = when {
            id.startsWith("DPAD_") -> getString(R.string.lbl_dpad, dirName(id.substring(5)))
            id.startsWith("LS_") -> getString(R.string.lbl_ls, dirName(id.substring(3)))
            id.startsWith("RS_") -> getString(R.string.lbl_rs, dirName(id.substring(3)))
            else -> id
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        box.addView(TextView(this).apply {
            text = getString(R.string.padmap_help)
            textSize = 12f; setPadding(0, 0, 0, dp(8))
        })
        for (id in ids) {
            val target = realTarget(id)
            val hasReal = target != null
            val off = if (hasReal) 2 else 1
            val names = listOf(getString(R.string.opt_default)) + (if (hasReal) listOf(getString(R.string.opt_input_gamepad)) else emptyList()) + CATALOG.map { it.name }
            val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val tv = TextView(this)
            fun refreshLabel() { tv.text = label(id) + srcText(target?.let { Prefs.realMap(this)[it] }) }
            refreshLabel()
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            var cur = when {
                target != null && Prefs.realMap(this)[target] != null -> 1
                Prefs.padMap(this)[id] != null -> CATALOG.indexOfFirst { it.code == Prefs.padMap(this)[id] } + off
                else -> 0
            }
            sp.setSelection(cur, false)
            fun clearReal() {
                if (target != null && Prefs.realMap(this)[target] != null) {
                    Prefs.setRealMap(this, target, null); Injector.setRealMap(Prefs.realMapText(this))
                }
            }
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, rid: Long) {
                    if (pos == cur && !(hasReal && pos == 1)) return
                    when {
                        pos == 0 -> { clearReal(); Prefs.setPadMap(this@MainActivity, id, null); cur = 0; refreshLabel() }
                        hasReal && pos == 1 -> {
                            if (!Injector.connected) { toast(getString(R.string.toast_injector_inactive)); sp.setSelection(cur, false); return }
                            val dlg = android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle(label(id))
                                .setMessage(getString(R.string.learn_msg))
                                .setNegativeButton(getString(R.string.cancel), null).create()
                            dlg.setOnDismissListener { Injector.cancelLearn(); sp.setSelection(cur, false) }
                            dlg.show()
                            Injector.learn { src ->
                                if (!dlg.isShowing) return@learn
                                if (src.isEmpty()) toast(getString(R.string.toast_no_input))
                                else {
                                    Prefs.setRealMap(this@MainActivity, target!!, src)
                                    Injector.setRealMap(Prefs.realMapText(this@MainActivity))
                                    Prefs.setPadMap(this@MainActivity, id, null)
                                    cur = 1; refreshLabel()
                                }
                                dlg.dismiss()
                            }
                        }
                        else -> {
                            clearReal()
                            Prefs.setPadMap(this@MainActivity, id, CATALOG[pos - off].code); cur = pos; refreshLabel()
                        }
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            row.addView(sp, LinearLayout.LayoutParams(dp(150), -2))
            box.addView(row)
        }
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.padmap_title))
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton(getString(R.string.done), null).show()
    }

    override fun onResume() {
        super.onResume()
        Injector.addListener(listener)
        Injector.connectAsync { refresh() }
        refresh()
    }

    override fun onPause() { super.onPause(); Injector.removeListener(listener) }

    private fun refresh() {
        status.text = if (!Injector.connected) getString(R.string.inj_off)
        else getString(R.string.inj_on)
    }
}
