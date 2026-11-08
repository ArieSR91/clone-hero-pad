package com.clonehero.pad

import android.app.Application
import android.util.Log
import java.io.File

/** Menyimpan galat yang membuat aplikasi berhenti, agar bisa dibaca lagi di layar utama. */
class ClonePadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("Thread: ${t.name}\n" + Log.getStackTraceString(e)) } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}
