package com.johnvv.photosync

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A row in the redundant-photo results — either a group heading or one photo in that group. */
sealed class RedundantListItem {
    data class Header(val groupNumber: Int, val size: Int, val reason: String) : RedundantListItem()
    data class Photo(val photo: DrivePhoto, val isAiPick: Boolean, val isKeeper: Boolean) : RedundantListItem()
}

/** Flattens [DuplicateFinder.Group]s into adapter rows, pre-selecting whatever the AI flagged. */
fun buildRedundantListItems(
    groups: List<DuplicateFinder.Group>,
    selection: MutableSet<String>
): List<RedundantListItem> {
    val items = mutableListOf<RedundantListItem>()
    groups.forEachIndexed { groupIndex, group ->
        items += RedundantListItem.Header(groupIndex + 1, group.photos.size, group.reason)
        group.photos.forEachIndexed { index, photo ->
            val isAiPick = index in group.redundantIndices
            if (isAiPick) selection += photo.fileId
            items += RedundantListItem.Photo(photo, isAiPick, isKeeper = index == group.keepIndex)
        }
    }
    return items
}

/**
 * Shows the groups of near-duplicate photos with a checkbox on each. The ones
 * Gemini flagged as redundant start ticked and the one it picked to keep is
 * labelled, but every box stays editable — the model proposes, the user
 * decides what actually gets deleted.
 */
class RedundantPhotoAdapter(
    private val context: Context,
    private val items: List<RedundantListItem>,
    private val drive: DriveServiceHelper,
    private val scope: CoroutineScope,
    private val accountName: String,
    private val selection: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_PHOTO = 1

        val KEEP_GREEN = Color.parseColor("#1E8E3E")
        val REDUNDANT_RED = Color.parseColor("#C5221F")
    }

    class HeaderViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val title: TextView = root.findViewById(R.id.groupTitle)
        val reason: TextView = root.findViewById(R.id.groupReason)
    }

    class PhotoViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val check: CheckBox = root.findViewById(R.id.selectCheck)
        val thumbnail: ImageView = root.findViewById(R.id.thumbnail)
        val fileNameText: TextView = root.findViewById(R.id.fileNameText)
        val takenTimeText: TextView = root.findViewById(R.id.takenTimeText)
        val verdictText: TextView = root.findViewById(R.id.verdictText)
        var thumbnailJob: Job? = null
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is RedundantListItem.Header -> VIEW_TYPE_HEADER
        is RedundantListItem.Photo -> VIEW_TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_redundant_header, parent, false))
        } else {
            PhotoViewHolder(inflater.inflate(R.layout.item_redundant_photo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RedundantListItem.Header -> bindHeader(holder as HeaderViewHolder, item)
            is RedundantListItem.Photo -> bindPhoto(holder as PhotoViewHolder, item)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder, item: RedundantListItem.Header) {
        holder.title.text = context.getString(R.string.redundant_group_title, item.groupNumber, item.size)
        holder.reason.text = item.reason.ifBlank { context.getString(R.string.redundant_no_ai_verdict) }
    }

    private fun bindPhoto(holder: PhotoViewHolder, item: RedundantListItem.Photo) {
        val photo = item.photo
        holder.thumbnailJob?.cancel()

        holder.fileNameText.text = photo.name
        holder.takenTimeText.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(photo.chronoTimeMs))

        when {
            item.isKeeper -> {
                holder.verdictText.text = context.getString(R.string.redundant_keep_label)
                holder.verdictText.setTextColor(KEEP_GREEN)
                holder.verdictText.visibility = View.VISIBLE
            }
            item.isAiPick -> {
                holder.verdictText.text = context.getString(R.string.redundant_delete_label)
                holder.verdictText.setTextColor(REDUNDANT_RED)
                holder.verdictText.visibility = View.VISIBLE
            }
            else -> holder.verdictText.visibility = View.GONE
        }

        // Detach the listener before setting the box, so recycling a row into a
        // different photo doesn't fire a change that edits the wrong selection.
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = photo.fileId in selection
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selection += photo.fileId else selection -= photo.fileId
            onSelectionChanged()
        }

        val cachedThumb = DrivePhotoCache.thumbnail(photo.fileId)
        if (cachedThumb != null) {
            holder.thumbnail.setImageBitmap(cachedThumb)
        } else {
            holder.thumbnail.setImageDrawable(null)
            holder.thumbnailJob = scope.launch {
                val bytes = withContext(Dispatchers.IO) { DrivePhotoCache.bytes(drive, photo.fileId) }
                val bitmap = bytes?.let { withContext(Dispatchers.Default) { OrientedBitmap.decode(it) } }
                if (bitmap != null) {
                    DrivePhotoCache.putThumbnail(photo.fileId, bitmap)
                    holder.thumbnail.setImageBitmap(bitmap)
                }
            }
        }

        holder.thumbnail.setOnClickListener {
            FullScreenPhotoActivity.start(context, photo.fileId, accountName)
        }
    }

    override fun getItemCount() = items.size
}
