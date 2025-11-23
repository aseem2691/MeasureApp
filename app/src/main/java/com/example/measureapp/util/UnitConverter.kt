package com.example.measureapp.util

/**
 * Utility class for converting between different units of measurement
 */
object UnitConverter {

    // Conversion constants
    private const val METERS_TO_FEET = 3.28084f
    private const val METERS_TO_INCHES = 39.3701f
    private const val METERS_TO_CM = 100f
    private const val METERS_TO_MM = 1000f
    private const val SQ_METERS_TO_SQ_FEET = 10.7639f

    /**
     * Convert meters to feet
     */
    fun metersToFeet(meters: Float): Float = meters * METERS_TO_FEET

    /**
     * Convert meters to inches
     */
    fun metersToInches(meters: Float): Float = meters * METERS_TO_INCHES

    /**
     * Convert meters to centimeters
     */
    fun metersToCentimeters(meters: Float): Float = meters * METERS_TO_CM

    /**
     * Convert meters to millimeters
     */
    fun metersToMillimeters(meters: Float): Float = meters * METERS_TO_MM

    /**
     * Convert feet to meters
     */
    fun feetToMeters(feet: Float): Float = feet / METERS_TO_FEET

    /**
     * Convert inches to meters
     */
    fun inchesToMeters(inches: Float): Float = inches / METERS_TO_INCHES

    /**
     * Convert square meters to square feet
     */
    fun squareMetersToSquareFeet(sqMeters: Float): Float = sqMeters * SQ_METERS_TO_SQ_FEET

    /**
     * Convert square feet to square meters
     */
    fun squareFeetToSquareMeters(sqFeet: Float): Float = sqFeet / SQ_METERS_TO_SQ_FEET

    /**
     * Format distance in metric units with auto-scaling
     */
    fun formatMetric(meters: Float): String {
        return when {
            meters < 0.01f -> "${(meters * METERS_TO_MM).toInt()} mm"
            meters < 1.0f -> "${(meters * METERS_TO_CM).toInt()} cm"
            meters < 1000f -> "%.2f m".format(meters)
            else -> "%.2f km".format(meters / 1000f)
        }
    }

    /**
     * Format distance in imperial units with auto-scaling
     */
    fun formatImperial(meters: Float): String {
        val inches = metersToInches(meters)
        return when {
            inches < 12.0f -> "%.1f in".format(inches)
            else -> {
                val feet = inches / 12.0f
                val wholeFeet = feet.toInt()
                val remainingInches = ((feet - wholeFeet) * 12).toInt()
                
                if (feet < 5280f) {
                    if (remainingInches > 0) {
                        "$wholeFeet' $remainingInches\""
                    } else {
                        "$wholeFeet'"
                    }
                } else {
                    val miles = feet / 5280f
                    "%.2f mi".format(miles)
                }
            }
        }
    }

    /**
     * Format area in metric units
     */
    fun formatAreaMetric(squareMeters: Float): String {
        return when {
            squareMeters < 1.0f -> "${(squareMeters * 10000).toInt()} cm²"
            squareMeters < 10000f -> "%.2f m²".format(squareMeters)
            else -> "%.2f hectares".format(squareMeters / 10000f)
        }
    }

    /**
     * Format area in imperial units
     */
    fun formatAreaImperial(squareMeters: Float): String {
        val squareFeet = squareMetersToSquareFeet(squareMeters)
        return when {
            squareFeet < 1.0f -> {
                val squareInches = squareFeet * 144
                "%.2f in²".format(squareInches)
            }
            squareFeet < 43560f -> "%.2f ft²".format(squareFeet)
            else -> {
                val acres = squareFeet / 43560f
                "%.2f acres".format(acres)
            }
        }
    }

    /**
     * Format volume in metric units
     */
    fun formatVolumeMetric(cubicMeters: Float): String {
        return when {
            cubicMeters < 1.0f -> "${(cubicMeters * 1000).toInt()} L"
            else -> "%.2f m³".format(cubicMeters)
        }
    }

    /**
     * Format volume in imperial units
     */
    fun formatVolumeImperial(cubicMeters: Float): String {
        val cubicFeet = cubicMeters * 35.3147f
        return "%.2f ft³".format(cubicFeet)
    }
}
