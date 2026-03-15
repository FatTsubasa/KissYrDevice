package com.AiFat.KissYrDevice.triggers

import android.content.Context

interface FaceTrigger {
    val gesture: FaceGesture
    val name: String
    fun onTrigger(context: Context)
}
