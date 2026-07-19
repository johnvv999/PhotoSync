package com.johnvv.photosync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
    private val scope: CoroutineScope
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_PHOTO = 1
    }

    private val bytesCache = LruCache<String, ByteArray>(16)
    private val thumbnailCache = LruCache<String, Bitmap>(64)
    private val infoCache = mutableMapOf<String, String>()
    private val expandedIds = mutableSetOf<String>()

    class HeaderViewHolder(val titleView: TextView) : RecyclerView.ViewHolder(titleView)

    class PhotoViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val thumbnail: ImageView = root.findViewById(R.id.thumbnail)
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

        val cachedThumb = thumbnailCache.get(photo.fileId)
        if (cachedThumb != null) {
            holder.thumbnail.setImageBitmap(cachedThumb)
        } else {
            holder.thumbnail.setImageDrawable(null)
            holder.thumbnailJob = scope.launch {
                val bytes = withContext(Dispatchers.IO) { downloadBytes(photo.fileId) }
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    thumbnailCache.put(photo.fileId, bitmap)
                    holder.thumbnail.setImageBitmap(bitmap)
                }
            }
        }

        val cachedInfo = infoCache[photo.fileId]
        holder.infoResult.text = cachedInfo.orEmpty()
        holder.infoResult.visibility = if (cachedInfo != null && photo.fileId in expandedIds) View.VISIBLE else View.GONE

        holder.infoLink.setOnClickListener {
            val existing = infoCache[photo.fileId]
            if (existing != null) {
                if (photo.fileId in expandedIds) expandedIds -= photo.fileId else expandedIds += photo.fileId
                holder.infoResult.visibility = if (photo.fileId in expandedIds) View.VISIBLE else View.GONE
                return@setOnClickListener
            }

            expandedIds += photo.fileId
            holder.infoResult.visibility = View.VISIBLE
            holder.infoResult.text = context.getString(R.string.loading_info)
            holder.infoJob = scope.launch {
                val bytes = withContext(Dispatchers.IO) { downloadBytes(photo.fileId) }
                val description = if (bytes != null) {
                    withContext(Dispatchers.IO) { GeminiClient.describeImage(bytes) }
                } else {
                    context.getString(R.string.couldnt_load_photo)
                }
                infoCache[photo.fileId] = description
                holder.infoResult.text = description
            }
        }

        val hasGps = photo.lat != null && photo.lon != null
        holder.mapLink.isEnabled = hasGps
        holder.mapLink.setTextColor(if (hasGps) Color.parseColor("#1A73E8") else Color.parseColor("#6B6B70"))
        holder.mapLink.setOnClickListener {
            if (!hasGps) return@setOnClickListener
            val uri = Uri.parse("geo:${photo.lat},${photo.lon}?q=${photo.lat},${photo.lon}")
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadBytes(fileId: String): ByteArray? {
        bytesCache.get(fileId)?.let { return it }
        return try {
            drive.downloadPhotoBytes(fileId).also { bytesCache.put(fileId, it) }
        } catch (e: Exception) {
            null
        }
    }

    override fun getItemCount() = items.size
}
