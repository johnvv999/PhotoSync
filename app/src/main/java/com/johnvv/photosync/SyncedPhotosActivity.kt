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

        binding.emptyText.text = getString(R.string.loading_synced_photos)
        binding.emptyText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val drive = DriveServiceHelper(this@SyncedPhotosActivity, accountName)
            val photos = withContext(Dispatchers.IO) {
                val folderId = syncState.rootFolderId ?: drive.getOrCreateRootFolder().also {
                    syncState.rootFolderId = it
                }
                drive.listPhotosInFolder(folderId)
            }
            if (photos.isEmpty()) {
                binding.emptyText.text = getString(R.string.no_synced_photos)
                binding.emptyText.visibility = View.VISIBLE
            } else {
                binding.emptyText.visibility = View.GONE
                val items = buildSyncedListItems(photos)
                binding.photosList.adapter = DrivePhotoAdapter(this@SyncedPhotosActivity, items, drive, lifecycleScope)
            }
        }
    }
}
