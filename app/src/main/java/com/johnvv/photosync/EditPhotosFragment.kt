package com.johnvv.photosync

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.johnvv.photosync.databinding.FragmentEditPhotosBinding
import kotlinx.coroutines.CancellationException
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

        // Ticking your way through hundreds of photos is minutes of work, and
        // Android is free to destroy this screen the moment you leave it — to
        // take a call, to check something, to answer a message. Without these,
        // coming back threw that work away and dropped you at the top of the
        // list, which is the one thing the picker exists to avoid.
        const val STATE_MODE = "editMode"
        const val STATE_SELECTED = "selectedForDeletion"
        const val STATE_FIRST_VISIBLE = "firstVisiblePosition"
    }

    private var _binding: FragmentEditPhotosBinding? = null
    private val binding get() = _binding!!

    private var drive: DriveServiceHelper? = null
    private var accountName: String? = null

    private var folderId: String? = null
    private var photos: List<DrivePhoto> = emptyList()
    private var editAdapter: EditPhotoAdapter? = null

    /** What the list is showing, headings included — what a scroll position indexes into. */
    private var listItems: List<SyncedListItem> = emptyList()

    /** File IDs ticked for deletion, shared with whichever adapter is showing. */
    private val selectedForDeletion = mutableSetOf<String>()

    /**
     * Where the list should be put once it has something to show. The photos
     * arrive from Drive well after the view does, so a restored scroll position
     * has to wait for them rather than being applied on the spot.
     */
    private var pendingScrollPosition: Int? = null

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
        restoreState(savedInstanceState)
        binding.photosList.layoutManager = LinearLayoutManager(requireContext())
        binding.fixLocationsButton.setOnClickListener { fixLocations() }
        binding.deleteSelectedButton.setOnClickListener { startPickingAnyPhoto() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.cancelButton.setOnClickListener { cancelPicking() }
    }

    /**
     * Keeps the picker's state across the screen being destroyed and rebuilt —
     * leaving the app, rotating the phone, or Android reclaiming the memory.
     *
     * The ticks are file IDs, so they survive the photo list being re-read from
     * Drive and still mean the same photos.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode.name)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selectedForDeletion))
        val layoutManager = _binding?.photosList?.layoutManager as? LinearLayoutManager
        outState.putInt(
            STATE_FIRST_VISIBLE,
            layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        )
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        mode = state.getString(STATE_MODE)
            ?.let { saved -> Mode.entries.firstOrNull { it.name == saved } }
            ?: Mode.BROWSE
        selectedForDeletion.addAll(state.getStringArrayList(STATE_SELECTED).orEmpty())
        val position = state.getInt(STATE_FIRST_VISIBLE, RecyclerView.NO_POSITION)
        if (position != RecyclerView.NO_POSITION) pendingScrollPosition = position
    }

    /**
     * Turns the ordinary photo list into a picker so any photo can be deleted.
     *
     * Asks where to start first. The folder runs to hundreds of photos and the
     * one you came to delete is rarely near the top, so without this every
     * deletion begins with a long scroll.
     */
    private fun startPickingAnyPhoto() {
        if (photos.isEmpty()) {
            binding.statusText.text = getString(R.string.no_synced_photos)
            return
        }

        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setSelection(text.length)
        }
        // Wrapped so the field isn't flush against the dialog's edges.
        val padding = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.start_at_title)
            .setMessage(getString(R.string.start_at_message, photos.size))
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Anything unusable falls back to the top rather than refusing
                // to open the list.
                val typed = input.text.toString().trim().toIntOrNull() ?: 1
                beginPicking(typed.coerceIn(1, photos.size))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
        // Widened to the screen, after show() — which is when there is a window
        // to resize. Asking for MATCH_PARENT alone isn't enough: the dialog
        // theme's background is an inset drawable, and it holds the panel off
        // the edges however wide the window gets. Replacing it with the same
        // rounded panel minus the inset is what lets the width take effect.
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_dialog_full_width)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun beginPicking(startAt: Int) {
        showLoading()
        selectedForDeletion.clear()
        mode = Mode.PICK_ANY
        showEditList()
        updateSelectionStatus()
        scrollToPhoto(startAt)
    }

    /**
     * Puts the [oneBasedIndex]th photo at the top of the list. Counted over
     * photos alone — the place headings between them are not something anyone
     * counts when they say "the 200th photo".
     */
    private fun scrollToPhoto(oneBasedIndex: Int) {
        var seen = 0
        var position = -1
        for ((index, item) in listItems.withIndex()) {
            if (item !is SyncedListItem.Photo) continue
            seen++
            if (seen == oneBasedIndex) {
                position = index
                break
            }
        }
        if (position < 0) return
        // Offset rather than a plain scroll, so the photo lands at the top of
        // the list instead of merely somewhere on screen.
        (binding.photosList.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, 0)
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
        // Neither job at the top can be started mid-pick, and Delete Selected
        // sitting above Delete is an easy mis-tap. They come back on Cancel, and
        // once a deletion finishes.
        binding.topButtonBar.visibility = if (mode == Mode.BROWSE) View.VISIBLE else View.GONE
        binding.deleteButton.visibility = View.VISIBLE
        binding.emptyMessage.visibility = View.GONE
        binding.photosList.visibility = View.VISIBLE
    }


    override fun onResume() {
        super.onResume()
        if (hasLoaded) {
            // The tab's view can be torn down and rebuilt while the fragment
            // itself lives on, which leaves the list with no adapter and the
            // screen blank. Rebuild it from the photos already in hand rather
            // than reading the folder again.
            if (photos.isNotEmpty() && binding.photosList.adapter == null) {
                showEditList()
                binding.statusText.text =
                    if (mode == Mode.PICK_ANY) selectionStatus() else describeFolder()
            }
            return
        }

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
            } catch (e: CancellationException) {
                // Leaving the tab, or the app, cancels this — and the cancellation
                // surfaces as an exception where the Drive call resumes. Caught as
                // an ordinary failure it went on to touch views that no longer
                // existed, which crashed the app: leave while it was loading, come
                // back, and the screen had started over with the ticks gone.
                throw e
            } catch (e: Exception) {
                // Put the list back, or a failure leaves "Loading…" on screen for good.
                if (_binding == null) return@launch
                applyMode()
                binding.statusText.text = getString(R.string.couldnt_load_synced_photos)
                setButtonsEnabled(true)
                return@launch
            }
            if (_binding == null) return@launch
            photos = loaded
            showEditList()
            // Mid-pick — restored after the screen was destroyed — the tick
            // count is the line that belongs here, not the folder summary.
            binding.statusText.text = when {
                finalStatus != null -> finalStatus
                mode == Mode.PICK_ANY -> selectionStatus()
                else -> describeFolder()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Put the list back, or a failure leaves "Loading…" on screen for good.
                if (_binding == null) return@launch
                applyMode()
                binding.statusText.text = getString(R.string.couldnt_load_synced_photos)
                setButtonsEnabled(true)
                return@launch
            }

            if (_binding == null) return@launch

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


    private fun selectionStatus(): String =
        getString(R.string.pick_any_selected, selectedForDeletion.size, photos.size)

    private fun updateSelectionStatus() {
        binding.statusText.text = selectionStatus()
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
                    } catch (e: CancellationException) {
                        // Not a photo that failed to delete — the screen is gone.
                        // Counted as a failure it would keep working through the
                        // rest of the list and report nonsense at the end.
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                    reportProgress(R.string.deleting_progress, deleted + failed, targets.size)
                }
            }

            selectedForDeletion.clear()
            mode = Mode.BROWSE
            if (_binding == null) return@launch
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
        listItems = items
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

        // Posted, because the list has only just been handed its rows and can't
        // scroll to one it hasn't laid out yet.
        pendingScrollPosition?.let { saved ->
            pendingScrollPosition = null
            val position = saved.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            binding.photosList.post {
                (_binding?.photosList?.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(position, 0)
            }
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
