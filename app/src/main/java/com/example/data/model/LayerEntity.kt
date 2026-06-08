package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "layers")
data class LayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val layerIndex: Int,
    val type: String, // "IMAGE", "TEXT", "SHAPE", "COLOR_FILL"
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val blendMode: String = "NORMAL", // "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "DARKEN", "LIGHTEN"
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val extraDataJson: String = "" // Custom fields (text string, color, shapes dynamic lists)
)
