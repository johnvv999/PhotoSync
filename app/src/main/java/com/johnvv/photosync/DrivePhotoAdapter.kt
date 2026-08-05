package com.johnvv.photosync

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chronological list of photos actually in the Drive folder, with a city title
 * row whenever the city changes, and an "Info" link beneath each photo that
 * fetches a Gemini description.
 */
class DrivePhotoAdapter(
    private val context: Context,
    private val items: List<SyncedListItem>,
    private val drive: DriveServiceHelper,
    private val scope: CoroutineScope,
    private val accountName: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_PHOTO = 1

        val LINK_BLUE = Color.parseColor("#1A73E8")
        val LINK_GREY = Color.parseColor("#6B6B70")
    }

    private val expandedIds = mutableSetOf<String>()

    class HeaderViewHolder(val titleView: TextView) : RecyclerView.ViewHolder(titleView)

    class PhotoViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val thumbnail: ImageView = root.findViewById(R.id.thumbnail)
        val fileNameText: TextView = root.findViewById(R.id.fileNameText)
        val infoLink: TextView = root.findViewById(R.id.infoLink)
        val mapLink: TextView = root.findViewById(R.id.mapLink)
        val infoResult: TextView = root.findViewById(R.id.infoResult)
        var thumbnailJob: Job? = null
        var infoJob: Job? = null
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
            val root = LayoutInflater.from(parent.context).inflate(R.layout.item_synced_photo, parent, false)
            PhotoViewHolder(root)
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

        // Without the extension: every photo here is a .jpg, so it is four
        // characters of noise competing for the space between the two links.
        holder.fileNameText.text = photo.name.substringBeforeLast('.')

        val cachedThumb = DrivePhotoCache.thumbnail(photo.fileId)
        if (cachedThumb != null) {
            holder.thumbnail.setImageBitmap(cachedThumb)
        } else {
            holder.thumbnail.setImageDrawable(null)
            holder.thumbnailJob = scope.launch {
                val bytes = PhotoDownloads.withSlot {
                    withContext(Dispatchers.IO) { DrivePhotoCache.bytes(drive, photo.fileId) }
                }
                // Sampled to row size rather than decoded whole: tapping through
                // to fullscreen re-decodes at full resolution, and that is the
                // only place the extra pixels are ever seen.
                val bitmap = bytes?.let {
                    withContext(Dispatchers.Default) {
                        OrientedBitmap.decodeSampled(it, OrientedBitmap.LIST_THUMBNAIL_PX)
                    }
                }
                if (bitmap != null) {
                    DrivePhotoCache.putThumbnail(photo.fileId, bitmap)
                    holder.thumbnail.setImageBitmap(bitmap)
                }
            }
        }

        val cachedInfo = DrivePhotoCache.description(photo.fileId)
        holder.infoResult.text = cachedInfo.orEmpty()
        holder.infoResult.visibility = if (cachedInfo != null && photo.fileId in expandedIds) View.VISIBLE else View.GONE

        holder.infoLink.setOnClickListener {
            val existing = DrivePhotoCache.description(photo.fileId)
            if (existing != null) {
                if (photo.fileId in expandedIds) expandedIds -= photo.fileId else expandedIds += photo.fileId
                holder.infoResult.visibility = if (photo.fileId in expandedIds) View.VISIBLE else View.GONE
                return@setOnClickListener
            }

            expandedIds += photo.fileId
            holder.infoResult.visibility = View.VISIBLE
            holder.infoResult.text = context.getString(R.string.loading_info)
            holder.infoJob = scope.launch {
                val description = withContext(Dispatchers.IO) {
                    DrivePhotoInfo.describe(drive, photo)
                } ?: context.getString(R.string.couldnt_load_photo)
                holder.infoResult.text = description
            }
        }

        holder.thumbnail.setOnClickListener {
            FullScreenPhotoActivity.start(context, photo.fileId, accountName)
        }

        bindMapLink(holder, photo)
    }

    /**
     * Lights up the Map link once the photo's GPS is known. Usually that costs
     * nothing — Drive reports coordinates in the folder listing itself — but a
     * photo Drive found no fix for still needs its EXIF header read over the
     * network, so this resolves off the main thread either way and shows the
     * link disabled/grey until an answer arrives.
     */
    private fun bindMapLink(holder: PhotoViewHolder, photo: DrivePhoto) {
        fun apply(coords: DoubleArray?) {
            val hasGps = coords != null
            holder.mapLink.isEnabled = hasGps
            holder.mapLink.setTextColor(if (hasGps) LINK_BLUE else LINK_GREY)
            holder.mapLink.setOnClickListener {
                if (coords != null) MapViewActivity.start(context, coords[0], coords[1])
            }
        }

        if (DrivePhotoCache.hasGps(photo.fileId)) {
            apply(DrivePhotoCache.gps(photo.fileId))
            return
        }
        // Unknown yet: show disabled, then resolve in the background.
        apply(null)
        scope.launch {
            val coords = withContext(Dispatchers.IO) { DrivePhotoInfo.coords(drive, photo) }
            // Only update if this holder is still bound to the same photo.
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                val current = items.getOrNull(holder.bindingAdapterPosition)
                if (current is SyncedListItem.Photo && current.photo.fileId == photo.fileId) {
                    apply(coords)
                }
            }
        }
    }

    override fun getItemCount() = items.size
}
