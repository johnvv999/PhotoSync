package com.johnvv.photosync

/** A row in the Browse Synced Photos list — either a city title or a photo. */
sealed class SyncedListItem {
    data class Header(val cityLabel: String) : SyncedListItem()
    data class Photo(val photo: DrivePhoto) : SyncedListItem()
}

/**
 * Groups [photos] under a [SyncedListItem.Header] each time the label changes.
 *
 * [label] defaults to the browse screen's "City, Country"; the Edit screen
 * passes a country-first one so its headings match how it orders the list.
 */
fun buildSyncedListItems(
    photos: List<DrivePhoto>,
    label: (DrivePhoto) -> String = { it.cityLabel }
): List<SyncedListItem> {
    val items = mutableListOf<SyncedListItem>()
    var lastLabel: String? = null
    for (photo in photos) {
        val current = label(photo)
        if (current != lastLabel) {
            items += SyncedListItem.Header(current)
            lastLabel = current
        }
        items += SyncedListItem.Photo(photo)
    }
    return items
}
