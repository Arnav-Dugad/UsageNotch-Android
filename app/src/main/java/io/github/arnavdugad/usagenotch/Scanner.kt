package io.github.arnavdugad.usagenotch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max

/** QR decoding with ZXing: pure Java, entirely on the phone. */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    fun decode(source: LuminanceSource): String? {
        for (candidate in listOf(source, source.invert())) {
            try { return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text } catch (_: ReaderException) { }
        }
        return null
    }
    /** A photo or screenshot; large images are scaled down so decoding stays quick. */
    fun decode(bitmap: Bitmap): String? {
        val scale = 1600f / max(bitmap.width, bitmap.height)
        val image = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        return decode(RGBLuminanceSource(image.width, image.height, pixels))
    }
    /** A camera frame: the luminance (Y) plane is all a QR code needs. */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]; val buffer = plane.buffer.duplicate().apply { rewind() }
        val stride = plane.rowStride
        val data = ByteArray(stride * image.height); buffer.get(data, 0, minOf(buffer.remaining(), data.size))
        return decode(PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false))
    }
}

/**
 * Full-screen scanner for the pairing QR code in UsageNotch for Windows (Settings → Phone). Frames are analysed in memory
 * and never stored. On success the viewfinder morphs into a pill naming the PC, then [onCode] receives the scanned text.
 */
@Composable fun ScannerScreen(reduced: Boolean, onCode: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current; val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current; val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Pairing?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    BackHandler(onBack = onClose)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    fun handle(text: String) {
        if (found != null) return
        val pairing = runCatching { Pairing.parse(text) }.getOrNull()
        if (pairing == null) { hint = "That QR code isn't a UsageNotch pairing. Scan the code in UsageNotch → Settings → Phone."; return }
        found = pairing
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { delay(if (reduced) 250 else 1150); onCode(text) }
    }
    // The system photo picker: one tap, and no storage permission.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching {
                // Large camera photos are decoded at a reduced size; a QR code needs far less.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1; while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.let { QrDecoder.decode(it) }
            }.getOrNull() }
            if (text == null) hint = "No QR code found in that image. Take a screenshot where the whole code is visible." else handle(text)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(owner) {
                val executor = Executors.newSingleThreadExecutor(); val main = Handler(Looper.getMainLooper())
                val future = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null
                future.addListener({
                    runCatching {
                        provider = future.get()
                        val selector = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setResolutionSelector(selector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { image -> val text = try { QrDecoder.decode(image) } catch (_: Exception) { null } finally { image.close() }; if (text != null) main.post { handle(text) } }
                        provider?.unbindAll()
                        camera = provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { hint = "The camera couldn't start. Scan a screenshot instead." }
                }, ContextCompat.getMainExecutor(context))
                onDispose { runCatching { provider?.unbindAll() }; executor.shutdown() }
            }
        }
        Viewfinder(found, reduced)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onClose, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f))) { Icon(Icons.Outlined.Close, "Close scanner", tint = Color.White) }
            if (granted && camera?.cameraInfo?.hasFlashUnit() == true) IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f))) {
                Icon(if (torch) Icons.Outlined.FlashlightOff else Icons.Outlined.FlashlightOn, if (torch) "Turn torch off" else "Turn torch on", tint = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(found == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when { !granted && asked -> "Camera access is off. You can scan a screenshot of the code instead."; hint != null -> hint!!; else -> "Point your phone at the QR code in UsageNotch → Settings → Phone on your PC." },
                        color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 14.dp, vertical = 10.dp))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan a screenshot instead") }
                    if (!granted && asked) TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera", color = Color.White) }
                }
            }
        }
    }
}

/** The viewfinder: a dimmed frame with a clear window, corner brackets and a sweeping line; on success it becomes a pill with the PC's name. */
@Composable private fun Viewfinder(found: Pairing?, reduced: Boolean) {
    val density = LocalDensity.current
    val done = found != null
    val spec = if (reduced) snap<Float>() else spring(dampingRatio = .72f, stiffness = 260f)
    val width by animateFloatAsState(if (done) 300f else 260f, spec, label = "Frame width")
    val height by animateFloatAsState(if (done) 72f else 260f, spec, label = "Frame height")
    val corner by animateFloatAsState(if (done) 36f else 30f, spec, label = "Frame corner")
    val dim by animateFloatAsState(if (done) .78f else .55f, tween(if (reduced) 0 else 400), label = "Dim")
    val sweep = rememberInfiniteTransition(label = "Scan line").animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Sweep")
    val mint = Color(0xFF2EE0A8)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val w = width.dp.toPx(); val h = height.dp.toPx(); val r = corner.dp.toPx()
            val left = (size.width - w) / 2; val top = (size.height - h) / 2.2f
            drawRect(Color.Black.copy(alpha = dim))
            drawRoundRect(Color.Transparent, Offset(left, top), Size(w, h), CornerRadius(r), blendMode = BlendMode.Clear)
            if (!done) {
                val arm = 34.dp.toPx(); val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                // Corner brackets.
                listOf(Offset(left, top) to Offset(1f, 1f), Offset(left + w, top) to Offset(-1f, 1f), Offset(left, top + h) to Offset(1f, -1f), Offset(left + w, top + h) to Offset(-1f, -1f)).forEach { (o, d) ->
                    drawLine(mint, Offset(o.x + d.x * r * .35f, o.y), Offset(o.x + d.x * arm, o.y), stroke.width, StrokeCap.Round)
                    drawLine(mint, Offset(o.x, o.y + d.y * r * .35f), Offset(o.x, o.y + d.y * arm), stroke.width, StrokeCap.Round)
                }
                if (!reduced) { val y = top + h * (.12f + .76f * sweep.value); drawLine(mint.copy(alpha = .75f), Offset(left + 18.dp.toPx(), y), Offset(left + w - 18.dp.toPx(), y), 2.dp.toPx(), StrokeCap.Round) }
            } else drawRoundRect(mint, Offset(left, top), Size(w, h), CornerRadius(r), style = Stroke(2.5.dp.toPx()))
        }
        AnimatedVisibility(done, enter = fadeIn(tween(if (reduced) 0 else 260, delayMillis = if (reduced) 0 else 180)) + scaleIn(initialScale = .9f), modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val top = (maxHeight - 72.dp) / 2.2f
                Row(Modifier.align(Alignment.TopCenter).padding(top = top).width(300.dp).height(72.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(mint), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, tint = Color(0xFF062B20), modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column { Text("Pairing with", color = Color.White.copy(alpha = .7f), fontSize = 12.sp); Text(found?.name ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }
}
