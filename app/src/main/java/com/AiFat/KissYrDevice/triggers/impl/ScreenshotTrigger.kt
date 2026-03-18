package com.AiFat.KissYrDevice.triggers.impl

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.AiFat.KissYrDevice.ScreenshotService
import com.AiFat.KissYrDevice.triggers.FaceGesture
import com.AiFat.KissYrDevice.triggers.FaceTrigger

class ScreenshotTrigger : FaceTrigger {
    override val gesture: FaceGesture = FaceGesture.PUCKER
    override val name: String = "Screenshot"

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // 使用全局静态变量确保冷却时间不受对象重建影响
        private var lastTriggerTime = 0L
        private const val COOLDOWN = 2000L // 2 seconds cooldown
    }

    override fun onTrigger(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTriggerTime > COOLDOWN) {
            lastTriggerTime = currentTime
            Log.d("ScreenshotTrigger", "Triggering screenshot action via Broadcast")
            
            // Send broadcast to ScreenshotService
            val intent = Intent(ScreenshotService.ACTION_TAKE_SCREENSHOT).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            // Feedback to user
            mainHandler.post {
                Toast.makeText(context, "检测到嘟嘴：准备截屏！", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
