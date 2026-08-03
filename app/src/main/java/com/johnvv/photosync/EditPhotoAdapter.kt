package com.johnvv.photosync

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Edit screen's photo list: the same chronological, city-headed layout as
 * Browse Synced Photos (thumbnail plus a Gemini "Info" link), with each photo's
 * location shown in an editable field so a wrong or inherited place name can be
 * corrected by hand.
 */
class EditPhotoAdapter(
    private val context: Context,
    private var items: List<SyncedListItem>,
    private val drive: DriveServiceHelper,
    private val scope: CoroutineScope,
    private val accountName: String,
    /** Ticked photos, shared with the fragment so Delete acts on the same set. */
    private val selection: MutableSet<String>,
    private val onSelectionChanged: () -> Unit,
    /** Invoked with the photo and the typed "Country, City" when Save is tapped. */
    private val onLocationEdited: (DrivePhoto, String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** When true every photo shows a tick box and tapping one selects rather than opens it. */
    var selectionMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_PHOTO = 1
    }

    private val expandedIds = mutableSetOf<String>()

    class HeaderViewHolder(val titleView: TextView) : RecyclerView.ViewHolder(titleView)

    class PhotoViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val thumbnail: ImageView = root.findViewById(R.id.thumbnail)
        val selectCheck: CheckBox = root.findViewById(R.id.selectCheck)
        val fileNameText: TextView = root.findViewById(R.id.fileNameText)
        val locationInput: EditText = root.findViewById(R.id.locationInput)
        val saveLocationButton: Button = root.findViewById(R.id.saveLocationButton)
        val infoLink: TextView = root.findViewById(R.id.infoLink)
        val infoResult: TextView = root.findViewById(R.id.infoResult)
        var thumbnailJob: Job? = null
        var infoJob: Job? = null
    }

    /** Swaps in a rebuilt list, e.g. after a rename moved a photo under a different city header. */
    fun submit(newItems: List<SyncedListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is SyncedListItem.Header -> VIEW_TYPE_HEADER
        is SyncedListItem.Photo -> VIEW_TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_HEADER) {
            val titleView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_city_header, parent, false) as TextView
            HeaderViewHolder(titleView)
        } else {
            PhotoViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_edit_photo, parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SyncedListItem.Header -> (holder as HeaderViewHolder).titleView.text = item.cityLabel
            is SyncedListItem.Photo -> bindPhoto(holder as PhotoViewHolder, item.photo)
        }
    }

    private fun bindPhoto(holder: PhotoViewHolder, photo: DrivePhoto) {
        holder.thumbnailJob?.cancel()
        holder.infoJob?.cancel()

        holder.fileNameText.text = photo.name
        // Left blank for a photo the uploader couldn't place, so the "City,
        // Country" hint shows instead — prefilling it with a placeholder would
        // put text in the box that must not be saved back as a location.
        holder.locationInput.setText(realPlaceLabel(photo).orEmpty())

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

        holder.saveLocationButton.setOnClickListener {
            onLocationEdited(photo, holder.locationInput.text.toString())
        }

        bindSelection(holder, photo)
        bindInfoLink(holder, photo)
    }

    /**
     * Wires the tick box and decides what tapping the photo does. In selection
     * mode the whole thumbnail toggles the box — a checkbox in the corner of a
     * photo is a small target, and tapping through to fullscreen when you meant
     * to select is a poor surprise.
     */
    private fun bindSelection(holder: PhotoViewHolder, photo: DrivePhoto) {
        holder.selectCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE

        // Detached before setting the state, so recycling a row into a different
        // photo can't fire a change that edits the wrong selection.
        holder.selectCheck.setOnCheckedChangeListener(null)
        holder.selectCheck.isChecked = photo.fileId in selection
        holder.selectCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selection += photo.fileId else selection -= photo.fileId
            onSelectionChanged()
        }

        holder.thumbnail.setOnClickListener {
            if (selectionMode) {
                holder.selectCheck.isChecked = !holder.selectCheck.isChecked
            } else {
                FullScreenPhotoActivity.start(context, photo.fileId, accountName)
            }
        }
    }

    private fun bindInfoLink(holder: PhotoViewHolder, photo: DrivePhoto) {
        val cachedInfo = DrivePhotoCache.description(photo.fileId)
        holder.infoResult.text = cachedInfo.orEmpty()
        holder.infoResult.visibility =
            if (cachedInfo != null && photo.fileId in expandedIds) View.VISIBLE else View.GONE

        holder.infoLink.setOnClickListener {
            if (DrivePhotoCache.description(photo.fileId) != null) {
                // Already fetched — the link just folds the text away and back.
                if (photo.fileId in expandedIds) expandedIds -= photo.fileId else expandedIds += photo.fileId
                holder.infoResult.visibility =
                    if (photo.fileId in expandedIds) View.VISIBLE else View.GONE
                return@setOnClickListener
            }

            expandedIds += photo.fileId
            holder.infoResult.visibility = View.VISIBLE
            holder.infoResult.text = context.getString(R.string.loading_info)
            holder.infoJob = scope.launch {
                val description = withContext(Dispatchers.IO) { DrivePhotoInfo.describe(drive, photo) }
                    ?: context.getString(R.string.couldnt_load_photo)
                holder.infoResult.text = description
            }
        }
    }

    /**
     * The photo's location as "Country, City", matching this screen's headings —
     * and the order [LocationNaming.fromCountryFirstLabel] reads back when Save
     * is tapped. Null if its name carries no real place.
     */
    private fun realPlaceLabel(photo: DrivePhoto): String? =
        LocationNaming.countryFirstLabel(photo.name)

    override fun getItemCount() = items.size
}
