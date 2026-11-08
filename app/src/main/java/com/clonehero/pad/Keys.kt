package com.clonehero.pad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** code = Android KeyEvent keycode. arrow = rotasi segitiga (0=atas, 90=kanan, 180=bawah, 270=kiri). */
data class KeyDef(
    val id: String, val label: String, val code: Int, val color: Int,
    val wMul: Float = 1f, val arrow: Float? = null,
    val hMul: Float = 1f,
    val pad: Boolean = false,      // tombol gamepad (bukan keyboard)
    val stick: Int = -1,           // 0 = analog kiri, 1 = analog kanan
    val trig: Int = -1,            // 2 = LT, 3 = RT (sumbu analog ikut dikirim)
    val colorful: Boolean = false, // ikut toggle "Warna ASJKLH"
    val circle: Boolean = false,   // tombol bulat (A B X Y)
    val dpad: Boolean = false      // satu tombol D-pad berisi 4 arah
)

private val GRAY = 0xFF607D8B.toInt()
private val DARK = 0xFF455A64.toInt()

val KEYS = listOf(
    KeyDef("A", "A", 29, 0xFF2ECC40.toInt(), colorful = true),
    KeyDef("S", "S", 47, 0xFFFF4136.toInt(), colorful = true),
    KeyDef("J", "J", 38, 0xFFFFDC00.toInt(), colorful = true),
    KeyDef("K", "K", 39, 0xFF0074D9.toInt(), colorful = true),
    KeyDef("L", "L", 40, 0xFFFF851B.toInt(), colorful = true),
    KeyDef("SPACE", "SPACE", 62, GRAY, 2.2f),
    KeyDef("TAB", "TAB", 61, GRAY, 1.4f),
    KeyDef("ESC", "ESC", 111, GRAY, 1.4f),
    KeyDef("ENTER", "ENTER", 66, GRAY, 1.4f),
    KeyDef("H", "H", 36, 0xFF9B59B6.toInt(), colorful = true),
    KeyDef("UP", "", 19, DARK, arrow = 0f),
    KeyDef("DOWN", "", 20, DARK, arrow = 180f),
    KeyDef("LEFT", "", 21, DARK, arrow = 270f),
    KeyDef("RIGHT", "", 22, DARK, arrow = 90f),
)

/** Tombol yang bisa dipilih pada menu "Tambah tombol". Nama = fungsi tombol (ESC, X, SHIFT ...). */
data class CatalogKey(
    val name: String, val code: Int, val arrow: Float? = null,
    val pad: Boolean = false, val stick: Int = -1, val trig: Int = -1, val dpad: Boolean = false
)

/** Daftar tombol gamepad untuk menu "Tambah tombol" (kode = KeyEvent.KEYCODE_BUTTON_*). */
val PAD_CATALOG: List<CatalogKey> = listOf(
    CatalogKey("A", 96, pad = true), CatalogKey("B", 97, pad = true),
    CatalogKey("X", 99, pad = true), CatalogKey("Y", 100, pad = true),
    CatalogKey("LB", 102, pad = true), CatalogKey("RB", 103, pad = true),
    CatalogKey("LT", 104, pad = true, trig = 2), CatalogKey("RT", 105, pad = true, trig = 3),
    CatalogKey("START", 108, pad = true), CatalogKey("SELECT", 109, pad = true),
    CatalogKey("GUIDE", 110, pad = true),
    CatalogKey("L3", 106, pad = true), CatalogKey("R3", 107, pad = true),
    CatalogKey("DPAD", 0, pad = true, dpad = true),
    CatalogKey("ANALOG KIRI", 0, pad = true, stick = 0), CatalogKey("ANALOG KANAN", 0, pad = true, stick = 1),
)

val CATALOG: List<CatalogKey> = ArrayList<CatalogKey>().apply {
    add(CatalogKey("ESC", 111))
    add(CatalogKey("SPACE", 62))
    add(CatalogKey("ENTER", 66))
    add(CatalogKey("TAB", 61))
    add(CatalogKey("BKSP", 67))
    add(CatalogKey("DEL", 112))
    add(CatalogKey("SHIFT", 59))
    add(CatalogKey("CTRL", 113))
    add(CatalogKey("ALT", 57))
    add(CatalogKey("CAPS", 115))
    add(CatalogKey("UP", 19, 0f))
    add(CatalogKey("DOWN", 20, 180f))
    add(CatalogKey("LEFT", 21, 270f))
    add(CatalogKey("RIGHT", 22, 90f))
    add(CatalogKey("HOME", 122))
    add(CatalogKey("END", 123))
    add(CatalogKey("PGUP", 92))
    add(CatalogKey("PGDN", 93))
    add(CatalogKey("INS", 124))
    for (i in 0 until 26) add(CatalogKey(('A' + i).toString(), 29 + i))
    for (i in 0..9) add(CatalogKey(i.toString(), 7 + i))
    for (i in 1..12) add(CatalogKey("F$i", 130 + i))
    add(CatalogKey(",", 55)); add(CatalogKey(".", 56)); add(CatalogKey("/", 76))
    add(CatalogKey(";", 74)); add(CatalogKey("'", 75)); add(CatalogKey("[", 71))
    add(CatalogKey("]", 72)); add(CatalogKey("\\", 73)); add(CatalogKey("-", 69))
    add(CatalogKey("=", 70)); add(CatalogKey("`", 68))
}

/** Tombol buatan pengguna (disimpan di Prefs). Semua berwarna netral. */
data class CustomKey(
    val id: String, val name: String, val code: Int, val arrow: Float?,
    val pad: Boolean = false, val stick: Int = -1, val trig: Int = -1, val dpad: Boolean = false
) {
    fun toDef(): KeyDef {
        if (dpad) return KeyDef(id, "", 0, DARK, wMul = 2.2f, hMul = 2.2f, pad = true, dpad = true)
        if (stick >= 0) return KeyDef(id, if (stick == 0) "L" else "R", 0, GRAY, wMul = 2f, hMul = 2f, pad = true, stick = stick)
        val shoulder = pad && name in setOf("LB", "RB", "LT", "RT")
        val small = pad && name in setOf("SELECT", "START", "GUIDE")
        val circle = pad && name in setOf("A", "B", "X", "Y", "L3", "R3")
        return KeyDef(
            id, if (arrow != null) "" else name, code,
            if (arrow != null) DARK else GRAY,
            wMul = when {
                arrow != null || circle -> 1f
                shoulder -> 1.7f
                small -> 1.3f
                else -> (1f + 0.2f * (name.length - 1)).coerceIn(1f, 2.4f)
            },
            arrow = arrow, hMul = if (shoulder) 0.8f else if (small) 0.65f else 1f, pad = pad, trig = trig, circle = circle
        )
    }
}

fun allKeys(c: Context): List<KeyDef> =
    (if (Prefs.blank(c)) emptyList() else KEYS) + Prefs.customKeys(c).map { it.toDef() }

object Prefs {
    private fun meta(c: Context) = c.getSharedPreferences("meta", Context.MODE_PRIVATE)
    private fun file(id: String) = if (id == "default") "cfg" else "cfg_$id"
    /** Pengaturan profil yang sedang aktif. Profil "default" memakai file lama "cfg" sehingga data lama tetap terbaca. */
    private fun sp(c: Context) = c.getSharedPreferences(file(activeId(c)), Context.MODE_PRIVATE)

    // ---------- Profil ----------
    private fun extraProfiles(c: Context): List<Pair<String, String>> = try {
        val a = JSONArray(meta(c).getString("profiles", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("id") to o.getString("name") } }
    } catch (e: Exception) { emptyList() }

    private fun saveExtra(c: Context, l: List<Pair<String, String>>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.first).put("name", it.second)) }
        meta(c).edit().putString("profiles", a.toString()).apply()
    }

    /** Daftar (id, nama); profil bawaan selalu pertama. */
    fun profiles(c: Context): List<Pair<String, String>> =
        listOf("default" to (meta(c).getString("default_name", "Default") ?: "Default")) + extraProfiles(c)

    fun activeId(c: Context): String {
        val id = meta(c).getString("active", "default") ?: "default"
        return if (id == "default" || extraProfiles(c).any { it.first == id }) id else "default"
    }
    fun setActive(c: Context, id: String) = meta(c).edit().putString("active", id).apply()

    /** Membuat profil baru lalu mengaktifkannya. copy = true: salin posisi & konfigurasi profil aktif; false: kosong. */
    fun createProfile(c: Context, name: String, copy: Boolean): String {
        val id = "p${System.currentTimeMillis()}"
        val e = c.getSharedPreferences(file(id), Context.MODE_PRIVATE).edit().clear()
        if (copy) for ((key, v) in sp(c).all) when (v) {
            is Int -> e.putInt(key, v); is Boolean -> e.putBoolean(key, v); is String -> e.putString(key, v)
            is Float -> e.putFloat(key, v); is Long -> e.putLong(key, v)
        }
        if (!copy) e.putBoolean("blank", true)   // profil kosong: tanpa tombol bawaan
        e.commit()
        saveExtra(c, extraProfiles(c) + (id to name))
        setActive(c, id)
        return id
    }

    /** Membuat profil bawaan "Gamepad" (semua tombol gamepad) satu kali; posisi diatur Service lewat id "g_<nama>". */
    fun ensureGamepadProfile(c: Context) {
        if (meta(c).getBoolean("gp_made", false)) return
        val a = JSONArray()
        for (k in PAD_CATALOG) {
            a.put(JSONObject().put("id", "g_${k.name}").put("name", k.name).put("code", k.code)
                .put("pad", k.pad).put("stick", k.stick).put("trig", k.trig).put("dpad", k.dpad)
                .apply { if (k.arrow != null) put("arrow", k.arrow.toDouble()) })
        }
        c.getSharedPreferences(file("gamepad"), Context.MODE_PRIVATE).edit().clear()
            .putBoolean("blank", true).putString("custom", a.toString()).commit()
        if (extraProfiles(c).none { it.first == "gamepad" }) saveExtra(c, extraProfiles(c) + ("gamepad" to "Gamepad"))
        meta(c).edit().putBoolean("gp_made", true).apply()
    }

    fun renameProfile(c: Context, id: String, name: String) {
        if (id == "default") meta(c).edit().putString("default_name", name).apply()
        else saveExtra(c, extraProfiles(c).map { if (it.first == id) id to name else it })
    }

    fun deleteProfile(c: Context, id: String) {
        if (id == "default") return
        if (activeId(c) == id) setActive(c, "default")
        saveExtra(c, extraProfiles(c).filter { it.first != id })
        c.getSharedPreferences(file(id), Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** true = profil tanpa tombol bawaan (hanya tombol yang ditambahkan sendiri). */
    fun blank(c: Context) = sp(c).getBoolean("blank", false)
    /** Skala ukuran per tombol (persen, 30..300), dikalikan dengan ukuran umum. */
    /** Opacity khusus satu tombol (20..100); null = ikut slider umum/ASJKL. */
    fun keyOpacity(c: Context, id: String): Int? = sp(c).getInt("op_$id", -1).takeIf { it >= 0 }
    fun setKeyOpacity(c: Context, id: String, v: Int) = sp(c).edit().putInt("op_$id", v).apply()
    fun keyScale(c: Context, id: String) = sp(c).getInt("sz_$id", 100)
    fun setKeyScale(c: Context, id: String, v: Int) = sp(c).edit().putInt("sz_$id", v).apply()
    fun stickGain(c: Context) = sp(c).getInt("stick_gain", 130)        // % penguat analog (100..250)
    fun setStickGain(c: Context, v: Int) = sp(c).edit().putInt("stick_gain", v).apply()
    fun stickSquare(c: Context) = sp(c).getBoolean("stick_square", true)
    fun setStickSquare(c: Context, v: Boolean) = sp(c).edit().putBoolean("stick_square", v).apply()
    /** Token rahasia (dibuat sekali) untuk jalur TCP ke Server. */
    fun token(c: Context): String {
        val m = meta(c)
        return m.getString("token", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also { m.edit().putString("token", it).apply() }
    }
    fun colorOn(c: Context) = sp(c).getBoolean("color_on", true)       // warna A S J K L H
    fun setColorOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("color_on", v).apply()
    fun opacity(c: Context) = sp(c).getInt("opacity", 80)          // 20..100
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v).apply()
    fun slideOpacity(c: Context) = sp(c).getInt("slide_opacity", 80)   // 20..100, khusus A S J K L
    fun setSlideOpacity(c: Context, v: Int) = sp(c).edit().putInt("slide_opacity", v).apply()
    fun slideOn(c: Context) = sp(c).getBoolean("slide_on", true)       // geser antar A S J K L
    fun setSlideOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("slide_on", v).apply()
    fun sizePct(c: Context) = sp(c).getInt("size", 100)            // 50..200
    fun setSizePct(c: Context, v: Int) = sp(c).edit().putInt("size", v).apply()

    private fun key(land: Boolean, id: String) = "pos_${if (land) "L" else "P"}_$id"
    fun getPos(c: Context, land: Boolean, id: String): Pair<Int, Int>? =
        sp(c).getString(key(land, id), null)?.split(",")?.let {
            if (it.size == 2) it[0].toInt() to it[1].toInt() else null
        }
    fun setPos(c: Context, land: Boolean, id: String, x: Int, y: Int) =
        sp(c).edit().putString(key(land, id), "$x,$y").apply()
    fun clearPos(c: Context, land: Boolean) {
        val e = sp(c).edit(); allKeys(c).forEach { e.remove(key(land, it.id)) }; e.apply()
    }

    fun customKeys(c: Context): List<CustomKey> = try {
        val a = JSONArray(sp(c).getString("custom", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CustomKey(
                o.getString("id"), o.getString("name"), o.getInt("code"),
                if (o.has("arrow")) o.getDouble("arrow").toFloat() else null,
                o.optBoolean("pad", false), o.optInt("stick", -1), o.optInt("trig", -1), o.optBoolean("dpad", false)
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun saveCustom(c: Context, l: List<CustomKey>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("code", it.code)
                .put("pad", it.pad).put("stick", it.stick).put("trig", it.trig).put("dpad", it.dpad)
                .apply { if (it.arrow != null) put("arrow", it.arrow.toDouble()) })
        }
        sp(c).edit().putString("custom", a.toString()).apply()
    }

    fun addCustom(c: Context, k: CustomKey) = saveCustom(c, customKeys(c) + k)
    fun removeCustom(c: Context, id: String) {
        saveCustom(c, customKeys(c).filter { it.id != id })
        sp(c).edit().remove(key(true, id)).remove(key(false, id)).remove("sz_$id").remove("op_$id").apply()
    }

    // ---------- Pemetaan gamepad asli -> kontrol (global) ----------
    fun realMap(c: Context): Map<String, String> = try {
        val o = JSONObject(meta(c).getString("realmap", "{}"))
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }
    fun setRealMap(c: Context, target: String, src: String?) {
        val m = realMap(c).toMutableMap()
        if (src == null) m.remove(target) else m[target] = src
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
        meta(c).edit().putString("realmap", o.toString()).apply()
    }
    fun clearRealMap(c: Context) = meta(c).edit().remove("realmap").apply()
    fun realMapText(c: Context) = realMap(c).entries.joinToString(";") { "${it.key}=${it.value}" }

    // ---------- Pemetaan gamepad -> keyboard (berlaku global, semua profil) ----------
    @Volatile private var padCache: Map<String, Int>? = null
    fun padMap(c: Context): Map<String, Int> = padCache ?: try {
        val o = JSONObject(meta(c).getString("padmap", "{}"))
        o.keys().asSequence().associateWith { o.getInt(it) }
    } catch (e: Exception) { emptyMap() }.also { padCache = it }
    fun setPadMap(c: Context, id: String, code: Int?) {
        val m = padMap(c).toMutableMap()
        if (code == null) m.remove(id) else m[id] = code
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
        meta(c).edit().putString("padmap", o.toString()).apply()
        padCache = m
    }

    // teks terakhir yang diketik di kolom "Kirim teks"
    fun lastText(c: Context) = meta(c).getString("text", "") ?: ""
    fun setLastText(c: Context, v: String) = meta(c).edit().putString("text", v).apply()
}
