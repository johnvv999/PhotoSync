package com.johnvv.photosync

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.johnvv.photosync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var syncState: SyncState

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            syncState.selectedAccountName = account.email
            binding.statusText.text = "Signed in as ${account.email}"
            launchAccountPickerIfNeeded()
        } catch (e: ApiException) {
            binding.statusText.text = "Sign-in failed (code ${e.statusCode}). Check the OAuth client setup in Google Cloud Console."
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* proceed regardless; worker will just skip GPS tagging if location perm denied */ }

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pickedName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && pickedName != null) {
            syncState.selectedAccountName = pickedName
            syncState.driveAccountAuthorized = true
            binding.statusText.text = "Signed in as $pickedName"
        } else {
            binding.statusText.text = "Drive account access wasn't granted — sync won't work until you allow it."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        syncState = SyncState(this)

        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_READONLY))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, signInOptions)

        requestNeededPermissions()

        val existingAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (existingAccount != null) {
            syncState.selectedAccountName = existingAccount.email
            binding.statusText.text = "Signed in as ${existingAccount.email}"
            launchAccountPickerIfNeeded()
        } else {
            binding.statusText.text = "Not signed in"
        }

        binding.signInButton.setOnClickListener {
            signInLauncher.launch(googleSignInClient.signInIntent)
        }

        binding.syncNowButton.setOnClickListener {
            triggerImmediateSync()
        }

        binding.getLinkButton.setOnClickListener {
            showFolderLink()
        }

        binding.syncOptionsButton.setOnClickListener {
            startActivity(Intent(this, SyncControlActivity::class.java))
        }

        binding.browseSyncedButton.setOnClickListener {
            startActivity(Intent(this, SyncedPhotosActivity::class.java))
        }
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

    private fun triggerImmediateSync() {
        val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "photosync_manual_upload",
            ExistingWorkPolicy.KEEP,
            request
        )
        binding.statusText.text = "Sync started…"

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    binding.statusText.text = "Sync complete"
                    SyncedPhotosActivity.invalidateCache()
                }
                WorkInfo.State.FAILED -> binding.statusText.text = "Sync failed. Check connection and sign-in."
                else -> {} // still enqueued/running — leave "Sync started…" showing
            }
        }
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
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        binding.statusText.text = "\"$label\" link copied — paste it into a message or email."
    }
}
