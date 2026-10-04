package dev.tuxterm

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * One flattened emulator frame, [CELLS_PER] ints per cell: see [Cell]. The
 * array is owned by the session and reused between frames, so a consumer must
 * read it before asking for another snapshot rather than holding it.
 */
class TermGrid(
    val rows: Int,
    val cols: Int,
    val cells: IntArray,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val bell: Int,
    val altscreen: Boolean,
)

/**
 * A live shell: one pseudo-terminal, one reader thread, one emulator.
 *
 * There is deliberately no second execution path. Every byte the shell produces
 * arrives on the master descriptor, goes straight into libvterm, and is drawn
 * from the emulator grid — nothing is written to a file and re-read, so there
 * is no intermediate copy that can silently drop output.
 *
 * [status] and [bytesRead] are always populated and surfaced on screen, so a
 * session that fails to produce text says why instead of rendering nothing.
 */
class TerminalSession(
    initialRows: Int,
    initialCols: Int,
) {

    /**
     * The emulator handle, or 0 once it has been destroyed. Volatile because
     * the reader thread publishes against it from outside this object's
     * monitor; every other reader is [Synchronized], which close() also is.
     */
    @Volatile
    private var handle: Long = Native.vtermCreate(initialRows, initialCols)
    private var master: ParcelFileDescriptor? = null
    private var out: OutputStream? = null
    private var readerThread: Thread? = null

    /** Set before the reader is joined and never cleared, so a late-waking
     *  reader can tell teardown from an ordinary shell exit. */
    @Volatile
    private var closing = false

    /**
     * Scratch buffer for [snapshot], reused across frames. A fresh IntArray per
     * redraw was ~90 KB of garbage on every chunk the shell wrote; it is sized
     * for the geometry actually in use, so only a resize reallocates.
     */
    private var frameCells = IntArray(initialRows * initialCols * CELLS_PER)
    private val frameMeta = IntArray(8)

    /**
     * Held by the reader around its use of the handle and by [close] around the
     * join plus [vtermDestroy], so the free can never overtake an in-flight
     * vtermInput. Every other caller is already [Synchronized] on `this`, which
     * close() holds too; only the reader escapes that monitor.
     */
    private val handleLock = ReentrantLock()

    var pid: Int = -1
        private set

    var rows: Int = initialRows
        private set

    var cols: Int = initialCols
        private set

    /** Why the session is in its current state. Never blank. */
    @Volatile
    var status: String = "created, not started"
        private set

    /** Set once the shell has actually exited. */
    @Volatile
    var exitCode: Int? = null
        private set

    /** Bytes read off the PTY master. Stays 0 exactly when output is lost. */
    @Volatile
    var bytesRead: Long = 0L
        private set

    @Volatile
    private var alive = true

    private val dirty = AtomicLong(0L)

    /** Monotonic marker the UI watches to know a redraw is warranted. */
    fun generation(): Long = dirty.get()

    fun isAlive(): Boolean = alive

    fun start(
        argv: List<String>,
        cwd: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): Boolean {
        if (pid >= 0) {
            status = "already started (pid $pid)"
            return false
        }

        val env = buildEnvironment(cwd, extraEnv)
        val spawned = Native.ptySpawn(
            argv.toTypedArray(),
            env.toTypedArray(),
            cwd,
            rows,
            cols,
        )
        if (spawned == null) {
            status = "spawn failed: could not create a pseudo-terminal"
            return false
        }

        pid = spawned[0]
        val fd = spawned[1]
        val pfd = try {
            ParcelFileDescriptor.adoptFd(fd)
        } catch (t: Throwable) {
            status = "start failed: $t"
            return false
        }
        return try {
            master = pfd
            out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            readerThread = Thread({ pump(input) }, "tuxterm-read").apply {
                isDaemon = true
                start()
            }
            status = "running (pid $pid)"
            true
        } catch (t: Throwable) {
            status = "start failed: $t"
            try {
                pfd.close()
            } catch (_: Throwable) {
                // The descriptor is unusable either way.
            }
            false
        }
    }

    private fun buildEnvironment(cwd: String, extraEnv: Map<String, String>): List<String> {
        val map = HashMap(System.getenv())
        map["TERM"] = "xterm-256color"
        map["HOME"] = cwd
        map["SHELL"] = SHELL
        map["LANG"] = "en_US.UTF-8"
        map.putIfAbsent("PATH", "/system/bin:/system/xbin")
        // Caller entries win. proot is not self-contained: it links against
        // libtalloc and libandroid-shmem from the bundle rather than from the
        // platform, so LD_LIBRARY_PATH has to point at them.
        map.putAll(extraEnv)
        return map.map { "${it.key}=${it.value}" }
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(8192)
        try {
            while (alive) {
                val n = input.read(buf)
                if (n < 0) {
                    status = "shell closed its terminal"
                    break
                }
                if (n == 0) continue
                bytesRead += n
                // close() can land between the loop test and here. Re-check
                // under the lock and let close() own the teardown, otherwise
                // this feeds bytes into an emulator it is about to free.
                handleLock.lock()
                try {
                    val live = handle
                    if (live == 0L || closing) break
                    Native.vtermInput(live, buf, 0, n)
                } finally {
                    handleLock.unlock()
                }
                dirty.incrementAndGet()
            }
        } catch (e: IOException) {
            // Linux reports EIO on a PTY master once the slave side is gone;
            // that is the shell's normal goodbye, not an application fault.
            status = "read ended: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: Throwable) {
            status = "reader failed: $e"
        } finally {
            reap()
        }
    }

    private fun reap() {
        var code = Native.STILL_RUNNING
        var waited = 0
        while (code == Native.STILL_RUNNING && waited < REAP_TIMEOUT_MS) {
            code = Native.ptyWait(pid, noHang = true)
            if (code == Native.STILL_RUNNING) {
                try {
                    Thread.sleep(REAP_POLL_MS.toLong())
                } catch (_: InterruptedException) {
                    break
                }
                waited += REAP_POLL_MS
            }
        }

        when {
            code == Native.STILL_RUNNING -> status =
                "output stopped while the shell (pid $pid) is still running"
            code < 0 -> status = "could not read the shell's exit status"
            else -> {
                exitCode = code
                status = "shell exited with code $code"
            }
        }
        dirty.incrementAndGet()
    }

    fun send(bytes: ByteArray) {
        if (!alive) return
        val stream = out
        if (stream == null) {
            status = "cannot write: no terminal output stream"
            dirty.incrementAndGet()
            return
        }
        try {
            stream.write(bytes)
            stream.flush()
        } catch (e: IOException) {
            status = "write failed: ${e.message ?: e.javaClass.simpleName}"
            dirty.incrementAndGet()
        }
    }

    fun send(text: String) = send(text.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0 || (newRows == rows && newCols == cols)) return
        rows = newRows
        cols = newCols
        if (!alive || handle == 0L) return
        master?.let { Native.ptyResize(it.fd, newRows, newCols) }
        Native.vtermResize(handle, newRows, newCols)
        dirty.incrementAndGet()
    }

    /** Read one frame off the emulator. Returns null if nothing can be drawn. */
    @Synchronized
    fun snapshot(): TermGrid? {
        if (!alive || handle == 0L) return null
        val gridRows = rows
        val gridCols = cols
        val need = gridRows * gridCols * CELLS_PER
        // Reuse the buffer while the geometry holds. The Canvas draw pass and
        // this both run on the main thread, so a redraw overwrites the same
        // array between frames rather than racing it. A resize gets a fresh
        // array instead, which is what keeps a stale TermGrid from reading a
        // buffer that no longer matches the rows/cols it reports.
        if (frameCells.size != need) {
            frameCells = IntArray(need)
        }
        val cells = frameCells
        val meta = frameMeta
        if (Native.vtermSnapshot(handle, cells, meta) < 0) return null
        return TermGrid(
            rows = meta[0],
            cols = meta[1],
            cells = cells,
            cursorRow = meta[2],
            cursorCol = meta[3],
            cursorVisible = meta[4] != 0,
            bell = meta[6],
            altscreen = meta[7] != 0,
        )
    }

    /** Lines currently held in scrollback. O(1). */
    @Synchronized
    fun scrollbackCount(): Int {
        if (!alive || handle == 0L) return 0
        return Native.vtermScrollbackCount(handle)
    }

    /**
     * Copy up to [maxLines] scrollback lines ending [skipNewest] lines before
     * the newest into [cells], padded to the current column count and ordered
     * oldest first. Returns how many lines were written, or -1 if [cells] is
     * too small.
     */
    @Synchronized
    fun scrollbackTail(skipNewest: Int, maxLines: Int, cells: IntArray): Int {
        if (!alive || handle == 0L || maxLines <= 0) return 0
        return Native.vtermScrollbackTail(handle, skipNewest, maxLines, cells)
    }

    /**
     * Copy content rows [fromRow]..[toRow] as padded cells plus one wrap flag
     * per row into [flags] (row continues the one above it), in a single
     * locked read. Returns the row count, or -1 if a buffer is too small.
     */
    @Synchronized
    fun copyRange(fromRow: Int, toRow: Int, cells: IntArray, flags: BooleanArray): Int {
        if (!alive || handle == 0L || toRow < fromRow) return 0
        return Native.vtermCopyRange(handle, fromRow, toRow, cells, flags)
    }

    @Synchronized
    fun close() {
        if (!alive && handle == 0L) return
        alive = false
        // Unlike `alive`, this is never cleared: a reader that wakes after the
        // join gave up must still recognise teardown rather than treat the
        // closed emulator as a live one.
        closing = true
        try {
            if (pid > 0) Native.ptyKill(pid, 15)
        } catch (_: Throwable) {
            // The shell may already be gone.
        }
        try {
            master?.close()
        } catch (_: Throwable) {
            // Nothing further to release.
        }
        try {
            readerThread?.join(JOIN_TIMEOUT_MS)
        } catch (_: Throwable) {
            // Do not block shutdown on a wedged reader.
        }
        // The reader may still be inside vtermInput. Taking handleLock here
        // waits for that call to finish, then keeps the handle off zero until
        // the destroy is done, so the two can never overlap.
        handleLock.lock()
        try {
            val doomed = handle
            if (doomed != 0L) {
                handle = 0L
                try {
                    Native.vtermDestroy(doomed)
                } catch (_: Throwable) {
                    // Already destroyed.
                }
            }
        } finally {
            handleLock.unlock()
        }
        status = "closed"
    }

    companion object {
        /** Android's shell lives here on every supported API level. */
        const val SHELL = "/system/bin/sh"

        private const val REAP_TIMEOUT_MS = 3000
        private const val REAP_POLL_MS = 20

        /**
         * How long close() waits for the reader before giving up on it. Short
         * on purpose: a shell that ignores SIGTERM must not wedge the UI
         * thread, and the handle lock makes the give-up safe.
         */
        private const val JOIN_TIMEOUT_MS = 500L
    }
}
