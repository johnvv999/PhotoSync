package com.johnvv.photosync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
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
 * Thumbnail grid of [PhotoEntry] items with a selection checkbox, for the
 * individual-photo picker. Photos already uploaded show a "Synced" badge so
 * the user can avoid re-picking duplicates, and a long-press toggles a
 * permanent "Excluded" flag ([ExcludedPhotoStore]) so a photo is never
 * uploaded by any sync mode.
 */
class PhotoGridAdapter(
    private val context: Context,
    private val photos: List<PhotoEntry>,
    private val selectedIds: MutableSet<Long>,
    private val scope: CoroutineScope,
    private val uploadedStore: UploadedPhotoStore,
    private val excludedStore: ExcludedPhotoStore
) : RecyclerView.Adapter<PhotoGridAdapter.ViewHolder>() {

    private val thumbnailCache = LruCache<Long, Bitmap>(64)

    class ViewHolder(root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val thumbnail: ImageView = root.findViewById(R.id.thumbnail)
        val checkBox: CheckBox = root.findViewById(R.id.photoCheckBox)
        val statusBadge: TextView = root.findViewById(R.id.statusBadge)
        val excludedOverlay: View = root.findViewById(R.id.excludedOverlay)
        var loadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false) as FrameLayout
        return ViewHolder(root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val photo = photos[position]
        bindStatus(holder, photo)

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = photo.id in selectedIds
        holder.checkBox.setOnCheckedChangeListener { _, checked ->
            if (excludedStore.isExcluded(photo.id)) {
                // Excluded photos can't be selected for upload.
                holder.checkBox.isChecked = false
                selectedIds -= photo.id
                return@setOnCheckedChangeListener
            }
            if (checked) selectedIds += photo.id else selectedIds -= photo.id
        }

        holder.itemView.setOnLongClickListener {
            val nowExcluded = !excludedStore.isExcluded(photo.id)
            excludedStore.setExcluded(photo.id, nowExcluded)
            if (nowExcluded) {
                selectedIds -= photo.id
                holder.checkBox.isChecked = false
            }
            bindStatus(holder, photo)
            Toast.makeText(
                context,
                if (nowExcluded) R.string.photo_excluded_toast else R.string.photo_unexcluded_toast,
                Toast.LENGTH_SHORT
            ).show()
            true
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

    private fun bindStatus(holder: ViewHolder, photo: PhotoEntry) {
        val excluded = excludedStore.isExcluded(photo.id)
        val uploaded = uploadedStore.isUploaded(photo.id)

        holder.excludedOverlay.visibility = if (excluded) View.VISIBLE else View.GONE
        holder.checkBox.visibility = if (excluded) View.GONE else View.VISIBLE

        when {
            excluded -> {
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.setText(R.string.badge_excluded)
                holder.statusBadge.setBackgroundColor(Color.parseColor("#D32F2F"))
            }
            uploaded -> {
                holder.statusBadge.visibility = View.VISIBLE
                holder.statusBadge.setText(R.string.badge_synced)
                holder.statusBadge.setBackgroundColor(Color.parseColor("#2E7D32"))
            }
            else -> holder.statusBadge.visibility = View.GONE
        }
    }

    override fun getItemCount() = photos.size
}
