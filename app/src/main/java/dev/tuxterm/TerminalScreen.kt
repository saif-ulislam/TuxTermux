package dev.tuxterm

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ScreenBg = Color(0xFF0B0F14)
private val StatusBarBg = Color(0xFF111820)
private val KeyBg = Color(0xFF1B2430)
private val TextDim = Color(0xFF9FB0C0)
private val Fault = Color(0xFFFF6B6B)
// Selection bar: light surface, dark labels, matching the platform's own
// text-selection bar so a terminal selection reads like every other one.
private val MenuBg = Color(0xFFF2F2F2)
private val MenuFg = Color(0xFF1A1A1A)
private val MenuFgDim = Color(0xFF9A9A9A)
private val HandleBlue = Color(0xFF2090F0)
// URLs in terminal output, so a tappable link is visible as one.
private const val LINK_FG = 0xFF4FA8FF.toInt()
// Selection fill: opaque light grey, with the glyphs knocked out dark.
private val SelHighlight = Color(0xFFD8DEE6)

// Vertical gap between the two key rows, and between pages. The diagnostics
// page is built from the same two rows so its height matches exactly; a page
// that stood taller would resize the PTY on every swipe in and out.
private val PAGE_GAP = 4.dp

// Built from code points rather than written as escape sequences so the source
// carries no control bytes.
private val ESC = Char(27).toString()
private val TAB = Char(9).toString()
private val ENTER = Char(13).toString()
private val DEL = Char(127).toString()
private val CTRL_C = Char(3).toString()
private val CTRL_D = Char(4).toString()

/**
 * The whole app: one terminal over a three-page key pad. Page 1 is the common
 * control keys, page 2 the function and paging keys, page 3 the session
 * diagnostics; a swipe moves between them.
 *
 * The status read-out is load-bearing. It reports the shell's state and how
 * many bytes actually came back from the PTY, turning red while a session that
 * claims to be running has delivered nothing -- the one condition a blank
 * screen alone cannot tell apart from a program that simply has not printed
 * yet. It lives on the pad's third page, which the page dots keep discoverable.
 */
@Composable
fun TerminalApp() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("preparing Ubuntu") }
    var ready by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Bumped to run provisioning again after a failed attempt.
    var attempt by remember { mutableIntStateOf(0) }
    // Completed by the picker callback; awaited by the provisioning coroutine
    // that asked for the file. Held in state because the two run in different
    // places -- the request comes from an IO thread, the answer from the main
    // thread's activity result.
    var pendingPick by remember { mutableStateOf<CompletableDeferred<File?>?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val deferred = pendingPick
        pendingPick = null
        if (uri == null) {
            deferred?.complete(null)
        } else {
            // Copy into app storage now. The retry that reads this runs off the
            // main thread well after the callback, and a content:// stream is
            // not guaranteed readable then; the grant is taken persistable for
            // the same reason.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val copied = runCatching {
                val dest = File(context.cacheDir, Ubuntu.TARBALL)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest.takeIf { it.isFile && it.length() > 0L }
            }.getOrNull()
            deferred?.complete(copied)
        }
    }

    // Provisioning runs once: unpack proot, fetch and verify the rootfs, extract
    // it. The terminal is not created until that succeeds, so a failure surfaces
    // as text instead of a shell that never starts.
    LaunchedEffect(attempt) {
        try {
            withContext(Dispatchers.IO) {
                Ubuntu.provision(
                    filesDir = context.filesDir,
                    assets = context.assets,
                    version = bundleVersion(context),
                    onProgress = { notice = it },
                    // Only called when the download could not be completed: a
                    // hosted link that expired or throttled, a blocked network,
                    // a captive portal answering with a login page. Asking for
                    // the file by hand is the one recovery the reader can
                    // actually perform, so it is offered instead of a dead end.
                    onManualFileNeeded = {
                        val deferred = CompletableDeferred<File?>()
                        withContext(Dispatchers.Main) {
                            pendingPick = deferred
                            picker.launch(
                                arrayOf(
                                    "application/gzip",
                                    "application/x-gzip",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        }
                        deferred.await()
                    },
                )
            }
            ready = true
        } catch (t: Throwable) {
            failure = t.message ?: t.toString()
        }
    }

    when {
        failure != null -> ProvisionFailed(
            message = failure!!,
            onRetry = {
                failure = null
                notice = "preparing Ubuntu"
                attempt++
            },
            onChooseFile = {
                failure = null
                notice = "waiting for the chosen file"
                attempt++
            },
        )
        !ready -> Provisioning(notice)
        else -> TerminalBody()
    }
}/**
 * Identifies the packaged tool bundle so provisioning can unpack it once.
 *
 * `lastUpdateTime` rather than `versionCode`: an app replaced by a reinstall of
 * the same build keeps its version number, and its files directory is still
 * wiped, so versionCode alone would let a stale proot survive an upgrade.
 */
/**
 * Link properties of the network currently carrying traffic, or null when none
 * can be determined. The app is not restricted to Wi-Fi, so rather than assume
 * an interface the default network is asked for directly.
 */
private fun activeLinkProperties(context: android.content.Context): android.net.LinkProperties? {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val network = cm.activeNetwork ?: return null
    return cm.getLinkProperties(network)
}

private fun bundleVersion(context: android.content.Context): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

/** Single-line progress while Ubuntu is being provisioned. */
@Composable
private fun Provisioning(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = TextDim, fontSize = 14.sp)
    }
}

/** Terminal-coloured report of a provisioning failure. */
@Composable
private fun ProvisionFailed(
    message: String,
    onRetry: () -> Unit,
    onChooseFile: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ubuntu setup failed\n\n$message", color = Fault, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            // Both ways out are offered because they fail differently: a retry
            // covers a transient network, and choosing the file covers a link
            // that is simply not usable from this device.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProvisionAction("Retry download", onRetry)
                ProvisionAction("Choose file", onChooseFile)
            }
        }
    }
}

@Composable
private fun ProvisionAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = TextDim,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(KeyBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun TerminalBody() {
    val context = LocalContext.current
    val session = remember {
        val home = context.filesDir.absolutePath
        // Before the shell starts, hand the sandbox the resolver the device is
        // really using. The base image's public nameservers are frequently
        // unreachable from mobile networks, which is what made `apt update`
        // hang on DNS.
        Ubuntu.syncResolvConf(context.filesDir, activeLinkProperties(context))
        val launch = Ubuntu.launch(context.filesDir)
        TerminalSession(initialRows = 24, initialCols = 80).also {
            it.start(launch.first, home, launch.second)
        }
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    var grid by remember { mutableStateOf<TermGrid?>(null) }
    var status by remember { mutableStateOf(session.status) }
    var bytesRead by remember { mutableStateOf(session.bytesRead) }
    var lastGeneration by remember { mutableStateOf(Long.MIN_VALUE) }
    // How many lines the emulator has pushed off the top of the screen. It
    // changes the layout of the scroll surface, so it rides the same frame
    // loop as the grid instead of being read during composition.
    var sbCount by remember { mutableStateOf(0) }

    // Frame loop: refresh the cheap scalars every frame, re-read the emulator
    // grid only when native code reports that it changed.
    // URLs the sandbox cannot open by itself are handed to Android instead.
    //
    // A CLI that wants a browser calls xdg-open, which inside the rootfs finds
    // no browser at all and fails silently -- Command Code's own login is the
    // case that surfaced this, and it looks like nothing happened. The app is
    // the only layer that can reach a real browser, so it watches the stream
    // and opens what the program meant to open.
    LaunchedEffect(session, context) {
        val opened = HashSet<String>()
        val seen = StringBuilder()
        while (true) {
            withFrameNanos { }
            val frame = grid ?: continue
            val text = frame.rowsText()
            if (text.isEmpty() || text == seen.toString()) continue
            // Only the lines that were not there before: a URL already on
            // screen must not reopen every time something else redraws, and a
            // login prompt reprints its link while it waits for the browser.
            val before = seen.toString()
            val fresh = if (text.startsWith(before)) text.substring(before.length) else text
            seen.setLength(0)
            seen.append(text)

            URL_RE.findAll(fresh).forEach { m ->
                val url = trimUrlEnd(m.value)
                // Loopback is skipped here on purpose. The login link carries a
                // callback to 127.0.0.1, which the device shares with the
                // sandbox, so the phone's browser fails to reach the listener
                // and the flow hangs; opening it elsewhere is completable. An
                // explicit tap is still allowed to open it (see below).
                val loopback = url.contains("127.0.0.1") || url.contains("localhost") ||
                    url.contains("[::1]")
                if (!loopback && opened.add(url)) openUrl(context, url)
            }
        }
    }

    LaunchedEffect(session) {
        while (true) {
            withFrameNanos { }
            status = session.status
            bytesRead = session.bytesRead
            val generation = session.generation()
            if (generation != lastGeneration) {
                val frame = session.snapshot()
                // Only mark the generation consumed once a frame really came
                // back. Recording it first would make a failed read (handle
                // gone, buffer refused) skip the retry and strand the terminal
                // on the last good grid.
                if (frame != null) {
                    lastGeneration = generation
                    grid = frame
                }
            }
            sbCount = session.scrollbackCount()
        }
    }

    // One FocusRequester for the whole screen. It is attached to the text field
    // inside TerminalGrid and requested by ExtraKeys after a chip tap, so focus
    // returns to the input surface. A second FocusRequester that is never
    // attached to a node throws IllegalStateException on requestFocus().
    val focus = remember { FocusRequester() }

    // Scroll position, measured from the top of the content (scrollback lines
    // then the live screen), and whether the reader is pinned to the bottom.
    // Both live here rather than inside TerminalGrid so this row can offer a
    // way back to live output.
    val scrollPx = remember { mutableStateOf(0f) }
    val following = remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ScreenBg),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TerminalGrid(
                session = session,
                grid = grid,
                sbCount = sbCount,
                scrollPx = scrollPx,
                following = following,
                focus = focus,
                modifier = Modifier.fillMaxSize(),
            )
            // Reading history is a place the reader can otherwise get stuck: new
            // output keeps arriving off-screen and nothing says where it went.
            //
            // This sits between the grid and the key pad rather than over the
            // pad, because the pad is a live key surface -- a button laid on top
            // of ESC and the arrows both hides them and swallows their taps.
            if (!following.value) {
                Text(
                    text = "↑ scrollback · tap for live",
                    color = TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(KeyBg)
                        .clickable(onClick = { following.value = true })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        KeyPad(
            session = session,
            focus = focus,
            status = status,
            bytesRead = bytesRead,
            rows = grid?.rows ?: session.rows,
            cols = grid?.cols ?: session.cols,
            following = following.value,
        )
    }
}

@Composable
private fun TerminalGrid(
    session: TerminalSession,
    grid: TermGrid?,
    sbCount: Int,
    scrollPx: MutableState<Float>,
    following: MutableState<Boolean>,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var typed by remember { mutableStateOf("") }

    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = density.run { 13.sp.toPx() }
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
    }
    // Captured before anything else can overwrite it: the glyph pass resets to
    // this, so one bold cell does not leave the rest of the screen bold.
    val normalTypeface = paint.typeface
    val boldTypeface = remember(paint) { Typeface.create(paint.typeface, Typeface.BOLD) }

    val metrics = remember(paint) {
        val font = paint.fontMetrics
        GridMetrics(
            advance = paint.measureText("M").coerceAtLeast(1f),
            textHeight = (font.descent - font.ascent).coerceAtLeast(1f),
            ascent = font.ascent,
        )
    }

    // Geometry the pan gesture needs. The gesture hangs on this composable's
    // modifier, which is assembled outside the BoxWithConstraints scope where
    // the cell size is worked out, so it reads these instead.
    val panCellH = remember { mutableStateOf(0f) }
    val panViewH = remember { mutableStateOf(0f) }
    val panLiveRows = remember { mutableStateOf(0) }
    val panCellW = remember { mutableStateOf(0f) }
    val panCols = remember { mutableStateOf(0) }

    // One publisher for the scrollback height the gesture should clamp
    // against, so the pan limit cannot disagree with what is on screen. Zero
    // while the alternate screen is active, where no history is displayed.
    val panVisibleSb = remember { mutableStateOf(0) }

    // Long-press menu, selection and the system clipboard. The menu replaces
    // the system text toolbar: the field under the grid is always empty,
    // which is why Android offered exactly one action, "Paste".
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val gridState = rememberUpdatedState(grid)
    // The selection menu is a row in this layout, not a popup. A popup needs
    // focus to receive taps, and giving it focus pulls the IME away and makes
    // the window remeasure -- the show/hide flap that used to make the byte
    // counter climb on its own. In-layout it composites above the canvas and
    // below the text field, so the field (which needs no focus for these taps)
    // receives them.
    var menuOpen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<TermSel?>(null) }
    // Where the selection bar sits in the terminal's own coordinates, so the
    // gesture can tell a press meant for the menu from one meant for the grid.
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    var windowPos by remember { mutableStateOf(Offset.Zero) }

    // The field's toolbar contract is silenced rather than wired: its
    // showMenu/hide calls follow the field's own focus and selection churn
    // (hide() on every focus change is what made the menu flap), so the menu
    // opens from this composable's own long-press instead.
    val terminalToolbar = remember {
        object : TextToolbar {
            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
            ) = Unit

            override fun hide() = Unit

            override val status: TextToolbarStatus
                get() = TextToolbarStatus.Hidden
        }
    }

    // The pan listens on PointerEventPass.Initial - parent before child - so a
    // drag is seen here first and consumed before the text field underneath
    // can claim it for a selection nobody can see. That competition is what
    // left the screen impossible to scroll.
    val panner = Modifier.pointerInput(sbCount) {
        // The pointerInput block is only a suspend lambda; coroutineScope
        // supplies the CoroutineScope the long-press timer launches into.
        coroutineScope {
        val scope = this

        fun panBy(delta: Float) {
            val max = ((panVisibleSb.value + panLiveRows.value) * panCellH.value - panViewH.value)
                .coerceAtLeast(0f)
            val next = (scrollPx.value + delta).coerceIn(0f, max)
            scrollPx.value = next
            following.value = next >= max - 0.5f
        }

        // Content row/col under a point, in [scrollback][live] coordinates.
        fun contentPoint(x: Float, y: Float): IntArray? {
            val ch = panCellH.value
            val cw = panCellW.value
            val cols = panCols.value
            val sb = panVisibleSb.value
            if (ch <= 0f || cw <= 0f || cols <= 0) return null
            val lastRow = sb + panLiveRows.value - 1
            if (lastRow < 0) return null
            val row = ((scrollPx.value + y) / ch).toInt().coerceIn(0, lastRow)
            val col = (x / cw).toInt().coerceIn(0, cols - 1)
            return intArrayOf(row, col)
        }

        // Which selection handle, if any, is under a point. The bars are drawn
        // at the outward edge of each endpoint cell, so the target is centred
        // there and widened vertically to cover the whole line height.
        fun handleAt(x: Float, y: Float): Handle? {
            val sel = selection ?: return null
            val ch = panCellH.value
            val cw = panCellW.value
            if (ch <= 0f || cw <= 0f) return null
            val top = panViewH.value
            val touchR = HANDLE_TOUCH_PX
            val barR = HANDLE_BAR_PX

            fun near(row: Int, col: Int, onLeftEdge: Boolean): Boolean {
                val cy = row * ch - scrollPx.value + ch / 2f
                val cx = if (onLeftEdge) col * cw - barR / 2f else (col + 1) * cw + barR / 2f
                // Outside the viewport nothing is drawn, so nothing is grabbable.
                if (cy < -ch || cy > top + ch) return false
                return abs(y - cy) <= touchR && abs(x - cx) <= touchR
            }

            if (near(sel.r0, sel.c0, onLeftEdge = true)) return Handle.START
            if (near(sel.r1, sel.c1, onLeftEdge = false)) return Handle.END
            return null
        }

        // The non-space run containing col; a touch on a space grabs the run
        // to its right, then the one to its left.
        fun selectWord(row: Int, col: Int): TermSel? {
            val g = gridState.value ?: return null
            val n = g.cols
            if (n <= 0) return null
            val cells = IntArray(n * CELLS_PER)
            val flags = BooleanArray(1)
            if (session.copyRange(row, row, cells, flags) <= 0) return null

            fun ch(c: Int) = if (c in 0 until n) cells[c * CELLS_PER + Cell.CP0] else 32
            var a = col.coerceIn(0, n - 1)
            var b = a
            if (ch(a) == 32) {
                var i = a + 1
                while (i < n && ch(i) == 32) i++
                if (i < n) {
                    a = i
                    b = i
                } else {
                    i = a - 1
                    while (i >= 0 && ch(i) == 32) i--
                    if (i < 0) return null
                    a = i
                    b = i
                }
            }
            while (a > 0 && ch(a - 1) != 32) a--
            while (b < n - 1 && ch(b + 1) != 32) b++
            return selOf(row, a, row, b, sbCount, panCols.value)
        }

        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val slop = viewConfiguration.touchSlop
            val pressX = down.position.x
            val pressY = down.position.y

            var lastX = pressX
            var lastY = pressY
            var prevY = pressY
            var travelled = 0f
            var moved = false
            var dragging = false

            // Selection is opened by holding, not by a menu entry. The timer
            // only marks the anchor: the gesture that follows extends the range
            // under the finger, which is the direct manipulation a second
            // "Select" mode (and its toast) could not give.
            var selecting = false
            var anchorRow = 0
            var anchorCol = 0

            // A touch that lands on a selection handle adopts that endpoint
            // instead of starting a hold. Checked before the hold is armed, and
            // the handle size is generous on purpose: the bar is a few pixels
            // wide, and a target the reader has to aim at is worse than no
            // handle at all.
            //
            // Checked before the menu is dismissed, too. Grabbing a handle is
            // how a selection gets adjusted, and the actions are wanted as soon
            // as the range is right -- dismissing here meant the menu vanished
            // the moment the reader reached for a handle and never came back
            // until they lifted and long-pressed again.
            val hit = handleAt(pressX, pressY)
            if (hit != null) {
                val moving: Handle = hit
                down.consume()
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial)
                        .changes
                        .firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    contentPoint(change.position.x, change.position.y)?.let { p ->
                        selection?.let { sel ->
                            // Only the grabbable end is rewritten; the opposite
                            // anchor stays where the reader left it.
                            selection = when (moving) {
                                Handle.START -> selOf(
                                    p[0], if (p[0] == sel.r1) minOf(p[1], sel.c1) else p[1],
                                    sel.r1, sel.c1, sbCount, panCols.value,
                                )
                                Handle.END -> selOf(
                                    sel.r0, sel.c0,
                                    p[0], if (p[0] == sel.r0) maxOf(p[1], sel.c0) else p[1],
                                    sbCount, panCols.value,
                                )
                            }
                        }
                    }
                    change.consume()
                }
                // Re-open on release, at the new position: the adjust gesture
                // ends the same way a fresh selection does.
                menuOpen = selection != null
                return@awaitEachGesture
            }

            // A press on the menu belongs to the menu. This handler sits on the
            // ancestor and runs at PointerEventPass.Initial, so without this it
            // dismissed the menu on the way down -- removing it from the
            // composition before the tap could reach the item under the finger,
            // which is why Copy appeared to do nothing. Returning without
            // consuming lets the press through to the menu's own clickables.
            if (menuOpen && menuBounds.contains(Offset(pressX, pressY))) {
                return@awaitEachGesture
            }

            // Any other touch dismisses an open menu. A touch that turns out to
            // be a plain tap also drops the selection, so the highlight is not
            // left behind after the reader has moved on.
            menuOpen = false

            val hold = scope.launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                val a = contentPoint(pressX, pressY) ?: return@launch
                anchorRow = a[0]
                anchorCol = a[1]
                selecting = true
                selection = selectWord(a[0], a[1])
                    ?: selOf(a[0], a[1], a[0], a[1], sbCount, panCols.value)
            }

            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial)
                    .changes
                    .firstOrNull { it.id == down.id } ?: break
                val pos = change.position
                lastX = pos.x
                lastY = pos.y
                if (!change.pressed) break

                if (selecting) {
                    contentPoint(pos.x, pos.y)?.let { f ->
                        selection = selOf(anchorRow, anchorCol, f[0], f[1], sbCount, panCols.value)
                    }
                    change.consume()
                    prevY = pos.y
                    continue
                }

                val dy = pos.y - prevY
                prevY = pos.y
                if (!dragging) {
                    travelled += dy
                    // Any drift past the slop - horizontal included - ends the
                    // hold and starts the pan.
                    if (abs(pos.y - pressY) < slop && abs(pos.x - pressX) < slop) continue
                    moved = true
                    dragging = true
                    hold.cancel()
                    menuOpen = false
                    // Give back only the part past the slop, so the first
                    // movement does not jump by a full slop's worth. Negated:
                    // scrollPx is the viewport's top within the content, so
                    // adding a downward finger delta would push the content up
                    // -- the reverse of dragging the text down with the finger.
                    val first = -(travelled - sign(travelled) * slop)
                    panBy(first)
                } else {
                    panBy(-dy)
                }
                change.consume()
            }
            hold.cancel()

            if (selecting) {
                // Released with a selection: offer the actions at the point the
                // finger left, with Copy already meaningful.
                menuOpen = true
            } else if (!moved) {
                // A tap on a URL opens it; any other tap just drops the
                // selection. Loopback links are opened here even though the
                // automatic pass skips them: that pass cannot know intent, but
                // an explicit tap is as clear as intent gets.
                val live = gridState.value
                val point = if (live != null) contentPoint(lastX, lastY) else null
                val tapped = point?.let { live?.urlAt(it[0] - panVisibleSb.value, it[1]) }
                if (tapped != null) openUrl(context, tapped) else selection = null
                menuOpen = false
            }
        }
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides terminalToolbar) {
    BoxWithConstraints(
        modifier
            .then(panner)
            .onGloballyPositioned { windowPos = it.positionInWindow() },
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val cols = floor(width / metrics.advance).toInt().coerceIn(1, 400)
        val rows = floor(height / (metrics.textHeight * 1.3f)).toInt().coerceIn(1, 400)
        // Cells stretch to fill the surface but never shrink below the font, so
        // every glyph fits its box and no seam of the surface shows through.
        val cellW = width / cols
        val cellH = height / rows

        LaunchedEffect(session, rows, cols) { session.resize(rows, cols) }

        // Output arriving shifts every content index; a stale selection would
        // copy the wrong lines, so it is dropped instead of misread. Resizes
        // rebuild the lines themselves, which drops it for the same reason.
        LaunchedEffect(sbCount) {
            selection?.let { if (it.sbCount != sbCount) selection = null }
        }
        LaunchedEffect(cols) {
            selection?.let { if (it.cols != cols) selection = null }
        }

        // Content is [scrollback][sbCount] lines followed by the live screen.
        // scrollPx is the viewport's top within that content, so the largest
        // useful value - the one showing live output - is content minus screen.
        //
        // On the alternate screen the scrollback is not part of what is on
        // display: `vim` and `less` take the alt buffer over the full surface,
        // and compositing the real scrollback above it would show the pre-vim
        // terminal through the top of the editor. Treat the surface as the
        // screen alone while that is active.
        val alt = grid?.altscreen == true
        val liveRows = grid?.rows ?: rows
        val visibleSb = if (alt) 0 else sbCount
        val maxScroll = ((visibleSb + liveRows) * cellH - height).coerceAtLeast(0f)

        // Output extends the content downward. A reader pinned to the bottom
        // follows it; a reader inspecting history stays on the line they
        // stopped on instead of being dragged along.
        LaunchedEffect(visibleSb, liveRows, cellH, following.value) {
            scrollPx.value =
                if (following.value) maxScroll else scrollPx.value.coerceAtMost(maxScroll)
        }

        // Two lines of slack over the viewport so the window is never a line
        // short when the offset lands between two rows.
        val sbCells = remember(rows, cols) { IntArray((rows + 2) * cols * CELLS_PER) }

        // Publish the geometry to the pan gesture, which is bound to the
        // modifier outside this scope. Writing an equal value changes nothing,
        // so this only invalidates the gesture when the layout really resized.
        panCellH.value = cellH
        panViewH.value = height
        panLiveRows.value = liveRows
        panCellW.value = cellW
        panCols.value = cols
        panVisibleSb.value = visibleSb

        Canvas(Modifier.fillMaxSize()) {
            val snapshot = grid ?: return@Canvas
            val cells = snapshot.cells
            val baseline = (cellH - metrics.textHeight) / 2f - metrics.ascent
            val padX = ((cellW - metrics.advance) / 2f).coerceAtLeast(0f)
            val yTop = scrollPx.value

            // Only lines crossing the viewport are fetched and drawn; the rest
            // of the content costs nothing this frame.
            val firstLine = floor(yTop / cellH).toInt().coerceAtLeast(0)
            val endLine = ceil((yTop + height) / cellH).toInt()

            fun drawLine(
                src: IntArray,
                base: Int,
                lineCols: Int,
                y: Float,
                links: List<IntRange>? = null,
            ) {
                // Pass one: every background, continuation cells included, so
                // wide glyphs and blank regions never expose the surface.
                var i = base
                var x = 0f
                var col = 0
                while (col < lineCols) {
                    val attrs = src[i + Cell.ATTRS]
                    val reversed = attrs and TermAttrs.REVERSE != 0
                    val onCursor = attrs and TermAttrs.CURSOR != 0
                    val bg = if (reversed xor onCursor) src[i + Cell.FG] else src[i + Cell.BG]
                    drawRect(
                        color = Color(bg),
                        topLeft = Offset(x, y),
                        size = Size(cellW, cellH),
                    )
                    x += cellW
                    i += CELLS_PER
                    col++
                }

                // Pass two: glyphs. Every visual property is assigned per cell
                // so nothing leaks in from the cell drawn before it.
                i = base
                x = 0f
                var skipUntil = -1
                col = 0
                while (col < lineCols) {
                    val ch = src[i + Cell.CP0]
                    val attrs = src[i + Cell.ATTRS]
                    val widthCells = (attrs shr TermAttrs.WIDTH_SHIFT) and 0x3
                    // Width 0 marks the invisible column behind a wide glyph.
                    // Draw nothing and let the next cell stand on its own.
                    val invisible = widthCells == 0

                    if (!invisible && col >= skipUntil && ch >= 32 &&
                        attrs and TermAttrs.CONCEAL == 0
                    ) {
                        val reversed = attrs and TermAttrs.REVERSE != 0
                        val onCursor = attrs and TermAttrs.CURSOR != 0
                        val swap = reversed xor onCursor

                        paint.color = if (swap) src[i + Cell.BG] else src[i + Cell.FG]
                        // A URL is drawn in the link colour so it reads as
                        // tappable. Skipped when the cell is reversed or under
                        // the cursor: there the terminal's own colours are
                        // already carrying meaning, and overriding them would
                        // hide a highlight or the cursor.
                        if (!swap && links != null && links.any { col in it }) {
                            paint.color = LINK_FG
                        }
                        paint.typeface =
                            if (attrs and TermAttrs.BOLD != 0) boldTypeface else normalTypeface
                        paint.isUnderlineText = attrs and TermAttrs.UNDERLINE != 0
                        paint.isStrikeThruText = attrs and TermAttrs.STRIKE != 0
                        paint.textSkewX = if (attrs and TermAttrs.ITALIC != 0) -0.2f else 0f

                        // Base plus its combining mark, when the cell carries
                        // one. appendCodePoint would throw on anything that is
                        // not a valid codepoint, so both slots are filtered.
                        val combined = StringBuilder(2)
                        if (ch <= Character.MAX_CODE_POINT && ch >= 32) {
                            combined.appendCodePoint(ch)
                            val mark = src[i + Cell.CP1]
                            if (mark in 1..Character.MAX_CODE_POINT) combined.appendCodePoint(mark)
                        }
                        if (combined.isNotEmpty()) {
                            drawContext.canvas.nativeCanvas.drawText(
                                combined.toString(),
                                x + padX,
                                y + baseline,
                                paint,
                            )
                        }
                    }
                    if (widthCells == 2) skipUntil = col + 1

                    x += cellW
                    i += CELLS_PER
                    col++
                }
            }

            // History occupies content lines [0, visibleSb). Fetch only the part
            // of it the viewport can actually show, described from the newest
            // line back so the buffer stays one screenful regardless of how
            // deep the reader has scrolled.
            //
            // Native packs every returned line to the column count it held at
            // read time. That is normally the same `cols` the surface was laid
            // out for, but across a resize the grid can still be one frame
            // behind, and indexing a narrower buffer with the wider stride
            // would run off the end. Skip the frame when they disagree rather
            // than draw from a stride the buffer does not have.
            val histFrom = firstLine.coerceIn(0, visibleSb)
            val histTo = endLine.coerceIn(0, visibleSb)
            val stride = cols
            if (histTo > histFrom && stride == snapshot.cols) {
                val got = session.scrollbackTail(visibleSb - histTo, histTo - histFrom, sbCells)
                if (got > 0) {
                    val lineCols = minOf(stride, cols)
                    for (n in 0 until got) {
                        val y = (histFrom + n) * cellH - yTop
                        if (y + cellH <= 0f || y >= height) continue
                        // n * cols * CELLS_PER, not n * cols: the buffer packs
                        // CELLS_PER ints per cell, so indexing a line by its
                        // column offset alone reads from a cell five times too
                        // far into the line. Every history row then drew a
                        // slice of the wrong line and the text visibly came
                        // apart as more history was scrolled into view.
                        drawLine(sbCells, n * stride * CELLS_PER, lineCols, y)
                    }
                }
            }

            // The live screen occupies content lines [visibleSb, ...).
            for (row in 0 until snapshot.rows) {
                val y = (visibleSb + row) * cellH - yTop
                if (y + cellH <= 0f || y >= height) continue
                // The scan is skipped for rows that cannot contain a URL: it
                // rebuilds the row's text, and every ordinary row would pay for
                // nothing.
                val links = if (snapshot.rowsContainHttp(row)) snapshot.linkRanges(row) else null
                drawLine(cells, row * snapshot.cols * CELLS_PER, snapshot.cols, y, links)
            }

            // Selection tint over whatever is already drawn. Rows are content
            // rows, so the highlight scrolls with the text; a selection made
            // at another geometry is skipped rather than shown in the wrong
            // place for the frame before the invalidation effect drops it.
            selection?.let { s ->
                if (s.sbCount == sbCount && s.cols == cols && !alt) {
                    // Termux inverts the selection: the highlighted cells get a
                    // solid light background and their glyphs are repainted in
                    // the terminal background. A translucent tint over coloured
                    // output leaves the text hard to read; inversion does not.
                    val tint = SelHighlight
                    var r = s.r0.coerceAtLeast(firstLine)
                    val rEnd = s.r1.coerceAtMost(endLine)
                    while (r <= rEnd) {
                        val y = r * cellH - yTop
                        val c0 = (if (r == s.r0) s.c0 else 0).coerceIn(0, cols - 1)
                        val c1 = (if (r == s.r1) s.c1 else cols - 1).coerceIn(0, cols - 1)
                        if (c0 <= c1) {
                            drawRect(
                                color = tint,
                                topLeft = Offset(c0 * cellW, y),
                                size = Size((c1 - c0 + 1) * cellW, cellH),
                            )
                            // Repaint the glyphs on top in the background colour
                            // so they read as knocked out of the highlight.
                            val line = if (r < visibleSb) null else r - visibleSb
                            if (line != null) {
                                // android.graphics.Paint takes a packed ARGB int.
                                paint.color = 0xFF0B0F14.toInt()
                                var c = c0
                                while (c <= c1) {
                                    val i = line * cols * CELLS_PER + c * CELLS_PER
                                    val ch = cells[i + Cell.CP0]
                                    val attrs = cells[i + Cell.ATTRS]
                                    val wcell = (attrs shr TermAttrs.WIDTH_SHIFT) and 0x3
                                    if (wcell != 0 && ch >= 32 && ch <= Character.MAX_CODE_POINT) {
                                        val sb = StringBuilder(2)
                                        sb.appendCodePoint(ch)
                                        val m = cells[i + Cell.CP1]
                                        if (m in 1..Character.MAX_CODE_POINT) sb.appendCodePoint(m)
                                        drawContext.canvas.nativeCanvas.drawText(
                                            sb.toString(),
                                            c * cellW + padX,
                                            y + baseline,
                                            paint,
                                        )
                                    }
                                    c += if (wcell == 2) 2 else 1
                                }
                            }
                        }
                        r++
                    }

                    // Handles at the two grabbable ends, drawn after the tint so
                    // they read as part of the selection. Each sits just outside
                    // its endpoint cell and spans the line height, which is what
                    // makes the endpoint obvious and draggable to an exact
                    // letter rather than to a whole word.
                    // Termux-style handles: a rounded capsule at each end,
                    // taller than the line and sitting just outside its cell, so
                    // the endpoint is obvious and can be grabbed without
                    // covering the character it marks.
                    val barW = HANDLE_BAR_PX
                    val barH = cellH * 1.25f
                    fun handle(row: Int, col: Int, onLeftEdge: Boolean) {
                        val y = row * cellH - yTop + (cellH - barH) / 2f
                        if (y + barH <= 0f || y >= height) return
                        val edge = if (onLeftEdge) col * cellW else (col + 1) * cellW
                        val x = (if (onLeftEdge) edge - barW / 2f else edge - barW / 2f)
                            .coerceIn(0f, (width - barW).coerceAtLeast(0f))
                        drawRoundRect(
                            color = HandleBlue,
                            topLeft = Offset(x, y),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                        )
                    }
                    handle(s.r0, s.c0, onLeftEdge = true)
                    handle(s.r1, s.c1, onLeftEdge = false)
                }
            }
        }

        BasicTextField(
            value = typed,
            onValueChange = { value ->
                if (value.isNotEmpty()) session.send(value)
                typed = ""
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> session.send("$ESC[D")
                        Key.DirectionRight -> session.send("$ESC[C")
                        Key.DirectionUp -> session.send("$ESC[A")
                        Key.DirectionDown -> session.send("$ESC[B")
                        Key.MoveHome -> session.send("$ESC[H")
                        Key.MoveEnd -> session.send("$ESC[F")
                        Key.Delete -> session.send("$ESC[3~")
                        Key.Backspace -> session.send(DEL)
                        Key.Tab -> session.send(TAB)
                        Key.Enter -> session.send(ENTER)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            textStyle = TextStyle(
                color = Color.Transparent,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
            singleLine = false,
            decorationBox = { inner -> Box(Modifier.fillMaxSize()) { inner() } },
        )

        BackHandler(enabled = menuOpen) { menuOpen = false }

        // Where the bar sits: centred over the selected text and clamped into
        // the surface. Its own size is measured after layout rather than
        // guessed -- a clamp against an assumed width either lets the bar run
        // off the edge or holds it in from the edge for no reason.
        var menuW by remember { mutableStateOf(0) }
        var menuH by remember { mutableStateOf(0) }
        val menuX = selection?.let {
            val centre = (it.c0 + it.c1 + 1) / 2f * cellW
            with(density) { (centre - menuW / 2f).toDp() }
                .coerceIn(0.dp, with(density) { (width - menuW).toDp() }.coerceAtLeast(0.dp))
        } ?: 0.dp
        val menuY = selection?.let {
            val gap = with(density) { 6.dp.toPx() }
            val selTop = it.r0 * cellH - scrollPx.value
            val selBottom = (it.r1 + 1) * cellH - scrollPx.value
            // Above the selection by preference. A selection on the first
            // visible row has nothing above it, and clamping to the top edge
            // there would lay the bar across the very text it acts on, so in
            // that case it drops below instead.
            val above = selTop - menuH - gap
            val y = if (above >= 0f) above else selBottom + gap
            with(density) { y.toDp() }
                .coerceIn(
                    0.dp,
                    with(density) { (height - menuH).toDp() }.coerceAtLeast(0.dp),
                )
        } ?: 0.dp

        // Termux-shaped selection bar: a single light row of dark labels sitting
        // just above the selection. It is placed in this layout rather than in a
        // popup for two reasons -- a popup needs focus, and stealing focus pulls
        // the IME away and remeasures the window (the flap that used to make the
        // byte counter climb on its own); and a popup anchors to a point, while
        // a bar wants to sit with the text it acts on.
        if (menuOpen) {
            SelectionMenu(
                session = session,
                grid = grid,
                sbCount = sbCount,
                selection = selection,
                onCopy = { text ->
                    if (text.isEmpty()) {
                        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
                    }
                    selection = null
                    menuOpen = false
                },
                onPaste = {
                    clipboard.getText()?.text?.let { sent ->
                        if (sent.isNotEmpty()) session.send(sent)
                    }
                    selection = null
                    menuOpen = false
                },
                onSelectAll = {
                    grid?.let { g ->
                        // On the alt screen the scrollback is not on display, so
                        // "select all" means the surface the reader can see.
                        val base = if (g.altscreen) 0 else sbCount
                        val last = base + g.rows - 1
                        if (last >= 0)
                            selection = TermSel(0, 0, last, g.cols - 1, sbCount, g.cols)
                    }
                },
                pasteEnabled = clipboard.hasText() &&
                    clipboard.getText()?.text?.isNotEmpty() == true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Hugs the selection rather than spanning the screen: the
                    // bar is placed with its left edge near the selected text
                    // and clamped so it cannot run off the right edge. The
                    // clamp uses the widest the bar can get, since its width is
                    // known only after layout and an overflow would clip "All".
                    .offset(x = menuX, y = menuY)
                    .onGloballyPositioned {
                        menuW = it.size.width
                        menuH = it.size.height
                        val at = it.positionInParent()
                        menuBounds = Rect(
                            at.x,
                            at.y,
                            at.x + it.size.width,
                            at.y + it.size.height,
                        )
                    },
            )
        }

        LaunchedEffect(session) {
            focus.requestFocus()
        }
    }
    }
}

/**
 * The selection actions, in a single horizontal row.
 *
 * Modelled on Termux's bar: a light surface with dark labels, sized so each
 * item is a comfortable tap target, and Copy first because it is what a
 * selection is almost always for. It reads the live [selection] rather than a
 * snapshot, so a selection that moved under the reader is copied as it is now.
 */
@Composable
private fun SelectionMenu(
    session: TerminalSession,
    grid: TermGrid?,
    sbCount: Int,
    selection: TermSel?,
    onCopy: (String) -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    pasteEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MenuBg),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun item(label: String, enabled: Boolean = true, onClick: () -> Unit) {
            Text(
                text = label,
                color = if (enabled) MenuFg else MenuFgDim,
                fontSize = 14.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                // No weight/fillMaxWidth: the bar has to wrap its labels, or the
                // Row stretches to every pixel between its offset and the screen
                // edge and stops being a compact bar at all.
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = 15.dp, vertical = 12.dp),
            )
        }
        item("Copy", enabled = selection != null) {
            onCopy(selection?.let { selectionText(session, grid, sbCount, it) }.orEmpty())
        }
        item("Paste", enabled = pasteEnabled, onClick = onPaste)
        item("Select All", enabled = grid != null, onClick = onSelectAll)
    }
}

private data class GridMetrics(
    val advance: Float,
    val textHeight: Float,
    val ascent: Float,
)

/** Which end of the selection a handle drag is moving. */
private enum class Handle { START, END }

/* Handle geometry in pixels. The bar is thin because it is drawn over glyphs;
 * the touch target around it is deliberately much larger. */
private const val HANDLE_BAR_PX = 5f
private const val HANDLE_TOUCH_PX = 26f

/**
 * A content-range selection: rows span [scrollback][live], row 0 being the
 * oldest retained line. [sbCount] and [cols] snapshot the geometry it was
 * made at, so the selection can be dropped rather than misread once either
 * moves.
 */
private data class TermSel(
    val r0: Int,
    val c0: Int,
    val r1: Int,
    val c1: Int,
    val sbCount: Int,
    val cols: Int,
)

/** Build a selection with both endpoints in row-major order. */
private fun selOf(rA: Int, cA: Int, rB: Int, cB: Int, sbCount: Int, cols: Int): TermSel =
    if (rA < rB || (rA == rB && cA <= cB)) TermSel(rA, cA, rB, cB, sbCount, cols)
    else TermSel(rB, cB, rA, cA, sbCount, cols)

/**
 * Flatten [sel] to text. Rows come from one locked copyRange read, cells are
 * clipped to the dragged columns and wide-glyph spacers are skipped. Rows the
 * emulator flagged as wrapped continue the previous line; every other line
 * boundary becomes a newline, with trailing spaces trimmed.
 */
private fun selectionText(
    session: TerminalSession,
    grid: TermGrid?,
    sbCount: Int,
    sel: TermSel,
): String {
    val cols = grid?.cols ?: 0
    if (cols <= 0) return ""
    // copyRange addresses rows as [scrollback][live screen]. On the alternate
    // screen nothing is being displayed from the scrollback, so a selection
    // cannot legitimately reach back into it.
    val liveSb = if (grid?.altscreen == true) 0 else sbCount
    val lastRow = liveSb + (grid?.rows ?: 0) - 1
    if (sel.r0 > lastRow) return ""
    val r0 = sel.r0.coerceAtLeast(0)
    val r1 = sel.r1.coerceAtMost(lastRow)
    if (r1 < r0) return ""

    val rows = r1 - r0 + 1
    val cells = IntArray(rows * cols * CELLS_PER)
    val flags = BooleanArray(rows)
    val got = session.copyRange(r0, r1, cells, flags)
    if (got <= 0) return ""

    val out = StringBuilder()
    var logicalStart = 0
    for (i in 0 until got) {
        if (i > 0 && !flags[i]) {
            trimTrailingSpaces(out, logicalStart)
            out.append('\n')
            logicalStart = out.length
        }
        val row = r0 + i
        val from = if (row == sel.r0) sel.c0.coerceIn(0, cols - 1) else 0
        val to = if (row == sel.r1) sel.c1.coerceIn(0, cols - 1) else cols - 1
        if (to >= from) appendRowCells(out, cells, i * cols * CELLS_PER, from, to)
    }
    trimTrailingSpaces(out, logicalStart)
    return out.toString()
}

/** Append cells [from..to] of one packed row, skipping wide-glyph spacers. */
private fun appendRowCells(sb: StringBuilder, cells: IntArray, base: Int, from: Int, to: Int) {
    var c = from
    while (c <= to) {
        val i = base + c * CELLS_PER
        val width = (cells[i + Cell.ATTRS] shr TermAttrs.WIDTH_SHIFT) and 0x3
        // Width 0 is the invisible column behind a wide glyph: it carries no
        // character and appendCodePoint would reject its placeholder value.
        if (width == 0) {
            c++
            continue
        }
        val ch = cells[i + Cell.CP0]
        if (ch >= 32 && ch <= Character.MAX_CODE_POINT) {
            sb.appendCodePoint(ch)
            val mark = cells[i + Cell.CP1]
            if (mark in 1..Character.MAX_CODE_POINT) sb.appendCodePoint(mark)
        }
        c += if (width == 2) 2 else 1
    }
}

private fun trimTrailingSpaces(sb: StringBuilder, from: Int) {
    while (sb.length > from && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
}

/**
 * The bottom surface: three pages of two rows each, moved between by a
 * horizontal swipe the way Termux exposes its extra keys.
 *
 * Page 1 is the common control keys, page 2 the function and paging keys, page
 * 3 the session diagnostics that used to sit in a permanent status line above
 * the keys. The diagnostics page is why the pad kept one row height on every
 * page: a page that changed height would resize the terminal on every swipe,
 * and the shell redraws on each SIGWINCH.
 *
 * Two things never page away. The fault banner replaces page 3's content and
 * takes a fault colour, because "running with no output" must not be something
 * a reader has to swipe to discover; and the scrollback escape hatch is pinned
 * over the pad whenever the reader has scrolled off live output, because
 * without it they are stuck with no visible way back.
 */
@Composable
private fun KeyPad(
    session: TerminalSession,
    focus: FocusRequester,
    status: String,
    bytesRead: Long,
    rows: Int,
    cols: Int,
    following: Boolean,
) {
    // No bytes while nominally running is the one condition that must never be
    // allowed to resemble a healthy empty screen.
    val fault = bytesRead == 0L && status.startsWith("running")

    // Sequences are the xterm-256color bindings this session advertises via
    // TERM. F1-F4 use the SS3 form, F5-F12 and the paging keys the CSI tilde
    // form; sending the CSI form for F1-F4 would not match terminfo and a
    // program reading them would see shifted keys. ${ESC} is braced because
    // "$ESC OP" interpolates the escape and then a literal space.
    val keyPages = remember {
        listOf(
            listOf(
                listOf(
                    "ESC" to ESC, "ENTER" to ENTER, "TAB" to TAB,
                    "LEFT" to "$ESC[D", "DOWN" to "$ESC[B", "UP" to "$ESC[A",
                    "RIGHT" to "$ESC[C", "HOME" to "$ESC[H", "END" to "$ESC[F",
                ),
                listOf(
                    "BS" to DEL, "DEL" to "$ESC[3~", "^C" to CTRL_C, "^D" to CTRL_D,
                    "/" to "/", "|" to "|", "~" to "~", "-" to "-",
                ),
            ),
            listOf(
                listOf(
                    "F1" to "${ESC}OP", "F2" to "${ESC}OQ", "F3" to "${ESC}OR",
                    "F4" to "${ESC}OS", "F5" to "$ESC[15~", "F6" to "$ESC[17~",
                    "F7" to "$ESC[18~",
                ),
                listOf(
                    "F8" to "$ESC[19~", "F9" to "$ESC[20~", "F10" to "$ESC[21~",
                    "F11" to "$ESC[23~", "F12" to "$ESC[24~", "PGUP" to "$ESC[5~",
                    "PGDN" to "$ESC[6~",
                ),
            ),
        )
    }

    @Composable
    fun RowScope.key(label: String, bytes: String) {
        KeyChip(
            label = label,
            onClick = {
                session.send(bytes)
                focus.requestFocus()
            },
            modifier = Modifier.weight(1f),
        )
    }

    val scroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(StatusBarBg),
    ) {
        // The pages are laid side by side at exactly one screen width each, so a
        // swipe always lands on a page boundary. horizontalScroll measures its
        // child with an unbounded width, which is why each page carries an
        // explicit width: fillMaxWidth()/weight(1f) have nothing to resolve
        // against in there and the rows would collapse.
        val pageW = maxWidth
        val stride = with(density) { pageW.toPx() }
        val page = ((scroll.value + stride / 2) / stride).toInt().coerceIn(0, 2)

        // horizontalScroll has no notion of pages, so a swipe ends wherever the
        // fling died and the next page is left half shown. Snap to the nearest
        // boundary once the gesture stops.
        LaunchedEffect(scroll.isScrollInProgress, stride) {
            if (!scroll.isScrollInProgress) {
                val dest = (page * stride).roundToInt()
                if (scroll.value != dest) scroll.animateScrollTo(dest)
            }
        }

        Row(
            Modifier
                .horizontalScroll(scroll)
                .padding(vertical = 6.dp),
        ) {
            keyPages.forEach { rowsOfPage ->
                Column(
                    Modifier
                        .width(pageW)
                        .padding(top = PAGE_GAP),
                ) {
                    rowsOfPage.forEachIndexed { ri, row ->
                        Row(
                            Modifier
                                .width(pageW)
                                .padding(horizontal = 6.dp)
                                .then(if (ri == 0) Modifier else Modifier.padding(top = 4.dp)),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            row.forEach { (label, bytes) -> key(label, bytes) }
                        }
                    }
                }
            }
            // Page 3 is the session read-out rather than a grid of chips. The
            // invisible rows underneath give it a key page's exact height; without
            // them the pad would stand a little taller on this page and settling
            // on it would resize the terminal.
            Column(
                Modifier
                    .width(pageW)
                    .padding(top = PAGE_GAP),
            ) {
                Box {
                    DiagnosticsRows(pageW)
                    StatusPage(
                        status = if (fault) "${status} - NO OUTPUT RECEIVED" else status,
                        bytesRead = bytesRead,
                        rows = rows,
                        cols = cols,
                        fault = fault,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 10.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }

        // Dots sit inside the pad's own padding, so the pad stays exactly two
        // key rows tall on every page. A page switch that leaves no trace is the
        // same invisible-gesture problem the old single scrolling strip had.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (i == page) TextDim else KeyBg),
                )
            }
        }
    }
}

/**
 * Session read-out shown on the third pad page. It uses the same two rows as a
 * key page so the pad's height does not move when it is swiped to.
 */
@Composable
private fun StatusPage(
    status: String,
    bytesRead: Long,
    rows: Int,
    cols: Int,
    fault: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = status,
            color = if (fault) Fault else TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${readableBytes(bytesRead)}  ${rows}x$cols",
            color = if (fault) Fault else TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/**
 * Invisible stand-ins for a key page's two rows, used under the diagnostics
 * read-out so that page is exactly as tall as the key pages. An empty Box would
 * only be as tall as its padding, which is what made this page shorter than the
 * key pages and resized the terminal every time the swipe settled on it; the
 * transparent text gives the chip the same line box a real label does.
 */
@Composable
private fun DiagnosticsRows(pageW: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(pageW)) {
        repeat(2) { i ->
            Row(
                Modifier
                    .width(pageW)
                    .padding(horizontal = 6.dp)
                    .then(if (i == 0) Modifier else Modifier.padding(top = 4.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(7) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(" ", fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(KeyBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 10sp keeps the longest label ("ENTER") inside a 42dp chip: nine even
        // chips per row leave ~34dp of text box after padding, and 12sp was
        // wide enough that RIGH/T/HOM clipped against the rounded corner.
        Text(label, color = TextDim, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * Visible lines of a grid joined by newlines. The grid holds the live screen,
 * so this describes what the reader can see.
 */
private fun TermGrid.rowsText(): String {
    val out = StringBuilder(rows * (cols + 1))
    for (r in 0 until rows) out.append(rowText(r)).append('\n')
    return out.toString()
}

/**
 * The URL under a live-screen cell, if any.
 *
 * Wrapped rows are rejoined first, because a wrapped URL is still one URL: a
 * CLI's login link is routinely wider than the screen, and searching row by row
 * would only ever find its first fragment. Every merged row but the last is
 * exactly [cols] wide, which is what lets a merged offset map back to a column.
 */
private fun TermGrid.urlAt(row: Int, col: Int): String? {
    if (row !in 0 until rows || cols <= 0 || col !in 0 until cols) return null
    val texts = Array(rows) { rowText(it) }

    var first = row
    var steps = 0
    while (first > 0 && texts[first - 1].length == cols && steps++ < 8) first--
    var last = row
    steps = 0
    while (last < rows - 1 && texts[last].length == cols && steps++ < 8) last++

    val merged = StringBuilder()
    for (r in first..last) merged.append(texts[r])
    val tapped = (row - first) * cols + col
    return URL_RE.find(merged)?.let { m -> if (tapped in m.range) trimUrlEnd(m.value) else null }
}

/**
 * One screen row as text. Non-BMP glyphs collapse to a space so one cell stays
 * one character and merged offsets keep mapping to columns.
 */
private fun TermGrid.rowText(r: Int): String {
    val sb = StringBuilder(cols)
    for (c in 0 until cols) {
        val ch = cells[(r * cols + c) * CELLS_PER + Cell.CP0]
        sb.append(if (ch in 32..0xFFFF) ch.toChar() else ' ')
    }
    return sb.toString()
}

/**
 * Hands a URL to Android.
 *
 * Nothing inside the rootfs can reach a browser: xdg-open finds none, and the
 * platform's own am cannot run under proot. The app is the only layer that can
 * start one, so both the automatic pass and a tap go through here.
 */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // Nothing can handle it; the text stays on screen to copy.
    }
}

/**
 * Drops punctuation a sentence wrapped around the URL.
 *
 * The character class cannot exclude '.' or ')' without also rejecting the ones
 * a URL legitimately contains, so the trailing run is trimmed instead.
 */
private fun trimUrlEnd(url: String): String {
    var end = url.length
    while (end > 0 && url[end - 1] in ".,;:!?)\"'}],") end--
    return url.substring(0, end)
}

/** Whether a row's text could contain a URL at all; the cheap pre-check. */
private fun TermGrid.rowsContainHttp(row: Int): Boolean {
    for (c in 0 until cols) {
        if (cells[(row * cols + c) * CELLS_PER + Cell.CP0] == 'h'.code) {
            val t = rowText(row)
            if (t.contains("http://") || t.contains("https://")) return true
            return false
        }
    }
    return false
}

/**
 * The columns of [row] that a URL occupies.
 *
 * Wrapped rows are rejoined before searching for the same reason [urlAt] does
 * it: a URL wider than the screen is still one URL, and matching row by row
 * would leave all but its first fragment uncoloured. The match is then mapped
 * back to this row's own columns, so a wrapped link colours every row it spans.
 */
private fun TermGrid.linkRanges(row: Int): List<IntRange> {
    if (cols <= 0 || row !in 0 until rows) return emptyList()
    val texts = Array(rows) { rowText(it) }

    var first = row
    var steps = 0
    while (first > 0 && texts[first - 1].length == cols && steps++ < 8) first--
    var last = row
    steps = 0
    while (last < rows - 1 && texts[last].length == cols && steps++ < 8) last++

    val merged = StringBuilder()
    for (r in first..last) merged.append(texts[r])
    val base = (row - first) * cols

    val out = ArrayList<IntRange>(2)
    URL_RE.findAll(merged).forEach { m ->
        val lo = maxOf(m.range.first, base)
        val hi = minOf(m.range.last, base + cols - 1)
        if (lo <= hi) out.add((lo - base)..(hi - base))
    }
    return out
}

/** Matches http(s) URLs as they appear in terminal output. */
private val URL_RE = Regex("https?://[^\\s\"'<>\\[\\]{}|\\\\^`]+")

private fun readableBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
