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
 * Every call runs on one dedicated thread. Python holds the GIL anyway, and
 * bridge.py keeps its state at module level on the understanding that calls
 * never overlap -- serialising them here is what makes that true.
 */
object Engine {
    private val pythonThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "python-engine")
    }.asCoroutineDispatcher()

    private var bridge: PyObject? = null

    /** Milliseconds the first `import bridge` (and so tvdinner + requests) took, or -1 before it has run. */
    var importMs: Long = -1
        private set

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
}
