package com.johnvv.photosync

import android.Manifest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

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
        } catch (e: ApiException) {
            binding.statusText.text = "Sign-in failed (code ${e.statusCode}). Check the OAuth client setup in Google Cloud Console."
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* proceed regardless; worker will just skip GPS tagging if location perm denied */ }

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

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
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
                WorkInfo.State.SUCCEEDED -> binding.statusText.text = "Sync complete"
                WorkInfo.State.FAILED -> binding.statusText.text = "Sync failed. Check connection and sign-in."
                else -> {} // still enqueued/running — leave "Sync started…" showing
            }
        }
    }

    private fun showFolderLink() {
        val accountName = syncState.selectedAccountName ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val drive = DriveServiceHelper(this@MainActivity, accountName)
            val folderId = syncState.rootFolderId ?: drive.getOrCreateRootFolder().also {
                syncState.rootFolderId = it
            }
            val link = drive.getWebViewLink(folderId)
            runOnUiThread {
                binding.statusText.text = link
                    ?: "Folder created, but no link yet — open Drive and share it manually."
            }
        }
    }
}
