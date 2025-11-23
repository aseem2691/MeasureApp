package com.example.measureapp.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.measureapp.data.local.dao.MeasurementDao
import com.example.measureapp.data.local.dao.PointDao
import com.example.measureapp.data.local.dao.ProjectDao
import com.example.measureapp.data.local.entities.MeasurementEntity
import com.example.measureapp.data.local.entities.PointEntity
import com.example.measureapp.data.local.entities.ProjectEntity

/**
 * Room database for the Measure app
 */
@Database(
    entities = [
        MeasurementEntity::class,
        PointEntity::class,
        ProjectEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MeasureDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun pointDao(): PointDao
    abstract fun projectDao(): ProjectDao
}
