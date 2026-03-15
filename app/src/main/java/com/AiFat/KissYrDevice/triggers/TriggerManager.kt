package com.AiFat.KissYrDevice.triggers

import android.content.Context
import android.util.Log

class TriggerManager(private val context: Context) {
    private val activeTriggers = mutableMapOf<FaceGesture, MutableList<FaceTrigger>>()

    fun addTrigger(trigger: FaceTrigger) {
        activeTriggers.getOrPut(trigger.gesture) { mutableListOf() }.add(trigger)
    }

    fun removeTrigger(gesture: FaceGesture, triggerName: String) {
        activeTriggers[gesture]?.removeIf { it.name == triggerName }
    }

    fun onGestureDetected(gesture: FaceGesture) {
        Log.d("TriggerManager", "Gesture detected: $gesture")
        activeTriggers[gesture]?.forEach { it.onTrigger(context) }
    }
}
