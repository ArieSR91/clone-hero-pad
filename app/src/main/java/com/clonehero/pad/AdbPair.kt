package com.clonehero.pad

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Koneksi ADB (Debug nirkabel) dari dalam aplikasi. Kunci disimpan agar pairing cukup sekali. */
class PairManager(ctx: Context) : AbsAdbConnectionManager() {
    private val pk: PrivateKey
    private val cert: Certificate

    init {
        val kf = File(ctx.filesDir, "adb_key.pk8")
        val cf = File(ctx.filesDir, "adb_cert.der")
        var k: PrivateKey? = null
        var c: Certificate? = null
        if (kf.exists() && cf.exists()) try {
            k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            c = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } catch (_: Exception) { k = null; c = null }
        if (k == null || c == null) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=ClonePad")
            val now = System.currentTimeMillis()
            val b = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86400000L), Date(now + 3650L * 86400000L), name, kp.public
            )
            val cc = JcaX509CertificateConverter().getCertificate(
                b.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            )
            kf.writeBytes(kp.private.encoded); cf.writeBytes(cc.encoded)
            k = kp.private; c = cc
        }
        pk = k!!; cert = c!!
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = pk
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "ClonePad"
}

object AdbPair {
    @Volatile private var mgr: PairManager? = null
    private fun manager(c: Context) = mgr ?: synchronized(this) { mgr ?: PairManager(c.applicationContext).also { mgr = it } }
    private val dbg = StringBuilder()
    private fun log(s: String) { synchronized(dbg) { dbg.append(s).append('\n') } }

    /** Perintah yang menjalankan Server sebagai shell (sama dengan yang ditampilkan di layar utama). */
    fun startCmd(c: Context) =
        "CLASSPATH=${c.applicationInfo.sourceDir} nohup app_process /system/bin com.clonehero.pad.Server ${c.applicationInfo.uid} ${Prefs.token(c)} >/data/local/tmp/clonepad.log 2>&1 &"

    /** Shell interaktif: tulis perintah lalu exit. Mengembalikan output (bisa kosong). */
    private fun shellInteractive(m: PairManager, cmd: String): String = try {
        val s = m.openStream("shell:")
        log("A: openStream ok")
        val sb = StringBuilder()
        val reader = Thread {
            try {
                val buf = ByteArray(4096); val ins = s.openInputStream()
                while (true) { val n = ins.read(buf); if (n < 0) break; synchronized(sb) { sb.append(String(buf, 0, n)) } }
            } catch (e: Throwable) { log("A: read error ${e.javaClass.simpleName}: ${e.message}") }
        }.apply { isDaemon = true; start() }
        val os = s.openOutputStream()
        os.write(("( $cmd )\nexit\n").toByteArray())   // spasi: "((" dibaca shell sebagai aritmetika; os.flush()
        reader.join(5000)
        try { s.close() } catch (_: Exception) {}
        synchronized(sb) { sb.toString() }.also { log("A: output ${it.length} byte: " + it.replace(Regex("\\s+"), " ").takeLast(250)) }
    } catch (e: Throwable) { log("A: error ${e.javaClass.simpleName}: ${e.message}"); "" }

    /** Satu perintah (service "shell:<cmd>"). */
    private fun shellOneShot(m: PairManager, cmd: String): String = try {
        val s = m.openStream("shell:$cmd")
        log("B: openStream ok")
        val out = try { s.openInputStream().use { String(it.readBytes()) } } catch (e: Throwable) { log("B: read error ${e.javaClass.simpleName}: ${e.message}"); "" }
        try { s.close() } catch (_: Exception) {}
        log("B: output ${out.length} byte"); out
    } catch (e: Throwable) { log("B: error ${e.javaClass.simpleName}: ${e.message}"); "" }

    private fun waitServer(secs: Int): Boolean {
        repeat(secs) {
            val latch = java.util.concurrent.CountDownLatch(1)
            Injector.connectAsync { latch.countDown() }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (Injector.connected) return true
            Thread.sleep(500)
        }
        return false
    }

    /** Pairing dengan kode, sambung ke adbd, lalu jalankan Server. Mengembalikan pesan galat, atau null jika berhasil. */
    fun pairAndStart(c: Context, host: String, port: Int, code: String): String? {
        synchronized(dbg) { dbg.setLength(0) }
        return try {
            val m = manager(c)
            val paired: Any? = m.pair(host, port, code)
            log("pair=$paired")
            if (paired == false) return c.getString(R.string.err_pair_failed)
            val connected: Any? = m.autoConnect(c, 20000L)
            val isConn = try { m.javaClass.getMethod("isConnected").invoke(m).toString() } catch (_: Throwable) { "?" }
            log("autoConnect=$connected isConnected=$isConn")
            if (connected == false) return c.getString(R.string.err_connect_failed) + "\n" + dbg
            Thread.sleep(500)
            val cmd = startCmd(c)
            shellInteractive(m, cmd)
            var ok = waitServer(8)
            if (!ok) {
                // diagnosis lewat shell interaktif (jalur yang terbukti menghasilkan output)
                val d = shellInteractive(m, "echo PS:; ps -A | grep app_process; echo LOG:; cat /data/local/tmp/clonepad.log; echo PORT:; grep -i ':BAD7' /proc/net/tcp /proc/net/tcp6")
                log("diagnosis: " + d.replace(Regex("\\s+"), " ").takeLast(700))
            }
            try { m.disconnect() } catch (_: Exception) {}
            if (ok) null else c.getString(R.string.err_server_inactive) + "\n" + synchronized(dbg) { dbg.toString() }
        } catch (e: Throwable) { c.getString(R.string.err_generic, "${e.javaClass.simpleName}: ${e.message}") + "\n" + synchronized(dbg) { dbg.toString() } }
    }
}
