package com.example.groceriesapp.vision.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.opencv.android.OpenCVLoader

@SuppressLint("ViewConstructor")
class ArCoreReceiptView(
    context: Context,
    nextFrameFile: () -> File,
    initialKeyframePath: String?,
    private val onObservation: (ReceiptCaptureObservation) -> Unit,
    private val onCaptureBusy: (Boolean) -> Unit,
    private val onKeyframeSaved: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : GLSurfaceView(context), GLSurfaceView.Renderer, Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val analysisBusy = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val requestedTorch = AtomicBoolean(false)
    private val pendingVision = AtomicReference<PendingFrameVision?>(null)
    private val backgroundRenderer = CameraBackgroundRenderer()
    private val processor: ContinuousReceiptFrameProcessor

    @Volatile
    private var session: Session? = null
    private var sessionResumed = false
    private var installRequested = false
    private var textureAttached = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var displayRotation = Surface.ROTATION_0
    private var lastAnalyzedTimestamp = 0L
    private var appliedTorch = false
    private var terminalErrorReported = false

    init {
        check(OpenCVLoader.initLocal()) {
            "OpenCV could not initialize on this device."
        }
        processor = ContinuousReceiptFrameProcessor(
            nextFrameFile = nextFrameFile,
            initialKeyframePath = initialKeyframePath,
            onCaptureBusy = { busy -> mainHandler.post { onCaptureBusy(busy) } },
            onKeyframeSaved = { path -> mainHandler.post { onKeyframeSaved(path) } },
            onCaptureError = { error -> mainHandler.post { onError(error) } },
        )
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setRecording(enabled: Boolean) {
        recording.set(enabled)
    }

    fun setTorch(enabled: Boolean) {
        requestedTorch.set(enabled)
    }

    fun resumeSession(activity: Activity) {
        if (sessionResumed) return
        try {
            if (session == null) {
                when (
                    ArCoreApk.getInstance().requestInstall(
                        activity,
                        !installRequested,
                    )
                ) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                session = Session(activity).also(::configureSession)
            }
            session?.resume()
            sessionResumed = true
            super.onResume()
        } catch (error: Throwable) {
            reportError(error)
        }
    }

    fun pauseSession() {
        if (!sessionResumed) return
        super.onPause()
        runCatching { session?.pause() }
        sessionResumed = false
    }

    override fun close() {
        pauseSession()
        analysisExecutor.shutdown()
        processor.close()
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        textureAttached = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        displayRotation = display?.rotation ?: Surface.ROTATION_0
        session?.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return
        if (!sessionResumed) return

        try {
            if (!textureAttached) {
                activeSession.setCameraTextureName(backgroundRenderer.textureId)
                activeSession.setDisplayGeometry(
                    displayRotation,
                    viewportWidth,
                    viewportHeight,
                )
                textureAttached = true
            }
            applyTorchIfNeeded(activeSession)
            val frame = activeSession.update()
            backgroundRenderer.draw(frame)
            publishPendingVision()
            maybeAnalyze(frame)
        } catch (error: CameraNotAvailableException) {
            reportError(error)
        } catch (error: Throwable) {
            reportError(error)
        }
    }

    private fun configureSession(activeSession: Session) {
        val filter = CameraConfigFilter(activeSession)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        val selected = activeSession.getSupportedCameraConfigs(filter)
            .minByOrNull { config ->
                val pixels = config.imageSize.width.toLong() * config.imageSize.height
                kotlin.math.abs(pixels - TARGET_CPU_PIXELS)
            }
        if (selected != null) activeSession.cameraConfig = selected

        val config = activeSession.config
            .setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)
            .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            .setFocusMode(Config.FocusMode.AUTO)
            .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            .setFlashMode(Config.FlashMode.OFF)
        activeSession.configure(config)
    }

    private fun applyTorchIfNeeded(activeSession: Session) {
        val desired = requestedTorch.get()
        if (desired == appliedTorch) return
        val config = activeSession.config
            .setFlashMode(if (desired) Config.FlashMode.TORCH else Config.FlashMode.OFF)
        activeSession.configure(config)
        appliedTorch = desired
    }

    private fun maybeAnalyze(frame: Frame) {
        val timestamp = frame.timestamp
        if (timestamp <= 0L || timestamp - lastAnalyzedTimestamp < ANALYSIS_INTERVAL_NANOS) return
        if (!analysisBusy.compareAndSet(false, true)) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            analysisBusy.set(false)
            return
        } catch (error: Throwable) {
            analysisBusy.set(false)
            reportError(error)
            return
        }
        lastAnalyzedTimestamp = timestamp
        val arTracking = frame.camera.trackingState == TrackingState.TRACKING
        val captureEnabled = recording.get()
        val imageToView = imageToViewTransform(
            frame = frame,
            imageWidth = image.width,
            imageHeight = image.height,
        )
        analysisExecutor.execute {
            try {
                processor.process(
                    image = image,
                    recording = captureEnabled,
                    arTracking = arTracking,
                )?.let { result ->
                    pendingVision.set(
                        PendingFrameVision(
                            result = result,
                            imageToView = imageToView,
                        ),
                    )
                }
            } catch (error: Throwable) {
                runCatching { image.close() }
                reportError(error)
            } finally {
                analysisBusy.set(false)
            }
        }
    }

    private fun publishPendingVision() {
        val pending = pendingVision.getAndSet(null) ?: return
        val result = pending.result
        val overlay = result.boundaryPoints
            ?.takeIf { it.size >= 4 }
            ?.let { boundaryPoints ->
            ReceiptBoundaryOverlay(
                points = boundaryPoints.map(pending.imageToView::map),
                confidence = result.boundaryConfidence,
                partial = result.boundaryPartial,
            )
        }
        val observation = ReceiptCaptureObservation(
            boundary = overlay,
            guidance = result.guidance,
            arTracking = result.arTracking,
            sharpness = result.sharpness,
            exposure = result.exposure,
            motion = result.motion,
            overlap = result.overlap,
            coverage = result.coverage,
        )
        mainHandler.post { onObservation(observation) }
    }

    private fun imageToViewTransform(
        frame: Frame,
        imageWidth: Int,
        imageHeight: Int,
    ): ImageToViewTransform {
        val input = floatArrayOf(
            0f,
            0f,
            imageWidth.toFloat(),
            0f,
            0f,
            imageHeight.toFloat(),
        )
        val output = FloatArray(input.size)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            input,
            Coordinates2d.VIEW_NORMALIZED,
            output,
        )
        return ImageToViewTransform(
            imageWidth = imageWidth.toFloat(),
            imageHeight = imageHeight.toFloat(),
            originX = output[0],
            originY = output[1],
            xEndX = output[2],
            xEndY = output[3],
            yEndX = output[4],
            yEndY = output[5],
        )
    }

    private fun reportError(error: Throwable) {
        if (terminalErrorReported) return
        terminalErrorReported = true
        mainHandler.post { onError(error) }
    }

    private companion object {
        const val ANALYSIS_INTERVAL_NANOS = 110_000_000L
        const val TARGET_CPU_PIXELS = 2_073_600L
    }
}

private data class PendingFrameVision(
    val result: FrameVisionResult,
    val imageToView: ImageToViewTransform,
)

private data class ImageToViewTransform(
    val imageWidth: Float,
    val imageHeight: Float,
    val originX: Float,
    val originY: Float,
    val xEndX: Float,
    val xEndY: Float,
    val yEndX: Float,
    val yEndY: Float,
) {
    fun map(point: PixelPoint): NormalizedPoint {
        val imageX = (point.x.toFloat() / imageWidth).coerceIn(0f, 1f)
        val imageY = (point.y.toFloat() / imageHeight).coerceIn(0f, 1f)
        return NormalizedPoint(
            x = (
                originX +
                    (xEndX - originX) * imageX +
                    (yEndX - originX) * imageY
                ).coerceIn(0f, 1f),
            y = (
                originY +
                    (xEndY - originY) * imageX +
                    (yEndY - originY) * imageY
                ).coerceIn(0f, 1f),
        )
    }
}

private class CameraBackgroundRenderer {
    var textureId: Int = 0
        private set
    private var program = 0
    private var positionAttribute = 0
    private var textureCoordinateAttribute = 0
    private var textureUniform = 0
    private var textureCoordinatesInitialized = false
    private val quadCoordinates = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val textureCoordinates = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        textureCoordinateAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    fun draw(frame: Frame) {
        if (frame.timestamp == 0L) return
        if (frame.hasDisplayGeometryChanged() || !textureCoordinatesInitialized) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoordinates,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoordinates,
            )
            textureCoordinatesInitialized = true
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadCoordinates.position(0)
        GLES20.glVertexAttribPointer(
            positionAttribute,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadCoordinates,
        )
        GLES20.glEnableVertexAttribArray(positionAttribute)
        textureCoordinates.position(0)
        GLES20.glVertexAttribPointer(
            textureCoordinateAttribute,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureCoordinates,
        )
        GLES20.glEnableVertexAttribArray(textureCoordinateAttribute)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(textureCoordinateAttribute)
        GLES20.glDepthMask(true)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Unable to link AR camera shader: ${GLES20.glGetProgramInfoLog(program)}"
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Unable to compile AR camera shader: ${GLES20.glGetShaderInfoLog(shader)}"
            }
        }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
