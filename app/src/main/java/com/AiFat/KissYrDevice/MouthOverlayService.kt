package com.AiFat.KissYrDevice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.AiFat.KissYrDevice.models.MouthData
import com.AiFat.KissYrDevice.triggers.FaceGesture
import com.AiFat.KissYrDevice.triggers.TriggerManager
import com.AiFat.KissYrDevice.triggers.impl.ScreenshotTrigger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MouthOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var cameraExecutor: ExecutorService
    private val mouthDataState = mutableStateOf<MouthData?>(null)
    private val screenSize = mutableStateOf(Size(0, 0))
    private val mViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private var imageAnalysis: ImageAnalysis? = null
    
    private lateinit var triggerManager: TriggerManager

    override val viewModelStore: ViewModelStore
        get() = mViewModelStore

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        Log.d("MouthOverlayService", "onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        triggerManager = TriggerManager(this)
        
        // Read the switch state from SharedPreferences
        val sharedPrefs = getSharedPreferences("KissYrSettings", Context.MODE_PRIVATE)
        val isScreenshotEnabled = sharedPrefs.getBoolean("screenshot_enabled", false)
        
        if (isScreenshotEnabled) {
            triggerManager.addTrigger(ScreenshotTrigger())
            Log.d("MouthOverlayService", "Screenshot trigger enabled from settings")
        } else {
            Log.d("MouthOverlayService", "Screenshot trigger disabled from settings")
        }
        
        updateScreenSize()
        showOverlay()
        startForegroundService()
        startCamera()
    }

    private fun updateScreenSize() {
        val metrics = resources.displayMetrics
        screenSize.value = Size(metrics.widthPixels, metrics.heightPixels)
        Log.d("MouthOverlayService", "Screen size updated: ${screenSize.value}")
    }

    @Suppress("DEPRECATION")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("MouthOverlayService", "onConfigurationChanged")
        
        updateScreenSize()
        val rotation = windowManager.defaultDisplay.rotation
        imageAnalysis?.targetRotation = rotation
        
        composeView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun startForegroundService() {
        val channelId = "mouth_overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "KissYrDevice Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("KissYrDevice Running")
            .setContentText("Tap to return to app")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    private fun showOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        composeView = ComposeView(this).apply {
            setContent {
                MouthOverlay(mouthDataState.value, screenSize.value)
            }
        }

        composeView?.setViewTreeLifecycleOwner(this)
        composeView?.setViewTreeSavedStateRegistryOwner(this)
        composeView?.setViewTreeViewModelStoreOwner(this)
        
        windowManager.addView(composeView, params)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                
                val analyzer = MediaPipeMouthAnalyzer(
                    this,
                    onGestureDetected = { gesture ->
                        triggerManager.onGestureDetected(gesture)
                    },
                    onMouthDataUpdate = { data ->
                        mouthDataState.value = data
                    }
                )

                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }

                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("MouthOverlayService", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e("MouthOverlayService", "Error unbinding", e)
        }

        super.onDestroy()
        cameraExecutor.shutdown()
        composeView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        mViewModelStore.clear()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }
}
