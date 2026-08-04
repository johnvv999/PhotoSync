package com.johnvv.photosync

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.johnvv.photosync.databinding.FragmentEditPhotosBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Edit tab: repairs what's already on Drive, rather than what's about to be
 * uploaded — fixing location names and capture order across the whole folder,
 * and finding near-duplicate photos worth deleting.
 */
class EditPhotosFragment : Fragment() {

    private companion object {
        /** Heading for photos whose name carries no real place; matches DriveServiceHelper's fallback. */
        const val OTHER_PHOTOS_LABEL = "Other Photos"
    }

    private var _binding: FragmentEditPhotosBinding? = null
    private val binding get() = _binding!!

    private var drive: DriveServiceHelper? = null
    private var accountName: String? = null

    private var folderId: String? = null
    private var photos: List<DrivePhoto> = emptyList()
    private var editAdapter: EditPhotoAdapter? = null

    /** File IDs ticked for deletion, shared with whichever adapter is showing. */
    private val selectedForDeletion = mutableSetOf<String>()

    /** What the list is currently for. */
    private enum class Mode {
        /** Reading and editing locations; no tick boxes, no delete bar. */
        BROWSE,

        /** Every photo tickable, so any of them can be deleted. */
        PICK_ANY
    }

    private var mode = Mode.BROWSE

    /**
     * Listing the folder is a network round trip, so it waits until this tab is
     * actually looked at — ViewPager2 builds neighbouring pages ahead of time,
     * and only the visible one reaches RESUMED.
     */
    private var hasLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEditPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.photosList.layoutManager = LinearLayoutManager(requireContext())
        binding.fixLocationsButton.setOnClickListener { fixLocations() }
        binding.deleteSelectedButton.setOnClickListener { startPickingAnyPhoto() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.cancelButton.setOnClickListener { cancelPicking() }
    }

    /**
     * Turns the ordinary photo list into a picker so any photo can be deleted,
     * not just ones the duplicate scan flagged.
     */
    private fun startPickingAnyPhoto() {
        if (photos.isEmpty()) {
            binding.statusText.text = getString(R.string.no_synced_photos)
            return
        }
        showLoading()
        selectedForDeletion.clear()
        mode = Mode.PICK_ANY
        showEditList()
        updateSelectionStatus()
    }

    /** Leaves either picker — or the "nothing found" screen — without deleting anything. */
    private fun cancelPicking() {
        selectedForDeletion.clear()
        mode = Mode.BROWSE
        showEditList()
        binding.statusText.text = describeFolder()
    }

    /**
     * Describes the folder in terms of what this screen shows — how many photos
     * still need a location — rather than a bare total, which would not match
     * the list beneath it.
     */
    private fun describeFolder(): String {
        if (photos.isEmpty()) return getString(R.string.no_synced_photos)
        val needing = photos.count { LocationNaming.isUnlocated(it.name) }
        return if (needing == 0) {
            getString(R.string.edit_all_located_count, photos.size)
        } else {
            getString(R.string.edit_needing_location, needing, photos.size)
        }
    }

    /**
     * Covers the list with "Loading…" until whatever was tapped has something to
     * show. Every top button leads somewhere that has to be fetched or computed
     * first, and without this the screen sits on the previous contents looking
     * like the tap was ignored.
     *
     * Cleared by [applyMode], which every finished path goes through.
     */
    private fun showLoading() {
        binding.photosList.visibility = View.GONE
        binding.emptyMessage.setText(R.string.loading_ellipsis)
        binding.emptyMessage.visibility = View.VISIBLE
        binding.deleteBar.visibility = View.GONE
    }

    /** The delete bar belongs to the two picking modes and nothing else. */
    private fun applyMode() {
        binding.deleteBar.visibility = if (mode == Mode.BROWSE) View.GONE else View.VISIBLE
        binding.deleteButton.visibility = View.VISIBLE
        binding.emptyMessage.visibility = View.GONE
        binding.photosList.visibility = View.VISIBLE
    }


    override fun onResume() {
        super.onResume()
        if (hasLoaded) return

        // Sign-in lives on the Settings tab, so the account may only appear after
        // this fragment was built — resolve it here, when the tab is first opened.
        val syncState = SyncState(requireContext())
        val account = syncState.selectedAccountName
        if (account == null) {
            binding.statusText.text = getString(R.string.sign_in_on_settings_tab)
            setButtonsEnabled(false)
            return
        }
        accountName = account
        drive = DriveServiceHelper(requireContext(), account)
        folderId = syncState.rootFolderId
        hasLoaded = true
        loadPhotos()
    }

    /**
     * Loads the folder listing so the tab shows the current photos before any
     * button is pressed. [finalStatus], when given, replaces the usual photo
     * count — so an action that ends by reloading can still report what it did.
     */
    private fun loadPhotos(finalStatus: String? = null) {
        val drive = this.drive ?: return
        binding.statusText.text = getString(R.string.loading_synced_photos)
        showLoading()
        setButtonsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val id = folderId ?: drive.getOrCreateRootFolder().id.also {
                        SyncState(requireContext()).rootFolderId = it
                        folderId = it
                    }
                    drive.listPhotosInFolder(id)
                }
            } catch (e: Exception) {
                // Put the list back, or a failure leaves "Loading…" on screen for good.
                applyMode()
                binding.statusText.text = getString(R.string.couldnt_load_synced_photos)
                setButtonsEnabled(true)
                return@launch
            }
            photos = loaded
            showEditList()
            binding.statusText.text = finalStatus ?: describeFolder()
            setButtonsEnabled(true)
        }
    }

    /**
     * Walks the folder in capture order, gives every "NoGPS, Unsorted" photo the
     * location of the last photo that had one, renumbers each location's
     * sequence, and writes the resulting order back to Drive.
     */
    private fun fixLocations() {
        // Say so rather than returning silently: with no folder resolved (the
        // listing failed, or sign-in hasn't happened) the button would otherwise
        // look simply broken.
        val drive = this.drive
        val id = folderId
        if (drive == null || id == null) {
            binding.statusText.text = getString(R.string.no_folder_yet)
            return
        }
        // Leave any picking mode first: renaming rebuilds the list, and carrying
        // ticks and a Delete bar through an unrelated operation invites deleting
        // something chosen for a reason that no longer applies.
        selectedForDeletion.clear()
        mode = Mode.BROWSE
        showLoading()

        setButtonsEnabled(false)
        binding.statusText.text = getString(R.string.fixing_locations)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    PhotoOrganizer.fixLocationsAndOrder(requireContext(), drive, id) { done, total ->
                        reportProgress(R.string.fixing_progress, done, total)
                    }
                }
            } catch (e: Exception) {
                // Put the list back, or a failure leaves "Loading…" on screen for good.
                applyMode()
                binding.statusText.text = getString(R.string.couldnt_load_synced_photos)
                setButtonsEnabled(true)
                return@launch
            }

            // An empty folder isn't a successful no-op worth reporting as one:
            // "renamed 0 photo(s), 0 took the location of an earlier photo" reads
            // like the pass failed, when really there is nothing here yet.
            if (result.photos.isEmpty()) {
                binding.statusText.text = getString(R.string.no_synced_photos)
                setButtonsEnabled(true)
                return@launch
            }

            photos = result.photos
            // Renaming changes each photo's stated location, and the AI descriptions
            // mention it — drop them so Info re-fetches against the corrected place.
            photos.forEach { DrivePhotoCache.forgetDescription(it.fileId) }
            SyncedPhotosActivity.invalidateCache()

            showEditList()
            binding.statusText.text = buildString {
                append(getString(R.string.fix_complete, result.renamed, result.locationsInherited))
                if (result.coordsStamped > 0) {
                    append(" ").append(getString(R.string.fix_coords_stamped, result.coordsStamped))
                }
                if (result.skipped > 0) append(" ").append(getString(R.string.fix_skipped, result.skipped))
                // The inheritance rule needs at least one located photo to copy
                // from. If every photo in the folder lacks GPS there is nothing to
                // propagate, and "renamed 0" on its own reads like a broken button.
                if (result.stillUnlocated > 0) {
                    append(" ").append(
                        if (result.locationsInherited == 0 && result.renamed == 0) {
                            getString(R.string.fix_no_source_location, result.stillUnlocated)
                        } else {
                            getString(R.string.fix_still_unlocated, result.stillUnlocated)
                        }
                    )
                }
            }
            setButtonsEnabled(true)
        }
    }

    /** Applies a hand-typed "City, Country" to one photo, renaming it on Drive. */
    private fun editLocation(photo: DrivePhoto, typedLabel: String) {
        val drive = this.drive ?: return
        // Only a real country is worth keeping when the user types a bare city
        // name; inheriting "Unsorted" from a placeholder filename would turn
        // "Paris" into Unsorted_Paris.
        val existingCountry = LocationNaming.parseFileName(photo.name)?.location
            ?.takeUnless { LocationNaming.isPlaceholder(it) }
            ?.country
            ?: "Unknown"
        val location = LocationNaming.fromCountryFirstLabel(typedLabel, existingCountry)
        if (location == null) {
            Toast.makeText(requireContext(), R.string.location_empty, Toast.LENGTH_SHORT).show()
            return
        }

        setButtonsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                PhotoOrganizer.relocate(requireContext(), drive, photo, location)
            }
            if (updated == null) {
                binding.statusText.text = getString(R.string.rename_failed, photo.name)
            } else {
                photos = photos.map { if (it.fileId == photo.fileId) updated else it }
                    .sortedWith(compareBy({ it.chronoTimeMs }, { it.name }))
                DrivePhotoCache.forgetDescription(photo.fileId)
                SyncedPhotosActivity.invalidateCache()
                showEditList()
                binding.statusText.text = getString(R.string.renamed_to, updated.name)
            }
            setButtonsEnabled(true)
        }
    }


    private fun updateSelectionStatus() {
        binding.statusText.text =
            getString(R.string.pick_any_selected, selectedForDeletion.size, photos.size)
    }

    private fun confirmDelete() {
        if (selectedForDeletion.isEmpty()) {
            binding.statusText.text = getString(R.string.nothing_ticked_to_delete)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_dialog_title)
            .setMessage(getString(R.string.delete_dialog_message, selectedForDeletion.size))
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteSelected() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Moves the ticked photos to Drive's trash. Trash rather than a permanent
     * delete: a photo the AI mis-flagged is then still recoverable from
     * drive.google.com, while leaving the PhotoSync folder straight away.
     */
    private fun deleteSelected() {
        val drive = this.drive ?: return
        val targets = selectedForDeletion.toList()
        setButtonsEnabled(false)
        binding.statusText.text = getString(R.string.deleting_progress, 0, targets.size)

        viewLifecycleOwner.lifecycleScope.launch {
            var deleted = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (fileId in targets) {
                    try {
                        drive.trashFile(fileId)
                        DrivePhotoCache.forget(fileId)
                        deleted++
                    } catch (e: Exception) {
                        failed++
                    }
                    reportProgress(R.string.deleting_progress, deleted + failed, targets.size)
                }
            }

            selectedForDeletion.clear()
            mode = Mode.BROWSE
            applyMode()
            SyncedPhotosActivity.invalidateCache()

            // Re-read the folder rather than editing the local list: a partial
            // failure means the surviving set can't be derived from the selection.
            loadPhotos(
                finalStatus = if (failed > 0) {
                    getString(R.string.deleted_with_failures, deleted, failed)
                } else {
                    getString(R.string.deleted_count, deleted)
                }
            )
        }
    }

    private fun showEditList() {
        val drive = this.drive ?: return
        val account = accountName ?: return
        applyMode()

        // Fix Locations is about photos that still lack one, so this list shows
        // only those. Deleting is a different job and needs the whole library.
        val visible = if (mode == Mode.PICK_ANY) {
            photos
        } else {
            photos.filter { LocationNaming.isUnlocated(it.name) }
        }

        if (visible.isEmpty() && photos.isNotEmpty()) {
            // Nothing left to repair is a result worth stating; an empty list
            // reads as a failure to load.
            binding.photosList.adapter = null
            editAdapter = null
            binding.photosList.visibility = View.GONE
            binding.emptyMessage.setText(R.string.all_photos_located)
            binding.emptyMessage.visibility = View.VISIBLE
            return
        }

        // Same grouping as the browsing page: chronological, drive-by places
        // absorbed, repeat visits collapsed.
        val items = buildSyncedListItems(visible) { photo ->
            LocationNaming.countryFirstLabel(photo.name) ?: OTHER_PHOTOS_LABEL
        }
        val selectable = mode == Mode.PICK_ANY
        val adapter = editAdapter
        if (adapter != null && binding.photosList.adapter === adapter) {
            adapter.selectionMode = selectable
            adapter.submit(items)
        } else {
            editAdapter = EditPhotoAdapter(
                requireContext(), items, drive, viewLifecycleOwner.lifecycleScope, account,
                selectedForDeletion, { updateSelectionStatus() }
            ) { photo, label -> editLocation(photo, label) }.also { it.selectionMode = selectable }
            binding.photosList.adapter = editAdapter
        }
    }

    /**
     * Posts a progress line from a background thread. The binding is nulled out
     * in onDestroyView, so a long Drive pass that outlives the tab's view must
     * not assume it's still there.
     */
    private fun reportProgress(messageRes: Int, done: Int, total: Int) {
        activity?.runOnUiThread {
            _binding?.statusText?.text = getString(messageRes, done, total)
        }
    }


    private fun setButtonsEnabled(enabled: Boolean) {
        binding.fixLocationsButton.isEnabled = enabled
        binding.deleteSelectedButton.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.photosList.adapter = null
        editAdapter = null
        _binding = null
    }
}
