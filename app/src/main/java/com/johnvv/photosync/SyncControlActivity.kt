package com.johnvv.photosync

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.johnvv.photosync.databinding.ActivitySyncControlBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar

/**
 * Lets the user manually sync a chosen date range as a whole, by GPS-derived
 * city, or as individually picked photos — on top of the always-on 15-minute
 * background sync in [PhotoUploadWorker].
 */
class SyncControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncControlBinding

    private var startEpochMs: Long? = null
    private var endEpochMs: Long? = null

    private val selectedCityKeys = mutableSetOf<String>()
    private val selectedPhotoIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateDateButtons()

        binding.startDateButton.setOnClickListener { pickDate(isStart = true) }
        binding.endDateButton.setOnClickListener { pickDate(isStart = false) }
        binding.modeGroup.setOnCheckedChangeListener { _, _ -> onModeChanged() }
        binding.scanButton.setOnClickListener { scan() }
        binding.startSyncButton.setOnClickListener { startSync() }

        onModeChanged()
    }

    private fun pickDate(isStart: Boolean) {
        val calendar = Calendar.getInstance()
        (if (isStart) startEpochMs else endEpochMs)?.let { calendar.timeInMillis = it }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, day, if (isStart) 0 else 23, if (isStart) 0 else 59, if (isStart) 0 else 59)
                    set(Calendar.MILLISECOND, if (isStart) 0 else 999)
                }.timeInMillis
                if (isStart) startEpochMs = picked else endEpochMs = picked
                updateDateButtons()
            },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateButtons() {
        val fmt = DateFormat.getDateInstance()
        binding.startDateButton.text = getString(
            R.string.start_date_label, startEpochMs?.let { fmt.format(it) } ?: getString(R.string.date_not_set)
        )
        binding.endDateButton.text = getString(
            R.string.end_date_label, endEpochMs?.let { fmt.format(it) } ?: getString(R.string.date_not_set)
        )
    }

    private fun onModeChanged() {
        selectedCityKeys.clear()
        selectedPhotoIds.clear()
        binding.resultsList.adapter = null
        binding.statusText.text = ""

        val needsScan = binding.modeGroup.checkedRadioButtonId != R.id.modeAll
        binding.scanButton.visibility = if (needsScan) View.VISIBLE else View.GONE
        binding.resultsList.visibility = View.GONE
    }

    private fun scan() {
        binding.statusText.text = getString(R.string.scanning_status)
        binding.scanButton.isEnabled = false

        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                PhotoScanner.queryPhotos(this@SyncControlActivity, startEpochMs, endEpochMs)
            }

            if (binding.modeGroup.checkedRadioButtonId == R.id.modeCity) {
                val cities = withContext(Dispatchers.IO) {
                    PhotoScanner.groupByCity(this@SyncControlActivity, photos)
                }
                binding.resultsList.layoutManager = LinearLayoutManager(this@SyncControlActivity)
                binding.resultsList.adapter = CityListAdapter(cities, selectedCityKeys)
                binding.statusText.text = if (cities.isEmpty()) getString(R.string.no_photos_found) else ""
            } else {
                binding.resultsList.layoutManager = GridLayoutManager(this@SyncControlActivity, 3)
                binding.resultsList.adapter =
                    PhotoGridAdapter(this@SyncControlActivity, photos, selectedPhotoIds, lifecycleScope)
                binding.statusText.text = if (photos.isEmpty()) getString(R.string.no_photos_found) else ""
            }
            binding.resultsList.visibility = View.VISIBLE
            binding.scanButton.isEnabled = true
        }
    }

    private fun startSync() {
        val data = when (binding.modeGroup.checkedRadioButtonId) {
            R.id.modeAll -> Data.Builder()
                .putString(PhotoUploadWorker.KEY_MODE, PhotoUploadWorker.MODE_ALL)
                .apply { addDateRange(this) }
                .build()

            R.id.modeCity -> {
                if (selectedCityKeys.isEmpty()) {
                    binding.statusText.text = getString(R.string.select_at_least_one)
                    return
                }
                Data.Builder()
                    .putString(PhotoUploadWorker.KEY_MODE, PhotoUploadWorker.MODE_CITY)
                    .putString(PhotoUploadWorker.KEY_CITY_KEYS, selectedCityKeys.joinToString(","))
                    .apply { addDateRange(this) }
                    .build()
            }

            else -> {
                if (selectedPhotoIds.isEmpty()) {
                    binding.statusText.text = getString(R.string.select_at_least_one)
                    return
                }
                Data.Builder()
                    .putString(PhotoUploadWorker.KEY_MODE, PhotoUploadWorker.MODE_INDIVIDUAL)
                    .putString(PhotoUploadWorker.KEY_PHOTO_IDS, selectedPhotoIds.joinToString(","))
                    .build()
            }
        }

        val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>().setInputData(data).build()
        WorkManager.getInstance(this).enqueue(request)
        binding.statusText.text = getString(R.string.sync_started_status)

        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> binding.statusText.text = getString(R.string.sync_complete_status)
                WorkInfo.State.FAILED -> binding.statusText.text = getString(R.string.sync_failed_status)
                else -> {} // still enqueued/running — leave "Sync started…" showing
            }
        }
    }

    private fun addDateRange(builder: Data.Builder) {
        startEpochMs?.let { builder.putLong(PhotoUploadWorker.KEY_START_EPOCH_MS, it) }
        endEpochMs?.let { builder.putLong(PhotoUploadWorker.KEY_END_EPOCH_MS, it) }
    }
}
