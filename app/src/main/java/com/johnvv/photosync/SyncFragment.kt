package com.johnvv.photosync

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.johnvv.photosync.databinding.FragmentSyncBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Sync tab: what's backed up, how a running sync is doing, and how to stop it. */
class SyncFragment : Fragment() {

    private var _binding: FragmentSyncBinding? = null
    private val binding get() = _binding!!

    /** Whether the last status update had a sync in flight, to spot the moment one finishes. */
    private var wasRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.syncOptionsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SyncControlActivity::class.java))
        }
        binding.browseSyncedButton.setOnClickListener {
            startActivity(Intent(requireContext(), SyncedPhotosActivity::class.java))
        }
        binding.stopSyncButton.setOnClickListener { stopSync() }

        binding.stopSyncButton.isEnabled = false
        observeSyncWork()
    }

    override fun onResume() {
        super.onResume()
        updateStorageStats()
        updateDriveCount()
    }

    /**
     * How many photos are actually in the Drive folder, shown beside the phone's
     * own count. The pair is what answers "did the sync finish?" — a number that
     * lags the phone's is the only visible sign that photos are still missing.
     */
    private fun updateDriveCount() {
        val syncState = SyncState(requireContext())
        val account = syncState.selectedAccountName
        val folderId = syncState.rootFolderId
        if (account == null || folderId == null) {
            binding.driveText.setText(R.string.drive_line_no_folder)
            return
        }

        binding.driveText.setText(R.string.drive_line_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    DriveServiceHelper(requireContext(), account).countPhotosInFolder(folderId)
                } catch (e: Exception) {
                    null
                }
            }
            binding.driveText.text = if (count == null) {
                getString(R.string.drive_line_unavailable)
            } else {
                getString(R.string.drive_line, count)
            }
        }
    }

    /**
     * Watches every upload request by tag rather than by id, so this tab reflects
     * syncs started from the Sync Options screen too — it has no start button of
     * its own, so anything it showed would otherwise be stale.
     */
    private fun observeSyncWork() {
        SyncStatus.watch(requireContext(), viewLifecycleOwner) { snapshot ->
            binding.stopSyncButton.isEnabled = snapshot?.running == true
            when {
                snapshot != null -> binding.statusText.text = snapshot.text
                // Nothing running and nothing ever recorded — fall back to who's
                // signed in, which is all this screen has to say when idle.
                else -> showSignedInState()
            }
            if (snapshot?.running == false && wasRunning) {
                SyncedPhotosActivity.invalidateCache()
                updateStorageStats()
                // Re-count now that the run has ended — this is the number that
                // says whether it actually got everything up.
                updateDriveCount()
            }
            wasRunning = snapshot?.running == true
        }
    }

    /** Cancels whatever sync is in flight, whichever screen started it. */
    private fun stopSync() {
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(PhotoUploadWorker.TAG_UPLOAD)
        updateDriveCount()
        // Recorded here as well as by the worker: cancellation only takes effect
        // when the worker next checks, so without this the screen would keep
        // showing progress for a second or two after the tap and look ignored.
        SyncState(requireContext()).recordSyncOutcome(SyncStatus.OUTCOME_STOPPED)
        binding.stopSyncButton.isEnabled = false
        binding.statusText.text = getString(R.string.sync_stopped)
        SyncedPhotosActivity.invalidateCache()
        updateStorageStats()
    }

    private fun showSignedInState() {
        val account = SyncState(requireContext()).selectedAccountName
        binding.statusText.text = if (account != null) {
            getString(R.string.signed_in_as, account)
        } else {
            getString(R.string.sign_in_on_settings_tab)
        }
    }

    private fun updateStorageStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { StorageInfo.read(requireContext()) }
            binding.storageText.text = getString(
                R.string.storage_line,
                stats.photoCount,
                StorageInfo.formatBytes(stats.photoBytes),
                StorageInfo.formatBytes(stats.freeBytes),
                StorageInfo.formatBytes(stats.totalBytes)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
