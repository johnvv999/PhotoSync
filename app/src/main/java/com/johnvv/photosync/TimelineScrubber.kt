package com.johnvv.photosync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A year, near enough for scrubbing: what one screen of finger travel covers. */
private const val YEAR_MS = 31_556_952_000L

/**
 * The browse list's date scrubber: a bar down the right edge saying where in the
 * collection's timeline the list is sitting, and that spot's date in the middle
 * of the screen.
 *
 * Both are drawn only while a finger is down. A collection is looked at far more
 * than it is navigated, so neither sits over the photos when nobody is moving.
 *
 * Dragging the bar moves through time at one fixed rate: a finger travelling the
 * height of the screen covers twelve months, whatever the collection spans. That
 * makes the gesture mean the same thing in a two-year folder as in a ten-year
 * one, at the cost of the thumb no longer keeping pace with the finger — it
 * reports where you have arrived rather than being a handle you carry. A long
 * collection therefore takes several drags to cross, which is the point: one
 * screen of travel should not skip a decade.
 *
 * Drawn as an overlay above the list rather than by the list itself, so the
 * photos scroll underneath it untouched.
 */
class TimelineScrubber @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(value: Float) = value * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33000000") }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E61A73E8") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    // Dates are read in UTC, matching how the list divides its days, so the
    // overlay never names a different day than the heading above the photo.
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var list: RecyclerView? = null

    /** Adapter positions of the photo rows, ascending. */
    private var photoPositions = IntArray(0)

    /** Where each of those photos is scrolled to — its heading, when it has one. */
    private var scrollPositions = IntArray(0)

    /** Each photo's capture time, rising alongside [photoPositions]. */
    private var photoTimes = LongArray(0)

    private var scrubbing = false
    private var anchorY = 0f

    /** The moment the drag is currently pointing at, which need not be a photo. */
    private var scrubTimeMs = 0L

    /** The photo whose date is on screen, as an index into [photoTimes]. */
    private var shownIndex = 0

    /** Watches the list for the two things that reveal and dismiss the scrubber. */
    private val touchWatcher = object : RecyclerView.OnItemTouchListener {
        // Never intercepts: this only needs to know when the finger lifts, and
        // the list still needs every one of those events itself.
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) hide()
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
    }

    private val scrollWatcher = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            // Shown on a drag rather than on a touch, so tapping a photo's Info
            // link doesn't flash a scrollbar over the list.
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) show()
            // A fling only ever starts once the finger has gone.
            if (newState == RecyclerView.SCROLL_STATE_SETTLING && !scrubbing) hide()
        }

        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (!scrubbing) shownIndex = indexAtOrAfter(firstVisiblePosition())
            invalidate()
        }
    }

    init {
        alpha = 0f
    }

    /** Points the scrubber at [list], whose rows are [items]. */
    fun attach(list: RecyclerView, items: List<SyncedListItem>) {
        this.list?.let {
            it.removeOnItemTouchListener(touchWatcher)
            it.removeOnScrollListener(scrollWatcher)
        }
        this.list = list

        val positions = mutableListOf<Int>()
        val scrolls = mutableListOf<Int>()
        val times = mutableListOf<Long>()
        items.forEachIndexed { index, item ->
            if (item !is SyncedListItem.Photo) return@forEachIndexed
            positions += index
            // Landing on the heading rather than on the photo, so an arrival
            // says where it has arrived.
            scrolls += if (index > 0 && items[index - 1] is SyncedListItem.Header) index - 1 else index
            // Grouping a day's photos by place can leave one slightly out of
            // time order; the searches below need a rising sequence, and holding
            // such a photo to its predecessor's time moves it by under a day.
            times += maxOf(item.photo.chronoTimeMs, times.lastOrNull() ?: Long.MIN_VALUE)
        }
        photoPositions = positions.toIntArray()
        scrollPositions = scrolls.toIntArray()
        photoTimes = times.toLongArray()
        shownIndex = 0

        list.addOnItemTouchListener(touchWatcher)
        list.addOnScrollListener(scrollWatcher)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Nothing to scrub through until there are two dates to sit between.
        if (photoTimes.size < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Everything left of the bar belongs to the photos underneath.
                if (event.x < width - dp(40f)) return false
                scrubbing = true
                anchorY = event.y
                shownIndex = indexAtOrAfter(firstVisiblePosition())
                scrubTimeMs = photoTimes[shownIndex]
                parent?.requestDisallowInterceptTouchEvent(true)
                show()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                scrubBy(event.y - anchorY)
                anchorY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false
                hide()
                return true
            }
        }
        return false
    }

    /** Moves [dy] pixels of finger travel through the timeline, and the list with it. */
    private fun scrubBy(dy: Float) {
        if (height == 0) return
        // Re-anchored on every move, so running off either end of the collection
        // leaves no wound-up slack to unwind before the list turns around.
        scrubTimeMs = (scrubTimeMs + (dy * YEAR_MS / height).toLong())
            .coerceIn(photoTimes.first(), photoTimes.last())

        shownIndex = nearestTimeIndex(scrubTimeMs)
        (list?.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(scrollPositions[shownIndex], 0)
        invalidate()
    }

    private fun show() {
        if (photoTimes.size < 2) return
        animate().alpha(1f).setDuration(90).start()
    }

    private fun hide() {
        animate().alpha(0f).setDuration(160).start()
    }

    private fun firstVisiblePosition(): Int {
        val manager = list?.layoutManager as? LinearLayoutManager ?: return 0
        val first = manager.findFirstVisibleItemPosition()
        return if (first == RecyclerView.NO_POSITION) 0 else first
    }

    /** The photo row at or after adapter position [position], as an index into [photoTimes]. */
    private fun indexAtOrAfter(position: Int): Int {
        var low = 0
        var high = photoPositions.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (photoPositions[mid] < position) low = mid + 1 else high = mid
        }
        return low.coerceIn(0, photoPositions.size - 1)
    }

    /** The photo taken closest to [timeMs], as an index into [photoTimes]. */
    private fun nearestTimeIndex(timeMs: Long): Int {
        var low = 0
        var high = photoTimes.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (photoTimes[mid] < timeMs) low = mid + 1 else high = mid
        }
        if (low > 0 && timeMs - photoTimes[low - 1] < photoTimes[low] - timeMs) return low - 1
        return low
    }

    override fun onDraw(canvas: Canvas) {
        if (photoTimes.size < 2 || alpha == 0f) return

        val centreX = width - dp(14f)
        val top = dp(10f)
        val bottom = height - dp(10f)
        val thumbHeight = dp(48f)

        rect.set(centreX - dp(1.5f), top, centreX + dp(1.5f), bottom)
        canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), trackPaint)

        // Placed along the collection's dates rather than along the list: a year
        // of holidays and a year of nothing then take up the same room, so the
        // bar answers "when" rather than "how far down".
        val span = (photoTimes.last() - photoTimes.first()).toFloat()
        val elapsed = (photoTimes[shownIndex] - photoTimes.first()).toFloat()
        val travel = bottom - top - thumbHeight
        val thumbTop = top + travel * (elapsed / span).coerceIn(0f, 1f)

        rect.set(centreX - dp(4f), thumbTop, centreX + dp(4f), thumbTop + thumbHeight)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), thumbPaint)

        // The date of the photo actually reached, not of the moment the finger
        // points at — dragging across a gap in the collection should show the
        // list standing still, because it is.
        val text = dateFormat.format(photoTimes[shownIndex])
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val centreY = height / 2f
        val halfText = (metrics.descent - metrics.ascent) / 2f
        rect.set(
            (width - textWidth) / 2f - dp(22f),
            centreY - halfText - dp(14f),
            (width + textWidth) / 2f + dp(22f),
            centreY + halfText + dp(14f)
        )
        canvas.drawRoundRect(rect, dp(14f), dp(14f), labelPaint)
        canvas.drawText(text, width / 2f, centreY - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }
}
