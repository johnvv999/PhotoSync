package com.johnvv.photosync

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows one photo fullscreen, filling the whole window. Tapping anywhere closes it. */
class FullScreenPhotoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_ACCOUNT_NAME = "account_name"

        fun start(context: Context, fileId: String, accountName: String) {
            context.startActivity(
                Intent(context, FullScreenPhotoActivity::class.java)
                    .putExtra(EXTRA_FILE_ID, fileId)
                    .putExtra(EXTRA_ACCOUNT_NAME, accountName)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        setContentView(imageView)
        imageView.setOnClickListener { finish() }

        val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: return
        val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME) ?: return

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val bytes = DriveServiceHelper(this@FullScreenPhotoActivity, accountName).downloadPhotoBytes(fileId)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this@FullScreenPhotoActivity, R.string.couldnt_load_photo, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
