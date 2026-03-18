package com.AiFat.KissYrDevice.triggers

import android.content.Context
import android.util.Log

class TriggerManager(private val context: Context) {
    private val activeTriggers = mutableMapOf<FaceGesture, MutableList<FaceTrigger>>()

    fun addTrigger(trigger: FaceTrigger) {
        val list = activeTriggers.getOrPut(trigger.gesture) { mutableListOf() }
        // 确保同名触发器只存在一个，防止重复添加导致的触发过频
        list.removeIf { it.name == trigger.name }
        list.add(trigger)
        Log.d("TriggerManager", "Trigger added: ${trigger.name} for ${trigger.gesture}")
    }

    fun removeTrigger(gesture: FaceGesture, triggerName: String) {
        activeTriggers[gesture]?.removeIf { it.name == triggerName }
        Log.d("TriggerManager", "Trigger removed: $triggerName")
    }

    fun onGestureDetected(gesture: FaceGesture) {
        activeTriggers[gesture]?.forEach { it.onTrigger(context) }
    }
}
