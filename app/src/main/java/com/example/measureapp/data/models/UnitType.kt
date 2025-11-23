package com.example.measureapp.data.models

/**
 * Represents a measurement unit type
 */
enum class UnitType {
    METRIC,
    IMPERIAL;

    /**
     * Convert meters to the display unit
     */
    fun formatDistance(meters: Float): String {
        return when (this) {
            METRIC -> {
                when {
                    meters < 0.01f -> "${(meters * 1000).toInt()} mm"
                    meters < 1.0f -> "${(meters * 100).toInt()} cm"
                    else -> "%.2f m".format(meters)
                }
            }
            IMPERIAL -> {
                val inches = meters * 39.3701f
                when {
                    inches < 12.0f -> "%.1f in".format(inches)
                    else -> {
                        val feet = inches / 12.0f
                        val wholeFeet = feet.toInt()
                        val remainingInches = ((feet - wholeFeet) * 12).toInt()
                        if (remainingInches > 0) {
                            "$wholeFeet' $remainingInches\""
                        } else {
                            "$wholeFeet'"
                        }
                    }
                }
            }
        }
    }

    /**
     * Convert square meters to the display unit for area
     */
    fun formatArea(squareMeters: Float): String {
        return when (this) {
            METRIC -> {
                when {
                    squareMeters < 1.0f -> "${(squareMeters * 10000).toInt()} cm²"
                    else -> "%.2f m²".format(squareMeters)
                }
            }
            IMPERIAL -> {
                val squareFeet = squareMeters * 10.7639f
                "%.2f ft²".format(squareFeet)
            }
        }
    }

    /**
     * Get the short unit symbol
     */
    fun getSymbol(): String {
        return when (this) {
            METRIC -> "m"
            IMPERIAL -> "ft"
        }
    }
}
