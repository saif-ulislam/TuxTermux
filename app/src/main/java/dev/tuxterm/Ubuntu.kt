package dev.tuxterm

import android.content.res.AssetManager
import android.net.LinkProperties
import android.system.Os
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Ubuntu provisioning: unpack a bundled proot, fetch Ubuntu Base once, extract
 * it, and produce the argv that runs a shell inside it.
 *
 * Every step reports through a progress callback so a stalled download or a
 * corrupt archive is visible on the status line instead of leaving a blank
 * terminal with no explanation. The rootfs is extracted into `rootfs.partial`
 * and only renamed once complete, so an interrupted run never leaves a
 * half-populated filesystem that looks ready.
 */
object Ubuntu {
    /**
     * The image the app unpacks on first run: Ubuntu Base plus the toolchain,
     * so python3 and Node are present the moment the terminal is usable.
     *
     * Baking them in removes apt, DNS and the network from the critical path
     * entirely. Doing it in-app instead meant every first run depended on the
     * user's mobile network -- and on this device plain apt could not install
     * anything at all until Android's refusal of link(2) was worked around.
     */
    const val TARBALL = "tuxterm-rootfs-arm64.tar.gz"
    /**
     * Where the image is fetched from.
     *
     * drive.usercontent.google.com rather than the usual
     * drive.google.com/uc?export=download: the latter answers a file this size
     * with a 2 KB "virus scan warning" HTML page, so the app would verify HTML
     * against the checksum and fail. This host with confirm=t returns the file
     * itself -- HTTP 200 with a Content-Length, which is what the downloader
     * needs to report progress.
     */
    private const val ROOTFS_URL =
        "https://drive.usercontent.google.com/download" +
            "?id=12nfMoqlfYEq2vEJH4b_CzuTL6Jv65BtD&export=download&confirm=t"
    private const val ROOTFS_SHA256 =
        "8e8b5cb65512dfbabfcd001fab04989c80e3aa38622da08b311b5a2532b4592b"

    /**
     * Files whose presence means the image is the fully provisioned one. Kept
     * to one entry per toolchain so this stays a cheap stat per launch.
     */
    private val TOOLCHAIN = listOf("usr/bin/python3", "usr/local/bin/node", "usr/local/bin/npm")

    /** 07777 -- every permission bit a tar entry can carry. */
    private const val MODE_MASK = 0xFFF

    fun prootPath(filesDir: File) = File(filesDir, "usr/bin/proot")

    fun rootfsPath(filesDir: File) = File(filesDir, "rootfs")

    /**
     * Makes the bundled tools and the rootfs available, downloading on demand.
     *
     * [version] identifies the packaged bundle: the tools are unpacked once per
     * revision and left alone on every later launch.
     */
    suspend fun provision(
        filesDir: File,
        assets: AssetManager,
        version: Long,
        onProgress: (String) -> Unit,
        onManualFileNeeded: (suspend (Throwable) -> File?) = { null },
    ) {
        onProgress("unpacking proot")
        installTools(filesDir, assets, version)

        // Readiness is proved by the toolchain, not by one file. The shipped
        // image carries python3 and Node; probing only bin/bash would call a
        // half-built or older rootfs ready and then fail at the first command.
        val rootfs = rootfsPath(filesDir)
        if (File(rootfs, "bin/bash").isFile) {
            val missing = TOOLCHAIN.filterNot { File(rootfs, it).isFile }
            if (missing.isEmpty()) {
                onProgress("Ubuntu ready")
                return
            }
            // bash but no toolchain: an image from before the toolchain was
            // baked in. Usable, so it is left alone rather than re-downloaded
            // behind the reader's back -- but said out loud, because the first
            // python3 or node command will otherwise look like a bug.
            onProgress("Ubuntu ready (no " + missing.joinToString(", ") + ")")
            return
        }

        // A previous run died mid-extract; its partial tree is unusable.
        File(filesDir, "rootfs.partial").deleteRecursively()

        val archive = File(filesDir, TARBALL)
        // A cached archive is re-verified, and a cached archive that fails is
        // discarded and fetched again. Without the retry a bad download -- or a
        // rebuild of the pinned archive under the same filename -- leaves the
        // install permanently unable to provision, because the cache is only
        // consulted when it is absent.
        if (archive.isFile && archive.length() > 0L) {
            onProgress("verifying Ubuntu archive")
            val cachedOk = runCatching { verify(archive) }.isSuccess
            if (!cachedOk) {
                onProgress("cached Ubuntu archive is unusable, refetching")
                archive.delete()
            }
        }
        if (!archive.isFile || archive.length() == 0L) {
            // The download can fail for reasons the app cannot control: a
            // hosted link that expires or is throttled, a blocked network, a
            // captive portal answering with a login page. Rather than leaving
            // the reader with an error they cannot act on, the failure is
            // offered to the caller as a chance to supply the file by hand.
            try {
                download(archive, onProgress)
            } catch (t: Throwable) {
                // A failed transfer leaves its partial behind; without this a
                // throttled retry would start from a stale file's leftovers.
                File(archive.path + ".part").delete()
                onProgress("download failed, asking for the file")
                val supplied = onManualFileNeeded(t)
                    ?: throw t
                if (!supplied.isFile || supplied.length() == 0L) {
                    throw IOException("chosen file is empty")
                }
                supplied.copyTo(archive, overwrite = true)
            }
            onProgress("verifying Ubuntu archive")
            verify(archive)
        }

        onProgress("extracting Ubuntu")
        val partial = File(filesDir, "rootfs.partial")
        extractTarGz(archive, partial, onProgress)

        if (rootfs.exists()) rootfs.deleteRecursively()
        if (!partial.renameTo(rootfs)) {
            throw IOException("could not move the extracted Ubuntu root into place")
        }
        archive.delete()
        onProgress("Ubuntu ready")
    }

    /**
     * Points the rootfs resolver at the nameservers the device is actually
     * using.
     *
     * The base image ships public resolvers, and on mobile networks plain DNS
     * to them is commonly dropped -- the lookups then fail or hang and every
     * `apt` run ends in "Temporary failure resolving". The link properties
     * carry what the platform itself resolves with, so they are written in
     * whenever the current set differs. A blank entry (the image ships a
     * malformed "nameserver" line with no address) is dropped rather than
     * copied down.
     */
    fun syncResolvConf(filesDir: File, link: LinkProperties?) {
        val servers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        if (servers.isEmpty()) return
        val text = buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            // Bound the damage when a server is unreachable: the resolver's
            // default of 5s x 2 is what turns one dead lookup into ten.
            append("options timeout:2 attempts:2\n")
        }
        runCatching {
            val file = File(rootfsPath(filesDir), "etc/resolv.conf")
            if (file.parentFile?.isDirectory == true && file.readText() != text) {
                file.writeText(text)
            }
        }
    }



    /**
     * argv and extra environment for a shell inside the rootfs.
     *
     * The variables below are load-bearing, not tuning:
     *
     *  - LD_LIBRARY_PATH: proot links against libtalloc/libandroid-shmem from
     *    the bundle rather than from the platform.
     *  - PROOT_LOADER: proot replaces the target program with its own
     *    bootstrap loader on every execve. With no explicit path it falls back
     *    to "./loader", which does not exist here, and the execve fails with
     *    EACCES -- which surfaces as "Permission denied" for any command.
     *  - PROOT_TMP_DIR: the compiled-in default is Termux's private tmp, which
     *    this app cannot write, so the f2fs probe and temp files would fail.
     *  - PROOT_NO_SECCOMP: only set when the probe below finds that proot
     *    cannot install its own seccomp filter, which is silent on a kernel
     *    without CONFIG_SECCOMP_FILTER and leaves nothing runnable. Forcing it
     *    unconditionally is not free: it removes the accelerated path and was
     *    measured here at 21.7s against 10.0s for the same workload.
     *
     * /proc is deliberately not bound in. A bind makes proot present that path
     * verbatim, so the host's /proc -- one AID per host process, with their
     * names -- appears inside the sandbox. That is what makes startup print
     *
     *     groups: cannot find name for group ID 3003
     *
     * for every host Android group the base image knows nothing about. Its
     * absence costs the few tools that read /proc/self/maps, and keeps host
     * process and AID metadata out of a root shell that is not otherwise
     * namespaced away from them.
     */
    fun launch(filesDir: File): Pair<List<String>, Map<String, String>> {
        val argv = listOf(
            prootPath(filesDir).absolutePath,
            "-0",
            // --link2symlink: Android denies link(2) outright on app storage,
            // and proot does not hide that, so every hard link fails. dpkg
            // builds its /var/lib/dpkg/status-old backup with link(), so
            // without this every install dies with
            //   dpkg: error: error creating new backup file
            //     '/var/lib/dpkg/status-old': Permission denied
            //   E: dpkg was interrupted, you must manually run 'dpkg --configure -a'
            // and dpkg --configure -a cannot repair it either, because the
            // repair uses the same call. With the flag proot emulates each link
            // as a symlink pair; verified here by installing python3, which
            // fails without it and completes in ~47s with it.
            "-l",
            "-r", rootfsPath(filesDir).absolutePath,
            "-b", "/dev",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "/bin/bash", "-i",
        )
        val libDir = File(filesDir, "usr/lib")
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "PROOT_LOADER" to File(libDir, "libprootloader.so").absolutePath,
            "PROOT_TMP_DIR" to File(filesDir, "tmp").absolutePath,
        )
        if (!seccompAvailable(filesDir)) env["PROOT_NO_SECCOMP"] = "1"
        return argv to env
    }

    /**
     * Whether proot can install its seccomp filter on this kernel.
     *
     * Probed, never assumed. With the filter proot resolves paths inside the
     * kernel; without it every syscall takes the slow ptrace path, measured here
     * at 21.7s against 10.0s for the same 1200 execs -- so an unconditional
     * `PROOT_NO_SECCOMP=1` costs the sandbox roughly half its speed on any
     * device that never needed it. The variable is only correct as a fallback
     * for a kernel built without CONFIG_SECCOMP_FILTER, which this probe
     * detects by running the real binary once and checking that it exits clean.
     *
     * The answer is cached next to the tools: it is a property of the kernel,
     * not of a launch, and the probe costs a process spawn.
     */
    private fun seccompAvailable(filesDir: File): Boolean {
        val marker = File(filesDir, "seccomp.txt")
        if (marker.isFile) return marker.readText().trim() == "1"

        val libDir = File(filesDir, "usr/lib")
        val ok = try {
            val process = ProcessBuilder(
                prootPath(filesDir).absolutePath,
                "-0",
                "-r", rootfsPath(filesDir).absolutePath,
                "/bin/sh", "-c", "exit 0",
            )
                .redirectErrorStream(true)
                .also { builder ->
                    // Deliberately no PROOT_NO_SECCOMP here: the point is to
                    // find out whether the filter path works unprompted.
                    builder.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
                    builder.environment()["PROOT_LOADER"] =
                        File(libDir, "libprootloader.so").absolutePath
                    builder.environment()["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
                }
                .start()
            // Drain before waiting: a full pipe would block the child forever.
            process.inputStream.readBytes()
            process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (t: Throwable) {
            false
        }
        runCatching { marker.writeText(if (ok) "1" else "0") }
        return ok
    }

    // --- bundle ------------------------------------------------------------

    /**
 * Records which bundle revision is currently unpacked, so the tools are
 * extracted once instead of on every launch.
 *
 * The assets live inside the APK and can only change when the app itself is
 * updated, so a matching stamp means there is nothing to do. Skipping on that
 * basis replaces decompressing and rewriting ~325 KB on each start.
 */
private fun stampFor(filesDir: File): File = File(filesDir, "tools.stamp")

private fun installTools(filesDir: File, assets: AssetManager, version: Long) {
    File(filesDir, "usr/bin").mkdirs()
    File(filesDir, "usr/lib").mkdirs()
    // launch() points proot at this directory for its f2fs probe and temp
    // files, so a failed mkdirs here would surface much later as a puzzling
    // proot failure. Fail here, where the cause is obvious.
    val tmp = File(filesDir, "tmp")
    if (!tmp.isDirectory && !tmp.mkdirs()) {
        throw IOException("could not create proot temp directory at ${tmp.absolutePath}")
    }

    val stamp = stampFor(filesDir)
    if (stamp.isFile && stamp.readText().trim() == version.toString() && toolsPresent(filesDir)) {
        return
    }

    copyAsset(assets, "bin/proot", prootPath(filesDir))
    copyAsset(assets, "lib/libtalloc.so", File(filesDir, "usr/lib/libtalloc.so"))
    copyAsset(
        assets,
        "lib/libandroid-shmem.so",
        File(filesDir, "usr/lib/libandroid-shmem.so"),
    )
    // proot execve's this on every launch; without it nothing runs.
    copyAsset(assets, "lib/libprootloader.so", File(filesDir, "usr/lib/libprootloader.so"))
    // proot now links against libtalloc.so. Drop the superseded name so a
    // pre-existing install cannot be left holding two talloc builds.
    File(filesDir, "usr/lib/libtalloc.so.2").delete()
    stamp.writeText(version.toString())
}

/**
 * The stamp is only a fast path, so the files it claims to describe are still
 * checked: a user or a partial write can remove one without the version ever
 * changing.
 */
private fun toolsPresent(filesDir: File): Boolean =
    prootPath(filesDir).canExecute() &&
        File(filesDir, "usr/lib/libprootloader.so").isFile &&
        File(filesDir, "usr/lib/libtalloc.so").isFile &&
        File(filesDir, "usr/lib/libandroid-shmem.so").isFile

private fun copyAsset(assets: AssetManager, from: String, to: File) {
    // Only reached on an install or an update, so extracting unconditionally
    // here costs nothing on the steady-state path.
    val bytes = assets.open(from).use { it.readBytes() }
    FileOutputStream(to).use { it.write(bytes) }
    Os.chmod(to.absolutePath, "755".toInt(8))
}

    // --- download ----------------------------------------------------------

    private fun download(dest: File, onProgress: (String) -> Unit) {
        val partial = File(dest.path + ".part")
        val conn = (URL(ROOTFS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("rootfs download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress("downloading Ubuntu $percent%")
                            }
                        }
                    }
                }
            }
            val hex = hex(digest.digest())
            if (!hex.equals(ROOTFS_SHA256, ignoreCase = true)) {
                partial.delete()
                throw IOException("rootfs checksum mismatch (got $hex)")
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) throw IOException("could not keep the download")
        } finally {
            conn.disconnect()
        }
    }

    private fun verify(file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = hex(digest.digest())
        if (!hex.equals(ROOTFS_SHA256, ignoreCase = true)) {
            file.delete()
            throw IOException("rootfs checksum mismatch (got $hex)")
        }
    }

    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // --- tar ---------------------------------------------------------------

    private fun extractTarGz(archive: File, dest: File, onProgress: (String) -> Unit) {
        dest.mkdirs()
        val dirModes = ArrayList<Pair<String, Int>>()
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            var longName: String? = null
            var longLink: String? = null
            var paxPath: String? = null
            var paxLink: String? = null
            var extracted = 0L
            var lastReport = 0L

            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it.toInt() == 0 }) break

                val name = strAt(header, 0, 100)
                val mode = octalAt(header, 100, 8).toInt()
                val size = octalAt(header, 124, 12)
                val type = (header[156].toInt() and 0xFF).toChar()
                val link = strAt(header, 157, 100)
                val blocks = padded(size)

                // GNU long-name and pax extensions each carry one payload and
                // are followed by the real entry they annotate.
                if (type == 'L' || type == 'K') {
                    val payload = readPayload(input, size)
                    if (type == 'L') longName = payload else longLink = payload
                    continue
                }
                if (type == 'x' || type == 'X' || type == 'g') {
                    if (type == 'g') {
                        skipBytes(input, blocks)
                    } else {
                        parsePax(readPayload(input, size))?.let { (p, l) ->
                            paxPath = p ?: paxPath
                            paxLink = l ?: paxLink
                        }
                    }
                    continue
                }

                val entry = paxPath ?: longName ?: name
                val entryLink = paxLink ?: longLink ?: link
                paxPath = null
                paxLink = null
                longName = null
                longLink = null

                // An entry that reduces to nothing ("." or "./") has no
                // content to write. Skipping it keeps a stray such entry from
                // failing an otherwise valid archive; sanitize still throws on
                // the `..` escape case, so that one is not silenced.
                val relative = sanitize(entry)
                val target = if (relative.isEmpty()) null else File(dest, relative)

                when (type) {
                    '5' -> {
                        if (target != null) target.mkdirs()
                        dirModes += entry to mode
                        skipBytes(input, blocks)
                    }
                    '0', '\u0000' -> {
                        if (target == null) {
                            skipBytes(input, blocks)
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> copyN(input, out, size) }
                            skipBytes(input, blocks - size)
                            // Directory modes are applied after the walk: chmodding
                            // a directory up front could lock us out of its children.
                            Os.chmod(target.absolutePath, mode and MODE_MASK)
                        }
                    }
                    '2' -> {
                        if (target != null) {
                            target.parentFile?.mkdirs()
                            if (!target.exists()) Os.symlink(entryLink, target.absolutePath)
                        }
                        skipBytes(input, blocks)
                    }
                    '1' -> {
                        val sourceRel = sanitize(entryLink)
                        if (target != null && sourceRel.isNotEmpty()) {
                            target.parentFile?.mkdirs()
                            val source = File(dest, sourceRel)
                            try {
                                Os.link(source.absolutePath, target.absolutePath)
                            } catch (_: Throwable) {
                                if (source.isFile) source.copyTo(target, overwrite = true)
                            }
                        }
                        skipBytes(input, blocks)
                    }
                    else -> skipBytes(input, blocks)
                }

                extracted += size
                if (extracted - lastReport >= 8L * 1024 * 1024) {
                    lastReport = extracted
                    onProgress("extracting Ubuntu (${extracted / (1024 * 1024)} MB)")
                }
            }
        }
        for ((rel, mode) in dirModes) {
            try {
                Os.chmod(File(dest, sanitize(rel)).absolutePath, mode and MODE_MASK)
            } catch (_: Throwable) {
                // A mode we cannot apply does not invalidate the rootfs.
            }
        }
    }

    /**
     * Normalise a tar entry path to a path relative to the extraction root.
     *
     * Returns "" for an entry that carries no path of its own ("." or "./"),
     * which the caller skips. Throws when the entry would escape the root, so
     * a malicious archive fails loudly rather than writing outside dest.
     */
    private fun sanitize(name: String): String {
        val parts = name.removePrefix("/").split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("tar entry escapes the root: $name")
        // "." or "./" normalise to nothing, and File(dest, "") is dest itself:
        // an entry that reduced to empty would be opened as the destination
        // directory.
        if (parts.isEmpty()) return ""
        return parts.joinToString("/")
    }

    private fun parsePax(payload: String): Pair<String?, String?>? {
        var path: String? = null
        var linkpath: String? = null
        for (record in payload.split('\n')) {
            if (record.isEmpty()) continue
            val space = record.indexOf(' ')
            if (space < 0) continue
            val kv = record.substring(space + 1)
            when {
                kv.startsWith("path=") -> path = kv.substringAfter("path=")
                kv.startsWith("linkpath=") -> linkpath = kv.substringAfter("linkpath=")
            }
        }
        return if (path == null && linkpath == null) null else path to linkpath
    }

    private fun readPayload(input: InputStream, size: Long): String {
        val raw = ByteArray(size.toInt())
        readFullyOrThrow(input, raw)
        skipBytes(input, padded(size) - size)
        var end = 0
        while (end < raw.size && raw[end].toInt() != 0) end++
        return String(raw, 0, end, Charsets.ISO_8859_1)
    }

    private fun padded(size: Long): Long = (size + 511L) / 512L * 512L

    private fun strAt(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && buf[end].toInt() != 0) end++
        return String(buf, off, end - off, Charsets.ISO_8859_1)
    }

    private fun octalAt(buf: ByteArray, off: Int, len: Int): Long {
        if ((buf[off].toInt() and 0x80) != 0) {
            throw IOException("tar header uses base-256 encoding, which is unsupported")
        }
        val text = strAt(buf, off, len).trim { it == ' ' || it == '\u0000' }
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: 0L
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun readFullyOrThrow(input: InputStream, buf: ByteArray) {
        if (!readFully(input, buf)) throw EOFException("tar archive ended mid-entry")
    }

    private fun skipBytes(input: InputStream, count: Long) {
        if (count <= 0) return
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) throw EOFException("tar archive ended mid-padding")
            left -= n
        }
    }

    private fun copyN(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) throw EOFException("tar archive ended mid-file")
            out.write(buf, 0, n)
            left -= n
        }
    }
}
