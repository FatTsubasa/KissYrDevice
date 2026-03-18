package com.AiFat.KissYrDevice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.AiFat.KissYrDevice.models.MouthData
import com.AiFat.KissYrDevice.triggers.FaceGesture
import com.AiFat.KissYrDevice.triggers.TriggerManager
import com.AiFat.KissYrDevice.triggers.impl.ScreenshotTrigger
import com.AiFat.KissYrDevice.ui.theme.KissYrDeviceTheme
import com.AiFat.KissYrDevice.utils.PermissionUtils
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var isServiceRunning = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        stopOverlayService()

        setContent {
            KissYrDeviceTheme {
                MainScreen(cameraExecutor, isServiceRunning)
            }
        }
    }

    private fun stopOverlayService() {
        stopService(Intent(this, MouthOverlayService::class.java))
        isServiceRunning.value = false
    }

    override fun onResume() {
        super.onResume()
        stopOverlayService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopOverlayService()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun MainScreen(cameraExecutor: ExecutorService, isServiceRunning: MutableState<Boolean>) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("KissYrSettings", Context.MODE_PRIVATE) }
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (hasCameraPermission) {
                FloatingActionButton(
                    onClick = {
                        // Check for accessibility permission first
                        if (!PermissionUtils.isAccessibilityServiceEnabled(context, ScreenshotService::class.java)) {
                            // Jump directly to accessibility settings
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                            Toast.makeText(context, "请在「已安装的应用」或「下载的服务」中开启 KissYrDevice 截屏助手", Toast.LENGTH_LONG).show()
                            return@FloatingActionButton
                        }

                        if (Settings.canDrawOverlays(context)) {
                            val intent = Intent(context, MouthOverlayService::class.java)
                            ContextCompat.startForegroundService(context, intent)
                            isServiceRunning.value = true
                            (context as? ComponentActivity)?.moveTaskToBack(true)
                        } else {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    }
                ) {
                    Text("后台运行")
                }
            }
        }
    ) { innerPadding ->
        if (hasCameraPermission) {
            // Only show camera content if service is NOT running
            if (!isServiceRunning.value) {
                FaceDetectionContent(
                    modifier = Modifier.padding(innerPadding),
                    cameraExecutor = cameraExecutor,
                    onScreenshotStatusChanged = { isEnabled ->
                        sharedPrefs.edit().putBoolean("screenshot_enabled", isEnabled).apply()
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Service is running...")
                }
            }
        } else {
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                Text(text = "Please grant camera permission")
            }
        }
    }
}

@Composable
fun FaceDetectionContent(
    modifier: Modifier = Modifier, 
    cameraExecutor: ExecutorService,
    onScreenshotStatusChanged: (Boolean) -> Unit
) {
    var mouthData by remember { mutableStateOf<MouthData?>(null) }
    var previewSize by remember { mutableStateOf(Size(0, 0)) }
    
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("KissYrSettings", Context.MODE_PRIVATE) }
    val triggerManager = remember { TriggerManager(context) }
    
    // Initialize state from SharedPreferences
    var isScreenshotEnabled by remember { 
        mutableStateOf(sharedPrefs.getBoolean("screenshot_enabled", false)) 
    }

    LaunchedEffect(isScreenshotEnabled) {
        if (isScreenshotEnabled) {
            triggerManager.addTrigger(ScreenshotTrigger())
        } else {
            triggerManager.removeTrigger(FaceGesture.PUCKER, "Screenshot")
        }
        onScreenshotStatusChanged(isScreenshotEnabled)
    }

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            onGestureDetected = { gesture ->
                triggerManager.onGestureDetected(gesture)
            },
            onMouthDataUpdate = { data -> mouthData = data },
            onPreviewSizeChanged = { size -> previewSize = size },
            cameraExecutor = cameraExecutor
        )
        MouthOverlay(mouthData, previewSize)

        // UI for triggering screenshot
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isScreenshotEnabled,
                onCheckedChange = { isScreenshotEnabled = it }
            )
            Text("嘟嘴截屏", color = Color.White)
        }
    }
}

@Composable
fun CameraPreview(
    onGestureDetected: (FaceGesture) -> Unit,
    onMouthDataUpdate: (MouthData) -> Unit,
    onPreviewSizeChanged: (Size) -> Unit,
    cameraExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx)
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, MediaPipeMouthAnalyzer(
                            context,
                            onGestureDetected = onGestureDetected,
                            onMouthDataUpdate = onMouthDataUpdate
                        ))
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    previewView.post {
                        onPreviewSizeChanged(Size(previewView.width, previewView.height))
                    }
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = Modifier.fillMaxSize()
    )
}

class MediaPipeMouthAnalyzer(
    context: Context,
    private val onGestureDetected: (FaceGesture) -> Unit = {},
    private val onMouthDataUpdate: (MouthData) -> Unit
) : ImageAnalysis.Analyzer {

    private var faceLandmarker: FaceLandmarker? = null
    private var isPuckeringLastFrame = false
    private var puckerFrameCount = 0

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setOutputFaceBlendshapes(true) // Required for mouthPucker
                .setResultListener { result, _ ->
                    processResult(result)
                }
                .setErrorListener { error ->
                    Log.e("MediaPipe", "Error: ${error.message}")
                }
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("MediaPipe", "Initialization failed", e)
        }
    }

    private var lastRotation = 0

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        lastRotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = imageProxy.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker?.detectAsync(mpImage, imageProxy.imageInfo.timestamp / 1_000_000)
        imageProxy.close()
    }

    private fun processResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isNotEmpty()) {
            val landmarks = result.faceLandmarks()[0]
            
            // Extract blendshapes for puckering detection
            var isPuckeringThisFrame = false
            val blendshapes = result.faceBlendshapes()
            if (blendshapes.isPresent) {
                val shapesList = blendshapes.get()
                if (shapesList.isNotEmpty()) {
                    for (shape in shapesList[0]) {
                        // 提高阈值，只有当深度撅嘴（分值 > 0.9）时才判定为 Pucker
                        if (shape.categoryName() == "mouthPucker" && shape.score() > 0.9f) {
                            isPuckeringThisFrame = true
                            break
                        }
                    }
                }
            }
            
            if (isPuckeringThisFrame) {
                puckerFrameCount++
            } else {
                puckerFrameCount = 0
            }

            // 只有当持续 3 帧以上判定为撅嘴，且上一逻辑帧未判定为撅嘴时，才触发动作
            val isPuckeringValidated = puckerFrameCount >= 3
            
            if (isPuckeringValidated && !isPuckeringLastFrame) {
                onGestureDetected(FaceGesture.PUCKER)
            }
            isPuckeringLastFrame = isPuckeringValidated
            
            val upperLipTopIndices = listOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291)
            val upperLipBottomIndices = listOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308)
            val lowerLipTopIndices = listOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308)
            val lowerLipBottomIndices = listOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291)

            fun mapIndices(indices: List<Int>) = indices.map { i ->
                val lm = landmarks[i]
                Offset(lm.x(), lm.y())
            }

            onMouthDataUpdate(MouthData(
                upperLipTop = mapIndices(upperLipTopIndices),
                upperLipBottom = mapIndices(upperLipBottomIndices),
                lowerLipTop = mapIndices(lowerLipTopIndices),
                lowerLipBottom = mapIndices(lowerLipBottomIndices),
                rotation = lastRotation,
                isPuckering = isPuckeringValidated
            ))
        }
    }
}

@Composable
fun MouthOverlay(mouthData: MouthData?, previewSize: Size) {
    val context = LocalContext.current
    val showDetect = 1

    fun getCroppedBitmap(resId: Int): Bitmap {
        val original = BitmapFactory.decodeResource(context.resources, resId)
        var l = original.width; var r = 0; var t = original.height; var b = 0
        for (y in 0 until original.height) {
            for (x in 0 until original.width) {
                if (((original.getPixel(x, y) shr 24) and 0xFF) > 5) {
                    if (x < l) l = x; if (x > r) r = x
                    if (y < t) t = y; if (y > b) b = y
                }
            }
        }
        return if (r < l) original else Bitmap.createBitmap(original, l, t, r - l + 1, b - t + 1)
    }

    fun interpolate(points: List<Offset>, fraction: Float): Offset {
        val size = points.size - 1
        if (size < 0) return Offset.Zero
        if (size == 0) return points[0]
        val rawIdx = fraction * size
        val idx = rawIdx.toInt()
        val f = rawIdx - idx
        val p1 = points[idx]
        val p2 = points[if (idx < size) idx + 1 else size]
        return Offset(p1.x + (p2.x - p1.x) * f, p1.y + (p2.y - p1.y) * f)
    }

    val mouthUp = remember { getCroppedBitmap(R.drawable.mouth01_up) }
    val mouthDown = remember { getCroppedBitmap(R.drawable.mouth01_down) }
    
    // Heart animation drawable
    val heartDrawable = remember {
        ContextCompat.getDrawable(context, R.drawable.heart)?.apply {
            callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {}
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
                override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
            }
        }
    }

    // Manual frame invalidation for animated WebP
    var lastFrameTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mouthData?.isPuckering) {
        if (mouthData?.isPuckering == true) {
            (heartDrawable as? Animatable)?.start()
            while(true) {
                withFrameNanos { lastFrameTime = it }
            }
        } else {
            (heartDrawable as? Animatable)?.stop()
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (mouthData != null && previewSize.width > 0 && previewSize.height > 0) {
            
            fun mapPoint(p: Offset): Offset {
                var x = p.x
                var y = p.y
                when (mouthData.rotation) {
                    90 -> { val oldX = x; x = 1f - y; y = oldX }
                    270 -> { val oldX = x; x = y; y = 1f - oldX }
                    180 -> { x = 1f - x; y = 1f - y }
                }
                x = 1f - x
                return Offset(x * previewSize.width, y * previewSize.height)
            }

            if (showDetect == 1) {
                fun List<Offset>.drawLipPoints(color: Color) {
                    for (point in this) {
                        val m = mapPoint(point)
                        drawRect(color = color, topLeft = Offset(m.x - 2.5f, m.y - 2.5f), size = androidx.compose.ui.geometry.Size(5f, 5f))
                    }
                }
                mouthData.upperLipTop.drawLipPoints(Color.Red)
                mouthData.upperLipBottom.drawLipPoints(Color.Red)
                mouthData.lowerLipTop.drawLipPoints(Color.Blue)
                mouthData.lowerLipBottom.drawLipPoints(Color.Blue)
            }

            drawIntoCanvas { canvas ->
                val meshW = 20
                val upVerts = FloatArray((meshW + 1) * 2 * 2)
                for (i in 0..meshW) {
                    val f = i.toFloat() / meshW
                    val pT = mapPoint(interpolate(mouthData.upperLipTop, f))
                    val pB = mapPoint(interpolate(mouthData.upperLipBottom, f))
                    upVerts[i * 2] = pT.x; upVerts[i * 2 + 1] = pT.y
                    upVerts[(meshW + 1) * 2 + i * 2] = pB.x; upVerts[(meshW + 1) * 2 + i * 2 + 1] = pB.y
                }
                canvas.nativeCanvas.drawBitmapMesh(mouthUp, meshW, 1, upVerts, 0, null, 0, null)

                val downVerts = FloatArray((meshW + 1) * 2 * 2)
                for (i in 0..meshW) {
                    val f = i.toFloat() / meshW
                    val pT = mapPoint(interpolate(mouthData.lowerLipTop, f))
                    val pB = mapPoint(interpolate(mouthData.lowerLipBottom, f))
                    downVerts[i * 2] = pT.x; downVerts[i * 2 + 1] = pT.y
                    downVerts[(meshW + 1) * 2 + i * 2] = pB.x; downVerts[(meshW + 1) * 2 + i * 2 + 1] = pB.y
                }
                canvas.nativeCanvas.drawBitmapMesh(mouthDown, meshW, 1, downVerts, 0, null, 0, null)

                if (mouthData.isPuckering && heartDrawable != null) {
                    val mappedPoints = mouthData.upperLipTop.map { mapPoint(it) }
                    val rightPoint = mappedPoints.maxByOrNull { it.x } ?: Offset.Zero
                    val topPoint = mappedPoints.minByOrNull { it.y } ?: Offset.Zero
                    
                    val heartSize = 300
                    val heartX = rightPoint.x.toInt()
                    val heartY = (topPoint.y - heartSize).toInt()
                    
                    heartDrawable.setBounds(heartX, heartY, heartX + heartSize, heartY + heartSize)
                    val dummy = lastFrameTime 
                    heartDrawable.draw(canvas.nativeCanvas)
                }
            }
        }
    }
}
