package com.fire17.linuxdesk.storage

import java.security.MessageDigest
import java.util.Random

/**
 * The delete gate's rules, as pure Kotlin (no Android, no org.json), so they are unit-tested on the JVM.
 * Proposals.kt holds the state on disk and calls these for every decision.
 *
 *   pending --select (in-app Continue, or the genie's preselect)--> selected  (frozen list + hash + phone nonce)
 *   selected --fs.execute (nonce + hash match)--> awaiting_confirm   (NOTHING deleted; the review sheet opens)
 *   awaiting_confirm --Approve tap on the phone--> executing --> done
 *   awaiting_confirm --Cancel / back--> selected (result=dialog_cancelled) · --Make changes--> pending
 *   executing --system dialog cancelled--> selected · pending|selected|awaiting_confirm --cancel--> cancelled
 *
 * There is no transition from `selected` to `executing`: a remote command can at most reach
 * `awaiting_confirm`. Only the review sheet's Approve moves a proposal on from there.
 */
object DeleteGate {
    const val MAX_PATHS = 200
    const val NONCE_LEN = 8
    const val NONCE_ABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    const val PENDING = "pending"
    const val SELECTED = "selected"
    const val AWAITING = "awaiting_confirm"
    const val EXECUTING = "executing"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    const val DISMISSED = "dismissed"

    /** States that still need the person (or are mid-way): one of these is "the active proposal". */
    val LIVE = setOf(PENDING, SELECTED, AWAITING, EXECUTING)

    enum class Ev {
        SELECT, EXECUTE, APPROVE, SHEET_CANCEL, SHEET_ADJUST, DIALOG_CANCEL, COMPLETE,
        CANCEL, ADJUST, DISMISS, REPLACE, INTERRUPT,
    }

    /** The next state for [ev] in [state], or null when the event is not allowed there. */
    fun next(state: String, ev: Ev, launched: Boolean = false): String? = when (ev) {
        Ev.SELECT -> if (state == PENDING) SELECTED else null
        Ev.EXECUTE -> if (state == SELECTED) AWAITING else null
        Ev.APPROVE -> if (state == AWAITING) EXECUTING else null
        Ev.SHEET_CANCEL -> if (state == AWAITING) SELECTED else null
        Ev.SHEET_ADJUST -> if (state == AWAITING) PENDING else null
        Ev.DIALOG_CANCEL -> if (state == EXECUTING) SELECTED else null
        Ev.COMPLETE -> if (state == EXECUTING) DONE else null
        // `executing` can be cancelled only until the system dialog is on screen.
        Ev.CANCEL -> when (state) {
            PENDING, SELECTED, AWAITING -> CANCELLED
            EXECUTING -> if (!launched) CANCELLED else null
            else -> null
        }
        Ev.ADJUST -> if (state == SELECTED) PENDING else null
        Ev.DISMISS -> if (state == PENDING) DISMISSED else null
        // A newer suggestion replaces an older one that is still waiting (one list at a time).
        Ev.REPLACE -> if (state == PENDING || state == SELECTED) DISMISSED else null
        // A crash between Approve and the outcome: back to `selected`, nothing assumed.
        Ev.INTERRUPT -> if (state == EXECUTING) SELECTED else null
    }

    /** sha256 hex of "path\tbytes\n" lines sorted by path (Storage Genie v0.4, byte-identical). */
    fun hashOf(items: List<Pair<String, Long>>): String {
        val s = items.sortedBy { it.first }.joinToString("") { "${it.first}\t${it.second}\n" }
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** A fresh phone-made nonce. Pass a SecureRandom in production. */
    fun nonce(rng: Random): String = (1..NONCE_LEN).map { NONCE_ABC[rng.nextInt(NONCE_ABC.length)] }.joinToString("")

    fun constEq(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    data class Item(val path: String, val bytes: Long, val media: Boolean = false)

    class Plan(val items: List<Item>, val rejected: List<String>, val error: String?) {
        val bytes: Long get() = items.sumOf { it.bytes }
    }

    /**
     * Which of the genie's [paths] may enter a proposal. A path is accepted only when the latest scan/list
     * reported it ([seen]) and it is still a deletable file of exactly that size now ([sizeNow], null =
     * missing, a directory, or not deletable). More than [MAX_PATHS] distinct paths refuses the whole request.
     */
    fun plan(paths: List<String>, seen: (String) -> Long?, sizeNow: (String) -> Long?): Plan {
        val uniq = LinkedHashSet(paths)
        if (uniq.size > MAX_PATHS) return Plan(emptyList(), emptyList(), "too_many")
        val items = ArrayList<Item>(); val rejected = ArrayList<String>()
        for (p in uniq) {
            val was = seen(p)
            val now = if (was == null) null else sizeNow(p)
            if (was == null || now == null || now != was) rejected.add(p) else items.add(Item(p, was))
        }
        return Plan(items, rejected, if (items.isEmpty()) "nothing_valid" else null)
    }

    /**
     * May `fs.execute` move this proposal to `awaiting_confirm`? Null = yes, else the error code.
     * [selected] is the frozen list as stored; its hash is recomputed, so a tampered store is caught too.
     */
    fun checkExecute(
        state: String, mode: String, givenNonce: String, givenHash: String,
        storedNonce: String, storedHash: String, selected: List<Pair<String, Long>>,
    ): String? {
        if (next(state, Ev.EXECUTE) == null) return "not_selected"
        if (mode != "trash" && mode != "delete") return "bad_mode"
        if (storedNonce.isEmpty() || givenNonce.isEmpty() || !constEq(storedNonce, givenNonce)) return "nonce_mismatch"
        val h = hashOf(selected)
        if (selected.isEmpty() || h != storedHash || !constEq(givenHash, h)) return "hash_mismatch"
        return null
    }

    class Recheck(
        val media: List<Item>, val direct: List<Item>,
        val failed: List<Pair<Item, String>>, val skipped: List<Pair<Item, String>>,
    ) {
        val acting: List<Item> get() = media + direct
    }

    /**
     * Right before acting: every frozen item is checked again. Missing or resized files are never touched.
     * In trash mode, non-media cannot go to the system Trash, so they are skipped (never deleted instead).
     */
    fun recheck(selected: List<Item>, mode: String, sizeNow: (String) -> Long?): Recheck {
        val media = ArrayList<Item>(); val direct = ArrayList<Item>()
        val failed = ArrayList<Pair<Item, String>>(); val skipped = ArrayList<Pair<Item, String>>()
        for (it in selected) {
            val now = sizeNow(it.path)
            when {
                now == null -> failed.add(it to "missing")
                now != it.bytes -> failed.add(it to "changed")
                it.media -> media.add(it)
                mode == "trash" -> skipped.add(it to "non_media")
                else -> direct.add(it)
            }
        }
        return Recheck(media, direct, failed, skipped)
    }
}
