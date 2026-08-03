package com.johnvv.photosync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.work.WorkManager
import com.google.android.material.tabs.TabLayoutMediator
import com.johnvv.photosync.databinding.ActivityMainBinding

/**
 * Hosts the three tabs — Sync, Edit and Settings — and owns the app-wide
 * startup work that isn't any one tab's business: runtime permissions, the
 * auto-sync floor, and clearing leftover background work.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* proceed regardless; worker will just skip GPS tagging if location perm denied */ }

    /** Tab order, left to right. */
    private enum class Tab(val titleRes: Int, val create: () -> Fragment) {
        SYNC(R.string.tab_sync, ::SyncFragment),
        EDIT(R.string.tab_edit, ::EditPhotosFragment),
        SETTINGS(R.string.tab_settings, ::SettingsFragment)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val syncState = SyncState(this)

        // Never let a stale/zeroed "last synced" mark send auto-sync through the
        // whole camera roll: floor it to the same date the worker enforces.
        if (syncState.lastSyncedEpochSeconds < PhotoUploadWorker.AUTO_SYNC_FLOOR_EPOCH_SECONDS) {
            syncState.lastSyncedEpochSeconds = PhotoUploadWorker.AUTO_SYNC_FLOOR_EPOCH_SECONDS
        }

        // One-time cleanup: an earlier build could start a runaway full-camera-roll
        // upload that survived restarts. Cancel any leftover work exactly once so
        // opening this build stops it; normal syncs enqueue fresh afterwards.
        if (!syncState.runawaySyncCleared) {
            WorkManager.getInstance(this).cancelAllWork()
            syncState.runawaySyncCleared = true
        }

        requestNeededPermissions()

        val tabs = Tab.entries
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int) = tabs[position].create()
        }
        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.text = getString(tabs[position].titleRes)
        }.attach()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.GET_ACCOUNTS)
        perms.add(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES
            else
                Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
