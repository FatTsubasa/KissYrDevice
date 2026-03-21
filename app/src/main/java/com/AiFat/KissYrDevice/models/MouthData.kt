package com.AiFat.KissYrDevice.models

import androidx.compose.ui.geometry.Offset

data class MouthData(
    val upperLipTop: List<Offset>,
    val upperLipBottom: List<Offset>,
    val lowerLipTop: List<Offset>,
    val lowerLipBottom: List<Offset>,
    val rotation: Int,
    val isPuckering: Boolean = false,
    val faceWidth: Float = 0f
)
