package com.johnvv.photosync

import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.johnvv.photosync.databinding.FragmentSettingsBinding

/**
 * The Settings tab: signing in, sharing the public browsing link, and pointing
 * this phone at a Drive folder shared from another account. Everything here is
 * set up once and then left alone, unlike the Sync tab's day-to-day controls.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var syncState: SyncState
    private lateinit var googleSignInClient: GoogleSignInClient

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            syncState.selectedAccountName = account.email
            binding.statusText.text = getString(R.string.signed_in_as, account.email)
            launchAccountPickerIfNeeded()
        } catch (e: ApiException) {
            binding.statusText.text = getString(R.string.sign_in_failed, e.statusCode)
        }
    }

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && pickedName != null) {
            syncState.selectedAccountName = pickedName
            syncState.driveAccountAuthorized = true
            binding.statusText.text = getString(R.string.signed_in_as, pickedName)
        } else {
            binding.statusText.text = getString(R.string.drive_access_not_granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncState = SyncState(requireContext())

        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_READONLY))
            .build()
        googleSignInClient = GoogleSignIn.getClient(requireContext(), signInOptions)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val existingAccount = GoogleSignIn.getLastSignedInAccount(requireContext())
        if (existingAccount != null) {
            syncState.selectedAccountName = existingAccount.email
            binding.statusText.text = getString(R.string.signed_in_as, existingAccount.email)
            launchAccountPickerIfNeeded()
        } else {
            binding.statusText.text = getString(R.string.status_not_signed_in)
        }

        binding.signInButton.setOnClickListener { signInLauncher.launch(googleSignInClient.signInIntent) }
        binding.getLinkButton.setOnClickListener { showFolderLink() }
        binding.useSharedFolderButton.setOnClickListener { showSharedFolderDialog() }
        binding.reuploadButton.setOnClickListener { confirmReupload() }
    }

    /**
     * Forgets which photos have already gone to Drive, so the whole library
     * uploads again under the current naming rules.
     *
     * This exists because deleting photos from Drive leaves the phone's own
     * "already uploaded" record untouched — a sync afterwards would upload
     * nothing at all and look broken. Nothing here touches Drive: the user
     * clears the folder themselves first, which keeps a destructive step out of
     * a button whose job is only to reset local bookkeeping.
     */
    private fun confirmReupload() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reupload_dialog_title)
            .setMessage(R.string.reupload_dialog_message)
            .setPositiveButton(R.string.reupload_confirm) { _, _ ->
                val context = requireContext()
                UploadedPhotoStore(context).clearAll()
                // Numbering restarts too, so the repopulated folder reads
                // Country_City_001 upward instead of continuing old counters.
                LocationIndexStore(context).clearAll()
                syncState.resetForFullReupload()
                binding.statusText.text = getString(R.string.reupload_ready)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Launches Android's account-chooser dialog once, the first time this
     * account is used. That explicit picker flow is what actually grants this
     * app AccountManager visibility into the account for GoogleAccountCredential
     * — just matching an email string from Google Sign-In isn't enough.
     */
    private fun launchAccountPickerIfNeeded() {
        if (syncState.driveAccountAuthorized) return
        val intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        accountPickerLauncher.launch(intent)
    }

    /**
     * Lets this phone sync into a Drive folder shared from another account
     * (e.g. so two phones on different accounts share one folder) instead of
     * creating its own. The user pastes the folder's share link; we store the
     * extracted folder ID as the sync target.
     */
    private fun showSharedFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.shared_folder_hint)
            if (syncState.usingSharedFolder) setText(syncState.rootFolderId.orEmpty())
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.shared_folder_title)
            .setMessage(R.string.shared_folder_message)
            .setView(input)
            .setPositiveButton(R.string.use_shared_folder) { _, _ ->
                val folderId = extractFolderId(input.text.toString())
                if (folderId == null) {
                    Toast.makeText(requireContext(), R.string.invalid_folder_link, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                syncState.rootFolderId = folderId
                syncState.usingSharedFolder = true
                SyncedPhotosActivity.invalidateCache()
                binding.statusText.text = getString(R.string.shared_folder_set)
            }
            .setNeutralButton(R.string.use_own_folder) { _, _ ->
                syncState.rootFolderId = null
                syncState.usingSharedFolder = false
                SyncedPhotosActivity.invalidateCache()
                binding.statusText.text = getString(R.string.shared_folder_cleared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pulls the folder ID out of a Drive folder link, or accepts a bare ID. */
    private fun extractFolderId(input: String): String? {
        val trimmed = input.trim()
        Regex("/folders/([a-zA-Z0-9_-]+)").find(trimmed)?.let { return it.groupValues[1] }
        // A bare folder ID: Drive IDs are long alphanumeric strings with - and _.
        if (trimmed.matches(Regex("[a-zA-Z0-9_-]{20,}"))) return trimmed
        return null
    }

    /**
     * Shares the public PhotoSync browsing page (docs/index.html, view-only,
     * no Drive UI or upload capability) rather than Drive's own folder link
     * — that opened Drive's native web UI, letting anyone with the link
     * upload files into the folder, which isn't what "share these photos"
     * should mean here.
     */
    private fun showFolderLink() {
        val label = "Our Adventure"
        val url = "https://tinyurl.com/JVVMyPhotos"
        // HTML representation shows as a named hyperlink when pasted into a
        // rich-text-capable target (e.g. an HTML email body); the plain-text
        // fallback (name + raw URL together) is what SMS and other plain-text
        // targets receive instead, since a custom-labelled link isn't possible there.
        val clip = ClipData.newHtmlText(label, "$label: $url", "<a href=\"$url\">$label</a>")
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        binding.statusText.text = getString(R.string.link_copied, label)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
