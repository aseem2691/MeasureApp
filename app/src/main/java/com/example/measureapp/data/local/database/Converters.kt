package com.example.measureapp.data.local.database

import androidx.room.TypeConverter
import com.example.measureapp.data.models.MeasurementType
import com.example.measureapp.data.models.UnitType

/**
 * Type converters for Room database
 */
class Converters {
    
    @TypeConverter
    fun fromMeasurementType(value: MeasurementType): String {
        return value.name
    }

    @TypeConverter
    fun toMeasurementType(value: String): MeasurementType {
        return try {
            MeasurementType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            MeasurementType.POINT_TO_POINT
        }
    }

    @TypeConverter
    fun fromUnitType(value: UnitType): String {
        return value.name
    }

    @TypeConverter
    fun toUnitType(value: String): UnitType {
        return try {
            UnitType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            UnitType.METRIC
        }
    }
}
