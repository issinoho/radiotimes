package com.issinoho.radiotimes

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

const val TAG = "radiotimes"

class RadiotimesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Started here rather than lazily so the cost lands once, at launch,
        // and is measured: how long CPython takes to come up on a Fire TV
        // Stick is one of the things Phase 0 exists to find out.
        val started = SystemClock.elapsedRealtime()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        pythonStartMs = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "python started in ${pythonStartMs} ms")
    }

    companion object {
        var pythonStartMs: Long = -1
            private set
    }
}
