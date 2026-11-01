package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.tor.Tor
import com.kilombino.kilowallet.core.tor.TorEngine
import com.kilombino.kilowallet.core.tor.TorHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tor's state handling, as Android's TorStateTest (1.2.7-beta5/beta6) plus what iOS adds. */
class TorStateTest {
    private lateinit var root: File

    private class FileHost(root: File) : TorHost {
        override val dir = File(root, "arti").path
        override val logPath = File(root, "tor.log").path
        var answers = true
        override fun files(dir: String) = File(dir).walkTopDown().filter { it.isFile }.map { it.path }.toList()
        override fun readBytes(path: String) = File(path).takeIf { it.exists() }?.readBytes()
        override fun writeText(path: String, text: String) { File(path).apply { parentFile.mkdirs() }.writeText(text) }
        override fun delete(path: String) { File(path).deleteRecursively() }
        override fun size(path: String) = File(path).length()
        override fun read(path: String, from: Long, max: Int): ByteArray? {
            val f = File(path); if (!f.exists()) return null
            java.io.RandomAccessFile(f, "r").use { r ->
                val n = minOf(max.toLong(), r.length() - from).toInt().coerceAtLeast(0)
                r.seek(from); val b = ByteArray(n); r.readFully(b); return b
            }
        }
        var nextPort = 40000
        override fun freePort() = nextPort++
        override fun socksAnswers(port: Int) = answers
        override fun nowMs() = System.currentTimeMillis()
    }

    /** Arti as a stand-in: writes what the real one logs, and fails as told. */
    private class FakeEngine(val host: FileHost) : TorEngine {
        @Volatile var st = 0
        val starts = ArrayList<Int>()
        /** Lines the next run logs before it stops (null: it bootstraps and stays up). */
        var dieWith: String? = null
        var busyPort: Int? = null
        var stateSeenAtStart: List<String> = emptyList()
        private fun log(s: String) = File(host.logPath).appendText("2026-11-01T10:00:00.000000Z  INFO kilombino_tor: $s\n")
        override fun start(dir: String, logPath: String, port: Int): String? {
            if (port == busyPort) return "Can't listen on 127.0.0.1:$port: Address already in use"
            starts += port
            stateSeenAtStart = host.files("$dir/state").map { File(it).name }
            log("Starting Arti 2.7.0 in proxy mode on 127.0.0.1:$port ...")
            val die = dieWith
            if (die != null) {
                dieWith = null
                File(host.logPath).appendText("2026-11-01T10:00:01.000000Z ERROR kilombino_tor: Arti stopped: $die\n")
                st = 0
            } else {
                st = 2
                log("Sufficiently bootstrapped; proxy now functional.")
            }
            return null
        }
        override fun stop() { st = 0 }
        override fun state() = st
    }

    private lateinit var host: FileHost
    private lateinit var engine: FakeEngine

    @BeforeTest fun setUp() {
        root = kotlin.io.path.createTempDirectory("tor-test").toFile()
        host = FileHost(root); engine = FakeEngine(host)
        Tor.host = host; Tor.engine = engine
    }

    @AfterTest fun tearDown() { Tor.stop(); Tor.engine = null; root.deleteRecursively() }

    /** An empty guards.json kept Arti down for good ("corrupted data in persistent state"): it goes, readable ones stay. */
    @Test fun unreadableStateFilesAreDeleted() {
        val state = File(root, "state")
        val empty = File(state, "guards.json").apply { parentFile.mkdirs(); writeText("") }
        val cut = File(state, "sub/circuit_timeouts.json").apply { parentFile.mkdirs(); writeText("{\"a\": [1, 2") }
        val good = File(state, "state.json").apply { writeText("{\"ok\": true}") }
        val list = File(state, "list.json").apply { writeText("[1, 2]") }
        val zeros = File(state, "zeros.json").apply { writeBytes(ByteArray(64)) }
        val bare = File(state, "bare.json").apply { writeText("true") }
        val other = File(state, "state.lock").apply { writeText("") }
        Tor.repairState(state.path)
        assertFalse(empty.exists()); assertFalse(cut.exists()); assertFalse(zeros.exists()); assertFalse(bare.exists())
        assertTrue(good.exists()); assertTrue(list.exists()); assertTrue(other.exists())
    }

    /** The last line worth showing, and the line that says the SOCKS port works, read as Android reads them. */
    @Test fun logLinesAreReadAsAndroidReadsThem() {
        Tor.onLine("2026-11-01T10:35:40.135042Z  INFO kilombino_tor: Bootstrap status: 93%: handshaking with Tor relays")
        assertEquals("kilombino_tor: Bootstrap status: 93%: handshaking with Tor relays", Tor.lastLine)
        Tor.onLine("2026-11-01T10:35:40.028692Z  INFO kilombino_tor: Sufficiently bootstrapped; proxy now functional.")
        Tor.onLine("   ")
        assertEquals("kilombino_tor: Sufficiently bootstrapped; proxy now functional.", Tor.lastLine)
    }

    /** Arti dies on a corrupted state while running (the guards are read while bootstrapping): state/ goes, and it starts again at once. */
    @Test fun aCorruptedStateIsWipedAndTorStartsAgain() = runBlocking {
        // A state file Arti can't read even though it is JSON (what repairState can't catch).
        val guards = File(host.dir, "state/state/guards.json").apply { parentFile.mkdirs(); writeText("{\"guards\": 7}") }
        engine.dieWith = "bootstrap: tor: corrupted data in persistent state: Error setting up the guard manager"
        assertNull(Tor.start())
        repeat(50) { if (engine.starts.size < 2 || !Tor.running) delay(100) }
        assertEquals(2, engine.starts.size, "started again")
        assertFalse(guards.exists(), "state wiped before the second start")
        assertTrue(engine.stateSeenAtStart.isEmpty())
        assertTrue(Tor.running)
        assertEquals(engine.starts[0], engine.starts[1], "same port")
    }

    /** The same port across restarts in one run (LND has it in its settings); a new one only if it is taken. */
    @Test fun thePortIsKeptAcrossRestarts() = runBlocking {
        assertNull(Tor.start())
        val first = Tor.port
        assertEquals(first, Tor.proxy(1000))
        engine.stop()
        assertNull(Tor.proxyIfRunning())
        assertNull(Tor.start())
        assertEquals(first, Tor.port)
        engine.stop(); engine.busyPort = first
        assertNull(Tor.start())
        assertNotEquals(first, Tor.port)
        assertTrue(Tor.running)
    }

    /** Back from the background with the port gone (iOS took it back): Tor is started again; one that answers is left alone. */
    @Test fun backInTheForegroundAPortThatDoesNotAnswerRestartsTor() = runBlocking {
        Tor.onForeground()
        assertTrue(engine.starts.isEmpty(), "nothing wanted Tor: nothing starts")
        assertNull(Tor.start())
        Tor.onForeground()
        assertEquals(1, engine.starts.size)
        host.answers = false
        Tor.onForeground()
        assertEquals(2, engine.starts.size)
        assertTrue(Tor.running)
    }

    /** Not running and not wanted: no proxy, no waiting. */
    @Test fun noTorMeansNoProxy() = runBlocking {
        assertNull(Tor.proxy(5_000))
        Tor.engine = null
        assertNull(Tor.startAndWait(1_000))
        assertEquals("Tor is not in this build", Tor.start())
    }
}
