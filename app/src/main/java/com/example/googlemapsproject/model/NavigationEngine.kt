package com.example.googlemapsproject.model

import kotlin.math.max

class NavigationEngine(private val route: RouteResult) {

    companion object {
        const val STEP_ADVANCE_METERS = 25.0
        const val ARRIVAL_METERS = 25.0
        const val OFF_ROUTE_METERS = 45.0
        const val REROUTE_COOLDOWN_MS = 10_000L
        const val TTS_APPROACH_METERS = 150.0
        const val TTS_IMMEDIATE_METERS = 35.0
    }

    data class EvaluationResult(
        val progress: NavigationProgress,
        val shouldTriggerReroute: Boolean
    )

    private var currentStepIndex = 0
    private val spokenApproachSteps = mutableSetOf<Int>()
    private val spokenImmediateSteps = mutableSetOf<Int>()
    private var arrivalSpoken = false
    private var lastRerouteRequestMs = -REROUTE_COOLDOWN_MS

    fun evaluate(
        location: GeoPointData,
        isSimulated: Boolean,
        nowMs: Long
    ): EvaluationResult {
        val destinationPoint = route.points.lastOrNull() ?: route.destination.location
        val distToDest = GeoMath.distanceMeters(location, destinationPoint)

        if (distToDest < ARRIVAL_METERS) {
            val arriveStep = route.steps.lastOrNull() ?: RouteStep(
                instruction = "Anda telah tiba di tujuan",
                roadName = route.destination.name,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                maneuverType = ManeuverType.ARRIVE,
                location = destinationPoint
            )
            val cue = if (!arrivalSpoken) {
                arrivalSpoken = true
                "Anda telah tiba di ${route.destination.name}"
            } else {
                null
            }
            return EvaluationResult(
                progress = NavigationProgress(
                    currentStepIndex = route.steps.lastIndex.coerceAtLeast(0),
                    currentStep = arriveStep,
                    distanceToNextManeuverMeters = 0.0,
                    remainingDistanceMeters = 0.0,
                    remainingDurationSeconds = 0.0,
                    isOffRoute = false,
                    hasArrived = true,
                    speechCue = cue
                ),
                shouldTriggerReroute = false
            )
        }

        // Advance past completed waypoints cleanly.
        while (currentStepIndex < route.steps.lastIndex) {
            val nextIdx = currentStepIndex + 1
            val distToNext = GeoMath.distanceMeters(location, route.steps[nextIdx].location)
            if (distToNext < STEP_ADVANCE_METERS) {
                currentStepIndex = nextIdx
            } else {
                break
            }
        }

        // Display the upcoming maneuver when departing step 0 and further steps exist.
        val displayStepIdx = if (currentStepIndex == 0 &&
            route.steps.size > 1 &&
            route.steps[0].maneuverType == ManeuverType.DEPART
        ) {
            1
        } else if (currentStepIndex + 1 < route.steps.size) {
            currentStepIndex + 1
        } else {
            currentStepIndex
        }

        val activeStep = route.steps.getOrNull(displayStepIdx) ?: RouteStep(
            instruction = "Menuju ${route.destination.name}",
            roadName = "",
            distanceMeters = distToDest,
            durationSeconds = route.durationSeconds,
            maneuverType = ManeuverType.STRAIGHT,
            location = destinationPoint
        )

        val distToManeuver = GeoMath.distanceMeters(location, activeStep.location)
        val remainingDist = if (route.points.size >= 2) {
            GeoMath.remainingDistanceAlongPolyline(location, route.points)
        } else {
            distToDest
        }

        val totalDist = max(1.0, route.distanceMeters)
        val remainingDur = (remainingDist / totalDist).coerceIn(0.0, 1.5) * route.durationSeconds

        val isOffRoute = !isSimulated &&
            route.points.size >= 2 &&
            GeoMath.distanceToPolylineMeters(location, route.points) > OFF_ROUTE_METERS

        val shouldReroute = if (isOffRoute && (nowMs - lastRerouteRequestMs >= REROUTE_COOLDOWN_MS)) {
            lastRerouteRequestMs = nowMs
            true
        } else {
            false
        }

        val speechCue = buildOneShotSpeechCue(displayStepIdx, activeStep, distToManeuver)

        return EvaluationResult(
            progress = NavigationProgress(
                currentStepIndex = displayStepIdx,
                currentStep = activeStep,
                distanceToNextManeuverMeters = distToManeuver,
                remainingDistanceMeters = remainingDist,
                remainingDurationSeconds = remainingDur,
                isOffRoute = isOffRoute,
                hasArrived = false,
                speechCue = speechCue
            ),
            shouldTriggerReroute = shouldReroute
        )
    }

    private fun buildOneShotSpeechCue(
        stepIdx: Int,
        step: RouteStep,
        distMeters: Double
    ): String? {
        if (distMeters <= TTS_IMMEDIATE_METERS && spokenImmediateSteps.add(stepIdx)) {
            spokenApproachSteps.add(stepIdx)
            return step.instruction
        }
        if (distMeters <= TTS_APPROACH_METERS && spokenApproachSteps.add(stepIdx)) {
            return "Dalam 150 meter, ${step.instruction}"
        }
        return null
    }
}
