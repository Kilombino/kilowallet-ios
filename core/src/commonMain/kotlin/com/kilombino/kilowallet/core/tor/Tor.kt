package com.kilombino.kilowallet.core.tor

import com.kilombino.kilowallet.core.platform.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Arti linked into the app (tor-engine/kilombino-tor), as each platform gives it. Android runs
 * Arti's program; iOS can't, so it runs the same proxy as a library on its own thread.
 */
interface TorEngine {
    /**
     * Starts Arti: a SOCKS port on 127.0.0.1:[port], cache and state under [dir], log appended to
     * [logPath]. Null once the port listens (it then connects on its own), else why it could not
     * start. Blocking.
     */
    fun start(dir: String, logPath: String, port: Int): String?
    /** Asks it to stop; returns at once. */
    fun stop()
    /** 0: not running. 1: running, still connecting. 2: its SOCKS port carries traffic. */
    fun state(): Int
}

/** Tor's files and the little networking it needs, as each platform gives them. */
interface TorHost {
    /** Arti's folder (cache/, state/), not backed up. */
    val dir: String
    /** Tor's own log (kept for the TOR LOG screen and crash reports; no addresses or keys in it). */
    val logPath: String
    /** Every file under [dir], at any depth. */
    fun files(dir: String): List<String>
    fun readBytes(path: String): ByteArray?
    fun writeText(path: String, text: String)
    /** A file or a whole folder. */
    fun delete(path: String)
    fun size(path: String): Long
    /** Up to [max] bytes of [path] from offset [from]. */
    fun read(path: String, from: Long, max: Int): ByteArray?
    /** A TCP port free on 127.0.0.1 right now. */
    fun freePort(): Int
    /** True when something answers a SOCKS5 greeting on 127.0.0.1:[port]. */
    fun socksAnswers(port: Int): Boolean
    fun nowMs(): Long
}

/**
 * Tor inside the app, ported from the Android app's `chain/Tor.kt` (1.4.4): Arti, started only
 * when a connection needs it (Lightning, a .onion server, "Connect to the server through Tor").
 * While it runs, its SOCKS port carries those connections.
 *
 * From Android 1.2.7-beta5/beta6: before each start, state files that are empty or not JSON
 * are deleted ([repairState]; an empty guards.json kept Arti down for good), and once Arti says
 * "corrupted data in persistent state" the whole state/ goes before the next start. On iOS that
 * next start comes at once.
 *
 * iPhone differences: the app is suspended in the background and its listening port may be
 * taken back by the system, so [onForeground] checks the port and starts Tor again (on the same
 * port, which LND already has in its settings) when something needed it before.
 */
object Tor {
    @Volatile var engine: TorEngine? = null
    lateinit var host: TorHost

    /** This build has Arti (iOS: KilombinoTor.xcframework linked in). */
    val available: Boolean get() = engine != null

    /** Arti's last line worth showing ("Bootstrap status: 45%…", an error): why it is not up yet, or why it stopped. */
    @Volatile var lastLine: String = ""
        private set

    /** Arti said its state was corrupted: the whole state goes before the next start. */
    @Volatile internal var stateCorrupted = false

    /** The log said Arti can carry traffic (kept with the engine's own state, as Android reads only the log). */
    @Volatile internal var readySeen = false

    /** The SOCKS port of this run of the app: kept across restarts, LND has it in its settings. */
    @Volatile var port: Int = -1
        private set

    /** Something needed Tor in this run: it comes back when the app returns to the foreground. */
    @Volatile var wanted = false
        private set

    /**
     * "Only over Tor" (on by default, as Android's since 1.2.7-beta6): what goes over Tor never
     * goes without it. Lightning waits for Tor and says so when it doesn't come up, instead of
     * starting without it after a minute (Android 1.2.7-beta4's silent fallback).
     */
    @Volatile var only: Boolean = true

    /** "Connect to the server through Tor" (off by default, as Android): every Electrum server through Tor. */
    @Volatile var electrum: Boolean = false

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var pump: Job? = null
    private var logOffset = 0L
    private var partial = ""

    /** Tor is up: its SOCKS port carries traffic. */
    val running: Boolean get() = engine?.state() == 2 && port > 0

    /** Tor's thread is alive (up, or still connecting). */
    val alive: Boolean get() = (engine?.state() ?: 0) > 0

    /** The SOCKS port when Tor is already running, else null: for requests that use Tor when it is on, and go directly otherwise. */
    fun proxyIfRunning(): Int? = if (running) port else null

    /**
     * Starts Tor if it isn't running (a no-op when it is). Null when it is starting or up, else
     * why it could not start. Blocking work runs off the caller's thread.
     */
    suspend fun start(): String? = lock.withLock {
        withContext(ioDispatcher) { startLocked() }
    }

    private suspend fun startLocked(): String? {
        val e = engine ?: return "Tor is not in this build"
        wanted = true
        if (alive) return null
        var err = launch(e)
        // Arti's own word settles it: a state it can't read goes, and it starts again from a clean one.
        if (err != null && CORRUPTED in err) err = launch(e)
        return err
    }

    private suspend fun launch(e: TorEngine): String? {
        val state = host.dir + "/state"
        if (stateCorrupted) { host.delete(state); stateCorrupted = false }
        repairState(state)
        trimLog()
        if (port <= 0) port = host.freePort()
        readySeen = false; lastLine = ""
        pump?.cancel(); pump = null
        logOffset = host.size(host.logPath); partial = ""
        var err = e.start(host.dir, host.logPath, port)
        // The port of an earlier run is taken by something else: a new one (LND picks it up on its next start).
        if (err != null && "listen" in err.lowercase()) {
            port = host.freePort()
            err = e.start(host.dir, host.logPath, port)
        }
        if (err != null) {
            readLog()
            if (CORRUPTED in err) stateCorrupted = true
            lastLine = err.take(160)
            return err
        }
        pump = scope.launch { follow() }
        return null
    }

    /**
     * Reads the log while Arti runs (and once more after it stops). Should it stop because its
     * state was corrupted, it is started again at once with a clean state, as long as something
     * still wants Tor.
     */
    private suspend fun follow() {
        while (true) {
            readLog()
            if (!alive) break
            delay(400)
        }
        readLog()
        if (stateCorrupted && wanted && scope.isActive) lock.withLock { if (!alive) engine?.let { launch(it) } }
    }

    private fun readLog() {
        val size = host.size(host.logPath)
        if (size < logOffset) { logOffset = 0; partial = "" }
        while (logOffset < size) {
            val chunk = host.read(host.logPath, logOffset, 64_000) ?: return
            if (chunk.isEmpty()) return
            logOffset += chunk.size
            val text = partial + chunk.decodeToString()
            val lines = text.split('\n')
            partial = lines.last()
            lines.dropLast(1).forEach { onLine(it) }
        }
    }

    /** One line of Arti's log, as Android reads its program's output. */
    internal fun onLine(line: String) {
        if (CORRUPTED in line) stateCorrupted = true
        if (line.isNotBlank()) lastLine = line.substringAfter(" INFO ").substringAfter(" WARN ").substringAfter(" ERROR ").trim().take(160)
        // Arti says this once it can carry traffic: from then on the SOCKS port works.
        if ("Sufficiently bootstrapped" in line || "SOCKS now functional" in line) readySeen = true
    }

    /**
     * Arti refuses to start on a state file it can't read ("corrupted data in persistent state"),
     * as a guards.json left empty when the app was killed mid-write: it stays down for good. Such a
     * file only holds what Arti learns again on its own (guards, timings), so an empty or unreadable
     * one is deleted before starting. Strictly an object or an array: a file of zero bytes (what a
     * cut-short write often leaves) is not JSON either.
     */
    fun repairState(state: String) {
        for (f in host.files(state).filter { it.endsWith(".json") }) {
            val text = runCatching { host.readBytes(f)?.decodeToString()?.trim() }.getOrNull() ?: ""
            val ok = runCatching {
                when (text.firstOrNull()) { '{', '[' -> { Json.parseToJsonElement(text); true }; else -> false }
            }.getOrDefault(false)
            if (!ok) host.delete(f)
        }
    }

    /** The log is kept short: past 256 kB, its last 32 kB. */
    private fun trimLog() {
        val size = host.size(host.logPath)
        if (size <= 256_000) return
        val tail = host.read(host.logPath, size - 32_000, 32_000) ?: return
        host.writeText(host.logPath, tail.decodeToString().substringAfter('\n'))
    }

    /** The end of the log, to show and copy: no addresses or keys in it (Arti's safe logging). */
    fun logTail(lines: Int = 40): String = runCatching {
        val size = host.size(host.logPath)
        val from = maxOf(0L, size - 24_000)
        (host.read(host.logPath, from, 24_000) ?: ByteArray(0)).decodeToString().lines()
            .filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    /** The SOCKS port, waiting up to [waitMs] for Tor to come up; null if it does not. */
    suspend fun proxy(waitMs: Long = 90_000): Int? {
        val until = host.nowMs() + waitMs
        while (true) {
            if (running) return port
            // Not running (never started, or it stopped): nothing to wait for.
            if (!alive) return null
            if (host.nowMs() >= until) return null
            delay(250)
        }
    }

    /** Starts Tor if needed and waits up to [waitMs] for it: the SOCKS port, or null. */
    suspend fun startAndWait(waitMs: Long = 90_000): Int? {
        if (!available) return null
        if (start() != null) return null
        return proxy(waitMs)
    }

    /**
     * Back in the foreground: iOS suspends the app in the background and may take its listening
     * port back. If Tor was in use, its port must still answer; if not, Tor starts again (same port).
     */
    suspend fun onForeground() {
        val e = engine ?: return
        if (!wanted) return
        lock.withLock {
            withContext(ioDispatcher) {
                if (alive && port > 0 && !host.socksAnswers(port)) {
                    e.stop()
                    val until = host.nowMs() + 8_000
                    while (alive && host.nowMs() < until) delay(100)
                }
                if (!alive) launch(e)
            }
        }
    }

    fun stop() {
        wanted = false
        engine?.stop()
    }

    /** What the settings show: off, connecting (and why it isn't up yet), on, or stopped (and why). */
    data class Status(val state: Int, val port: Int, val line: String, val wanted: Boolean)
    fun status(): Status = Status(engine?.state() ?: 0, port, lastLine, wanted)

    internal const val CORRUPTED = "corrupted data in persistent state"
}
