package com.example.data.database

import androidx.room.*
import com.example.data.model.LayerEntity
import com.example.data.model.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoXDao {
    @Query("SELECT * FROM projects ORDER BY lastModified DESC")
    fun getAllProjectsFlow(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("SELECT * FROM layers WHERE projectId = :projectId ORDER BY layerIndex ASC")
    fun getLayersByProjectFlow(projectId: Long): Flow<List<LayerEntity>>

    @Query("SELECT * FROM layers WHERE projectId = :projectId ORDER BY layerIndex ASC")
    suspend fun getLayersByProject(projectId: Long): List<LayerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayer(layer: LayerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayers(layers: List<LayerEntity>)

    @Query("DELETE FROM layers WHERE id = :layerId")
    suspend fun deleteLayerById(layerId: Long)

    @Query("DELETE FROM layers WHERE projectId = :projectId")
    suspend fun deleteLayersByProject(projectId: Long)
}
