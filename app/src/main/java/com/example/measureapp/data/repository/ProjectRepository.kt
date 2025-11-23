package com.example.measureapp.data.repository

import com.example.measureapp.data.local.dao.ProjectDao
import com.example.measureapp.data.local.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for project/collection data operations
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao
) {
    
    /**
     * Get all projects
     */
    fun getAllProjects(): Flow<List<ProjectEntity>> {
        return projectDao.getAllProjects()
    }
    
    /**
     * Get project by ID
     */
    suspend fun getProjectById(id: Long): ProjectEntity? {
        return projectDao.getProjectById(id)
    }
    
    /**
     * Create new project
     */
    suspend fun createProject(project: ProjectEntity): Long {
        return projectDao.insertProject(project)
    }
    
    /**
     * Update existing project
     */
    suspend fun updateProject(project: ProjectEntity) {
        projectDao.updateProject(project)
    }
    
    /**
     * Delete project
     */
    suspend fun deleteProject(project: ProjectEntity) {
        projectDao.deleteProject(project)
    }
    
    /**
     * Update project's last modified timestamp
     */
    suspend fun touchProject(id: Long) {
        projectDao.updateProjectTimestamp(id, System.currentTimeMillis())
    }
}
