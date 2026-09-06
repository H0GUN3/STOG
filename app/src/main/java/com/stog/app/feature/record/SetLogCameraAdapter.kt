package com.stog.app.feature.record

import android.content.Context
import android.os.Looper
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.Serializable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class SetLogCameraLens(val label: String) {
    REAR("Rear camera"),
    FRONT("Front camera"),
}

internal enum class SetLogFlashMode(val label: String, val cameraXMode: Int) {
    OFF("Flash off", ImageCapture.FLASH_MODE_OFF),
    AUTO("Flash auto", ImageCapture.FLASH_MODE_AUTO),
    ON("Flash on", ImageCapture.FLASH_MODE_ON),
}

internal data class SetLogLensCapability(
    val lens: SetLogCameraLens,
    val label: String,
    val available: Boolean,
) : Serializable

internal data class SetLogCameraCapabilities(
    val lenses: List<SetLogLensCapability>,
    val selectedLens: SetLogCameraLens?,
    val flashAvailable: Boolean,
    val supportedFlashModes: List<SetLogFlashMode>,
    val selectedFlashMode: SetLogFlashMode,
) : Serializable {
    fun lensAvailable(lens: SetLogCameraLens): Boolean = lenses
        .firstOrNull { it.lens == lens }
        ?.available == true
}

internal enum class SetLogCameraUnavailable { NO_CAMERA, REAR_LENS, FRONT_LENS, FLASH }

internal enum class SetLogCameraError {
    PROVIDER_UNAVAILABLE,
    BIND_FAILED,
    CAPTURE_NOT_READY,
    IMAGE_SAVE_FAILED,
    REBIND_TOKEN_REQUIRED,
}

internal sealed interface SetLogCameraCallback : Serializable {
    val token: SetLogCaptureToken

    data class Ready(
        override val token: SetLogCaptureToken,
        val capabilities: SetLogCameraCapabilities,
    ) : SetLogCameraCallback

    data class CapabilitiesChanged(
        override val token: SetLogCaptureToken,
        val capabilities: SetLogCameraCapabilities,
    ) : SetLogCameraCallback

    data class Captured(
        override val token: SetLogCaptureToken,
        val outputId: String,
    ) : SetLogCameraCallback

    data class Unavailable(
        override val token: SetLogCaptureToken,
        val capability: SetLogCameraUnavailable,
    ) : SetLogCameraCallback

    data class Error(
        override val token: SetLogCaptureToken,
        val error: SetLogCameraError,
    ) : SetLogCameraCallback
}

internal fun SetLogCameraCallback.toCaptureEvent(): SetLogCaptureEvent? = when (this) {
    is SetLogCameraCallback.Ready -> SetLogCaptureEvent.CameraReady(token)
    is SetLogCameraCallback.Captured -> SetLogCaptureEvent.CaptureCompleted(token, outputId)
    is SetLogCameraCallback.Error -> SetLogCaptureEvent.CallbackFailed(token, error.name)
    is SetLogCameraCallback.Unavailable -> SetLogCaptureEvent.CallbackFailed(
        token,
        "CAMERA_${capability.name}_UNAVAILABLE",
    )
    is SetLogCameraCallback.CapabilitiesChanged -> null
}

internal fun setLogCameraCapabilities(
    rearAvailable: Boolean,
    frontAvailable: Boolean,
    selectedLens: SetLogCameraLens?,
    flashAvailable: Boolean,
    selectedFlashMode: SetLogFlashMode = SetLogFlashMode.OFF,
): SetLogCameraCapabilities {
    val modes = if (flashAvailable) SetLogFlashMode.entries else listOf(SetLogFlashMode.OFF)
    return SetLogCameraCapabilities(
        lenses = listOf(
            SetLogLensCapability(SetLogCameraLens.REAR, SetLogCameraLens.REAR.label, rearAvailable),
            SetLogLensCapability(SetLogCameraLens.FRONT, SetLogCameraLens.FRONT.label, frontAvailable),
        ),
        selectedLens = selectedLens,
        flashAvailable = flashAvailable,
        supportedFlashModes = modes,
        selectedFlashMode = selectedFlashMode.takeIf { it in modes } ?: SetLogFlashMode.OFF,
    )
}

internal fun SetLogCameraCapabilities.nextFlashMode(): SetLogFlashMode {
    val current = supportedFlashModes.indexOf(selectedFlashMode).coerceAtLeast(0)
    return supportedFlashModes[(current + 1) % supportedFlashModes.size]
}

internal class SetLogCameraCallbackGate(
    private val callback: (SetLogCameraCallback) -> Unit,
    private val assertMainThread: () -> Unit = {
        check(Looper.myLooper() == Looper.getMainLooper())
    },
) {
    private var generation = 0L
    private var activeToken: SetLogCaptureToken? = null
    private var closed = false

    fun begin(token: SetLogCaptureToken): Long {
        assertMainThread()
        check(!closed)
        generation += 1
        activeToken = token
        return generation
    }

    fun currentGeneration(token: SetLogCaptureToken): Long? {
        assertMainThread()
        return generation.takeIf { !closed && activeToken == token }
    }

    fun activeToken(): SetLogCaptureToken? {
        assertMainThread()
        return activeToken.takeUnless { closed }
    }

    fun accepts(expectedGeneration: Long, token: SetLogCaptureToken): Boolean {
        assertMainThread()
        return !closed && generation == expectedGeneration && activeToken == token
    }

    fun dispatch(expectedGeneration: Long, event: SetLogCameraCallback): Boolean {
        assertMainThread()
        if (!accepts(expectedGeneration, event.token)) return false
        callback(event)
        return true
    }

    fun invalidate() {
        assertMainThread()
        generation += 1
        activeToken = null
    }

    fun close() {
        assertMainThread()
        invalidate()
        closed = true
    }
}

internal class SetLogCameraAdapter(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    callback: (SetLogCameraCallback) -> Unit,
) : AutoCloseable {
    val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val callbackGate = SetLogCameraCallbackGate(callback)
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var capabilities: SetLogCameraCapabilities? = null
    private var requestedLens = SetLogCameraLens.REAR
    private var closed = false

    fun bind(token: SetLogCaptureToken) {
        if (closed) return
        invalidateAndUnbind()
        val generation = callbackGate.begin(token)
        cameraProviderFuture.addListener(
            {
                if (!callbackGate.accepts(generation, token)) return@addListener
                val provider = try {
                    cameraProviderFuture.get()
                } catch (_: Throwable) {
                    callbackGate.dispatch(
                        generation,
                        SetLogCameraCallback.Error(token, SetLogCameraError.PROVIDER_UNAVAILABLE),
                    )
                    return@addListener
                }
                cameraProvider = provider
                bindUseCases(provider, generation, token)
            },
            mainExecutor,
        )
    }

    fun switchLens(newToken: SetLogCaptureToken) {
        val currentToken = callbackGate.activeToken() ?: return
        val current = capabilities ?: return
        if (newToken == currentToken) {
            callbackGate.currentGeneration(currentToken)?.let { generation ->
                callbackGate.dispatch(
                    generation,
                    SetLogCameraCallback.Error(currentToken, SetLogCameraError.REBIND_TOKEN_REQUIRED),
                )
            }
            return
        }
        val target = when (current.selectedLens) {
            SetLogCameraLens.REAR -> SetLogCameraLens.FRONT
            SetLogCameraLens.FRONT, null -> SetLogCameraLens.REAR
        }
        if (!current.lensAvailable(target)) {
            callbackGate.currentGeneration(currentToken)?.let { generation ->
                callbackGate.dispatch(
                    generation,
                    SetLogCameraCallback.Unavailable(
                        currentToken,
                        if (target == SetLogCameraLens.REAR) {
                            SetLogCameraUnavailable.REAR_LENS
                        } else {
                            SetLogCameraUnavailable.FRONT_LENS
                        },
                    ),
                )
            }
            return
        }
        requestedLens = target
        bind(newToken)
    }

    fun cycleFlash(token: SetLogCaptureToken) {
        val generation = callbackGate.currentGeneration(token) ?: return
        val current = capabilities ?: return
        if (!current.flashAvailable) {
            callbackGate.dispatch(
                generation,
                SetLogCameraCallback.Unavailable(token, SetLogCameraUnavailable.FLASH),
            )
            return
        }
        val next = current.nextFlashMode()
        imageCapture?.flashMode = next.cameraXMode
        val updated = current.copy(selectedFlashMode = next)
        capabilities = updated
        callbackGate.dispatch(
            generation,
            SetLogCameraCallback.CapabilitiesChanged(token, updated),
        )
    }

    fun updateDisplayRotation() {
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    fun capture(token: SetLogCaptureToken, output: SetLogLocalAsset): Boolean {
        val generation = callbackGate.currentGeneration(token) ?: return false
        val capture = imageCapture
        if (capture == null) {
            callbackGate.dispatch(
                generation,
                SetLogCameraCallback.Error(token, SetLogCameraError.CAPTURE_NOT_READY),
            )
            return true
        }
        val outputFile = File(output.path).also { it.parentFile?.mkdirs() }
        return try {
            updateDisplayRotation()
            val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            capture.takePicture(
                options,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        mainExecutor.execute {
                            if (!callbackGate.dispatch(
                                    generation,
                                    SetLogCameraCallback.Captured(token, output.outputId),
                                )
                            ) {
                                outputFile.delete()
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        outputFile.delete()
                        mainExecutor.execute {
                            callbackGate.dispatch(
                                generation,
                                SetLogCameraCallback.Error(token, SetLogCameraError.IMAGE_SAVE_FAILED),
                            )
                        }
                    }
                },
            )
            true
        } catch (_: Exception) {
            outputFile.delete()
            callbackGate.dispatch(
                generation,
                SetLogCameraCallback.Error(token, SetLogCameraError.CAPTURE_NOT_READY),
            )
            true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        callbackGate.close()
        unbindCameraObjects()
        captureExecutor.shutdownNow()
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        generation: Long,
        token: SetLogCaptureToken,
    ) {
        try {
            val rearAvailable = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val frontAvailable = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val selectedLens = when {
                requestedLens == SetLogCameraLens.REAR && rearAvailable -> SetLogCameraLens.REAR
                requestedLens == SetLogCameraLens.FRONT && frontAvailable -> SetLogCameraLens.FRONT
                rearAvailable -> SetLogCameraLens.REAR
                frontAvailable -> SetLogCameraLens.FRONT
                else -> null
            }
            if (selectedLens == null) {
                capabilities = setLogCameraCapabilities(
                    rearAvailable = false,
                    frontAvailable = false,
                    selectedLens = null,
                    flashAvailable = false,
                )
                callbackGate.dispatch(
                    generation,
                    SetLogCameraCallback.Unavailable(token, SetLogCameraUnavailable.NO_CAMERA),
                )
                return
            }

            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(
                        AspectRatio.RATIO_4_3,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO,
                    ),
                )
                .build()
            val previewUseCase = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val captureUseCase = ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            val selector = if (selectedLens == SetLogCameraLens.REAR) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                previewUseCase,
                captureUseCase,
            )
            if (!callbackGate.accepts(generation, token)) {
                provider.unbindAll()
                return
            }
            preview = previewUseCase
            imageCapture = captureUseCase
            camera = boundCamera
            requestedLens = selectedLens
            val readyCapabilities = setLogCameraCapabilities(
                rearAvailable = rearAvailable,
                frontAvailable = frontAvailable,
                selectedLens = selectedLens,
                flashAvailable = boundCamera.cameraInfo.hasFlashUnit(),
            )
            capabilities = readyCapabilities
            callbackGate.dispatch(
                generation,
                SetLogCameraCallback.Ready(token, readyCapabilities),
            )
        } catch (_: Throwable) {
            provider.unbindAll()
            preview = null
            imageCapture = null
            camera = null
            capabilities = null
            callbackGate.dispatch(
                generation,
                SetLogCameraCallback.Error(token, SetLogCameraError.BIND_FAILED),
            )
        }
    }

    private fun invalidateAndUnbind() {
        callbackGate.invalidate()
        unbindCameraObjects()
    }

    private fun unbindCameraObjects() {
        cameraProvider?.unbindAll()
        preview = null
        imageCapture = null
        camera = null
        capabilities = null
    }
}
