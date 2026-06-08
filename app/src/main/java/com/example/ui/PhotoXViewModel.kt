package com.example.ui

import android.content.Context
import android.app.Application
import android.graphics.*
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.PhotoXDatabase
import com.example.data.model.LayerEntity
import com.example.data.model.ProjectEntity
import com.example.data.repository.PhotoXRepository
import com.example.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*

class PhotoXViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PhotoXRepository
    init {
        val database = PhotoXDatabase.getDatabase(application)
        repository = PhotoXRepository(application, database.dao())
    }

    // --- MAIN STATE FLOWS ---
    val projects = repository.allProjects

    private val _activeProject = MutableStateFlow<ProjectEntity?>(null)
    val activeProject: StateFlow<ProjectEntity?> = _activeProject.asStateFlow()

    private val _layersList = MutableStateFlow<List<LayerState>>(emptyList())
    val layersList: StateFlow<List<LayerState>> = _layersList.asStateFlow()

    private val _activeLayerId = MutableStateFlow<Long?>(null)
    val activeLayerId: StateFlow<Long?> = _activeLayerId.asStateFlow()

    // --- TOOLBAR OPTIONS ---
    private val _selectedTool = MutableStateFlow("Déplacer") // "Déplacer", "Pinceau", "Gomme", etc.
    val selectedTool: StateFlow<String> = _selectedTool.asStateFlow()

    // Tools Configuration
    var brushSize = MutableStateFlow(24f)
    var brushColor = MutableStateFlow("#3A8EED") // default neon blue accent
    var isEraserSize = MutableStateFlow(32f)
    var magicWandTolerance = MutableStateFlow(32)
    var textInput = MutableStateFlow("Texte PhotoX")
    var shapeType = MutableStateFlow("Rectangle") // "Rectangle", "Cercle", "Ligne"
    var cloneSourcePoint = MutableStateFlow<PointF?>(null) // Alt+click source

    // --- CAMERA RAW IMPORT FLOW ---
    private val _rawImportingBitmap = MutableStateFlow<Bitmap?>(null)
    val rawImportingBitmap: StateFlow<Bitmap?> = _rawImportingBitmap.asStateFlow()

    // Camera Raw Adjustment sliders states
    var rawExposure = MutableStateFlow(0f)
    var rawContrast = MutableStateFlow(0f)
    var rawHighlights = MutableStateFlow(0f)
    var rawShadows = MutableStateFlow(0f)
    var rawWhites = MutableStateFlow(0f)
    var rawBlacks = MutableStateFlow(0f)
    var rawClarity = MutableStateFlow(0f)
    var rawVibrance = MutableStateFlow(0f)
    var rawSaturation = MutableStateFlow(1f)
    var rawTemperature = MutableStateFlow(0f)
    var rawTint = MutableStateFlow(0f)
    var rawNoiseReduction = MutableStateFlow(0f)
    var rawSharpness = MutableStateFlow(0f)

    private val _cameraRawPreview = MutableStateFlow<Bitmap?>(null)
    val cameraRawPreview: StateFlow<Bitmap?> = _cameraRawPreview.asStateFlow()

    // --- MAGIC WAND SELECTION MASK ---
    private val _selectionMask = MutableStateFlow<Bitmap?>(null)
    val selectionMask: StateFlow<Bitmap?> = _selectionMask.asStateFlow()

    // --- UNDO/REDO ENGINE ---
    private val undoStack = Stack<List<LayerStateSnapshot>>()
    private val redoStack = Stack<List<LayerStateSnapshot>>()

    data class LayerStateSnapshot(
        val layerId: Long,
        val name: String,
        val type: String,
        val isVisible: Boolean,
        val opacity: Float,
        val blendMode: String,
        val offsetX: Float,
        val offsetY: Float,
        val scale: Float,
        val extraDataJson: String,
        val bitmapCopy: Bitmap
    )

    // --- PROJECT ACTIONS ---
    fun selectProject(projectId: Long?) {
        viewModelScope.launch {
            if (projectId == null) {
                _activeProject.value = null
                // Clear active states
                _layersList.value = emptyList()
                _activeLayerId.value = null
                _selectionMask.value = null
                undoStack.clear()
                redoStack.clear()
            } else {
                val proj = repository.getProjectById(projectId)
                if (proj != null) {
                    _activeProject.value = proj
                    loadProjectLayers(proj)
                }
            }
        }
    }

    private suspend fun loadProjectLayers(project: ProjectEntity) {
        withContext(Dispatchers.IO) {
            val dbLayers = repository.getLayers(project.id)
            val stateLayers = dbLayers.map { entity ->
                val bmp = repository.loadLayerBitmap(project.id, entity.id, project.width, project.height)
                LayerState(
                    id = entity.id,
                    name = entity.name,
                    layerIndex = entity.layerIndex,
                    type = entity.type,
                    isVisible = entity.isVisible,
                    opacity = entity.opacity,
                    blendMode = entity.blendMode,
                    offsetX = entity.offsetX,
                    offsetY = entity.offsetY,
                    scale = entity.scale,
                    extraDataJson = entity.extraDataJson,
                    bitmap = bmp
                )
            }
            _layersList.value = stateLayers
            if (stateLayers.isNotEmpty()) {
                _activeLayerId.value = stateLayers.last().id
            }
            // Reset undo/redo on loading new project
            undoStack.clear()
            redoStack.clear()
        }
    }

    fun createNewProject(templateName: String, width: Int = 512, height: Int = 512) {
        viewModelScope.launch {
            val (w, h) = when (templateName) {
                "Flyer A6" -> Pair(413, 583)
                "Affiche A4" -> Pair(595, 842)
                "Carte visite" -> Pair(336, 192)
                "Logo carré" -> Pair(512, 512)
                else -> Pair(width.coerceIn(100, 1024), height.coerceIn(100, 1024))
            }
            val projectId = repository.createProject("Projet $templateName", w, h)
            selectProject(projectId)
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            if (_activeProject.value?.id == projectId) {
                selectProject(null)
            }
            repository.deleteProject(projectId)
        }
    }

    // --- TOOL ACTIONS ---
    fun selectTool(tool: String) {
        _selectedTool.value = tool
        if (tool != "Tampon") {
            cloneSourcePoint.value = null // reset source point when changing tool
        }
    }

    fun clearSelection() {
        _selectionMask.value = null
    }

    // --- LAYERS ACTIONS ---
    fun addNewLayer(type: String = "IMAGE") {
        val proj = _activeProject.value ?: return
        viewModelScope.launch {
            saveToHistory()
            val index = _layersList.value.size
            val layerName = "Calque ${index + 1}"
            val entity = LayerEntity(
                projectId = proj.id,
                name = layerName,
                layerIndex = index,
                type = type
            )
            val layerId = repository.saveLayerMeta(entity)

            // Blank transparent bitmap
            val blankBmp = Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ARGB_8888)
            blankBmp.eraseColor(Color.TRANSPARENT)
            repository.saveLayerBitmap(proj.id, layerId, blankBmp)

            val newLayer = LayerState(
                id = layerId,
                name = layerName,
                layerIndex = index,
                type = type,
                bitmap = blankBmp
            )
            _layersList.value = _layersList.value + newLayer
            _activeLayerId.value = layerId
        }
    }

    fun deleteLayer(layerId: Long) {
        if (_layersList.value.size <= 1) return // Keep at least one layer
        val proj = _activeProject.value ?: return
        viewModelScope.launch {
            saveToHistory()
            repository.deleteLayer(proj.id, layerId)
            _layersList.value = _layersList.value.filter { it.id != layerId }
            if (_activeLayerId.value == layerId) {
                _activeLayerId.value = _layersList.value.lastOrNull()?.id
            }
        }
    }

    fun selectLayer(layerId: Long) {
        _activeLayerId.value = layerId
    }

    fun toggleLayerVisibility(layerId: Long) {
        _layersList.value = _layersList.value.map {
            if (it.id == layerId) {
                val updatedVal = !it.isVisible
                viewModelScope.launch {
                    val proj = _activeProject.value ?: return@launch
                    repository.saveLayerMeta(
                        LayerEntity(
                            id = it.id,
                            projectId = proj.id,
                            name = it.name,
                            layerIndex = it.layerIndex,
                            type = it.type,
                            isVisible = updatedVal,
                            opacity = it.opacity,
                            blendMode = it.blendMode,
                            offsetX = it.offsetX,
                            offsetY = it.offsetY,
                            scale = it.scale,
                            extraDataJson = it.extraDataJson
                        )
                    )
                }
                it.copy(isVisible = updatedVal)
            } else it
        }
    }

    fun updateLayerOpacity(layerId: Long, opacity: Float) {
        _layersList.value = _layersList.value.map {
            if (it.id == layerId) {
                viewModelScope.launch {
                    val proj = _activeProject.value ?: return@launch
                    repository.saveLayerMeta(
                        LayerEntity(
                            id = it.id,
                            projectId = proj.id,
                            name = it.name,
                            layerIndex = it.layerIndex,
                            type = it.type,
                            isVisible = it.isVisible,
                            opacity = opacity,
                            blendMode = it.blendMode,
                            offsetX = it.offsetX,
                            offsetY = it.offsetY,
                            scale = it.scale,
                            extraDataJson = it.extraDataJson
                        )
                    )
                }
                it.copy(opacity = opacity)
            } else it
        }
    }

    fun updateLayerBlendMode(layerId: Long, blendMode: String) {
        _layersList.value = _layersList.value.map {
            if (it.id == layerId) {
                viewModelScope.launch {
                    val proj = _activeProject.value ?: return@launch
                    repository.saveLayerMeta(
                        LayerEntity(
                            id = it.id,
                            projectId = proj.id,
                            name = it.name,
                            layerIndex = it.layerIndex,
                            type = it.type,
                            isVisible = it.isVisible,
                            opacity = it.opacity,
                            blendMode = blendMode,
                            offsetX = it.offsetX,
                            offsetY = it.offsetY,
                            scale = it.scale,
                            extraDataJson = it.extraDataJson
                        )
                    )
                }
                it.copy(blendMode = blendMode)
            } else it
        }
    }

    fun moveLayerIndex(layerId: Long, direction: Int) { // -1 up, +1 down (UI list order matches index order)
        val list = _layersList.value.toMutableList()
        val index = list.indexOfFirst { it.id == layerId }
        val targetIndex = index + direction
        if (index == -1 || targetIndex !in list.indices) return

        saveToHistory()
        val temp = list[index]
        list[index] = list[targetIndex]
        list[targetIndex] = temp

        val updatedList = list.mapIndexed { idx, it ->
            val finalLayer = it.copy(layerIndex = idx)
            viewModelScope.launch {
                val proj = _activeProject.value ?: return@launch
                repository.saveLayerMeta(
                    LayerEntity(
                        id = finalLayer.id,
                        projectId = proj.id,
                        name = finalLayer.name,
                        layerIndex = finalLayer.layerIndex,
                        type = finalLayer.type,
                        isVisible = finalLayer.isVisible,
                        opacity = finalLayer.opacity,
                        blendMode = finalLayer.blendMode,
                        offsetX = finalLayer.offsetX,
                        offsetY = finalLayer.offsetY,
                        scale = finalLayer.scale,
                        extraDataJson = finalLayer.extraDataJson
                    )
                )
            }
            finalLayer
        }
        _layersList.value = updatedList
    }

    fun translateLayer(layerId: Long, dx: Float, dy: Float) {
        _layersList.value = _layersList.value.map {
            if (it.id == layerId) {
                it.copy(offsetX = it.offsetX + dx, offsetY = it.offsetY + dy)
            } else it
        }
    }

    fun scaleLayer(layerId: Long, dScale: Float) {
        _layersList.value = _layersList.value.map {
            if (it.id == layerId) {
                it.copy(scale = (it.scale * dScale).coerceIn(0.1f, 10f))
            } else it
        }
    }

    // --- DRAWING AND EDITING ENGINE ON SELECTED LAYER ---
    // Paints or modifies pixels directly in active layer's Bitmap object
    fun drawOrPaintOnLayer(x: Float, y: Float, actionType: String) { // "MOVE_START", "MOVE_DRAG", "MOVE_END", "TAP"
        val activeId = _activeLayerId.value ?: return
        val activeLayer = _layersList.value.find { it.id == activeId } ?: return
        if (!activeLayer.isVisible) return

        val proj = _activeProject.value ?: return
        val bmp = activeLayer.bitmap
        val canvas = Canvas(bmp)

        // Ensure we record history at startup of action
        if (actionType == "MOVE_START" || actionType == "TAP") {
            saveToHistory()
        }

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        when (_selectedTool.value) {
            "Pinceau" -> {
                paint.color = Color.parseColor(brushColor.value)
                paint.strokeWidth = brushSize.value
                paint.style = Paint.Style.STROKE
                // Draw circle or line at coordinates
                canvas.drawCircle(x, y, brushSize.value / 2f, paint.apply { style = Paint.Style.FILL })
            }
            "Gomme" -> {
                paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.CLEAR))
                paint.strokeWidth = isEraserSize.value
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(x, y, isEraserSize.value / 2f, paint.apply { style = Paint.Style.FILL })
            }
            "Tampon" -> {
                val source = cloneSourcePoint.value
                if (source == null) {
                    // Set clone stamp source point!
                    cloneSourcePoint.value = PointF(x, y)
                } else {
                    // Paint using pixels from background/other layers or current layer
                    // Calculate relative offset of source point to current point
                    val dx = source.x - x
                    val dy = source.y - y
                    // Let's copy a small patch of size brushSize around source point to destination
                    val size = brushSize.value.toInt().coerceAtLeast(4)
                    val srcRect = Rect(
                        (source.x - size / 2).toInt().coerceIn(0, proj.width),
                        (source.y - size / 2).toInt().coerceIn(0, proj.height),
                        (source.x + size / 2).toInt().coerceIn(0, proj.width),
                        (source.y + size / 2).toInt().coerceIn(0, proj.height)
                    )
                    val destRect = Rect(
                        (x - size / 2).toInt().coerceIn(0, proj.width),
                        (y - size / 2).toInt().coerceIn(0, proj.height),
                        (x + size / 2).toInt().coerceIn(0, proj.width),
                        (y + size / 2).toInt().coerceIn(0, proj.height)
                    )
                    // We draw this patch directly onto the layer
                    canvas.drawBitmap(bmp, srcRect, destRect, paint)

                    // Shift source slightly to simulate continuous painting brush
                    if (actionType == "MOVE_DRAG") {
                        cloneSourcePoint.value = PointF(source.x + (x - source.x) * 0.05f, source.y + (y - source.y) * 0.05f)
                    }
                }
            }
            "Pipette" -> {
                // Pick color on layer at px, py
                val px = x.toInt().coerceIn(0, bmp.width - 1)
                val py = y.toInt().coerceIn(0, bmp.height - 1)
                val pixelColor = bmp.getPixel(px, py)
                val hexStr = String.format("#%06X", 0xFFFFFF and pixelColor)
                brushColor.value = hexStr
            }
            "Baguette magique" -> {
                if (actionType == "TAP" || actionType == "MOVE_START") {
                    val mask = ImageProcessor.applyMagicWand(bmp, x.toInt(), y.toInt(), magicWandTolerance.value)
                    _selectionMask.value = mask
                }
            }
            "Texte" -> {
                if (actionType == "TAP") {
                    paint.color = Color.parseColor(brushColor.value)
                    paint.textSize = brushSize.value * 2f
                    paint.textAlign = Paint.Align.CENTER
                    paint.style = Paint.Style.FILL
                    canvas.drawText(textInput.value, x, y, paint)
                }
            }
            "Formes" -> {
                if (actionType == "TAP") {
                    paint.color = Color.parseColor(brushColor.value)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brushSize.value / 4f
                    val size = brushSize.value * 1.5f
                    when (shapeType.value) {
                        "Rectangle" -> canvas.drawRect(x - size, y - size, x + size, y + size, paint)
                        "Cercle" -> canvas.drawCircle(x, y, size, paint)
                        "Ligne" -> canvas.drawLine(x - size, y, x + size, y, paint)
                    }
                }
            }
            "Dégradé" -> {
                if (actionType == "TAP" || actionType == "MOVE_START") {
                    // Apply linear gradient color from primary brush color to black/white on current layer
                    val endColor = Color.TRANSPARENT
                    val startColor = Color.parseColor(brushColor.value)
                    val gradient = LinearGradient(0f, 0f, 0f, bmp.height.toFloat(), startColor, endColor, Shader.TileMode.CLAMP)
                    paint.shader = gradient
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
                }
            }
        }

        // Notify state update by forcing triggering list composition
        _layersList.value = _layersList.value.map {
            if (it.id == activeId) it.copy() else it
        }

        // Persist on IO thread upon operation release
        if (actionType == "MOVE_END" || actionType == "TAP") {
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveLayerBitmap(proj.id, activeId, bmp)
            }
        }
    }

    // --- EXPLICIT COMPOSITION ACTIONS ---
    fun applyRemplir(colorHex: String) {
        val activeId = _activeLayerId.value ?: return
        val activeLayer = _layersList.value.find { it.id == activeId } ?: return
        val proj = _activeProject.value ?: return

        saveToHistory()
        val bmp = activeLayer.bitmap
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.FILL
        }

        val mask = _selectionMask.value
        if (mask != null) {
            // Fill restricted to selection mask region!
            val maskPixels = IntArray(mask.width * mask.height)
            mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)
            val layerPixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(layerPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

            val fColor = Color.parseColor(colorHex)
            for (i in maskPixels.indices) {
                if (maskPixels[i] != 0) {
                    layerPixels[i] = fColor
                }
            }
            bmp.setPixels(layerPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        } else {
            canvas.drawColor(Color.parseColor(colorHex))
        }

        _layersList.value = _layersList.value.map {
            if (it.id == activeId) it.copy() else it
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.saveLayerBitmap(proj.id, activeId, bmp)
        }
    }

    fun applyContour(colorHex: String, widthDp: Float) {
        val activeId = _activeLayerId.value ?: return
        val activeLayer = _layersList.value.find { it.id == activeId } ?: return
        val proj = _activeProject.value ?: return

        saveToHistory()
        val bmp = activeLayer.bitmap
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.STROKE
            strokeWidth = widthDp
        }

        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)

        _layersList.value = _layersList.value.map {
            if (it.id == activeId) it.copy() else it
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.saveLayerBitmap(proj.id, activeId, bmp)
        }
    }

    // --- OFFLINE RENDERING FILTERS INTERFACES ---
    fun applyOfflineFilter(filterCategory: String, filterTypeName: String) {
        val activeId = _activeLayerId.value ?: return
        val activeLayer = _layersList.value.find { it.id == activeId } ?: return
        val proj = _activeProject.value ?: return

        saveToHistory()
        val srcBmp = activeLayer.bitmap

        viewModelScope.launch(Dispatchers.Default) {
            val processed = when (filterCategory) {
                "FLU" -> {
                    when (filterTypeName) {
                        "Flou gaussien" -> ImageProcessor.applyGaussianBlur(srcBmp, 10)
                        "Flou de mouvement" -> ImageProcessor.applyMotionBlur(srcBmp, 15, 45f)
                        "Flou radial" -> ImageProcessor.applyRadialBlur(srcBmp, 0.4f)
                        "Flou d'adoucissement" -> ImageProcessor.applySmoothBlur(srcBmp, 6)
                        else -> srcBmp
                    }
                }
                "RENDU" -> {
                    when (filterTypeName) {
                        "Nuages" -> ImageProcessor.applyClouds(proj.width, proj.height)
                        "Éclairage" -> ImageProcessor.applyLighting(srcBmp, proj.width / 2f, proj.height / 2f, proj.width * 0.6f, 1.5f)
                        else -> srcBmp
                    }
                }
                "NETTETÉ" -> {
                    when (filterTypeName) {
                        "Masque flou" -> ImageProcessor.applyUnsharpMask(srcBmp, 1.5f)
                        "Accentuation" -> ImageProcessor.applySharpen(srcBmp, 1.0f)
                        else -> srcBmp
                    }
                }
                "DÉFORMATION" -> {
                    when (filterTypeName) {
                        "Ondulation" -> ImageProcessor.applyRipple(srcBmp, 12f, 15f)
                        "Sphérisation" -> ImageProcessor.applySpherize(srcBmp, 0.6f)
                        else -> srcBmp
                    }
                }
                "BRUIT" -> {
                    when (filterTypeName) {
                        "Ajouter du bruit" -> ImageProcessor.applyAddNoise(srcBmp, 0.15f)
                        "Atténuation du bruit" -> ImageProcessor.applySmoothBlur(srcBmp, 2)
                        else -> srcBmp
                    }
                }
                "AUTRES" -> {
                    when (filterTypeName) {
                        "Niveaux de gris" -> ImageProcessor.applyGrayscale(srcBmp)
                        "Sépia" -> ImageProcessor.applySepia(srcBmp)
                        "Négatif" -> ImageProcessor.applyNegative(srcBmp)
                        "Posterisation" -> ImageProcessor.applyPosterize(srcBmp, 5)
                        "Détection des contours" -> ImageProcessor.applyEdgeDetection(srcBmp)
                        else -> srcBmp
                    }
                }
                else -> srcBmp
            }

            // Copy pixels into active layer bitmap securely
            val pixels = IntArray(processed.width * processed.height)
            processed.getPixels(pixels, 0, processed.width, 0, 0, processed.width, processed.height)
            srcBmp.setPixels(pixels, 0, srcBmp.width, 0, 0, srcBmp.width, srcBmp.height)

            // Notify UI
            withContext(Dispatchers.Main) {
                _layersList.value = _layersList.value.map {
                    if (it.id == activeId) it.copy() else it
                }
                // Save to file system
                viewModelScope.launch(Dispatchers.IO) {
                    repository.saveLayerBitmap(proj.id, activeId, srcBmp)
                }
            }
        }
    }

    // --- CAMERA RAW IMPORT COMPONENT ---
    fun triggerCameraRawImport(bitmap: Bitmap) {
        _rawImportingBitmap.value = bitmap
        // Reset raw development parameters
        rawExposure.value = 0f
        rawContrast.value = 0f
        rawHighlights.value = 0f
        rawShadows.value = 0f
        rawWhites.value = 0f
        rawBlacks.value = 0f
        rawClarity.value = 0f
        rawVibrance.value = 0f
        rawSaturation.value = 1f
        rawTemperature.value = 0f
        rawTint.value = 0f
        rawNoiseReduction.value = 0f
        rawSharpness.value = 0f

        updateCameraRawPreview()
    }

    fun updateCameraRawPreview() {
        val src = _rawImportingBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val res = ImageProcessor.applyCameraRaw(
                src = src,
                exposure = rawExposure.value,
                contrast = rawContrast.value,
                highlights = rawHighlights.value,
                shadows = rawShadows.value,
                whites = rawWhites.value,
                blacks = rawBlacks.value,
                clarity = rawClarity.value,
                vibrance = rawVibrance.value,
                saturation = rawSaturation.value,
                temperature = rawTemperature.value,
                tint = rawTint.value,
                noiseReduction = rawNoiseReduction.value,
                sharpness = rawSharpness.value
            )
            _cameraRawPreview.value = res
        }
    }

    fun commitCameraRawImport() {
        val devBmp = _cameraRawPreview.value ?: _rawImportingBitmap.value ?: return
        val proj = _activeProject.value ?: return
        viewModelScope.launch {
            saveToHistory()
            val index = _layersList.value.size
            val layerName = "Calque Photo RAW"
            val entity = LayerEntity(
                projectId = proj.id,
                name = layerName,
                layerIndex = index,
                type = "IMAGE"
            )
            val layerId = repository.saveLayerMeta(entity)

            // Scaled fit to canvas size
            val resized = Bitmap.createScaledBitmap(devBmp, proj.width, proj.height, true)
            repository.saveLayerBitmap(proj.id, layerId, resized)

            val newLayer = LayerState(
                id = layerId,
                name = layerName,
                layerIndex = index,
                type = "IMAGE",
                bitmap = resized
            )
            _layersList.value = _layersList.value + newLayer
            _activeLayerId.value = layerId

            // Reset RAW wizard
            _rawImportingBitmap.value = null
            _cameraRawPreview.value = null
        }
    }

    fun discardCameraRawImport() {
        _rawImportingBitmap.value = null
        _cameraRawPreview.value = null
    }

    // --- UNDO / REDO CONTROLLER ---
    private fun saveToHistory() {
        val currentStates = _layersList.value.map { layer ->
            // Deep copy bitmap to independent history buffer
            val originalBmp = layer.bitmap
            val bmpCopy = Bitmap.createBitmap(originalBmp.width, originalBmp.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmpCopy)
            canvas.drawBitmap(originalBmp, 0f, 0f, null)

            LayerStateSnapshot(
                layerId = layer.id,
                name = layer.name,
                type = layer.type,
                isVisible = layer.isVisible,
                opacity = layer.opacity,
                blendMode = layer.blendMode,
                offsetX = layer.offsetX,
                offsetY = layer.offsetY,
                scale = layer.scale,
                extraDataJson = layer.extraDataJson,
                bitmapCopy = bmpCopy
            )
        }
        undoStack.push(currentStates)
        redoStack.clear() // clear redo after action
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val proj = _activeProject.value ?: return

        val popped = undoStack.pop()

        // Push current matching state onto redo
        val currentStates = _layersList.value.map { layer ->
            val originalBmp = layer.bitmap
            val bmpCopy = Bitmap.createBitmap(originalBmp.width, originalBmp.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmpCopy)
            canvas.drawBitmap(originalBmp, 0f, 0f, null)

            LayerStateSnapshot(
                layerId = layer.id,
                name = layer.name,
                type = layer.type,
                isVisible = layer.isVisible,
                opacity = layer.opacity,
                blendMode = layer.blendMode,
                offsetX = layer.offsetX,
                offsetY = layer.offsetY,
                scale = layer.scale,
                extraDataJson = layer.extraDataJson,
                bitmapCopy = bmpCopy
            )
        }
        redoStack.push(currentStates)

        // Restore popped state
        viewModelScope.launch(Dispatchers.IO) {
            val restoredLayers = popped.map { snapshot ->
                // Write the bitmap back
                repository.saveLayerBitmap(proj.id, snapshot.layerId, snapshot.bitmapCopy)
                LayerState(
                    id = snapshot.layerId,
                    name = snapshot.name,
                    layerIndex = 0, // dynamic
                    type = snapshot.type,
                    isVisible = snapshot.isVisible,
                    opacity = snapshot.opacity,
                    blendMode = snapshot.blendMode,
                    offsetX = snapshot.offsetX,
                    offsetY = snapshot.offsetY,
                    scale = snapshot.scale,
                    extraDataJson = snapshot.extraDataJson,
                    bitmap = snapshot.bitmapCopy
                )
            }
            withContext(Dispatchers.Main) {
                _layersList.value = restoredLayers
                _activeLayerId.value = restoredLayers.lastOrNull()?.id
            }
        }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val proj = _activeProject.value ?: return

        val popped = redoStack.pop()

        // Push current matching state onto undo
        val currentStates = _layersList.value.map { layer ->
            val originalBmp = layer.bitmap
            val bmpCopy = Bitmap.createBitmap(originalBmp.width, originalBmp.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmpCopy)
            canvas.drawBitmap(originalBmp, 0f, 0f, null)

            LayerStateSnapshot(
                layerId = layer.id,
                name = layer.name,
                type = layer.type,
                isVisible = layer.isVisible,
                opacity = layer.opacity,
                blendMode = layer.blendMode,
                offsetX = layer.offsetX,
                offsetY = layer.offsetY,
                scale = layer.scale,
                extraDataJson = layer.extraDataJson,
                bitmapCopy = bmpCopy
            )
        }
        undoStack.push(currentStates)

        // Restore popped state
        viewModelScope.launch(Dispatchers.IO) {
            val restoredLayers = popped.map { snapshot ->
                repository.saveLayerBitmap(proj.id, snapshot.layerId, snapshot.bitmapCopy)
                LayerState(
                    id = snapshot.layerId,
                    name = snapshot.name,
                    layerIndex = 0,
                    type = snapshot.type,
                    isVisible = snapshot.isVisible,
                    opacity = snapshot.opacity,
                    blendMode = snapshot.blendMode,
                    offsetX = snapshot.offsetX,
                    offsetY = snapshot.offsetY,
                    scale = snapshot.scale,
                    extraDataJson = snapshot.extraDataJson,
                    bitmap = snapshot.bitmapCopy
                )
            }
            withContext(Dispatchers.Main) {
                _layersList.value = restoredLayers
                _activeLayerId.value = restoredLayers.lastOrNull()?.id
            }
        }
    }

    // --- PHOTOX FORMAT EXPORTING ---
    // Combined rasterization of visible layers adhering to BlendModes, Opacities and custom properties!
    fun renderCompositedCanvas(): Bitmap {
        val proj = _activeProject.value ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val composed = Bitmap.createBitmap(proj.width, proj.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        canvas.drawColor(Color.TRANSPARENT) // root slate transparent

        val activeList = _layersList.value.filter { it.isVisible }
        for (layer in activeList) {
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            }

            // Map standard blends to Android PorterDuff / BlendModes
            val blendModeAndroid = when (layer.blendMode) {
                "MULTIPLY" -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                "SCREEN" -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                "DARKEN" -> PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                "LIGHTEN" -> PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                "OVERLAY" -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                else -> null
            }
            if (blendModeAndroid != null) {
                paint.setXfermode(blendModeAndroid)
            }

            val matrix = Matrix().apply {
                postTranslate(layer.offsetX, layer.offsetY)
                postScale(layer.scale, layer.scale, layer.offsetX + layer.bitmap.width/2f, layer.offsetY + layer.bitmap.height/2f)
            }

            canvas.drawBitmap(layer.bitmap, matrix, paint)
        }

        return composed
    }

    fun exportCompositedImage(context: Context, formatName: String): Uri? { // "PNG", "JPEG", "WEBP"
        val bmp = renderCompositedCanvas()
        val suffix = formatName.lowercase()
        val file = File(context.cacheDir, "export_${System.currentTimeMillis()}.$suffix")

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
            val compressFormat = when (formatName) {
                "JPEG" -> Bitmap.CompressFormat.JPEG
                "WEBP" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            fos?.let { bmp.compress(compressFormat, 100, it) }
            return Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            fos?.close()
        }
        return null
    }

    // Clean up memory
    override fun onCleared() {
        super.onCleared()
        // recycle bitmaps
        _layersList.value.forEach {
            it.bitmap.recycle()
        }
        _rawImportingBitmap.value?.recycle()
        _cameraRawPreview.value?.recycle()
        _selectionMask.value?.recycle()
    }
}
