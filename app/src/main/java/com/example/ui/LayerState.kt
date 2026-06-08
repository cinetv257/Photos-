package com.example.ui

import android.graphics.Bitmap

data class LayerState(
    val id: Long,
    val name: String,
    val layerIndex: Int,
    val type: String, // "IMAGE", "TEXT", "SHAPE", "COLOR_FILL"
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val blendMode: String = "NORMAL", // "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "DARKEN", "LIGHTEN"
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val extraDataJson: String = "", // e.g. Text string, Shape type, or Fill color
    val bitmap: Bitmap // Live bitmap in RAM for instant rendering and local modification
)
