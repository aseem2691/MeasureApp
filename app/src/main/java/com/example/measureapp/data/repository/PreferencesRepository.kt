package com.example.measureapp.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.measureapp.data.models.UnitType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Extension to create DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "measure_preferences")

/**
 * Repository for app preferences using DataStore
 */
@Singleton
class PreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    
    private val dataStore = context.dataStore
    
    companion object {
        private val UNIT_TYPE_KEY = stringPreferencesKey("unit_type")
        private val SHOW_TUTORIAL_KEY = booleanPreferencesKey("show_tutorial")
        private val HAPTIC_FEEDBACK_KEY = booleanPreferencesKey("haptic_feedback")
        private val SOUND_ENABLED_KEY = booleanPreferencesKey("sound_enabled")
        private val AUTO_SAVE_KEY = booleanPreferencesKey("auto_save")
    }
    
    /**
     * Get selected unit type
     */
    val unitType: Flow<UnitType> = dataStore.data.map { preferences ->
        val unitString = preferences[UNIT_TYPE_KEY] ?: UnitType.METRIC.name
        try {
            UnitType.valueOf(unitString)
        } catch (e: Exception) {
            UnitType.METRIC
        }
    }
    
    /**
     * Set unit type
     */
    suspend fun setUnitType(unitType: UnitType) {
        dataStore.edit { preferences ->
            preferences[UNIT_TYPE_KEY] = unitType.name
        }
    }
    
    /**
     * Get whether to show tutorial
     */
    val showTutorial: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SHOW_TUTORIAL_KEY] ?: true
    }
    
    /**
     * Set tutorial shown
     */
    suspend fun setTutorialShown() {
        dataStore.edit { preferences ->
            preferences[SHOW_TUTORIAL_KEY] = false
        }
    }
    
    /**
     * Get haptic feedback preference
     */
    val hapticFeedbackEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[HAPTIC_FEEDBACK_KEY] ?: true
    }
    
    /**
     * Set haptic feedback preference
     */
    suspend fun setHapticFeedback(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAPTIC_FEEDBACK_KEY] = enabled
        }
    }
    
    /**
     * Get sound enabled preference
     */
    val soundEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SOUND_ENABLED_KEY] ?: true
    }
    
    /**
     * Set sound enabled preference
     */
    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SOUND_ENABLED_KEY] = enabled
        }
    }
    
    /**
     * Get auto-save preference
     */
    val autoSaveEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUTO_SAVE_KEY] ?: false
    }
    
    /**
     * Set auto-save preference
     */
    suspend fun setAutoSave(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_SAVE_KEY] = enabled
        }
    }
    
    /**
     * Clear all preferences
     */
    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
