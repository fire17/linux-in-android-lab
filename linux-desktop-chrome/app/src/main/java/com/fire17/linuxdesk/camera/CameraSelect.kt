package com.fire17.linuxdesk.camera

/**
 * Cameras v4: GENERIC camera selection — plain rules over the inventory data, no per-phone guesses.
 * Pure Kotlin (no Android types), so CameraSelectTest runs it on synthetic devices.
 *
 * Input: every camera id the phone reports ([Lens]: top-level ids from CameraManager.cameraIdList, plus the physical
 * lenses of logical multi-cameras), each with facing, focal lengths and whether it can stream (has a YUV size).
 *
 * Rules (Tami 21:41: "generic code that will be able to handle all of them"):
 *  1. Exposed = every streamable top-level id, plus every streamable physical lens that is not ALSO a top-level id
 *     (then it is listed once, as the top-level one). Each id appears at most once.
 *  2. Order: back first, then external, then unknown, front LAST. Within a facing: the "standard" lens first, then
 *     by focal length ascending (ultra-wide → tele), then by id.
 *  3. Default = the back LOGICAL camera if there is one (it zooms across its lenses and is what phone camera apps
 *     open), choosing among several by the standard focal; else the back lens with the standard focal; else the
 *     first exposed camera (e.g. a front-only device → its front camera).
 *  4. "Standard" focal of a group = its LOWER MEDIAN known focal (sorted, index (n-1)/2). That is the main/wide lens
 *     on any phone: ultra-wide < main < tele(s), so with 3 lenses it is the middle one, with 4 (ultra, main, 3x, 10x)
 *     still the main, with 2 the shorter, with 1 that one. Exact and deterministic (no float "closest" ties).
 *     Unknown focal never wins over a known one.
 */
object CameraSelect {

    enum class Facing { BACK, FRONT, EXTERNAL, UNKNOWN }

    data class Lens(
        val id: String,
        val facing: Facing,
        /** First (or only) focal length in mm; null = unknown. */
        val focalMm: Float?,
        /** Logical parent this physical lens is opened through; null = a top-level camera id. */
        val physicalOf: String? = null,
        /** The physical lenses inside this logical camera (empty = not logical). */
        val physicalIds: List<String> = emptyList(),
        /** Has a YUV_420_888 output size (CameraServer can stream it). */
        val streamable: Boolean = true,
    ) {
        val logical: Boolean get() = physicalIds.isNotEmpty()
    }

    data class Choice(val ordered: List<Lens>, val default: Lens?, val hidden: Map<String, String>)

    private fun facingRank(f: Facing) = when (f) { Facing.BACK -> 0; Facing.EXTERNAL -> 1; Facing.UNKNOWN -> 2; Facing.FRONT -> 3 }

    /** The lower-median known focal (see rule 4). Null if none known. */
    fun standardFocal(focals: List<Float?>): Float? {
        val known = focals.filterNotNull().sorted()
        return if (known.isEmpty()) null else known[(known.size - 1) / 2]
    }

    fun choose(all: List<Lens>): Choice {
        val topIds = all.filter { it.physicalOf == null }.map { it.id }.toSet()
        val hidden = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        val exposed = ArrayList<Lens>()
        for (l in all) {
            val why = when {
                !l.streamable -> "no YUV output"
                l.physicalOf != null && l.id in topIds -> "also a top-level camera (listed there)"
                l.id in seen -> "duplicate id"
                else -> null
            }
            if (why != null) { hidden.putIfAbsent(l.id + if (l.physicalOf != null) "@" + l.physicalOf else "", why); continue }
            seen += l.id
            exposed += l
        }
        // Per-facing standard focal (over the lenses of that facing that we expose).
        val std = exposed.groupBy { it.facing }.mapValues { (_, ls) -> standardFocal(ls.map { it.focalMm }) }
        fun isStd(l: Lens) = l.focalMm != null && l.focalMm == std[l.facing]
        val ordered = exposed.sortedWith(compareBy<Lens>(
            { facingRank(it.facing) },
            { if (isStd(it)) 0 else 1 },
            { it.focalMm ?: Float.MAX_VALUE },
            { it.id.toIntOrNull() ?: Int.MAX_VALUE },
            { it.id },
        ))
        val back = ordered.filter { it.facing == Facing.BACK }
        val backLogical = back.filter { it.logical }
        val default = when {
            backLogical.size == 1 -> backLogical[0]
            backLogical.size > 1 -> {
                val s = standardFocal(backLogical.map { it.focalMm })
                backLogical.firstOrNull { it.focalMm != null && it.focalMm == s } ?: backLogical[0]
            }
            back.isNotEmpty() -> back.firstOrNull { isStd(it) } ?: back[0]
            else -> ordered.firstOrNull()
        }
        // The default always goes first, so "first camera" consumers (v1 path, gUM without a deviceId) get it.
        val withDefaultFirst = if (default == null) ordered else listOf(default) + ordered.filter { it !== default }
        return Choice(withDefaultFirst, default, hidden)
    }
}
