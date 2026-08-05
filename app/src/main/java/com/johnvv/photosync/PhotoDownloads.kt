package com.johnvv.photosync

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Limits how many photos are being pulled into memory at once.
 *
 * A list row starts a download when it binds, and scrolling binds rows far
 * faster than Drive answers. Cancelling the row's job doesn't help: the
 * download is a blocking read, so a cancelled one carries on to the end still
 * holding its buffer, and the reads that matter are the ones already running.
 * On a folder-sized list that let dozens run at once — each a multi-megabyte
 * file, each doubling its buffer as it grows — and the app ran out of heap
 * mid-download rather than mid-decode.
 *
 * Waiting for a permit is a suspension point, so a row scrolled past before its
 * turn comes is cancelled here and never downloads at all. That is the larger
 * half of the saving: most rows in a fast scroll are never looked at.
 */
object PhotoDownloads {

    /**
     * Three at a time. Enough to keep a scroll supplied — a row's photo
     * generally arrives before it settles — while capping what a burst can
     * hold to a few tens of megabytes.
     */
    private val slots = Semaphore(3)

    suspend fun <T> withSlot(block: suspend () -> T): T = slots.withPermit { block() }
}
