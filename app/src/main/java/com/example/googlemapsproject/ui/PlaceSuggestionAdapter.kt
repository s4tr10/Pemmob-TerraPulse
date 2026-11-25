package com.example.googlemapsproject.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.googlemapsproject.R
import com.example.googlemapsproject.databinding.ItemPlaceSuggestionBinding
import com.example.googlemapsproject.model.GeoMath
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.PlaceItem

class PlaceSuggestionAdapter(
    private val onItemClick: (PlaceItem) -> Unit
) : RecyclerView.Adapter<PlaceSuggestionAdapter.ViewHolder>() {

    enum class IconMode { SEARCH_RESULT, RECENT, FAVORITE }

    private var items: List<PlaceItem> = emptyList()
    private var referenceLocation: GeoPointData? = null
    private var iconMode: IconMode = IconMode.SEARCH_RESULT

    fun submitList(
        newItems: List<PlaceItem>,
        userLocation: GeoPointData?,
        mode: IconMode
    ) {
        items = newItems
        referenceLocation = userLocation
        iconMode = mode
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaceSuggestionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemPlaceSuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PlaceItem) {
            binding.tvSuggestionName.text = item.name
            binding.tvSuggestionAddress.text = item.address

            val iconRes = when (iconMode) {
                IconMode.SEARCH_RESULT -> R.drawable.ic_gps_fixed
                IconMode.RECENT -> R.drawable.ic_history
                IconMode.FAVORITE -> R.drawable.ic_star_filled
            }
            binding.ivSuggestionIcon.setImageResource(iconRes)

            val ref = referenceLocation
            if (ref != null) {
                val dist = GeoMath.distanceMeters(ref, item.location)
                binding.tvSuggestionDistance.visibility = View.VISIBLE
                binding.tvSuggestionDistance.text = GeoMath.formatDistanceId(dist)
            } else {
                binding.tvSuggestionDistance.visibility = View.GONE
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
