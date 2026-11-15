package com.issinoho.radiotimes

import android.os.SystemClock
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The Kotlin side of the boundary with tvdinner. Everything goes through
 * `bridge.py`; nothing here knows tvdinner's own API.
 *
 * Calls run on one of two dedicated threads. bridge.py keeps its state at
 * module level on the understanding that calls touching the same state
 * never overlap, so live TV (`call`) and Plex (`callPlex`) each get one
 * thread: their state is disjoint, and a Plex request shouldn't queue for
 * ~15 s behind a guide refresh on a Fire TV Stick. (The GIL is released
 * while either waits on the network.)
 */
object Engine {
    private val pythonThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "python-engine")
    }.asCoroutineDispatcher()
    private val plexThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "python-plex")
    }.asCoroutineDispatcher()

    private var bridge: PyObject? = null

    /** Milliseconds the first `import bridge` (and so tvdinner + requests) took, or -1 before it has run. */
    var importMs: Long = -1
        private set

    @Synchronized
    private fun module(): PyObject = bridge ?: run {
        val started = SystemClock.elapsedRealtime()
        Python.getInstance().getModule("bridge").also {
            importMs = SystemClock.elapsedRealtime() - started
            bridge = it
        }
    }

    /** Call a bridge function. Always returns its JSON string; bridge.py never raises. */
    suspend fun call(function: String, vararg args: Any?): String = withContext(pythonThread) {
        module().callAttr(function, *args).toString()
    }

    /** A `plex_*` bridge function, on the Plex thread. */
    suspend fun callPlex(function: String, vararg args: Any?): String = withContext(plexThread) {
        module().callAttr(function, *args).toString()
    }
}
