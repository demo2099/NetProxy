package com.interstellar.proxy.ui.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.interstellar.proxy.utils.QrDecoder
import com.interstellar.proxy.utils.ScanPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码导入订阅 —— 和小火箭的「扫一扫」对齐：把码放进框里就自动识别并导入。
 *
 * 支持三种来源：
 *   1. 实时相机（CameraX + ZXing，离线，不依赖 Google Play 服务）
 *   2. 相册里的二维码图片（系统选择器，不需要存储权限）
 *   3. 权限被拒时给出明确指引，而不是卡在黑色取景框
 */
@Composable
fun QrScanDialog(
    onDismiss: () -> Unit,
    onScanned: (ScanPayload) -> Unit,
) {
    val context = LocalContext.current
    // 手电筒按钮画在遮罩之上，所以相机实例要提升到这一层
    var camera by remember { mutableStateOf<Camera?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var denied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        denied = !granted
    }

    // 打开就申请：多一步「点按钮才申请」会让用户以为坏了
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (hasPermission) {
                CameraScanner(
                    onFound = { text ->
                        // 认不出来也要回到上层，让对话框把原因显示出来（不能静默丢弃）
                        onScanned(ScanPayload.parse(text))
                    },
                    onCamera = { camera = it },
                )
            } else {
                PermissionHint(
                    denied = denied,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                            )
                        }
                    },
                )
            }

            ScanOverlay(
                active = hasPermission,
                camera = camera,
                onClose = onDismiss,
                onScanned = onScanned,
            )
        }
    }
}

@Composable
private fun PermissionHint(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (denied) "没有相机权限" else "需要相机权限",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (denied) {
                "扫码要用相机。如果刚才点了「禁止」，需要到系统设置里手动打开。"
            } else {
                "扫订阅二维码需要打开相机，仅在前台取景时使用。"
            },
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable { onRequest() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("重新授权", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("去设置", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

/** 取景 + 逐帧解码。识别到一帧就立刻回调，并且只回调一次。 */
@Composable
private fun CameraScanner(onFound: (String) -> Unit, onCamera: (Camera) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hit = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE = TextureView：在 Compose 里叠层/裁剪最稳，部分机型用 SurfaceView 会盖住浮层
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    // 只保留最新一帧：解码慢的时候不要把帧堆起来（越堆越卡、越扫不出）
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val text = QrDecoder.decode(image)
                        if (!text.isNullOrBlank() && hit.compareAndSet(false, true)) {
                            onFound(text)
                        }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                onCamera(bound)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdownNow()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** 取景框 + 说明 + 关闭按钮。单独抽出来，权限被拒时也能显示标题栏。 */
@Composable
private fun ScanOverlay(
    active: Boolean,
    camera: Camera?,
    onClose: () -> Unit,
    onScanned: (ScanPayload) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    // 相册兜底：系统选择器，不申请 READ_MEDIA_IMAGES（Android 13+ 权限太碎，不值得）
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        picking = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream) ?: return@use null
                        QrDecoder.decode(bitmap).also { bitmap.recycle() }
                    }
                }.getOrNull()
            }
            picking = false
            if (!text.isNullOrBlank()) {
                onClose()
                onScanned(ScanPayload.parse(text))
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "scanline")
    val lineProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanline-progress",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩 + 四角括号 + 扫描线
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = minOf(size.width * 0.74f, size.height * 0.46f)
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f - size.height * 0.04f
            val box = Rect(left, top, left + side, top + side)

            // 框外压暗：Path 的 EvenOdd 规则挖洞，比四个矩形拼更干净
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(
                    RoundRect(box, CornerRadius(side * 0.06f, side * 0.06f))
                )
            }
            drawPath(mask, Color.Black.copy(alpha = 0.5f))

            // 四角括号
            val arm = side * 0.16f
            val stroke = 4.dp.toPx()
            val accent = Color.White
            fun bracket(cx: Float, cy: Float, dx: Float, dy: Float) {
                drawLine(accent, Offset(cx, cy), Offset(cx + dx * arm, cy), stroke, StrokeCap.Round)
                drawLine(accent, Offset(cx, cy), Offset(cx, cy + dy * arm), stroke, StrokeCap.Round)
            }
            bracket(box.left, box.top, 1f, 1f)
            bracket(box.right, box.top, -1f, 1f)
            bracket(box.left, box.bottom, 1f, -1f)
            bracket(box.right, box.bottom, -1f, -1f)

            // 扫描线
            if (active) {
                val y = box.top + side * (0.08f + 0.84f * lineProgress)
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(box.left + side * 0.06f, y),
                    end = Offset(box.right - side * 0.06f, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // 顶部：关闭 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "扫描订阅二维码",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 底部：说明 + 相册
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 96.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "把二维码放进框里，识别后会自动导入订阅",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 手电筒：很多码印在暗处 / 对着另一块屏幕拍，没有补光基本扫不出来
                if (active) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable {
                                val next = !torchOn
                                torchOn = next
                                camera?.cameraControl?.enableTorch(next)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(enabled = !picking) { pickLauncher.launch("image/*") }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (picking) "正在识别…" else "从相册选择",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
