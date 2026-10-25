package com.korgnote.common.sync

/** The outcome of merging two edits of one file. */
sealed interface MergeResult {
    /** Both sides' changes fit together; [text] contains all of them. */
    data class Merged(val text: String) : MergeResult

    /** The same lines were changed differently. Neither version is chosen here. */
    data class Conflicted(val regions: Int) : MergeResult
}

/**
 * A line-based three-way merge (diff3) of [local] and [remote] over their common ancestor [base].
 *
 * This is what makes two devices editing one file converge without asking the user: edits to Org
 * files usually land under different headings, and those are different lines, so they merge cleanly.
 *
 * One deliberate departure from strict diff3: when both sides only *added* lines at the same point,
 * both additions are kept rather than declared a conflict. See the note at that branch.
 *
 * It deliberately never emits conflict markers. A file full of `<<<<<<<` is a corrupted note, and
 * the caller's answer to a real conflict is to keep both versions as separate files instead — see
 * [conflictCopyName] and `docs/05-sync-protocol.md`.
 */
fun merge(base: String, local: String, remote: String): MergeResult {
    if (local == remote) return MergeResult.Merged(local)
    if (local == base) return MergeResult.Merged(remote)
    if (remote == base) return MergeResult.Merged(local)

    val b = base.lineSequence().toList()
    val l = local.lineSequence().toList()
    val r = remote.lineSequence().toList()

    val toLocal = alignment(b, l)
    val toRemote = alignment(b, r)

    val out = mutableListOf<String>()
    var conflicts = 0
    var bi = 0
    var li = 0
    var ri = 0

    while (bi < b.size || li < l.size || ri < r.size) {
        // A line both sides left alone, in the position both sides left it: copy it through.
        if (bi < b.size && toLocal[bi] == li && toRemote[bi] == ri) {
            out.add(b[bi])
            bi++; li++; ri++
            continue
        }

        // Otherwise gather the region up to the next such anchor and decide it as a whole.
        var nextB = bi
        while (nextB < b.size) {
            val al = toLocal[nextB]
            val ar = toRemote[nextB]
            if (al != null && ar != null && al >= li && ar >= ri) break
            nextB++
        }
        val nextL = if (nextB < b.size) toLocal[nextB]!! else l.size
        val nextR = if (nextB < b.size) toRemote[nextB]!! else r.size

        val baseRegion = b.subList(bi, nextB)
        val localRegion = l.subList(li, nextL)
        val remoteRegion = r.subList(ri, nextR)

        when {
            localRegion == remoteRegion -> out.addAll(localRegion)
            localRegion == baseRegion -> out.addAll(remoteRegion)
            remoteRegion == baseRegion -> out.addAll(localRegion)

            // Both sides *inserted* at the same point without touching anything that was there.
            // Strict diff3 calls this a conflict, and for source code it is right to: the order may
            // matter. For a list of Org headings it does not, and this is the commonest case there
            // is — capturing a TODO on the phone and another on the laptop. Keeping both, local
            // first, loses nothing; declaring a conflict would litter the notes with copies.
            baseRegion.isEmpty() -> {
                out.addAll(localRegion)
                out.addAll(remoteRegion)
            }

            else -> conflicts++
        }

        bi = nextB; li = nextL; ri = nextR
    }

    return if (conflicts > 0) MergeResult.Conflicted(conflicts) else MergeResult.Merged(out.joinToString("\n"))
}

/**
 * For each line of [from], the index of the line it corresponds to in [to], or null when the line
 * was changed or removed. Computed as a longest common subsequence.
 */
internal fun alignment(from: List<String>, to: List<String>): Array<Int?> {
    val result = arrayOfNulls<Int>(from.size)
    if (from.isEmpty() || to.isEmpty()) return result

    // Classic LCS table. Notes are thousands of lines at most, so the quadratic table is fine.
    val lengths = Array(from.size + 1) { IntArray(to.size + 1) }
    for (i in from.indices.reversed()) {
        for (j in to.indices.reversed()) {
            lengths[i][j] = if (from[i] == to[j]) {
                lengths[i + 1][j + 1] + 1
            } else {
                maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
    }

    var i = 0
    var j = 0
    while (i < from.size && j < to.size) {
        when {
            from[i] == to[j] -> { result[i] = j; i++; j++ }
            lengths[i + 1][j] >= lengths[i][j + 1] -> i++
            else -> j++
        }
    }
    return result
}
