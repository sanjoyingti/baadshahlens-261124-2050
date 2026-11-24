package com.aibuilt.baadshahlens.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibuilt.baadshahlens.camera.CameraController
import com.aibuilt.baadshahlens.camera.HardwareKeys
import com.aibuilt.baadshahlens.camera.KeyPhase
import com.aibuilt.baadshahlens.data.AppContainer
import com.aibuilt.baadshahlens.data.appContainer
import com.aibuilt.baadshahlens.data.settings.Gender
import com.aibuilt.baadshahlens.data.settings.QualityTier
import com.aibuilt.baadshahlens.ui.common.ErrorStrip
import com.aibuilt.baadshahlens.ui.common.TierPicker
import com.aibuilt.baadshahlens.ui.common.VmFactory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenRecord: () -> Unit,
    onOpenResult: (String, String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    val container = remember { context.appContainer() }
    val vm: CameraViewModel = viewModel(factory = VmFactory { CameraViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val progress by container.models.progress.collectAsStateWithLifecycle()
    val modelsReady = remember(progress) { container.models.allReady() }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var torch by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val tick = remember { longArrayOf(0L) }
    val slow = remember { intArrayOf(0) }
    val controller = remember {
        CameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrame = {
                val now = SystemClock.elapsedRealtime()
                if (tick[0] != 0L) {
                    val dt = now - tick[0]
                    if (dt > 40L) slow[0]++ else if (slow[0] > 0) slow[0]--
                    if (slow[0] > 20) {
                        slow[0] = 0
                        vm.demoteToFast()
                    }
                }
                tick[0] = now
            },
            onRecordEvent = { vm.onRecordEvent(it) }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result[Manifest.permission.CAMERA] == true
        if (!granted) vm.setError("The camera stays dark until you allow the camera permission.")
    }

    LaunchedEffect(Unit) {
        container.models.refresh()
        if (!granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        }
    }

    LaunchedEffect(modelsReady) {
        if (modelsReady) {
            controller.prepare(container.models.fileFor("pose"), container.models.fileFor("seg"))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(controller) { onDispose { controller.release() } }

    LaunchedEffect(previewView, granted, lensFacing, modelsReady) {
        val view = previewView ?: return@LaunchedEffect
        if (!granted || !modelsReady) return@LaunchedEffect
        controller.unbind()
        val error = controller.bind(view, lensFacing)
        if (error != null) vm.setError(error)
    }

    LaunchedEffect(state.strength, state.gender, state.overlayOn, state.ghost) {
        controller.strength = state.strength
        controller.gender = state.gender
        controller.overlayOn = state.overlayOn
        controller.ghostAlpha = state.ghost
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            vm.inferGender(controller.pipelineGender())
        }
    }

    LaunchedEffect(state.glasses) {
        HardwareKeys.consuming = state.glasses
        if (!state.glasses) return@LaunchedEffect
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@LaunchedEffect
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                vm.setTilt(event.values[2])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        try {
            awaitCancellation()
        } finally {
            manager.unregisterListener(listener)
        }
    }

    LaunchedEffect(state.glasses) {
        var pressed = false
        HardwareKeys.events.collect { phase ->
            when (phase) {
                KeyPhase.DOWN -> pressed = true
                KeyPhase.REPEAT -> {
                    if (pressed) {
                        pressed = false
                        if (!controller.isRecording) {
                            startVideo(context, controller, vm, state.muted, state.cleanVideo)
                        }
                    }
                }
                KeyPhase.UP -> {
                    if (pressed) {
                        pressed = false
                        shoot(controller, vm, onOpenResult)
                    } else if (controller.isRecording) {
                        controller.stopRecording()
                    }
                }
            }
        }
    }

    LaunchedEffect(state.recording) {
        while (state.recording) {
            delay(500)
            vm.tickRecord()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state.glasses) {
                    detectTapGestures { offset ->
                        controller.focus(offset.x / size.width, offset.y / size.height)
                        if (state.glasses) vm.sharpenTap()
                    }
                }
                .transformable(rememberTransformableState { zoomChange, _, _ ->
                    controller.zoomBy(zoomChange)
                })
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (state.grid) {
                Canvas(Modifier.fillMaxSize()) {
                    val stepX = size.width / 3f
                    val stepY = size.height / 3f
                    for (i in 1..2) {
                        drawLine(Color.White.copy(alpha = 0.32f), Offset(stepX * i, 0f), Offset(stepX * i, size.height), 1f)
                        drawLine(Color.White.copy(alpha = 0.32f), Offset(0f, stepY * i), Offset(size.width, stepY * i), 1f)
                    }
                }
            }

            if (state.showGuide) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val stroke = Stroke(width = 3f)
                    drawRoundRect(
                        color = Color(0xFFF2B33D).copy(alpha = 0.85f),
                        topLeft = Offset(w * 0.28f, h * 0.14f),
                        size = Size(w * 0.44f, h * 0.68f),
                        cornerRadius = CornerRadius(26f, 26f),
                        style = stroke
                    )
                    drawCircle(
                        color = Color(0xFFF2B33D).copy(alpha = 0.85f),
                        radius = w * 0.075f,
                        center = Offset(w * 0.5f, h * 0.22f),
                        style = stroke
                    )
                }
            }
        }

        if (!granted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                    Text("Camera permission", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The app needs the camera to show and paint bodies. Audio is only used when you record with sound.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    }) { Text("Allow camera") }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onBack) { Text("Open settings instead") }
                }
            }
        }

        if (granted && !modelsReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tracking models not downloaded", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Open Settings → Downloads to fetch them.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onBack) { Text("Settings") }
                }
            }
        }

        state.error?.let { message ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 64.dp)) {
                ErrorStrip(message = message, onRetry = { vm.clearError() })
            }
        }

        if (state.glasses) {
            GlassesChrome(onExit = { vm.toggleGlasses() }, hintShown = state.hintShown, onTimedOut = { vm.markHintShown() })
        } else {
            NormalChrome(
                state = state,
                torch = torch,
                modelsReady = modelsReady,
                onSettings = onBack,
                onGallery = onOpenGallery,
                onRecord = onOpenRecord,
                onFlip = {
                    torch = false
                    controller.setTorch(false)
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                onTorch = {
                    torch = !torch
                    controller.setTorch(torch)
                },
                onGrid = { vm.toggleGrid() },
                onGender = { vm.toggleGender() },
                onTier = { vm.setTier(it) },
                onStrength = { vm.setStrength(it) },
                onGuideDismiss = { vm.dismissGuide() },
                onGlasses = { vm.toggleGlasses() },
                onShutter = { shoot(controller, vm, onOpenResult) }
            )
        }

        if (state.glasses && state.recording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 26.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).background(Color(0xFFE2564C), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(text = vm.formatTime(), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun startVideo(
    context: Context,
    controller: CameraController,
    vm: CameraViewModel,
    muted: Boolean,
    clean: Boolean
) {
    val dir = File(context.filesDir, "videos").also { it.mkdirs() }
    val file = File(dir, "clip_${System.currentTimeMillis()}.mp4")
    controller.overlayOn = !clean
    vm.beginRecord(file)
    val started = controller.startRecording(file, muted) { vm.setError(it) }
    if (!started) vm.abortRecord()
}

private fun shoot(controller: CameraController, vm: CameraViewModel, onOpenResult: (String, String, String, String, String) -> Unit) {
    if (controller.isRecording) {
        controller.stopRecording()
        return
    }
    vm.shoot(controller) { before, after, mask, occ, tier -> onOpenResult(before, after, mask, occ, tier) }
}

@Composable
private fun NormalChrome(
    state: CameraUiState,
    torch: Boolean,
    modelsReady: Boolean,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onRecord: () -> Unit,
    onFlip: () -> Unit,
    onTorch: () -> Unit,
    onGrid: () -> Unit,
    onGender: () -> Unit,
    onTier: (QualityTier) -> Unit,
    onStrength: (Float) -> Unit,
    onGuideDismiss: () -> Unit,
    onGlasses: () -> Unit,
    onShutter: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            Spacer(Modifier.width(2.dp))
            Text("Baadshah Lens", style = MaterialTheme.typography.titleMedium, color = Color(0xFFF2B33D))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onTorch, enabled = modelsReady) {
                Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Torch")
            }
            IconButton(onClick = onGrid) {
                Icon(if (state.grid) Icons.Filled.GridOn else Icons.Filled.GridOff, "Grid")
            }
            IconButton(onClick = onGender) {
                Icon(if (state.gender == Gender.MALE) Icons.Filled.Male else Icons.Filled.Female, "Body shape")
            }
            IconButton(onClick = onFlip) { Icon(Icons.Filled.Cameraswitch, "Switch camera") }
        }

        Spacer(Modifier.weight(1f))

        if (state.showGuide) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Full body in frame · arms away from the torso · stand straight",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onGuideDismiss, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Filled.Close, "Hide guide", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            TierPicker(selected = state.tier, onSelect = onTier)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strength", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.strength.coerceIn(0.1f, 1f),
                    onValueChange = onStrength,
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text("${(state.strength * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onGallery) { Icon(Icons.Filled.PhotoLibrary, "Gallery") }
                IconButton(onClick = onRecord) { Icon(Icons.Filled.Videocam, "Record") }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(72.dp)
                        .border(4.dp, Color(0xFFF2B33D), CircleShape)
                        .padding(6.dp)
                        .background(if (state.busy) Color(0xFFC98A18) else Color.White, CircleShape)
                        .pointerInput(Unit) { detectTapGestures { onShutter() } }
                )
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = onGlasses, label = { Text("Glasses") })
            }
        }
    }
}

@Composable
private fun GlassesChrome(onExit: () -> Unit, hintShown: Boolean, onTimedOut: () -> Unit) {
    var hintVisible by remember { mutableStateOf(!hintShown) }
    LaunchedEffect(Unit) {
        if (hintVisible) {
            delay(5000)
            hintVisible = false
            onTimedOut()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            IconButton(onClick = onExit) { Icon(Icons.Filled.Close, "Leave glasses mode", tint = Color.White) }
        }
        if (hintVisible) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp)) {
                Text(
                    "Tilt down to sleep · volume to freeze · long-press to record · tap to sharpen",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Suppress("unused")
private val screenUnusedContainer: AppContainer? = null
