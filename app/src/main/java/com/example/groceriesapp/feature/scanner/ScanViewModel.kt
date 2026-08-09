package com.example.groceriesapp.feature.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ScanSession
import com.example.groceriesapp.vision.ProcessingStage
import com.example.groceriesapp.vision.ReceiptProcessingPipeline
import com.example.groceriesapp.vision.capture.LiveCaptureGuidance
import com.example.groceriesapp.vision.capture.ReceiptCaptureObservation
import com.example.groceriesapp.vision.ocr.MlKitReceiptOcrRecognizer
import com.example.groceriesapp.vision.ocr.ReceiptOcrRecognizer
import com.example.groceriesapp.vision.overallProgress
import com.example.groceriesapp.vision.parsing.ReceiptItemExtractor
import com.example.groceriesapp.vision.parsing.RulesReceiptItemExtractor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScanPhase {
    READY,
    RECORDING,
    PROCESSING,
    COMPLETE,
    ERROR,
}

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.READY,
    val observation: ReceiptCaptureObservation = ReceiptCaptureObservation(),
    val acceptedFramePaths: List<String> = emptyList(),
    val captureBusy: Boolean = false,
    val torchEnabled: Boolean = false,
    val processingStage: ProcessingStage? = null,
    val processingProgress: Float = 0f,
    /** Stitched panorama preview, available once the image is built and OCR is still running. */
    val processingPreviewPath: String? = null,
    /** True when the selected OCR engine runs a local language model and may take minutes. */
    val slowOcrEngine: Boolean = false,
    /** True when item extraction goes through a language model and may take a while. */
    val slowExtraction: Boolean = false,
    val completedReceiptId: String? = null,
    val errorMessage: String? = null,
    val processingFailure: Boolean = false,
) {
    val acceptedSections: Int get() = acceptedFramePaths.size
    val initialKeyframePath: String? get() = acceptedFramePaths.lastOrNull()
    val canRecord: Boolean
        get() = phase == ScanPhase.READY &&
            observation.arTracking &&
            observation.boundary != null &&
            observation.guidance in setOf(
                LiveCaptureGuidance.READY,
                LiveCaptureGuidance.HOLD_STEADY,
            )
    val canFinish: Boolean
        get() = phase == ScanPhase.RECORDING &&
            acceptedFramePaths.isNotEmpty() &&
            !captureBusy
}

class ScanViewModel(
    private val repository: GroceriesRepository,
    sessionId: String,
    ocrRecognizer: ReceiptOcrRecognizer,
    itemExtractor: ReceiptItemExtractor = RulesReceiptItemExtractor(),
) : ViewModel() {
    private val draft = repository.openOrCreateScanSession(sessionId)
    private val session: ScanSession = draft.session
    private val processingPipeline = ReceiptProcessingPipeline(
        repository = repository,
        ocrRecognizer = ocrRecognizer,
        itemExtractor = itemExtractor,
    )
    private val terminated = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(
        ScanUiState(
            acceptedFramePaths = draft.acceptedFramePaths,
            slowOcrEngine = ocrRecognizer !is MlKitReceiptOcrRecognizer,
            slowExtraction = itemExtractor !is RulesReceiptItemExtractor,
        ),
    )
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()
    private var processingJob: Job? = null
    private var processingGeneration = 0L

    @Synchronized
    fun onObservation(observation: ReceiptCaptureObservation) {
        if (terminated.get()) return
        if (_uiState.value.phase in setOf(
                ScanPhase.PROCESSING,
                ScanPhase.COMPLETE,
                ScanPhase.ERROR,
            )
        ) {
            return
        }
        _uiState.update { it.copy(observation = observation) }
    }

    @Synchronized
    fun startRecording() {
        if (terminated.get()) return
        val current = _uiState.value
        if (!current.canRecord) return
        _uiState.value = current.copy(
            phase = ScanPhase.RECORDING,
            errorMessage = null,
        )
    }

    @Synchronized
    fun nextFrameFile(): File {
        check(!terminated.get()) { "This scan has ended." }
        val state = _uiState.value
        check(state.phase == ScanPhase.RECORDING) { "The scanner is not recording." }
        return repository.nextFrameFile(session, state.acceptedFramePaths.size)
    }

    @Synchronized
    fun onCaptureBusy(busy: Boolean) {
        if (terminated.get()) return
        _uiState.update { current ->
            if (current.phase == ScanPhase.RECORDING) {
                current.copy(captureBusy = busy)
            } else {
                current
            }
        }
    }

    @Synchronized
    fun onKeyframeSaved(path: String) {
        if (terminated.get()) return
        val current = _uiState.value
        if (current.phase != ScanPhase.RECORDING) {
            deleteUncommittedFrame(path)
            return
        }
        val absolutePath = File(path).absolutePath
        if (absolutePath in current.acceptedFramePaths) return
        val updated = current.acceptedFramePaths + absolutePath
        runCatching { repository.updateScanDraft(session, updated) }
            .onSuccess { persisted ->
                _uiState.value = _uiState.value.copy(
                    acceptedFramePaths = persisted.acceptedFramePaths,
                    captureBusy = false,
                    errorMessage = null,
                )
            }
            .onFailure(::onCaptureError)
    }

    @Synchronized
    fun onCaptureError(error: Throwable) {
        if (terminated.get()) return
        if (_uiState.value.phase in setOf(ScanPhase.PROCESSING, ScanPhase.COMPLETE)) return
        _uiState.update {
            it.copy(
                phase = ScanPhase.ERROR,
                captureBusy = false,
                processingFailure = false,
                errorMessage = error.message ?: "The live receipt capture stopped.",
            )
        }
    }

    @Synchronized
    fun toggleTorch() {
        if (terminated.get()) return
        _uiState.update { it.copy(torchEnabled = !it.torchEnabled) }
    }

    @Synchronized
    fun finishScan() {
        if (terminated.get()) return
        val current = _uiState.value
        if (!current.canFinish) return
        process(current.acceptedFramePaths)
    }

    @Synchronized
    fun retry() {
        if (terminated.get()) return
        val current = _uiState.value
        if (current.phase != ScanPhase.ERROR) return
        if (current.processingFailure && current.acceptedFramePaths.isNotEmpty()) {
            process(current.acceptedFramePaths)
        } else {
            _uiState.value = current.copy(
                phase = ScanPhase.READY,
                captureBusy = false,
                errorMessage = null,
                processingFailure = false,
            )
        }
    }

    @Synchronized
    fun discardSession() {
        if (!terminated.compareAndSet(false, true)) return
        processingJob?.cancel()
        repository.discardScanSession(session)
    }

    @Synchronized
    private fun process(framePaths: List<String>) {
        processingJob?.cancel()
        processingGeneration += 1
        val generation = processingGeneration
        _uiState.update {
            it.copy(
                phase = ScanPhase.PROCESSING,
                captureBusy = false,
                processingStage = ProcessingStage.STITCHING,
                processingProgress = 0f,
                processingPreviewPath = null,
                errorMessage = null,
                processingFailure = false,
            )
        }
        processingJob = viewModelScope.launch {
            try {
                val receipt = processingPipeline.process(
                    session = session,
                    framePaths = framePaths,
                    onPreviewReady = { previewPath ->
                        if (generation == processingGeneration) {
                            _uiState.update { it.copy(processingPreviewPath = previewPath) }
                        }
                    },
                ) { stage, stageProgress ->
                    if (generation == processingGeneration) {
                        _uiState.update {
                            it.copy(
                                processingStage = stage,
                                processingProgress = stage.overallProgress(stageProgress),
                            )
                        }
                    }
                }
                if (generation == processingGeneration) {
                    _uiState.update {
                        it.copy(
                            phase = ScanPhase.COMPLETE,
                            completedReceiptId = receipt.id,
                            processingProgress = 1f,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == processingGeneration) {
                    _uiState.update {
                        it.copy(
                            phase = ScanPhase.ERROR,
                            processingFailure = true,
                            errorMessage =
                                error.message ?: "The receipt panorama could not be created.",
                        )
                    }
                }
            }
        }
    }

    private fun deleteUncommittedFrame(path: String) {
        runCatching {
            val candidate = File(path).canonicalFile
            val originals = File(session.directoryPath, "original").canonicalFile
            if (candidate.parentFile == originals &&
                candidate.absolutePath !in _uiState.value.acceptedFramePaths
            ) {
                candidate.delete()
            }
        }
    }

    override fun onCleared() {
        terminated.set(true)
        processingJob?.cancel()
        super.onCleared()
    }
}

class ScanViewModelFactory(
    private val repository: GroceriesRepository,
    private val sessionId: String,
    private val ocrRecognizer: ReceiptOcrRecognizer,
    private val itemExtractor: ReceiptItemExtractor,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ScanViewModel::class.java))
        return ScanViewModel(repository, sessionId, ocrRecognizer, itemExtractor) as T
    }
}
