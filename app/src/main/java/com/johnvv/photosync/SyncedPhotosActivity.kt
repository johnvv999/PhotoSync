package com.johnvv.photosync

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.johnvv.photosync.databinding.ActivitySyncedPhotosBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists the actual contents of the Drive PhotoSync folder, each with a Gemini-powered "Info" link. */
class SyncedPhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncedPhotosBinding

    companion object {
        // Resolving the folder listing downloads each photo's bytes (for city/GPS),
        // so it's slow. Cache the resolved list at process level so that returning
        // to this screen (e.g. from the fullscreen photo view, which recreates this
        // activity) reuses it instead of re-fetching everything from Drive.
        private var cachedFolderId: String? = null
        private var cachedPhotos: List<DrivePhoto>? = null

        /** Call after a sync so the next open of this screen refetches fresh contents. */
        fun invalidateCache() {
            cachedFolderId = null
            cachedPhotos = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncedPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.photosList.layoutManager = LinearLayoutManager(this)

        val syncState = SyncState(this)
        val accountName = syncState.selectedAccountName
        if (accountName == null) {
            binding.emptyText.text = getString(R.string.not_signed_in_for_browse)
            binding.emptyText.visibility = View.VISIBLE
            return
        }

        val drive = DriveServiceHelper(this, accountName)

        // Reuse the cached listing if we already have it for this folder.
        val cached = cachedPhotos
        if (cached != null && cachedFolderId == syncState.rootFolderId) {
            showPhotos(cached, drive, accountName)
            return
        }

        binding.emptyText.text = getString(R.string.loading_synced_photos)
        binding.emptyText.visibility = View.VISIBLE

        lifecycleScope.launch {
            var resolvedFolderId: String? = syncState.rootFolderId
            val photos = try {
                withContext(Dispatchers.IO) {
                    val folderId = syncState.rootFolderId ?: drive.getOrCreateRootFolder().id.also {
                        syncState.rootFolderId = it
                    }
                    resolvedFolderId = folderId
                    drive.listPhotosInFolder(folderId)
                }
            } catch (e: Exception) {
                binding.emptyText.text = getString(R.string.couldnt_load_synced_photos)
                binding.emptyText.visibility = View.VISIBLE
                return@launch
            }
            cachedFolderId = resolvedFolderId
            cachedPhotos = photos
            showPhotos(photos, drive, accountName)
        }
    }

    private fun showPhotos(photos: List<DrivePhoto>, drive: DriveServiceHelper, accountName: String) {
        if (photos.isEmpty()) {
            binding.emptyText.text = getString(R.string.no_synced_photos)
            binding.emptyText.visibility = View.VISIBLE
        } else {
            binding.emptyText.visibility = View.GONE
            val items = buildSyncedListItems(photos)
            binding.photosList.adapter = DrivePhotoAdapter(this, items, drive, lifecycleScope, accountName)
        }
    }
}
