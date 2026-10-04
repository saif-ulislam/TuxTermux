package dev.tuxterm

/**
 * JNI surface for TuxTerm.
 *
 * Declared on an `object` so every function is an instance method: the C side
 * receives `jobject self` as its second argument. The library is loaded here,
 * before any call can dispatch, rather than in an activity where a static
 * initializer would race first use.
 */
object Native {

    init {
        System.loadLibrary("tuxterm")
    }

    /** Returned by [ptyWait] while the shell has not exited yet. */
    const val STILL_RUNNING = -2

    /**
     * Start a process with [argv] on a new pseudo-terminal and return
     * `[pid, masterFd]`. The caller owns the returned descriptor.
     * Returns `null` when the PTY could not be created at all.
     */
    external fun ptySpawn(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): IntArray?

    /** Reap [pid]. With [noHang] returns [STILL_RUNNING] if it is still alive. */
    external fun ptyWait(pid: Int, noHang: Boolean): Int

    external fun ptyKill(pid: Int, signal: Int)

    external fun ptyResize(fd: Int, rows: Int, cols: Int): Boolean

    external fun vtermCreate(rows: Int, cols: Int): Long
    external fun vtermDestroy(handle: Long)
    external fun vtermInput(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    external fun vtermResize(handle: Long, rows: Int, cols: Int)

/**
 * Flatten the emulator grid into [cells] and write metadata into
 * [meta] = `[rows, cols, cursorRow, cursorCol, cursorVisible, epoch, bell,
 * altscreen]`. Returns the cell count, or -1 when a buffer is too small.
 *
 * [cells] holds CELLS_PER ints per cell; see [TermGrid].
 */
external fun vtermSnapshot(handle: Long, cells: IntArray, meta: IntArray): Int

/** Lines currently pushed off the top of the screen and still retained. */
external fun vtermScrollbackCount(handle: Long): Int

/**
 * Copy up to [maxLines] scrollback lines ending [skipNewest] lines before
 * the newest into [cells], padded or truncated to the current column
 * count, oldest first. Returns the line count, or -1 when the buffer is too
 * small.
 */
external fun vtermScrollbackTail(
    handle: Long,
    skipNewest: Int,
    maxLines: Int,
    cells: IntArray,
): Int

/**
 * Copy content rows [fromRow]..[toRow] (scrollback first, then the live
 * screen) into [cells], plus one wrap flag per row into [flags], in a single
 * locked read. Row 0 is the oldest retained scrollback line; cells are padded
 * or truncated to the current column count. Returns the row count, or -1 when
 * a buffer is too small.
 */
external fun vtermCopyRange(
    handle: Long,
    fromRow: Int,
    toRow: Int,
    cells: IntArray,
    flags: BooleanArray,
): Int
}

/**
 * Ints per packed cell, in this order: base codepoint, combining codepoint (0
 * when there is none), fg, bg, attrs. Must match `CELLS_PER` in
 * `tux_vterm.c`.
 */
const val CELLS_PER = 5

/** Index of each field within one packed cell. */
object Cell {
const val CP0 = 0
const val CP1 = 1
const val FG = 2
const val BG = 3
const val ATTRS = 4
}

/** Attribute bits produced by `tux_vterm.c`; must stay in sync with it. */
object TermAttrs {
const val BOLD = 1 shl 0
const val UNDERLINE = 1 shl 1
const val ITALIC = 1 shl 2
const val REVERSE = 1 shl 3
const val STRIKE = 1 shl 4
const val CONCEAL = 1 shl 5
const val BLINK = 1 shl 6
const val CURSOR = 1 shl 7
const val WIDTH_SHIFT = 8
}
