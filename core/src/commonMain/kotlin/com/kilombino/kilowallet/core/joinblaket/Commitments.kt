package com.kilombino.kilowallet.core.joinblaket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The PoDLE commitments this wallet already showed to makers (`taker/podle_manager.py`). A
 * commitment is burned once a maker has seen it: makers broadcast `!hp2 <commitment>` and every
 * maker then refuses it, so one is never sent twice. It is marked used BEFORE it is sent, so a
 * crash in between wastes an index instead of reusing one.
 */
interface CommitmentStore {
    fun isUsed(commitmentHex: String): Boolean
    /** Record [commitmentHex] as used, durably, before returning. */
    fun markUsed(commitmentHex: String)
}

/**
 * [CommitmentStore] in Python's `cmtdata/commitments.json` format, `{"used": [hex…], "external": {}}`,
 * the same text Kilowallet for Android keeps (and puts in the .kab as `joinblaket_commitments`).
 * Where the text lives is the platform's: [load] gives it (null when nothing was used yet) and
 * [save] must keep it durably before returning (the app: its settings, written to disk at once).
 */
class JsonCommitmentStore(private val load: () -> String?, private val save: (String) -> Unit) : CommitmentStore {
    private fun read(): MutableSet<String> {
        val text = load() ?: return LinkedHashSet()
        val a = runCatching { JbJson.obj(text)["used"] as? JsonArray }.getOrNull() ?: return LinkedHashSet()
        return a.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.content?.lowercase() }
    }

    override fun isUsed(commitmentHex: String): Boolean = commitmentHex.lowercase() in read()

    override fun markUsed(commitmentHex: String) {
        val used = read()
        if (!used.add(commitmentHex.lowercase())) return
        save(format(used))
    }

    /** The JSON for a backup, or null when nothing was used yet. */
    fun export(): String? = load()

    /**
     * Adds the used commitments of a backup ([json], the same format) to these: a restore never
     * forgets one already used here, and never brings back an index the backup had burned.
     */
    fun mergeFrom(json: String) {
        val a = JbJson.obj(json)["used"] as? JsonArray ?: return
        val used = read()
        var changed = false
        for (e in a) { val h = e.jsonPrimitive.content.lowercase(); if (used.add(h)) changed = true }
        if (changed) save(format(used))
    }

    /** How many commitments were used: for tests and the backup summary. */
    fun count(): Int = read().size

    companion object {
        /** `{"used":[…],"external":{}}`, as Android's org.json writes it. */
        fun format(used: Collection<String>): String =
            JsonObject(mapOf("used" to JsonArray(used.map { JsonPrimitive(it) }), "external" to JsonObject(emptyMap()))).toString()
    }
}
