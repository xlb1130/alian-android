package com.alian.assistant.presentation.ui.screens.phonecall

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alian.assistant.core.agent.PhoneCallMessage
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.FloatingWindowState
import com.alian.assistant.presentation.viewmodel.PhoneCallState
import com.alian.assistant.presentation.viewmodel.PhoneCallViewModel
import kotlin.math.abs

/**
 * 手机通话浮动窗口视图
 * 
 * 使用 WindowManager 创建真正的系统级浮动窗口
 */
class PhoneCallFloatingWindowView(
    private val context: Context,
    private val viewModel: PhoneCallViewModel,
    private val onClose: () -> Unit
) {
    companion object {
        private const val TAG = "PhoneCallFloatingWindow"
    }

    private val overlayContext = context.applicationContext
    private val windowManager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingView: View? = null

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * 显示浮动窗口
     */
    @SuppressLint("ClickOnUiThread")
    fun show() {
        if (floatingView != null) {
            Log.w(TAG, "浮动窗口已存在")
            return
        }

        Log.d(TAG, "显示浮动窗口")

        // 创建一个永远存活的生命周期所有者
        val lifecycleOwner = object : LifecycleOwner,
            ViewModelStoreOwner {
            private val _lifecycle = LifecycleRegistry(this).apply {
                // 先设置为 INITIALIZED 状态，以便 SavedStateRegistry 可以正确初始化
                currentState = Lifecycle.State.INITIALIZED
            }
            private val _viewModelStore = ViewModelStore()

            override val lifecycle: Lifecycle
                get() = _lifecycle

            override val viewModelStore: ViewModelStore
                get() = _viewModelStore
        }

        // 创建一个简单的 SavedStateRegistryOwner
        val savedStateRegistryOwner = object : SavedStateRegistryOwner {
            private val _savedStateRegistryController = SavedStateRegistryController.create(this)

            override val lifecycle: Lifecycle
                get() = lifecycleOwner.lifecycle

            override val savedStateRegistry: SavedStateRegistry
                get() = _savedStateRegistryController.savedStateRegistry

            fun performRestore(savedState: Bundle?) {
                _savedStateRegistryController.performRestore(savedState)
            }
        }

        // 初始化 SavedStateRegistry（必须在生命周期处于 INITIALIZED 状态时调用）
        savedStateRegistryOwner.performRestore(null)

        // 完成初始化后，将生命周期设置为 RESUMED
        (lifecycleOwner.lifecycle as LifecycleRegistry).handleLifecycleEvent(
            Lifecycle.Event.ON_CREATE
        )
        (lifecycleOwner.lifecycle as LifecycleRegistry).handleLifecycleEvent(
            Lifecycle.Event.ON_START
        )
        (lifecycleOwner.lifecycle as LifecycleRegistry).handleLifecycleEvent(
            Lifecycle.Event.ON_RESUME
        )

        // 创建 ComposeView
        val composeView = ComposeView(overlayContext).apply {
            // 设置 ViewTreeLifecycleOwner
            this.setViewTreeLifecycleOwner(lifecycleOwner)
            // 设置 ViewTreeViewModelStoreOwner
            this.setViewTreeViewModelStoreOwner(lifecycleOwner)
            // 设置 ViewTreeSavedStateRegistryOwner
            this.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            
            setContent {
                val state by viewModel.callState.collectAsState()
                val floatingWindowState by viewModel.floatingWindowState.collectAsState()
                val windowOpacity by viewModel.floatingWindowOpacity
                val conversationHistory = viewModel.conversationHistory
                val currentRecognizedText = viewModel.currentRecognizedText.value
                val currentOperation = viewModel.currentOperation.value
                val isAiOperating = viewModel.isAiOperating.value

                BaoziTheme {
                    FloatingWindowContent(
                        state = state,
                        floatingWindowState = floatingWindowState,
                        windowOpacity = windowOpacity,
                        conversationHistory = conversationHistory,
                        currentRecognizedText = currentRecognizedText,
                        currentOperation = currentOperation,
                        isAiOperating = isAiOperating,
                        viewModel = viewModel,
                        onClose = onClose
                    )
                }
            }
        }

        // 创建布局参数
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            val savedPosition = viewModel.floatingWindowPosition.value
            x = if (savedPosition.first == 0) 100 else savedPosition.first
            y = if (savedPosition.second == 0) 200 else savedPosition.second
        }

        // 添加拖动功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 20f

        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (abs(deltaX) > dragThreshold || abs(deltaY) > dragThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + deltaX.toInt()).coerceAtLeast(0)
                        params.y = (initialY + deltaY.toInt()).coerceAtLeast(0)
                        windowManager.updateViewLayout(composeView, params)
                        // 更新 ViewModel 中的位置
                        viewModel.updateFloatingWindowPosition(params.x, params.y)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val consumed = isDragging
                    isDragging = false
                    consumed
                }
                else -> false
            }
        }

        // 添加到窗口管理器
        try {
            windowManager.addView(composeView, params)
            floatingView = composeView
        } catch (e: Exception) {
            Log.e(TAG, "添加浮动窗口失败", e)
            floatingView = null
        }
    }

    /**
     * 隐藏浮动窗口
     */
    fun hide() {
        Log.d(TAG, "隐藏浮动窗口")
        runOnMainThread {
            floatingView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "移除浮动窗口失败", e)
                }
            }
            floatingView = null
        }
    }

    /**
     * 设置浮动窗口可见性（轻量级，不销毁视图）
     *
     * 用于截图保护：截图前隐藏悬浮窗，截图后恢复，
     * 避免悬浮窗遮挡屏幕内容影响 VLM 分析。
     *
     * @param visible true 显示，false 隐藏
     */
    fun setVisible(visible: Boolean) {
        runOnMainThread {
            floatingView?.let { view ->
                view.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * 更新浮动窗口
     */
    fun update() {
        // 浮动窗口会自动响应 ViewModel 状态变化，无需手动更新
    }
}

/**
 * 浮动窗口内容
 */
@Composable
private fun FloatingWindowContent(
    state: PhoneCallState,
    floatingWindowState: FloatingWindowState,
    windowOpacity: Float,
    conversationHistory: List<PhoneCallMessage>,
    currentRecognizedText: String,
    currentOperation: String,
    isAiOperating: Boolean,
    viewModel: PhoneCallViewModel,
    onClose: () -> Unit
) {
    val colors = BaoziTheme.colors
    val isMinimized = floatingWindowState is FloatingWindowState.Minimized

    // 根据状态调整窗口大小
    val windowWidth = when (floatingWindowState) {
        is FloatingWindowState.Minimized -> 200.dp
        is FloatingWindowState.Maximized -> 400.dp
        else -> 360.dp
    }

    Box(
        modifier = Modifier
            .width(windowWidth)
            .alpha(windowOpacity)
            .background(
                color = colors.backgroundCard.copy(alpha = 0.95f),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 最小化/展开按钮
                IconButton(
                    onClick = {
                        when (floatingWindowState) {
                            is FloatingWindowState.Minimized -> viewModel.showFloatingWindow()
                            else -> viewModel.minimizeFloatingWindow()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isMinimized) Icons.Default.PhoneAndroid else Icons.Default.Minimize,
                        contentDescription = if (isMinimized) "展开" else "最小化",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 标题（最小化时显示状态描述）
                Text(
                    text = if (isMinimized) viewModel.getStateDescription() else "手机通话",
                    color = colors.textPrimary,
                    fontSize = if (isMinimized) 12.sp else 14.sp,
                    fontWeight = FontWeight.Medium
                )

                // 关闭按钮
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = colors.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 最小化时只显示标题栏，不显示内容区域
            if (!isMinimized) {
                // 内容区域（仅在非最小化状态下显示）
                val contentHeight = when (floatingWindowState) {
                    is FloatingWindowState.Maximized -> 540.dp
                    else -> 480.dp
                }

                Box(
                    modifier = Modifier
                        .size(width = windowWidth, height = contentHeight)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 对话消息区域
                        Box(
                            modifier = Modifier
                                .weight(0.7f)
                                .fillMaxWidth()
                        ) {
                            PhoneCallMessageList(
                                conversationHistory = conversationHistory,
                                currentRecognizedText = currentRecognizedText,
                                colors = colors,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 操作状态栏
                        if (isAiOperating) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .background(
                                        color = Color(0xFF8B5CF6).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Color(0xFF8B5CF6),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = currentOperation,
                                        color = Color(0xFF8B5CF6),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                            // 控制按钮区域
                            PhoneCallControlBar(
                                state = state,
                                isMicrophoneEnabled = viewModel.isMicrophoneEnabled.value,
                                onHangUpClick = onClose,
                                onStartCallClick = {},
                                onToggleMicrophone = { viewModel.toggleMicrophone() },
                                onTextInputClick = {},
                                colors = colors,
                                textInputEnabled = false
                            )
                        }
                    }
                }
        }
    }
}
