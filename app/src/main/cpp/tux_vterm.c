/*
 * libvterm bridge for TuxTerm.
 *
 * A single VTerm lives behind an opaque handle. The PTY reader thread writes
 * bytes into it and the UI thread snapshots the cell grid, so every entry point
 * takes the same lock: without it the two threads race inside libvterm's
 * internal screen buffers and produce torn glyphs instead of an obvious crash.
 *
 * The snapshot flattens a whole row-major grid into one preallocated jint
 * array ([char, fg, bg, attrs] per cell) so a redraw costs one JNI array
 * write rather than a call per cell.
 */
#include <jni.h>

#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "vterm.h"

/* Attribute bit packing, mirrored by TermAttrs in Kotlin. */
#define ATTR_BOLD     (1u << 0)
#define ATTR_UNDERLINE (1u << 1)
#define ATTR_ITALIC   (1u << 2)
#define ATTR_REVERSE  (1u << 3)
#define ATTR_STRIKE   (1u << 4)
#define ATTR_CONCEAL  (1u << 5)
#define ATTR_BLINK    (1u << 6)
#define ATTR_CURSOR   (1u << 7)
#define ATTR_WIDTH_SHIFT 8

/* Ints per packed cell. Must match CELLS_PER in TerminalSession.kt. */
#define CELLS_PER 5

/* One line that scrolled off the top of the primary screen, packed exactly
 * like the snapshot ([cp0, cp1, fg, bg, attrs] per cell) so rendering a
 * scrollback line costs the same work as rendering a live one. */
typedef struct {
    int cols;       /* width the line was captured at; may differ from now */
    bool continuation; /* wraps the line above it; sb_pushline4 supplies this */
    int32_t *cells; /* cols * CELLS_PER entries, or NULL if allocation failed */
} ScrollLine;

typedef struct {
    ScrollLine *lines; /* ring storage; oldest live line sits at `start` */
    int cap;           /* allocated slots; 0 means scrollback is disabled */
    int start;
    int size;
} Scrollback;

typedef struct {
    VTerm *vt;
    VTermScreen *screen;
    VTermState *state;
    int rows;
    int cols;
    pthread_mutex_t lock;

    /* Lines pushed off the top of the screen. Ring-buffered: a shell that
     * runs for days must not grow without bound, and the oldest lines are
     * the ones nobody scrolls back to. */
    Scrollback sb;

    /* Bumped by every damage/cursor/property callback so the UI can skip a
     * redraw when nothing changed instead of re-snapshotting at 60 Hz. */
    unsigned long long epoch;
    int cursor_row;
    int cursor_col;
    int cursor_visible;
    int bell_count;
    int altscreen_active;
} TuxTerm;

static TuxTerm *from_handle(jlong handle)
{
    return (TuxTerm *)(intptr_t)handle;
}

static int rgb_pack(VTermColor col, int fallback)
{
    if (VTERM_COLOR_IS_DEFAULT_FG(&col) || VTERM_COLOR_IS_DEFAULT_BG(&col))
        return fallback;
    return (int)(0xFF000000u |
                 ((unsigned)col.rgb.red << 16) |
                 ((unsigned)col.rgb.green << 8) |
                 (unsigned)col.rgb.blue);
}

/* libvterm marks the column behind a double-width glyph by storing
 * (uint32_t)-1 in chars[0]. That value is not a codepoint: handing it to
 * Character.toChars on the Kotlin side throws IllegalArgumentException, so
 * every read path here replaces it with a space tagged width 0 -- an invisible
 * cell the renderer skips and the text extractor drops. */
#define SPACER_CP ((uint32_t)0xFFFFFFFFu)

/* One screen cell packed into [cp0, cp1, fg, bg, attrs]. Two codepoint slots
 * because a cell may hold base + combining mark (e->U+0301); cp1 == 0 means
 * the cell holds only cp0. The push callback, the snapshot and the range copy
 * all flatten cells through here so the three cannot drift apart. */
static void pack_cell(VTermScreen *screen, const VTermScreenCell *cell,
                      int fg_default, int bg_default, int32_t out[5])
{
    unsigned attrs = 0;
    VTermColor fg, bg;
    uint32_t ch0, ch1;

    if(cell->chars[0] == SPACER_CP) {
        out[0] = (int32_t)' ';
        out[1] = 0;
        out[3] = (int32_t)bg_default;
        out[4] = 0; /* width 0: nothing to draw, nothing to copy */
        fg = cell->fg;
        bg = cell->bg;
        vterm_screen_convert_color_to_rgb(screen, &fg);
        vterm_screen_convert_color_to_rgb(screen, &bg);
        out[2] = rgb_pack(fg, fg_default);
        return;
    }

    ch0 = cell->chars[0] ? cell->chars[0] : ' ';
    ch1 = 0;
    for (int i = 1; i < VTERM_MAX_CHARS_PER_CELL && cell->chars[i]; i++) {
        /* Only one combining slot fits; later marks are dropped rather than
         * silently appended to the wrong cell. */
        if (!ch1) ch1 = cell->chars[i];
    }

    if (cell->attrs.bold)
        attrs |= ATTR_BOLD;
    if (cell->attrs.underline)
        attrs |= ATTR_UNDERLINE;
    if (cell->attrs.italic)
        attrs |= ATTR_ITALIC;
    if (cell->attrs.reverse)
        attrs |= ATTR_REVERSE;
    if (cell->attrs.strike)
        attrs |= ATTR_STRIKE;
    if (cell->attrs.conceal)
        attrs |= ATTR_CONCEAL;
    if (cell->attrs.blink)
        attrs |= ATTR_BLINK;
    attrs |= ((unsigned)(cell->width > 0 ? cell->width : 1)) << ATTR_WIDTH_SHIFT;

    fg = cell->fg;
    bg = cell->bg;
    vterm_screen_convert_color_to_rgb(screen, &fg);
    vterm_screen_convert_color_to_rgb(screen, &bg);

    out[0] = (int32_t)ch0;
    out[1] = (int32_t)ch1;
    out[2] = rgb_pack(fg, fg_default);
    out[3] = rgb_pack(bg, bg_default);
    out[4] = (int32_t)attrs;
}

static int cb_damage(VTermRect rect, void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    (void)rect;
    t->epoch++;
    return 1;
}

static int cb_movecursor(VTermPos pos, VTermPos old, int visible, void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    (void)old;
    t->cursor_row = pos.row;
    t->cursor_col = pos.col;
    t->cursor_visible = visible;
    t->epoch++;
    return 1;
}

static int cb_settermprop(VTermProp prop, VTermValue *val, void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    /* ALTSCREEN is not cosmetic: `vim`/`less` swap the primary buffer out, so
     * the UI has to stop compositing scrollback above the screen while it is
     * on, or it paints the old primary buffer through the alt screen. */
    if (prop == VTERM_PROP_ALTSCREEN) {
        t->altscreen_active = val->boolean ? 1 : 0;
    }
    t->epoch++;
    return 1;
}

static int cb_bell(void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    t->bell_count++;
    t->epoch++;
    return 1;
}

static int cb_resize(int rows, int cols, void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    if (rows > 0 && cols > 0) {
        t->rows = rows;
        t->cols = cols;
    }
    t->epoch++;
    return 1;
}

static void sb_release_all(Scrollback *sb)
{
    int i;
    for (i = 0; i < sb->size; i++) {
        free(sb->lines[(sb->start + i) % sb->cap].cells);
        sb->lines[(sb->start + i) % sb->cap].cells = NULL;
    }
    sb->start = 0;
    sb->size = 0;
}

/* libvterm calls this for every line pushed off the top of the primary
 * buffer. Runs while the caller already holds t->lock (input and resize both
 * take it), so no extra locking is needed here. sb_pushline4 carries the
 * line's wrap flag, stored so copied text rejoins wrapped lines instead of
 * breaking mid-sentence. */
static int cb_sb_pushline4(int cols, const VTermScreenCell *cells,
                           bool continuation, void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    VTermColor dfg, dbg;
    ScrollLine *slot;
    int fg_default, bg_default, c;

    if (t->sb.cap <= 0 || cols <= 0 || !cells)
        return 1;

    if (t->sb.size == t->sb.cap) {
        free(t->sb.lines[t->sb.start].cells);
        t->sb.lines[t->sb.start].cells = NULL;
        t->sb.start = (t->sb.start + 1) % t->sb.cap;
        t->sb.size--;
    }

    slot = &t->sb.lines[(t->sb.start + t->sb.size) % t->sb.cap];
    slot->cells = (int32_t *)malloc((size_t)cols * CELLS_PER * sizeof(int32_t));
    if (!slot->cells)
        return 1;
    slot->cols = cols;
    slot->continuation = continuation;

    vterm_state_get_default_colors(t->state, &dfg, &dbg);
    vterm_screen_convert_color_to_rgb(t->screen, &dfg);
    vterm_screen_convert_color_to_rgb(t->screen, &dbg);
    fg_default = rgb_pack(dfg, 0xFFCCCCCC);
    bg_default = rgb_pack(dbg, 0xFF0B0F14);

    for (c = 0; c < cols; c++)
        pack_cell(t->screen, &cells[c], fg_default, bg_default,
                  slot->cells + c * CELLS_PER);

    t->sb.size++;
    return 1;
}

/* The three-argument form libvterm falls back to when the pushline4 opt-in
 * is missing; the wrap flag cannot be reported through it. */
static int cb_sb_pushline(int cols, const VTermScreenCell *cells, void *user)
{
    return cb_sb_pushline4(cols, cells, false, user);
}

/* CSI 3 J - erase scrollback. libvterm treats a non-zero return as handled. */
static int cb_sb_clear(void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    if (t->sb.cap > 0)
        sb_release_all(&t->sb);
    return 1;
}

/* Resize refill: libvterm calls this for each row the new geometry left
 * unfilled, newest history line first, and stops when we return 0. The
 * returned line leaves the ring for good -- it now lives on the screen, and
 * a later shrink will push it back through cb_sb_pushline4. */
static int cb_sb_popline(int cols, VTermScreenCell *cells, void *user)
{
    TuxTerm *t = (TuxTerm *)user;
    ScrollLine *slot;
    const int32_t *src;
    int i, n;

    if (t->sb.cap <= 0 || t->sb.size == 0 || cols <= 0 || !cells)
        return 0;

    slot = &t->sb.lines[(t->sb.start + t->sb.size - 1) % t->sb.cap];

    /* Blank every destination cell up front. The caller walks columns by
     * cell width, so an untouched zero-width cell would never advance. */
    for (i = 0; i < cols; i++) {
        memset(&cells[i], 0, sizeof(cells[i]));
        cells[i].width = 1;
    }

    src = slot->cells;
    n = slot->cols < cols ? slot->cols : cols;
    if (src) {
        for (i = 0; i < n; i++, src += CELLS_PER) {
            unsigned attrs = (unsigned)src[4];
            int width = (int)((attrs >> ATTR_WIDTH_SHIFT) & 0x3);
            uint32_t cp0 = (uint32_t)src[0];

            memset(&cells[i], 0, sizeof(cells[i]));
            /* A width-0 cell is one of our own spacers (the column behind a
             * double-width glyph). libvterm marks that column with -1, which
             * its own width-walking code would never advance past, so restore
             * the marker rather than letting it look like a blank cell. */
            if (width == 0) {
                cells[i].chars[0] = SPACER_CP;
                cells[i].width = 1;
                cells[i].attrs.bold = (attrs & ATTR_BOLD) != 0;
                vterm_color_rgb(&cells[i].fg,
                                (uint8_t)((src[2] >> 16) & 0xFF),
                                (uint8_t)((src[2] >> 8) & 0xFF),
                                (uint8_t)(src[2] & 0xFF));
                vterm_color_rgb(&cells[i].bg,
                                (uint8_t)((src[3] >> 16) & 0xFF),
                                (uint8_t)((src[3] >> 8) & 0xFF),
                                (uint8_t)(src[3] & 0xFF));
                continue;
            }

            cells[i].chars[0] = cp0;
            cells[i].chars[1] = (uint32_t)src[1];
            cells[i].width = (char)(width > 0 ? width : 1);
            cells[i].attrs.bold = (attrs & ATTR_BOLD) != 0;
            cells[i].attrs.underline = (attrs & ATTR_UNDERLINE) ? 1 : 0;
            cells[i].attrs.italic = (attrs & ATTR_ITALIC) != 0;
            cells[i].attrs.reverse = (attrs & ATTR_REVERSE) != 0;
            cells[i].attrs.strike = (attrs & ATTR_STRIKE) != 0;
            cells[i].attrs.conceal = (attrs & ATTR_CONCEAL) != 0;
            cells[i].attrs.blink = (attrs & ATTR_BLINK) != 0;
            vterm_color_rgb(&cells[i].fg,
                            (uint8_t)((src[2] >> 16) & 0xFF),
                            (uint8_t)((src[2] >> 8) & 0xFF),
                            (uint8_t)(src[2] & 0xFF));
            vterm_color_rgb(&cells[i].bg,
                            (uint8_t)((src[3] >> 16) & 0xFF),
                            (uint8_t)((src[3] >> 8) & 0xFF),
                            (uint8_t)(src[3] & 0xFF));
        }
    }

    free(slot->cells);
    slot->cells = NULL;
    t->sb.size--;
    if (t->sb.size == 0)
        t->sb.start = 0;
    return 1;
}

static const VTermScreenCallbacks k_callbacks = {
    .damage = cb_damage,
    .movecursor = cb_movecursor,
    .settermprop = cb_settermprop,
    .bell = cb_bell,
    .resize = cb_resize,
    .sb_pushline = cb_sb_pushline,
    .sb_popline = cb_sb_popline,
    .sb_clear = cb_sb_clear,
    .sb_pushline4 = cb_sb_pushline4,
};

JNIEXPORT jlong JNICALL
Java_dev_tuxterm_Native_vtermCreate(JNIEnv *env, jobject self, jint rows, jint cols)
{
    TuxTerm *t;

    (void)env;
    (void)self;
    if (rows <= 0 || cols <= 0)
        return 0;

    t = (TuxTerm *)calloc(1, sizeof(TuxTerm));
    if (!t)
        return 0;
    t->vt = vterm_new(rows, cols);
    if (!t->vt) {
        free(t);
        return 0;
    }
    /* libvterm defaults to mode.utf8 == 0, which routes bytes with the high
     * bit set through a 7-bit table encoding and strips that bit: 0xC3 0xA9
     * decodes as 'C' and ')' instead of U+00E9. A real shell emits non-ASCII
     * constantly (box drawing, arrows, UTF-8 paths), so turn it on before any
     * input arrives. */
    vterm_set_utf8(t->vt, 1);
    t->rows = rows;
    t->cols = cols;
    t->cursor_visible = 1;
    pthread_mutex_init(&t->lock, NULL);

    /* 1000 lines x 4 ints per cell: about 1.6 MB at 100 columns. Deep enough
     * to scrub back through a full package build, bounded enough that a shell
     * left running for a week cannot exhaust the heap. */
    t->sb.cap = 1000;
    t->sb.lines = (ScrollLine *)calloc((size_t)t->sb.cap, sizeof(ScrollLine));
    if (!t->sb.lines)
        t->sb.cap = 0;

    t->screen = vterm_obtain_screen(t->vt);
    t->state = vterm_obtain_state(t->vt);
    vterm_screen_set_callbacks(t->screen, &k_callbacks, t);
    /* ABI gate: without this call libvterm only invokes the legacy
     * sb_pushline and the wrap flag from sb_pushline4 never arrives. */
    vterm_screen_callbacks_has_pushline4(t->screen);
    /* Altscreen support so `less`/`vim` do not scroll away the real buffer. */
    vterm_screen_enable_altscreen(t->screen, 1);
    vterm_screen_reset(t->screen, 0);

    return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL
Java_dev_tuxterm_Native_vtermDestroy(JNIEnv *env, jobject self, jlong handle)
{
    TuxTerm *t = from_handle(handle);

    (void)env;
    (void)self;
    if (!t)
        return;
    pthread_mutex_destroy(&t->lock);
    vterm_free(t->vt);
    if (t->sb.lines) {
        sb_release_all(&t->sb);
        free(t->sb.lines);
    }
    free(t);
}

/* Feed raw PTY output. Returns the number of bytes consumed. */
JNIEXPORT jint JNICALL
Java_dev_tuxterm_Native_vtermInput(JNIEnv *env, jobject self, jlong handle,
                                   jbyteArray data, jint offset, jint length)
{
    TuxTerm *t = from_handle(handle);
    jbyte *bytes;
    size_t consumed = 0;

    (void)self;
    if (!t || !data || length <= 0)
        return 0;

    bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes)
        return 0;

    pthread_mutex_lock(&t->lock);
    consumed = vterm_input_write(t->vt, (const char *)bytes + offset,
                                 (size_t)length);
    /* Damage callbacks are deferred until flush; without this the grid would
     * lag one full read behind the bytes that changed it. */
    vterm_screen_flush_damage(t->screen);
    pthread_mutex_unlock(&t->lock);

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint)consumed;
}

JNIEXPORT void JNICALL
Java_dev_tuxterm_Native_vtermResize(JNIEnv *env, jobject self, jlong handle,
                                    jint rows, jint cols)
{
    TuxTerm *t = from_handle(handle);

    (void)env;
    (void)self;
    if (!t || rows <= 0 || cols <= 0)
        return;
    pthread_mutex_lock(&t->lock);
    if (rows != t->rows || cols != t->cols) {
        vterm_set_size(t->vt, rows, cols);
        t->rows = rows;
        t->cols = cols;
        t->epoch++;
    }
    pthread_mutex_unlock(&t->lock);
}

/*
 * Flatten the grid into `cells` as [char, fg, bg, attrs] per cell and write
 * [rows, cols, cursorRow, cursorCol, cursorVisible, epoch, bell] into `meta`.
 * Returns the number of cells written, or -1 if `cells` is too small.
 */
JNIEXPORT jint JNICALL
Java_dev_tuxterm_Native_vtermSnapshot(JNIEnv *env, jobject self, jlong handle,
                                      jintArray cells, jintArray meta)
{
    TuxTerm *t = from_handle(handle);
    VTermColor default_fg, default_bg;
    jint *out;
    jint *m;
    int r, c, needed;

    (void)self;
    if (!t || !cells || !meta)
        return -1;

    pthread_mutex_lock(&t->lock);
    needed = t->rows * t->cols;
    if ((*env)->GetArrayLength(env, cells) < needed * CELLS_PER ||
        (*env)->GetArrayLength(env, meta) < 8) {
        pthread_mutex_unlock(&t->lock);
        return -1;
    }

    vterm_state_get_default_colors(t->state, &default_fg, &default_bg);
    vterm_screen_convert_color_to_rgb(t->screen, &default_fg);
    vterm_screen_convert_color_to_rgb(t->screen, &default_bg);
    int fg_default = rgb_pack(default_fg, 0xFFCCCCCC);
    int bg_default = rgb_pack(default_bg, 0xFF0B0F14);

    out = (*env)->GetPrimitiveArrayCritical(env, cells, NULL);
    if (!out) {
        pthread_mutex_unlock(&t->lock);
        return -1;
    }

    for (r = 0; r < t->rows; r++) {
        for (c = 0; c < t->cols; c++) {
            VTermPos pos = { .row = r, .col = c };
            VTermScreenCell cell;
            int base = (r * t->cols + c) * CELLS_PER;

            memset(&cell, 0, sizeof(cell));
            vterm_screen_get_cell(t->screen, pos, &cell);
            pack_cell(t->screen, &cell, fg_default, bg_default, out + base);
            if (r == t->cursor_row && c == t->cursor_col && t->cursor_visible)
                out[base + 4] |= ATTR_CURSOR;
        }
    }

    (*env)->ReleasePrimitiveArrayCritical(env, cells, out, 0);

    m = (*env)->GetIntArrayElements(env, meta, NULL);
    if (m) {
        m[0] = t->rows;
        m[1] = t->cols;
        m[2] = t->cursor_row;
        m[3] = t->cursor_col;
        m[4] = t->cursor_visible;
        m[5] = (jint)t->epoch;
        m[6] = t->bell_count;
        m[7] = t->altscreen_active;
        (*env)->ReleaseIntArrayElements(env, meta, m, 0);
    }

    pthread_mutex_unlock(&t->lock);
    return needed;
}

/* Number of lines currently held in scrollback. O(1). */
JNIEXPORT jint JNICALL
Java_dev_tuxterm_Native_vtermScrollbackCount(JNIEnv *env, jobject self,
                                             jlong handle)
{
    TuxTerm *t = from_handle(handle);
    jint n;

    (void)env;
    (void)self;
    if (!t)
        return 0;

    pthread_mutex_lock(&t->lock);
    n = (jint)t->sb.size;
    pthread_mutex_unlock(&t->lock);
    return n;
}

/*
 * Copy up to `maxLines` scrollback lines ending `skipNewest` lines before the
 * newest into `cells`, each padded or truncated to the current column count,
 * oldest first, packed as [char, fg, bg, attrs] per cell exactly like the
 * snapshot. Returns the number of lines written, or -1 if `cells` is too
 * small.
 *
 * Padded to the current width rather than stored width so the renderer always
 * gets `cols` cells per line: losing the tail of a line that was captured
 * wide before a resize is what every other emulator does, and reflowing
 * scrollback would be a much larger change for no reading benefit.
 */
JNIEXPORT jint JNICALL
Java_dev_tuxterm_Native_vtermScrollbackTail(JNIEnv *env, jobject self,
                                            jlong handle, jint skipNewest,
                                            jint maxLines, jintArray cells)
{
    TuxTerm *t = from_handle(handle);
    VTermColor dfg, dbg;
    jint *out;
    int avail, skip, end, count, first, fg_default, bg_default, cols;
    int li, i;

    (void)self;
    if (!t || !cells || maxLines <= 0)
        return -1;

    pthread_mutex_lock(&t->lock);
    cols = t->cols;
    avail = t->sb.size;

    skip = skipNewest < 0 ? 0 : (skipNewest > avail ? avail : skipNewest);
    end = avail - skip; /* exclusive index of the newest line we return */
    count = end < maxLines ? end : maxLines;
    first = end - count;

    if (count <= 0 || cols <= 0) {
        pthread_mutex_unlock(&t->lock);
        return 0;
    }
    if ((*env)->GetArrayLength(env, cells) < count * cols * CELLS_PER) {
        pthread_mutex_unlock(&t->lock);
        return -1;
    }

    vterm_state_get_default_colors(t->state, &dfg, &dbg);
    vterm_screen_convert_color_to_rgb(t->screen, &dfg);
    vterm_screen_convert_color_to_rgb(t->screen, &dbg);
    fg_default = rgb_pack(dfg, 0xFFCCCCCC);
    bg_default = rgb_pack(dbg, 0xFF0B0F14);

    out = (*env)->GetIntArrayElements(env, cells, NULL);
    if (!out) {
        pthread_mutex_unlock(&t->lock);
        return -1;
    }

    for (li = 0; li < count; li++) {
        int idx = (t->sb.start + first + li) % t->sb.cap;
        const int32_t *src = t->sb.lines[idx].cells;
        int stored = t->sb.lines[idx].cols;
        int width = stored < cols ? stored : cols;
        jint *dst = out + (size_t)li * cols * CELLS_PER;

        if (!src) {
            /* Allocation failed at push time; leave the line blank rather
             * than reading freed or uninitialised memory. */
            width = 0;
        }

        for (i = 0; i < width; i++, src += CELLS_PER) {
            dst[i * CELLS_PER + 0] = (jint)src[0];
            dst[i * CELLS_PER + 1] = (jint)src[1];
            dst[i * CELLS_PER + 2] = (jint)src[2];
            dst[i * CELLS_PER + 3] = (jint)src[3];
            dst[i * CELLS_PER + 4] = (jint)src[4];
        }
        for (; i < cols; i++) {
            dst[i * CELLS_PER + 0] = (jint)' ';
            dst[i * CELLS_PER + 1] = 0;
            dst[i * CELLS_PER + 2] = (jint)fg_default;
            dst[i * CELLS_PER + 3] = (jint)bg_default;
            dst[i * CELLS_PER + 4] = 0;
        }
    }

    (*env)->ReleaseIntArrayElements(env, cells, out, 0);
    pthread_mutex_unlock(&t->lock);
    return count;
}

/*
 * Copy content rows [fromRow..toRow] -- scrollback first, then the live
 * screen -- as [char, fg, bg, attrs] cells plus one wrap flag per row, in a
 * single locked read so a push landing between two calls cannot shift cells
 * and flags apart. Cells are padded or truncated to the current column
 * count, like vtermScrollbackTail. Content row 0 is the oldest retained
 * scrollback line. Returns the number of rows written, or -1 when either
 * array is too small.
 */
JNIEXPORT jint JNICALL
Java_dev_tuxterm_Native_vtermCopyRange(JNIEnv *env, jobject self, jlong handle,
                                       jint fromRow, jint toRow,
                                       jintArray cells, jbooleanArray flags)
{
    TuxTerm *t = from_handle(handle);
    VTermColor dfg, dbg;
    jint *out;
    jboolean *fl;
    int rows, r, cols, sbRows, fg_default, bg_default;

    (void)self;
    if (!t || !cells || !flags || fromRow < 0 || toRow < fromRow)
        return -1;

    pthread_mutex_lock(&t->lock);
    cols = t->cols;
    sbRows = t->sb.size;
    rows = toRow - fromRow + 1;
    if (cols <= 0 ||
        (*env)->GetArrayLength(env, cells) < rows * cols * CELLS_PER ||
        (*env)->GetArrayLength(env, flags) < rows) {
        pthread_mutex_unlock(&t->lock);
        return -1;
    }

    vterm_state_get_default_colors(t->state, &dfg, &dbg);
    vterm_screen_convert_color_to_rgb(t->screen, &dfg);
    vterm_screen_convert_color_to_rgb(t->screen, &dbg);
    fg_default = rgb_pack(dfg, 0xFFCCCCCC);
    bg_default = rgb_pack(dbg, 0xFF0B0F14);

    out = (*env)->GetIntArrayElements(env, cells, NULL);
    if (!out) {
        pthread_mutex_unlock(&t->lock);
        return -1;
    }
    fl = (*env)->GetBooleanArrayElements(env, flags, NULL);
    if (!fl) {
        (*env)->ReleaseIntArrayElements(env, cells, out, 0);
        pthread_mutex_unlock(&t->lock);
        return -1;
    }

    for (r = 0; r < rows; r++) {
        int content = fromRow + r;
        jint *dst = out + (size_t)r * cols * CELLS_PER;

        if (content < sbRows) {
            int idx = (t->sb.start + content) % t->sb.cap;
            const int32_t *src = t->sb.lines[idx].cells;
            int stored = t->sb.lines[idx].cols;
            int width = stored < cols ? stored : cols;
            int i;

            fl[r] = t->sb.lines[idx].continuation ? JNI_TRUE : JNI_FALSE;
            for (i = 0; i < width && src; i++, src += CELLS_PER) {
                dst[i * CELLS_PER + 0] = (jint)src[0];
                dst[i * CELLS_PER + 1] = (jint)src[1];
                dst[i * CELLS_PER + 2] = (jint)src[2];
                dst[i * CELLS_PER + 3] = (jint)src[3];
                dst[i * CELLS_PER + 4] = (jint)src[4];
            }
            for (; i < cols; i++) {
                dst[i * CELLS_PER + 0] = (jint)' ';
                dst[i * CELLS_PER + 1] = 0;
                dst[i * CELLS_PER + 2] = (jint)fg_default;
                dst[i * CELLS_PER + 3] = (jint)bg_default;
                dst[i * CELLS_PER + 4] = 0;
            }
        } else {
            int srow = content - sbRows;
            const VTermLineInfo *info;
            int c;

            info = (srow >= 0 && srow < t->rows)
                ? vterm_state_get_lineinfo(t->state, srow) : NULL;
            fl[r] = (info && info->continuation) ? JNI_TRUE : JNI_FALSE;

            if (srow < 0 || srow >= t->rows) {
                /* Selection raced a shrink; emit a blank row rather than
                 * reading rows the screen no longer has. */
                for (c = 0; c < cols; c++) {
                    dst[c * CELLS_PER + 0] = (jint)' ';
                    dst[c * CELLS_PER + 1] = 0;
                    dst[c * CELLS_PER + 2] = (jint)fg_default;
                    dst[c * CELLS_PER + 3] = (jint)bg_default;
                    dst[c * CELLS_PER + 4] = 0;
                }
            } else {
                for (c = 0; c < cols; c++) {
                    VTermPos pos = { .row = srow, .col = c };
                    VTermScreenCell cell;

                    memset(&cell, 0, sizeof(cell));
                    vterm_screen_get_cell(t->screen, pos, &cell);
                    pack_cell(t->screen, &cell, fg_default, bg_default,
                              dst + c * CELLS_PER);
                }
            }
        }
    }

    (*env)->ReleaseBooleanArrayElements(env, flags, fl, 0);
    (*env)->ReleaseIntArrayElements(env, cells, out, 0);
    pthread_mutex_unlock(&t->lock);
    return rows;
}
