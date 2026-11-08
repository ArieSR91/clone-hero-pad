package com.clonehero.pad

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat

/**
 * Mencari layanan "Pasangkan perangkat dengan kode" (mDNS _adb-tls-pairing._tcp). Saat kode tampil di layar,
 * muncul notifikasi dengan kolom isian untuk kode 6 angka (seperti Shizuku).
 */
class PairingService : Service() {
    private lateinit var nsd: NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    private fun post(text: String, ongoing: Boolean = false) =
        getSystemService(NotificationManager::class.java).notify(
            CODE_ID,
            NotificationCompat.Builder(this, CH_CODE)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(getString(R.string.pair_title)).setContentText(text.lineSequence().first()).setOngoing(ongoing)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true).build()
        )

    /** Kode dari notifikasi: dikerjakan di sini (service latar depan, tanpa batas waktu broadcast). */
    private fun handleCode(i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence("code")?.toString()?.trim().orEmpty()
        val host = i.getStringExtra("host") ?: "127.0.0.1"
        val port = i.getIntExtra("port", 0)
        if (code.length != 6 || port == 0) { post(getString(R.string.pair_bad_code)); return }
        main.removeCallbacksAndMessages(null)           // jangan berhenti sendiri saat pairing berjalan
        post(getString(R.string.pair_processing), true)
        Thread {
            val err = AdbPair.pairAndStart(applicationContext, host, port, code)
            main.post {
                if (err == null) { post(getString(R.string.pair_ok)); Injector.connectAsync(); stopSelf() }
                else { post(getString(R.string.pair_fail, err)); main.postDelayed({ stopSelf() }, 60_000L) }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        Injector.init(this)
        if (intent?.action == ACTION_CODE) { handleCode(intent); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pair_bg", getString(R.string.ch_pair_bg), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CODE, getString(R.string.ch_pair_code), NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(this, "pair_bg")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.pair_searching))
            .setContentText(getString(R.string.pair_hint))
            .setOngoing(true).build()
        ServiceCompat.startForeground(
            this, 2, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        startDiscovery()
        main.postDelayed({ stopSelf() }, 3 * 60 * 1000L)   // berhenti sendiri setelah 3 menit
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        if (listener != null) return
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {
                getSystemService(NotificationManager::class.java).cancel(CODE_ID)   // dialog kode ditutup
            }
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: "127.0.0.1"
                        main.post { showInput(host, i.port) }
                    }
                })
            }
        }
        listener = l
        try { nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    private fun showInput(host: String, port: Int) {
        val remote = RemoteInput.Builder("code").setLabel(getString(R.string.pair_label)).build()
        val pi = PendingIntent.getService(
            this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE)
                .putExtra("host", host).putExtra("port", port),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val action = NotificationCompat.Action.Builder(0, getString(R.string.pair_action), pi).addRemoteInput(remote).build()
        val n = NotificationCompat.Builder(this, CH_CODE)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.pair_title))
            .setContentText(getString(R.string.pair_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(action).setAutoCancel(false).build()
        getSystemService(NotificationManager::class.java).notify(CODE_ID, n)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        super.onDestroy()
    }

    companion object { const val CH_CODE = "pair_code"; const val CODE_ID = 3; const val ACTION_CODE = "com.clonehero.pad.PAIR_CODE" }
}
