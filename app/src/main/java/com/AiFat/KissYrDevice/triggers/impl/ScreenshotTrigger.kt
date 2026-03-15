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

    private var lastTriggerTime = 0L
    private val cooldown = 2000L // 2 seconds cooldown
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onTrigger(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTriggerTime > cooldown) {
            lastTriggerTime = currentTime
            Log.d("ScreenshotTrigger", "Triggering screenshot action via Broadcast")
            
            // Send broadcast to ScreenshotService
            val intent = Intent(ScreenshotService.ACTION_TAKE_SCREENSHOT).apply {
                // Important: If targeting API 34+, you might need to set the package
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
