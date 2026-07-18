package com.johnvv.photosync

/** A row in the Browse Synced Photos list — either a city title or a photo. */
sealed class SyncedListItem {
    data class Header(val cityLabel: String) : SyncedListItem()
    data class Photo(val photo: DrivePhoto) : SyncedListItem()
}

/** Groups chronologically-sorted [photos] under a [SyncedListItem.Header] each time the city changes. */
fun buildSyncedListItems(photos: List<DrivePhoto>): List<SyncedListItem> {
    val items = mutableListOf<SyncedListItem>()
    var lastCity: String? = null
    for (photo in photos) {
        if (photo.cityLabel != lastCity) {
            items += SyncedListItem.Header(photo.cityLabel)
            lastCity = photo.cityLabel
        }
        items += SyncedListItem.Photo(photo)
    }
    return items
}
