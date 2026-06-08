package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.example.data.database.PhotoXDao
import com.example.data.model.LayerEntity
import com.example.data.model.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PhotoXRepository(
    private val context: Context,
    private val dao: PhotoXDao
) {
    val allProjects: Flow<List<ProjectEntity>> = dao.getAllProjectsFlow()

    suspend fun getProjectById(projectId: Long): ProjectEntity? {
        return dao.getProjectById(projectId)
    }

    suspend fun createProject(name: String, width: Int, height: Int): Long {
        return withContext(Dispatchers.IO) {
            val project = ProjectEntity(
                name = name,
                width = width,
                height = height
            )
            val projectId = dao.insertProject(project)

            // Auto-create a background layer
            val bgLayer = LayerEntity(
                projectId = projectId,
                name = "Arrière-plan",
                layerIndex = 0,
                type = "COLOR_FILL",
                extraDataJson = "#FFFFFF" // Fill solid white by default
            )
            val bgLayerId = dao.insertLayer(bgLayer)

            // Generate an initial blank white bitmap for the background
            val file = getLayerFile(projectId, bgLayerId)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            saveBitmapToFile(bitmap, file)
            bitmap.recycle()

            projectId
        }
    }

    suspend fun deleteProject(projectId: Long) {
        withContext(Dispatchers.IO) {
            val project = dao.getProjectById(projectId)
            if (project != null) {
                dao.deleteProject(project)
                dao.deleteLayersByProject(projectId)
                // Delete project directory
                val dir = getProjectDirectory(projectId)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    fun getLayersFlow(projectId: Long): Flow<List<LayerEntity>> {
        return dao.getLayersByProjectFlow(projectId)
    }

    suspend fun getLayers(projectId: Long): List<LayerEntity> {
        return dao.getLayersByProject(projectId)
    }

    suspend fun saveLayerMeta(layer: LayerEntity): Long {
        return dao.insertLayer(layer)
    }

    suspend fun deleteLayer(projectId: Long, layerId: Long) {
        withContext(Dispatchers.IO) {
            dao.deleteLayerById(layerId)
            val file = getLayerFile(projectId, layerId)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    suspend fun saveLayerBitmap(projectId: Long, layerId: Long, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            val file = getLayerFile(projectId, layerId)
            saveBitmapToFile(bitmap, file)
        }
    }

    suspend fun loadLayerBitmap(projectId: Long, layerId: Long, width: Int, height: Int): Bitmap {
        return withContext(Dispatchers.IO) {
            val file = getLayerFile(projectId, layerId)
            if (file.exists()) {
                val opt = BitmapFactory.Options().apply {
                    inMutable = true
                }
                BitmapFactory.decodeFile(file.absolutePath, opt) ?: createEmptyBitmap(width, height)
            } else {
                createEmptyBitmap(width, height)
            }
        }
    }

    private fun getProjectDirectory(projectId: Long): File {
        val dir = File(context.filesDir, "project_$projectId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getLayerFile(projectId: Long, layerId: Long): File {
        val dir = getProjectDirectory(projectId)
        return File(dir, "layer_$layerId.png")
    }

    private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            fos?.close()
        }
    }

    private fun createEmptyBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        return bitmap
    }
}
