package com.alian.assistant.presentation.ui.screens.videocall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.ByteArrayOutputStream

/**
 * 相机预览组件
 * 使用 Camera2 API 实现相机预览和帧捕获
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    enable: Boolean = true,
    hasPermission: Boolean = false,
    onFrameCaptured: (Bitmap) -> Unit = {},
    onCameraReady: (Boolean) -> Unit = {}
) {
    Log.d("CameraPreview", "[DEBUG] CameraPreview Composable 被调用, enable=$enable, hasPermission=$hasPermission")
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var imageReader by remember { mutableStateOf<ImageReader?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }  // 保存 Surface 引用
    var processingThread by remember { mutableStateOf<HandlerThread?>(null) }  // 保存图像处理线程

    val handlerThread = remember { HandlerThread("CameraThread") }
    var handler by remember { mutableStateOf<Handler?>(null) }

    // 跟踪 SurfaceTexture 的更新次数
    var surfaceUpdateCount by remember { mutableStateOf(0) }
    var surfaceUpdateCountInitialized by remember { mutableStateOf(false) }

    // 启动 HandlerThread
    LaunchedEffect(Unit) {
        Log.d("CameraPreview", "[DEBUG] CameraPreview 组件初始化开始, enable=$enable, hasPermission=$hasPermission")
        if (handlerThread.state == Thread.State.NEW) {
            handlerThread.start()
            handler = Handler(handlerThread.looper)
            Log.d("CameraPreview", "[DEBUG] HandlerThread 已启动: ${handlerThread.name}")
        }
    }

    // 监听 enable 变化，管理相机打开/关闭
    LaunchedEffect(enable, hasPermission) {
        Log.d("CameraPreview", "[DEBUG] LaunchedEffect: enable=$enable, hasPermission=$hasPermission, cameraDevice=${cameraDevice != null}")
        if (enable && hasPermission && cameraDevice == null) {
            val currentHandler = handler
            if (currentHandler != null) {
                Log.d("CameraPreview", "[DEBUG] LaunchedEffect: 打开相机")
                openCamera(
                    context,
                    currentHandler,
                    surface,
                    onFrameCaptured,
                    onCameraReady,
                    onCameraOpened = { device ->
                        cameraDevice = device
                    },
                    onSessionCreated = { session, reader, thread ->
                        captureSession = session
                        imageReader = reader
                        processingThread = thread
                    }
                )
            }
        } else if (!enable && cameraDevice != null) {
            Log.d("CameraPreview", "[DEBUG] LaunchedEffect: 关闭相机")
            closeCamera(cameraDevice, captureSession, imageReader, processingThread)
            cameraDevice = null
            captureSession = null
            imageReader = null
            processingThread = null
        } else if (!enable) {
            Log.d("CameraPreview", "[DEBUG] LaunchedEffect: enable=false 但 cameraDevice=null，无需关闭")
        }
    }

    // 监听生命周期事件，管理相机生命周期
    DisposableEffect(Unit) {
        Log.d("CameraPreview", "[DEBUG] DisposableEffect 初始化")

        val observer = LifecycleEventObserver { _, event ->
            Log.d("CameraPreview", "[DEBUG] 生命周期事件: $event, enable=$enable, hasPermission=$hasPermission")
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 页面恢复时，如果 enable=true 且相机未打开，尝试打开相机
                    // 注意：这里不设置 isCameraOpenedByLifecycle 标志，避免与 LaunchedEffect 的逻辑冲突
                    if (enable && hasPermission && cameraDevice == null) {
                        Log.d("CameraPreview", "[DEBUG] ON_RESUME: 尝试打开相机")
                        val currentHandler = handler
                        if (currentHandler != null) {
                            openCamera(
                                context,
                                currentHandler,
                                surface,
                                onFrameCaptured,
                                onCameraReady,
                                onCameraOpened = { device ->
                                    cameraDevice = device
                                },
                                onSessionCreated = { session, reader, thread ->
                                    captureSession = session
                                    imageReader = reader
                                    processingThread = thread
                                }
                            )
                        }
                    }
                }

                // 移除 ON_PAUSE 处理，相机的开关完全由 enable 参数控制
                // 这样可以避免页面因其他原因（如键盘弹出、焦点变化）触发 ON_PAUSE 时意外关闭相机

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d("CameraPreview", "[DEBUG] DisposableEffect 清理")
            lifecycleOwner.lifecycle.removeObserver(observer)
            closeCamera(cameraDevice, captureSession, imageReader, processingThread)
            cameraDevice = null
            captureSession = null
            imageReader = null
            processingThread = null
        }
    }

    // 管理 HandlerThread 生命周期（只在组件销毁时清理）
    DisposableEffect(Unit) {
        Log.d("CameraPreview", "[DEBUG] HandlerThread DisposableEffect 初始化")
        onDispose {
            Log.d("CameraPreview", "[DEBUG] 组件销毁，退出 HandlerThread")
            handlerThread.quitSafely()
        }
    }

    AndroidView(
            factory = { ctx ->
                Log.d("CameraPreview", "[DEBUG] AndroidView factory 创建")
                TextureView(ctx).apply {
                    Log.d("CameraPreview", "[DEBUG] TextureView 已创建")
                    // 设置 layoutParams 确保填满父容器
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 确保视图可见
                    visibility = View.VISIBLE

                    // 添加布局监听器，确保视图始终填满父容器
                    viewTreeObserver.addOnGlobalLayoutListener(
                        object : ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                if (viewTreeObserver.isAlive) {
                                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                                }
                                // 确保 layoutParams 正确
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                Log.d("CameraPreview", "[DEBUG] TextureView 布局完成: ${width}x${height}")
                            }
                        }
                    )

                    // 配置 TextureView
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            Log.d(
                                "CameraPreview",
                                "[DEBUG] SurfaceTexture 已创建: ${width}x${height}"
                            )

                            // 获取相机支持的预览尺寸
                            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice?.id ?: "0")
                            val streamConfigurationMap =
                                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            val previewSizes = streamConfigurationMap?.getOutputSizes(SurfaceHolder::class.java)

                            // 选择合适的预览尺寸
                            val targetWidth = 1280
                            val targetHeight = 720
                            val previewSize = previewSizes?.let { sizes ->
                                sizes.minByOrNull { size ->
                                    val widthDiff = Math.abs(size.width - targetWidth)
                                    val heightDiff = Math.abs(size.height - targetHeight)
                                    widthDiff + heightDiff
                                }
                            } ?: previewSizes?.firstOrNull()

                            // 使用相机支持的预览尺寸设置 SurfaceTexture 的默认缓冲区大小
                            val bufferWidth = previewSize?.width ?: 1280
                            val bufferHeight = previewSize?.height ?: 720
                            surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
                            Log.d("CameraPreview", "[DEBUG] SurfaceTexture 默认缓冲区大小已设置为: ${bufferWidth}x${bufferHeight}")

                            val previewSurface = Surface(surfaceTexture)
                            surface = previewSurface  // 保存 Surface 引用
                            val currentHandler = handler
                            if (hasPermission && currentHandler != null) {
                                if (cameraDevice != null) {
                                    Log.d("CameraPreview", "[DEBUG] 相机已打开，立即启动预览会话")
                                    Log.d("CameraPreview", "[DEBUG] previewSurface: isValid=${previewSurface.isValid}")
                                    startPreviewSession(
                                        ctx,
                                        cameraDevice!!,
                                        previewSurface,
                                        currentHandler,
                                        onFrameCaptured,
                                        onCameraReady,
                                        onSessionCreated = { session, reader, thread ->
                                            captureSession = session
                                            imageReader = reader
                                            processingThread = thread
                                        }
                                    )
                                } else {
                                    Log.d(
                                        "CameraPreview",
                                        "[DEBUG] 相机未打开，等待相机打开后启动预览"
                                    )
                                    // SurfaceTexture 已创建，但相机还没打开，等待相机打开
                                    // 注意：openCamera 函数会在相机打开后检查 surface 是否已创建
                                    // 如果 surface 已创建，会自动调用 startPreviewSession
                                }
                            } else {
                                Log.w(
                                    "CameraPreview",
                                    "[DEBUG] SurfaceTexture 已创建但无法启动预览: hasPermission=$hasPermission, handler=${currentHandler != null}"
                                )
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            Log.d(
                                "CameraPreview",
                                "[DEBUG] SurfaceTexture 尺寸变化: ${width}x${height}"
                            )
                            // SurfaceTexture 尺寸变化时，暂时不重新配置预览会话
                            // 因为频繁的尺寸变化会导致预览不稳定
                            // 只记录日志，不进行任何操作
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            Log.d("CameraPreview", "[DEBUG] SurfaceTexture 已销毁")
                            surface = null  // 清除 Surface 引用
                            // SurfaceTexture 销毁时停止预览
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                            // SurfaceTexture 更新时，这是正常的
                            val mainHandler = Handler(Looper.getMainLooper())
                            mainHandler.post {
                                if (!surfaceUpdateCountInitialized) {
                                    surfaceUpdateCount = 0
                                    surfaceUpdateCountInitialized = true
                                    Log.d("CameraPreview", "[DEBUG] SurfaceTexture 首次更新")
                                }
                                surfaceUpdateCount++
                                if (surfaceUpdateCount <= 10) {
                                    // 前 10 次更新都打印日志
                                    Log.d("CameraPreview", "[DEBUG] SurfaceTexture 已更新: updateCount=$surfaceUpdateCount")
                                } else if (surfaceUpdateCount % 30 == 0) {
                                    // 之后每 30 次更新打印一次日志
                                    Log.d("CameraPreview", "[DEBUG] SurfaceTexture 已更新: updateCount=$surfaceUpdateCount")
                                }
                            }
                        }
                    }
                }
            },
            update = { surfaceView ->
                Log.d("CameraPreview", "[DEBUG] AndroidView update 被调用, enable=$enable")
                Log.d("CameraPreview", "[DEBUG] surfaceView 尺寸: ${surfaceView.width}x${surfaceView.height}")
                // 确保 layoutParams 始终正确
                if (surfaceView.layoutParams == null ||
                    surfaceView.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                    surfaceView.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    surfaceView.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    Log.d("CameraPreview", "[DEBUG] layoutParams 已更新")
                }
                // 请求重新布局
                surfaceView.requestLayout()
            },
            modifier = modifier
        )
}

/**
 * 打开相机
 */
private fun openCamera(
    context: Context,
    handler: Handler,
    surface: Surface?,
    onFrameCaptured: (Bitmap) -> Unit,
    onCameraReady: (Boolean) -> Unit,
    onCameraOpened: (CameraDevice) -> Unit,
    onSessionCreated: (CameraCaptureSession, ImageReader, HandlerThread) -> Unit
) {
    Log.d("CameraPreview", "[DEBUG] openCamera 开始")

    // 检查相机权限
    val hasPermission = ActivityCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasPermission) {
        Log.e("CameraPreview", "[DEBUG] 没有相机权限，无法打开相机")
        return
    }

    Log.d("CameraPreview", "[DEBUG] 相机权限已授予")

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    try {
        // 获取后置相机
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            Log.d("CameraPreview", "[DEBUG] 检查相机: $id")
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            Log.d("CameraPreview", "[DEBUG] 相机 $id 的朝向: $facing")
            facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        Log.d("CameraPreview", "[DEBUG] 选择相机 ID: $cameraId")
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d("CameraPreview", "[DEBUG] 相机已打开: $cameraId")
                onCameraOpened(camera)

                // 延迟一小段时间，确保相机设备完全初始化
                handler.postDelayed({
                    try {
                        // 尝试访问相机设备来验证它是否仍然有效
                        val cameraIdCheck = camera.id
                        if (cameraIdCheck == null) {
                            Log.w("CameraPreview", "[DEBUG] 相机设备已关闭，跳过启动预览会话")
                            onCameraReady(false)
                            return@postDelayed
                        }

                        // 相机打开后，检查 Surface 是否已创建
                        if (surface != null) {
                            Log.d("CameraPreview", "[DEBUG] 相机已打开且 Surface 已创建，启动预览会话")
                            Log.d("CameraPreview", "[DEBUG] Surface 信息: isValid=${surface.isValid}")
                            startPreviewSession(
                                context,
                                camera,
                                surface,
                                handler,
                                onFrameCaptured,
                                onCameraReady,
                                onSessionCreated
                            )
                        } else {
                            Log.d("CameraPreview", "[DEBUG] 相机已打开但 Surface 未创建，等待 Surface 创建")
                            // 启动一个超时检查，如果 3 秒后 Surface 仍未创建，报告错误
                            handler.postDelayed({
                                if (surface == null) {
                                    Log.e("CameraPreview", "[DEBUG] Surface 创建超时（3秒）")
                                    onCameraReady(false)
                                }
                            }, 3000)
                        }
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "[DEBUG] 相机设备无效，跳过启动预览会话", e)
                        onCameraReady(false)
                    }
                }, 100)  // 延迟 100ms
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w("CameraPreview", "[DEBUG] 相机已断开")
                camera.close()
                onCameraReady(false)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("CameraPreview", "[DEBUG] 相机错误: $error")
                camera.close()
                onCameraReady(false)
            }
        }, handler)
    } catch (e: CameraAccessException) {
        Log.e("CameraPreview", "[DEBUG] 打开相机失败", e)
    }
}

/**
 * 启动预览会话
 */
private fun startPreviewSession(
    context: Context,
    cameraDevice: CameraDevice,
    surface: Surface,
    handler: Handler,
    onFrameCaptured: (Bitmap) -> Unit,
    onCameraReady: (Boolean) -> Unit,
    onSessionCreated: (CameraCaptureSession, ImageReader, HandlerThread) -> Unit
) {
    try {
        Log.d("CameraPreview", "[DEBUG] startPreviewSession 开始")
        Log.d("CameraPreview", "[DEBUG] 传入的 Surface: isValid=${surface.isValid}")
        Log.d("CameraPreview", "[DEBUG] Surface 是否为 null: ${surface == null}")
        Log.d("CameraPreview", "[DEBUG] Surface class: ${surface?.javaClass?.name}")
        val startTime = System.currentTimeMillis()

        // 验证相机设备是否仍然打开
        try {
            val cameraIdCheck = cameraDevice.id
            if (cameraIdCheck == null) {
                Log.e("CameraPreview", "[DEBUG] 相机设备已关闭，无法启动预览会话")
                onCameraReady(false)
                return
            }
        } catch (e: Exception) {
            Log.e("CameraPreview", "[DEBUG] 相机设备无效，无法启动预览会话", e)
            onCameraReady(false)
            return
        }

        // 创建 ImageReader 用于捕获帧
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
        val streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // 获取支持的预览尺寸
        val previewSizes = streamConfigurationMap?.getOutputSizes(SurfaceHolder::class.java)
        Log.d("CameraPreview", "[DEBUG] 支持的预览尺寸数量: ${previewSizes?.size ?: 0}")

        // 选择合适的预览尺寸（优先选择接近 1280x720 的尺寸）
        val targetWidth = 1280
        val targetHeight = 720
        val previewSize = previewSizes?.let { sizes ->
            // 先尝试找到接近目标尺寸的
            val closestSize = sizes.minByOrNull { size ->
                val widthDiff = Math.abs(size.width - targetWidth)
                val heightDiff = Math.abs(size.height - targetHeight)
                widthDiff + heightDiff
            }
            // 如果找到的尺寸太大（超过目标尺寸的 2 倍），选择较小的尺寸
            if (closestSize != null && (closestSize.width > targetWidth * 2 || closestSize.height > targetHeight * 2)) {
                // 找一个较小的尺寸
                sizes.filter { it.width <= targetWidth * 2 && it.height <= targetHeight * 2 }
                    .maxByOrNull { it.width * it.height } ?: closestSize
            } else {
                closestSize
            }
        } ?: previewSizes?.firstOrNull()

        Log.d(
            "CameraPreview",
            "[DEBUG] 选择的预览尺寸: ${previewSize?.width}x${previewSize?.height}"
        )

        // 使用 YUV_420_888 格式而不是 JPEG，更适合实时预览
        val imageReader = ImageReader.newInstance(
            previewSize?.width ?: 640,
            previewSize?.height ?: 480,
            ImageFormat.YUV_420_888,
            3
        )
        Log.d(
            "CameraPreview",
            "[DEBUG] ImageReader 已创建: ${imageReader.width}x${imageReader.height}, 格式=YUV_420_888"
        )

        var frameCount = 0
        var lastFrameTime = 0L
        val frameIntervalMs = 500L  // 500ms 捕获一帧

        // 创建一个共享的后台线程用于处理图像，避免每次都创建新线程
        val processingThread = HandlerThread("ImageProcessingThread")
        processingThread.start()
        val processingHandler = Handler(processingThread.looper)

        imageReader.setOnImageAvailableListener({ reader ->
            // 直接获取最新帧，不进行节流
            val image = try {
                reader.acquireLatestImage()
            } catch (e: IllegalStateException) {
                // 如果缓冲区满了，先丢弃旧图像再获取
                Log.w("CameraPreview", "[DEBUG] ImageReader 缓冲区已满，丢弃旧图像")
                try {
                    val oldImage = reader.acquireNextImage()
                    oldImage?.close()
                    // 再次尝试获取最新帧
                    reader.acquireLatestImage()
                } catch (e2: Exception) {
                    Log.e("CameraPreview", "[DEBUG] 获取图像失败", e2)
                    null
                }
            } catch (e: Exception) {
                Log.e("CameraPreview", "[DEBUG] 获取图像异常", e)
                null
            }
            
            if (image != null) {
                // 只在处理帧时更新计数器
                frameCount++
                lastFrameTime = System.currentTimeMillis()
                Log.d("CameraPreview", "[DEBUG] 成功获取图像 #$frameCount: ${image.width}x${image.height}")

                // 在后台线程处理图像，避免阻塞主线程
                processingHandler.post {
                    try {
                        val width = image.width
                        val height = image.height
                        val planes = image.planes

                        // 检查 planes 是否有效
                        if (planes == null || planes.size < 3) {
                            Log.w("CameraPreview", "[DEBUG] Image planes 无效: planes=${planes?.size}")
                            image.close()
                            return@post
                        }

                        // 获取 YUV 数据
                        val yBuffer = planes[0].buffer
                        val uBuffer = planes[1].buffer
                        val vBuffer = planes[2].buffer

                        // 检查 buffer 是否有效
                        if (yBuffer == null || uBuffer == null || vBuffer == null) {
                            Log.w("CameraPreview", "[DEBUG] Image buffer 无效")
                            image.close()
                            return@post
                        }

                        // 保存当前位置，稍后恢复
                        val yPosition = yBuffer.position()
                        val uPosition = uBuffer.position()
                        val vPosition = vBuffer.position()

                        val ySize = yBuffer.remaining()
                        val uSize = uBuffer.remaining()
                        val vSize = vBuffer.remaining()

                        // 检查尺寸是否有效
                        if (ySize <= 0 || uSize <= 0 || vSize <= 0) {
                            Log.w("CameraPreview", "[DEBUG] Image buffer size 无效: ySize=$ySize, uSize=$uSize, vSize=$vSize")
                            image.close()
                            return@post
                        }

                        val nv21 = ByteArray(ySize + uSize + vSize)

                        // Y 分量
                        yBuffer.position(yPosition)
                        yBuffer.get(nv21, 0, ySize)

                        // U 和 V 分量
                        val uvPixelStride = if (planes[1].pixelStride == 1) 1 else 2
                        if (uvPixelStride == 1) {
                            // 连续存储
                            uBuffer.position(uPosition)
                            uBuffer.get(nv21, ySize, uSize)
                            vBuffer.position(vPosition)
                            vBuffer.get(nv21, ySize + uSize, vSize)
                        } else {
                            // 交错存储
                            val uvBuffer = ByteArray(uSize + vSize)
                            uBuffer.position(uPosition)
                            uBuffer.get(uvBuffer, 0, uSize)
                            vBuffer.position(vPosition)
                            vBuffer.get(uvBuffer, uSize, vSize)

                            // 转换为 NV21 格式
                            var uvIndex = 0
                            for (i in 0 until uSize step 2) {
                                if (uvIndex + 1 < nv21.size - ySize) {
                                    nv21[ySize + uvIndex] = uvBuffer[i]
                                    nv21[ySize + uvIndex + 1] = uvBuffer[uSize + i]
                                    uvIndex += 2
                                }
                            }
                        }

                        // 将 NV21 转换为 Bitmap
                        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        val outputStream = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, outputStream)
                        val jpegBytes = outputStream.toByteArray()

                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bitmap != null) {
                            // 复制 Bitmap，确保传递的是独立的副本
                            val copiedBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                            // 回收原始 Bitmap
                            bitmap.recycle()

                            // 在主线程回调
                            Handler(Looper.getMainLooper()).post {
                                onFrameCaptured(copiedBitmap)
                            }
                            Log.d("CameraPreview", "[DEBUG] 捕获到帧: ${copiedBitmap.width}x${copiedBitmap.height}")
                        } else {
                            Log.w("CameraPreview", "[DEBUG] 无法解码 Bitmap")
                        }
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "[DEBUG] 处理帧失败", e)
                    } finally {
                        image.close()
                    }
                }
            }
        }, handler)

        // 创建 CaptureRequest
        Log.d("CameraPreview", "[DEBUG] 创建 CaptureRequest")
        Log.d("CameraPreview", "[DEBUG] Surface 是否有效: ${surface.isValid}")
        Log.d("CameraPreview", "[DEBUG] ImageReader Surface 是否有效: ${imageReader.surface.isValid}")

        // 验证 Surface 是否支持相机输出
        val isSurfaceSupported = streamConfigurationMap?.isOutputSupportedFor(surface) ?: false
        Log.d("CameraPreview", "[DEBUG] Surface 是否被支持: $isSurfaceSupported")

        val isImageReaderSurfaceSupported = streamConfigurationMap?.isOutputSupportedFor(imageReader.surface) ?: false
        Log.d("CameraPreview", "[DEBUG] ImageReader Surface 是否被支持: $isImageReaderSurfaceSupported")

        val captureRequest = try {
            cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(imageReader.surface)
                // 自动对焦
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                // 自动曝光
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // 自动白平衡
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                // 防抖
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
        } catch (e: CameraAccessException) {
            Log.e("CameraPreview", "[DEBUG] 创建 CaptureRequest 失败，相机可能已断开", e)
            onCameraReady(false)
            return
        }

        // 创建 CaptureSession
        Log.d("CameraPreview", "[DEBUG] 创建 CaptureSession")
        cameraDevice.createCaptureSession(
            listOf(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        Log.d("CameraPreview", "[DEBUG] CaptureSession 已配置")
                        val request = captureRequest.build()
                        Log.d("CameraPreview", "[DEBUG] CaptureRequest 已构建")
                        Log.d("CameraPreview", "[DEBUG] Surface 是否有效: ${surface.isValid}")
                        Log.d("CameraPreview", "[DEBUG] ImageReader Surface 是否有效: ${imageReader.surface.isValid}")

                        var previewFrameCount = 0

                        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureStarted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                timestamp: Long,
                                frameNumber: Long
                            ) {
                                previewFrameCount++
                                Log.d("CameraPreview", "[DEBUG] 预览帧捕获开始: frameNumber=$frameNumber, previewFrameCount=$previewFrameCount")
                            }

                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                // 每 30 帧打印一次日志，避免日志过多
                                if (previewFrameCount % 30 == 0) {
                                    Log.d("CameraPreview", "[DEBUG] 预览帧已完成: previewFrameCount=$previewFrameCount")
                                }
                            }

                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                Log.e("CameraPreview", "[DEBUG] 预览帧捕获失败: reason=${failure.reason}, frameNumber=${failure.frameNumber}")
                            }

                            override fun onCaptureSequenceCompleted(
                                session: CameraCaptureSession,
                                sequenceId: Int,
                                frameNumber: Long
                            ) {
                                Log.d("CameraPreview", "[DEBUG] 预览序列已完成: sequenceId=$sequenceId, frameNumber=$frameNumber")
                            }

                            override fun onCaptureSequenceAborted(
                                session: CameraCaptureSession,
                                sequenceId: Int
                            ) {
                                Log.e("CameraPreview", "[DEBUG] 预览序列被中止: sequenceId=$sequenceId")
                            }
                        }, handler)
                        Log.d("CameraPreview", "[DEBUG] setRepeatingRequest 已调用")
                        onSessionCreated(session, imageReader, processingThread)
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.d("CameraPreview", "[DEBUG] 预览会话已启动，耗时: ${elapsed}ms")
                        Log.d("CameraPreview", "[DEBUG] 预览会话已启动，等待预览显示...")
                        // 延迟 1 秒后检查预览是否正常
                        handler.postDelayed({
                            Log.d("CameraPreview", "[DEBUG] 预览会话启动 1 秒后，Surface 仍然有效: ${surface.isValid}")
                            Log.d("CameraPreview", "[DEBUG] 预览会话启动 1 秒后，已捕获帧数: $previewFrameCount")
                        }, 1000)
                        onCameraReady(true)
                    } catch (e: CameraAccessException) {
                        Log.e("CameraPreview", "[DEBUG] 启动预览失败", e)
                        onCameraReady(false)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraPreview", "[DEBUG] 配置预览会话失败")
                    Log.e("CameraPreview", "[DEBUG] Surface 是否有效: ${surface.isValid}")
                    Log.e("CameraPreview", "[DEBUG] ImageReader Surface 是否有效: ${imageReader.surface.isValid}")
                    Log.e("CameraPreview", "[DEBUG] Surface 是否为 null: ${surface == null}")
                    Log.e("CameraPreview", "[DEBUG] ImageReader Surface 是否为 null: ${imageReader.surface == null}")
                    onCameraReady(false)
                }
            },
            handler
        )
    } catch (e: CameraAccessException) {
        Log.e("CameraPreview", "[DEBUG] 启动预览会话失败", e)
    }
}

/**
 * 关闭相机
 */
private fun closeCamera(
    cameraDevice: CameraDevice?,
    captureSession: CameraCaptureSession?,
    imageReader: ImageReader?,
    processingThread: HandlerThread? = null
) {
    Log.d("CameraPreview", "[DEBUG] closeCamera 开始")
    try {
        captureSession?.close()
        Log.d("CameraPreview", "[DEBUG] CaptureSession 已关闭")
        imageReader?.close()
        Log.d("CameraPreview", "[DEBUG] ImageReader 已关闭")
        cameraDevice?.close()
        Log.d("CameraPreview", "[DEBUG] CameraDevice 已关闭")
        // 清理图像处理线程
        processingThread?.quitSafely()
        Log.d("CameraPreview", "[DEBUG] ProcessingThread 已关闭")
        Log.d("CameraPreview", "[DEBUG] 相机资源已全部释放")
    } catch (e: Exception) {
        Log.e("CameraPreview", "[DEBUG] 关闭相机失败", e)
    }
}