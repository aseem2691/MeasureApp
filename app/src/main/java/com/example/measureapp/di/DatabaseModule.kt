package com.example.measureapp.di

import android.content.Context
import androidx.room.Room
import com.example.measureapp.data.local.dao.MeasurementDao
import com.example.measureapp.data.local.dao.PointDao
import com.example.measureapp.data.local.dao.ProjectDao
import com.example.measureapp.data.local.database.MeasureDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for database dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMeasureDatabase(
        @ApplicationContext context: Context
    ): MeasureDatabase {
        return Room.databaseBuilder(
            context,
            MeasureDatabase::class.java,
            "measure_database"
        )
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideMeasurementDao(database: MeasureDatabase): MeasurementDao {
        return database.measurementDao()
    }

    @Provides
    fun providePointDao(database: MeasureDatabase): PointDao {
        return database.pointDao()
    }

    @Provides
    fun provideProjectDao(database: MeasureDatabase): ProjectDao {
        return database.projectDao()
    }
    
    @Provides
    @Singleton
    fun provideMeasurementRepository(
        measurementDao: MeasurementDao,
        pointDao: PointDao
    ): com.example.measureapp.data.repository.MeasurementRepository {
        return com.example.measureapp.data.repository.MeasurementRepository(measurementDao, pointDao)
    }
    
    @Provides
    @Singleton
    fun provideProjectRepository(
        projectDao: ProjectDao
    ): com.example.measureapp.data.repository.ProjectRepository {
        return com.example.measureapp.data.repository.ProjectRepository(projectDao)
    }
}
