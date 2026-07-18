package com.johnvv.photosync

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView

/** Checkbox list of [CityGroup]s for the "Sync by City" picker. */
class CityListAdapter(
    private val groups: List<CityGroup>,
    private val selectedKeys: MutableSet<String>
) : RecyclerView.Adapter<CityListAdapter.ViewHolder>() {

    class ViewHolder(val checkBox: CheckBox) : RecyclerView.ViewHolder(checkBox)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val checkBox = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_city, parent, false) as CheckBox
        return ViewHolder(checkBox)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.text = "${group.displayName} (${group.photos.size})"
        holder.checkBox.isChecked = group.locationKey in selectedKeys
        holder.checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedKeys += group.locationKey else selectedKeys -= group.locationKey
        }
    }

    override fun getItemCount() = groups.size
}
