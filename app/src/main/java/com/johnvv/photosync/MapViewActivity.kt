package com.johnvv.photosync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

/** Fullscreen in-app map for one photo's location — just a pannable/zoomable map and a pin, no place-details UI. */
class MapViewActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val DEFAULT_ZOOM = 15f

        fun start(context: Context, lat: Double, lon: Double) {
            context.startActivity(
                Intent(context, MapViewActivity::class.java)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LON, lon)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_view)
        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(this)
        findViewById<android.widget.Button>(R.id.returnButton).setOnClickListener { finish() }
    }

    override fun onMapReady(map: com.google.android.gms.maps.GoogleMap) {
        val location = LatLng(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
        map.addMarker(MarkerOptions().position(location))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, DEFAULT_ZOOM))
    }
}
