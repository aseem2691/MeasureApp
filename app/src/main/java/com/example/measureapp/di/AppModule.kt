package com.example.measureapp.di

import android.content.Context
import com.example.measureapp.data.repository.MeasurementRepository
import com.example.measureapp.data.repository.PreferencesRepository
import com.example.measureapp.data.repository.ProjectRepository
import com.example.measureapp.domain.calculator.AreaCalculator
import com.example.measureapp.domain.calculator.DistanceCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for general app dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDistanceCalculator(): DistanceCalculator {
        return DistanceCalculator()
    }

    @Provides
    @Singleton
    fun provideAreaCalculator(): AreaCalculator {
        return AreaCalculator()
    }
    
    @Provides
    @Singleton
    fun providePreferencesRepository(
        @ApplicationContext context: Context
    ): PreferencesRepository {
        return PreferencesRepository(context)
    }
}
