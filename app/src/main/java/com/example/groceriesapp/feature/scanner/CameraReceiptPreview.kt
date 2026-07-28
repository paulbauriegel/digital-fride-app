package com.example.groceriesapp.feature.scanner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.groceriesapp.vision.capture.ArCoreReceiptView
import com.example.groceriesapp.vision.capture.ReceiptCaptureObservation
import java.io.File

@Composable
fun CameraReceiptPreview(
    recording: Boolean,
    torchEnabled: Boolean,
    initialKeyframePath: String?,
    nextFrameFile: () -> File,
    onObservation: (ReceiptCaptureObservation) -> Unit,
    onCaptureBusy: (Boolean) -> Unit,
    onKeyframeSaved: (String) -> Unit,
    onCameraError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestNextFrameFile by rememberUpdatedState(nextFrameFile)
    val latestObservation by rememberUpdatedState(onObservation)
    val latestCaptureBusy by rememberUpdatedState(onCaptureBusy)
    val latestKeyframeSaved by rememberUpdatedState(onKeyframeSaved)
    val latestCameraError by rememberUpdatedState(onCameraError)
    val restoredKeyframePath = remember { initialKeyframePath }
    val view = remember(context) {
        ArCoreReceiptView(
            context = context,
            initialKeyframePath = restoredKeyframePath,
            nextFrameFile = { latestNextFrameFile() },
            onObservation = { latestObservation(it) },
            onCaptureBusy = { latestCaptureBusy(it) },
            onKeyframeSaved = { latestKeyframeSaved(it) },
            onError = { latestCameraError(it) },
        )
    }

    AndroidView(
        factory = { view },
        update = { view ->
            view.setRecording(recording)
            view.setTorch(torchEnabled)
        },
        onRelease = ArCoreReceiptView::close,
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner, activity, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activity?.let(view::resumeSession)
                Lifecycle.Event.ON_PAUSE -> view.pauseSession()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            activity?.let(view::resumeSession)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.pauseSession()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
