package com.johnvv.photosync

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thumbnail grid of [PhotoEntry] items with a selection checkbox, for the individual-photo picker. */
class PhotoGridAdapter(
    private val context: Context,
    private val photos: List<PhotoEntry>,
    private val selectedIds: MutableSet<Long>,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PhotoGridAdapter.ViewHolder>() {

    private val thumbnailCache = LruCache<Long, Bitmap>(64)

    class ViewHolder(root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val thumbnail: ImageView = root.findViewById(R.id.thumbnail)
        val checkBox: CheckBox = root.findViewById(R.id.photoCheckBox)
        var loadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false) as FrameLayout
        return ViewHolder(root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val photo = photos[position]

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = photo.id in selectedIds
        holder.checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedIds += photo.id else selectedIds -= photo.id
        }

        holder.loadJob?.cancel()
        val cached = thumbnailCache.get(photo.id)
        if (cached != null) {
            holder.thumbnail.setImageBitmap(cached)
            return
        }
        holder.thumbnail.setImageDrawable(null)
        holder.loadJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { PhotoThumbnails.load(context, photo) }
            if (bitmap != null) {
                thumbnailCache.put(photo.id, bitmap)
                holder.thumbnail.setImageBitmap(bitmap)
            }
        }
    }

    override fun getItemCount() = photos.size
}
