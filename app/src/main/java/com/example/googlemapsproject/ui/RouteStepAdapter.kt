package com.example.googlemapsproject.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.example.googlemapsproject.R
import com.example.googlemapsproject.databinding.ItemRouteStepBinding
import com.example.googlemapsproject.model.GeoMath
import com.example.googlemapsproject.model.ManeuverType
import com.example.googlemapsproject.model.RouteStep

class RouteStepAdapter : RecyclerView.Adapter<RouteStepAdapter.ViewHolder>() {

    private var steps: List<RouteStep> = emptyList()

    fun submitSteps(newSteps: List<RouteStep>) {
        steps = newSteps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRouteStepBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(steps[position])
    }

    override fun getItemCount(): Int = steps.size

    inner class ViewHolder(
        private val binding: ItemRouteStepBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(step: RouteStep) {
            binding.tvStepInstruction.text = step.instruction
            binding.tvStepDistance.text = if (step.distanceMeters > 0) {
                GeoMath.formatDistanceId(step.distanceMeters)
            } else {
                "Tiba"
            }
            binding.ivStepManeuver.setImageResource(iconForManeuver(step.maneuverType))
        }
    }

    companion object {
        @DrawableRes
        fun iconForManeuver(type: ManeuverType): Int = when (type) {
            ManeuverType.DEPART, ManeuverType.STRAIGHT -> R.drawable.ic_turn_straight
            ManeuverType.SLIGHT_LEFT -> R.drawable.ic_turn_slight_left
            ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> R.drawable.ic_turn_left
            ManeuverType.SLIGHT_RIGHT -> R.drawable.ic_turn_slight_right
            ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> R.drawable.ic_turn_right
            ManeuverType.UTURN -> R.drawable.ic_turn_uturn
            ManeuverType.ROUNDABOUT -> R.drawable.ic_turn_roundabout
            ManeuverType.ARRIVE -> R.drawable.ic_flag_arrive
        }
    }
}
