package com.johnvv.photosync

/** A row in a photo list — either a place heading or a photo. */
sealed class SyncedListItem {
    data class Header(val cityLabel: String) : SyncedListItem()
    data class Photo(val photo: DrivePhoto) : SyncedListItem()
}

private const val MS_PER_DAY = 86_400_000L

/** A photo together with the heading it will appear under, which merging can change. */
private class Entry(val photo: DrivePhoto, val label: String)

/**
 * Arranges [photos] into headed groups, matching how the browsing page orders
 * the same folder — so a photo sits in the same place whether it is being
 * looked at on the web or in the app.
 *
 * Three passes:
 *  1. Strictly chronological. Places group themselves, because a day's travel
 *     is sequential; sorting by place inside the day would reverse the trip.
 *  2. One-photo runs are absorbed into the run beside them. Passing a village
 *     still earns a GPS fix, so somewhere never actually visited would
 *     otherwise get a heading of its own between two stretches of somewhere
 *     real.
 *  3. Repeat visits within a day collapse to a single heading, placed where
 *     that day's first photo of it falls — a day that goes out and comes back
 *     names each place once.
 *
 * The heading is therefore approximate for absorbed photos: one taken in a
 * village passed en route sits under the stretch it belongs to. Each row still
 * shows the photo's own filename, which says where it was actually taken.
 *
 * Days are divided in UTC, matching the page, so the two never disagree about
 * which day a photo near midnight belongs to.
 */
fun buildSyncedListItems(
    photos: List<DrivePhoto>,
    label: (DrivePhoto) -> String = { it.cityLabel }
): List<SyncedListItem> {
    val items = mutableListOf<SyncedListItem>()
    var lastHeader: String? = null

    fun add(entries: List<Entry>) {
        for (entry in entries) {
            if (entry.label != lastHeader) {
                items += SyncedListItem.Header(entry.label)
                lastHeader = entry.label
            }
            items += SyncedListItem.Photo(entry.photo)
        }
    }

    // Pass 1 — chronological, with the name breaking ties so the order is
    // stable rather than left to however Drive returned the listing.
    val ordered = photos
        .sortedWith(compareBy({ it.chronoTimeMs }, { it.name }))
        .map { Entry(it, label(it)) }

    // Consecutive photos of one place, kept per day.
    val dayRuns = LinkedHashMap<Long, MutableList<MutableList<Entry>>>()
    for (entry in ordered) {
        val day = Math.floorDiv(entry.photo.chronoTimeMs, MS_PER_DAY)
        val runs = dayRuns.getOrPut(day) { mutableListOf() }
        val current = runs.lastOrNull()
        if (current != null && current.first().label == entry.label) current += entry
        else runs += mutableListOf(entry)
    }

    // Pass 2 — absorb one-photo runs into the run before them, or the one after
    // if they open the day. Done on true adjacency, before repeat visits are
    // collapsed, so a photo joins whatever it was genuinely next to.
    for (runs in dayRuns.values) {
        var i = 0
        while (i < runs.size) {
            if (runs.size < 2 || runs[i].size != 1) {
                i++
                continue
            }
            val backwards = i > 0
            val target = if (backwards) runs[i - 1] else runs[1]
            val moved = runs[i].map { Entry(it.photo, target.first().label) }
            // Backwards the photo follows the run it joins; forwards it precedes it.
            if (backwards) target += moved else target.addAll(0, moved)
            runs.removeAt(i)
            // Deliberately not advancing: this slot now holds the following run,
            // so consecutive singles all fold into the same neighbour.
        }
    }

    // Pass 3 — one heading per place per day.
    for (runs in dayRuns.values) {
        val visits = LinkedHashMap<String, MutableList<Entry>>()
        for (run in runs) visits.getOrPut(run.first().label) { mutableListOf() } += run
        for (entries in visits.values) add(entries)
    }

    return items
}
