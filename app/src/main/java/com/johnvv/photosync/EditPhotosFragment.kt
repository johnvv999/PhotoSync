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

    private var _binding: FragmentEditPhotosBinding? = null
    private val binding get() = _binding!!

    private var drive: DriveServiceHelper? = null
    private var accountName: String? = null

    private var folderId: String? = null
    private var photos: List<DrivePhoto> = emptyList()
    private var editAdapter: EditPhotoAdapter? = null

    /** File IDs ticked in the redundant-photo results, awaiting Delete Selected. */
    private val selectedForDeletion = mutableSetOf<String>()

    /** True while the redundant-photo results are showing instead of the edit list. */
    private var showingRedundant = false

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
        binding.findRedundantButton.setOnClickListener { findRedundant() }
        binding.deleteSelectedButton.setOnClickListener { confirmDelete() }
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
                binding.statusText.text = getString(R.string.couldnt_load_synced_photos)
                setButtonsEnabled(true)
                return@launch
            }
            photos = loaded
            showEditList()
            binding.statusText.text = finalStatus ?: if (loaded.isEmpty()) {
                getString(R.string.no_synced_photos)
            } else {
                getString(R.string.edit_photo_count, loaded.size)
            }
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
        val location = LocationNaming.fromDisplayLabel(typedLabel, existingCountry)
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

    /** Groups visually similar photos on-device, then has Gemini judge which are truly redundant. */
    private fun findRedundant() {
        val drive = this.drive ?: return
        val account = accountName ?: return
        if (photos.size < 2) {
            binding.statusText.text = getString(R.string.not_enough_photos_to_compare)
            return
        }
        setButtonsEnabled(false)
        selectedForDeletion.clear()
        binding.statusText.text = getString(R.string.scanning_for_duplicates)

        viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                DuplicateFinder.findRedundantGroups(
                    drive,
                    photos,
                    onProgress = { done, total -> reportProgress(R.string.comparing_progress, done, total) },
                    onStage = { reportStage(R.string.asking_ai) }
                )
            }

            if (groups.isEmpty()) {
                binding.statusText.text = getString(R.string.no_redundant_found)
                showEditList()
                setButtonsEnabled(true)
                return@launch
            }

            showingRedundant = true
            val items = buildRedundantListItems(groups, selectedForDeletion)
            binding.photosList.adapter = RedundantPhotoAdapter(
                requireContext(), items, drive, viewLifecycleOwner.lifecycleScope, account, selectedForDeletion
            ) { updateSelectionStatus() }
            updateSelectionStatus(groupCount = groups.size)
            setButtonsEnabled(true)
        }
    }

    private fun updateSelectionStatus(groupCount: Int? = null) {
        binding.statusText.text = if (groupCount != null) {
            getString(R.string.redundant_found, groupCount, selectedForDeletion.size)
        } else {
            getString(R.string.redundant_selected, selectedForDeletion.size)
        }
    }

    private fun confirmDelete() {
        if (!showingRedundant || selectedForDeletion.isEmpty()) {
            binding.statusText.text = getString(R.string.nothing_selected_to_delete)
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
            showingRedundant = false
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
        showingRedundant = false

        // Photos with no real location float to the top. They're the ones this
        // screen exists to fix, and after a Fix Locations pass they're whatever
        // the inheritance couldn't reach — so burying them mid-list, in whatever
        // order they happened to be taken, hides the only rows needing a hand.
        // Everything else stays chronological beneath them.
        val ordered = photos.sortedWith(
            compareBy(
                { if (LocationNaming.isUnlocated(it.name)) 0 else 1 },
                { it.chronoTimeMs },
                { it.name }
            )
        )
        val items = buildSyncedListItems(ordered)
        val adapter = editAdapter
        if (adapter != null && binding.photosList.adapter === adapter) {
            adapter.submit(items)
        } else {
            editAdapter = EditPhotoAdapter(
                requireContext(), items, drive, viewLifecycleOwner.lifecycleScope, account
            ) { photo, label -> editLocation(photo, label) }
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

    private fun reportStage(messageRes: Int) {
        activity?.runOnUiThread { _binding?.statusText?.text = getString(messageRes) }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.fixLocationsButton.isEnabled = enabled
        binding.findRedundantButton.isEnabled = enabled
        binding.deleteSelectedButton.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.photosList.adapter = null
        editAdapter = null
        _binding = null
    }
}
