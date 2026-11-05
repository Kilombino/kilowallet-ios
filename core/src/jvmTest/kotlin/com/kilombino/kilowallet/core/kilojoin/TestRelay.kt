package com.kilombino.kilowallet.core.kilojoin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A nostr relay in the test's own process (the Android app's `TestRelay`, 1.5.2), handed to the sessions as
 * their [WebSocketFactory]: NIP-01 EVENT/REQ/CLOSE with the filters Kilojoin uses (ids, kinds, authors, #p,
 * #d, #t, since, until, limit), parameterised replaceable events (30000–39999), OK and EOSE. Nothing leaves
 * this process, so no test pool can ever be seen by a real wallet.
 *
 * And the faults a real relay and network can have, each switched on by the test:
 *  - [lose]: an event is answered OK but neither stored nor delivered (a lost message, a lost acknowledgment);
 *  - [noOk]: an event is stored and delivered but its OK never comes back (the sender can't tell);
 *  - [duplicate]: every event is delivered twice to every live subscription;
 *  - [shuffle]: stored events are replayed in random order (not newest first);
 *  - [forget]: stored events matching a predicate are dropped from history (lost or pruned history);
 *  - [hold]: an event is answered OK but stored and delivered only at [release] (a message that comes late).
 */
class TestRelay : WebSocketFactory {
    /** Stored events, in arrival order. */
    val stored = CopyOnWriteArrayList<NostrEvent>()
    /** Every event ever published, lost ones included (what each wallet sent). */
    val published = CopyOnWriteArrayList<NostrEvent>()
    private val conns = CopyOnWriteArrayList<Conn>()

    @Volatile var lose: (NostrEvent) -> Boolean = { false }
    @Volatile var noOk: (NostrEvent) -> Boolean = { false }
    @Volatile var duplicate = false
    @Volatile var shuffle = false
    @Volatile var hold: (NostrEvent) -> Boolean = { false }
    private val held = CopyOnWriteArrayList<NostrEvent>()

    /** Stores and delivers the events [hold] kept back, in the order they came; returns how many. */
    @Synchronized fun release(): Int {
        val l = held.toList(); held.removeAll(l.toSet())
        for (ev in l) { store(ev); for (c in conns) c.deliver(ev) }
        return l.size
    }

    /** Drops stored events matching [pred] from history; returns how many. */
    fun forget(pred: (NostrEvent) -> Boolean): Int { val l = stored.filter(pred); stored.removeAll(l.toSet()); return l.size }

    /** Closes every client connection (as a network drop would): the clients reconnect and replay. */
    fun dropConnections() { conns.toList().forEach { it.drop() } }

    fun close() = dropConnections()

    inner class Conn(val l: WebSocketListener) : WebSocket {
        val subs = ConcurrentHashMap<String, List<JsonObject>>()
        @Volatile var open = true
        override fun send(text: String): Boolean { if (!open) return false; handle(this, text); return true }
        override fun ping(): Boolean { if (!open) return false; l.onPong(); return true }
        override fun close() { open = false; conns.remove(this) }
        fun drop() { if (!open) return; open = false; conns.remove(this); l.onClosed("dropped") }
        fun deliver(ev: NostrEvent) {
            for ((id, filters) in subs) if (filters.any { matches(it, ev) }) {
                out(id, ev)
                if (duplicate) out(id, ev)
            }
        }
        fun out(id: String, ev: NostrEvent) { if (open) l.onText(JsonArray(listOf(JsonPrimitive("EVENT"), JsonPrimitive(id), ev.toJson())).toString()) }
    }

    override fun open(url: String, listener: WebSocketListener): WebSocket = Conn(listener).also { conns.add(it) }

    @Synchronized private fun handle(c: Conn, text: String) {
        val a = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
        when (a[0].jsonPrimitive.content) {
            "EVENT" -> {
                val ev = NostrEvent.parse(a[1].jsonObject)
                published.add(ev)
                if (!ev.verify()) { c.l.onText("[\"OK\",\"${ev.id}\",false,\"invalid: bad signature\"]"); return }
                val lost = lose(ev)
                val late = !lost && hold(ev)
                if (late) held.add(ev)
                if (!lost && !late) store(ev)
                if (!noOk(ev)) c.l.onText("[\"OK\",\"${ev.id}\",true,\"\"]")
                if (!lost && !late) for (o in conns) o.deliver(ev)
            }
            "REQ" -> {
                val id = a[1].jsonPrimitive.content
                val filters = a.drop(2).map { it.jsonObject }
                c.subs[id] = filters
                val matched = filters.flatMap { f ->
                    val m = stored.filter { matches(f, it) }.sortedByDescending { it.createdAt }
                    val limit = (f["limit"] as? JsonPrimitive)?.long?.toInt()
                    if (limit != null) m.take(limit) else m
                }.distinctBy { it.id }
                val order = if (shuffle) matched.shuffled() else matched
                for (ev in order) c.out(id, ev)
                c.l.onText("[\"EOSE\",\"$id\"]")
            }
            "CLOSE" -> c.subs.remove(a[1].jsonPrimitive.content)
        }
    }

    private fun store(ev: NostrEvent) {
        if (ev.kind in 30000..39999) {
            val d = ev.tag("d") ?: ""
            val older = stored.filter { it.kind == ev.kind && it.pubkey == ev.pubkey && (it.tag("d") ?: "") == d }
            if (older.any { it.createdAt > ev.createdAt }) return
            stored.removeAll(older.toSet())
        }
        if (stored.none { it.id == ev.id }) stored.add(ev)
    }

    companion object {
        fun matches(f: JsonObject, ev: NostrEvent): Boolean {
            fun list(k: String) = (f[k] as? JsonArray)?.map { it.jsonPrimitive.content }
            list("ids")?.let { if (ev.id !in it) return false }
            list("kinds")?.let { if (ev.kind.toString() !in it) return false }
            list("authors")?.let { if (ev.pubkey !in it) return false }
            for (k in f.keys) if (k.startsWith("#")) {
                val want = list(k) ?: continue
                val name = k.substring(1)
                if (ev.tags.none { it.size >= 2 && it[0] == name && it[1] in want }) return false
            }
            (f["since"] as? JsonPrimitive)?.long?.let { if (ev.createdAt < it) return false }
            (f["until"] as? JsonPrimitive)?.long?.let { if (ev.createdAt > it) return false }
            return true
        }
    }
}
