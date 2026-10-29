package app.xreader.data.reader

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.xreader.domain.reader.EyeState
import app.xreader.domain.reader.EyeTrackingController
import app.xreader.domain.reader.EyeTurnDetector
import app.xreader.domain.reader.FaceEvent
import app.xreader.domain.reader.TurnCommand
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device eye-tracking source (PLAN §6 Labs): CameraX front-camera
 * analysis → ML Kit face classification (eye-open probabilities + head
 * pitch) → the pure [EyeTurnDetector]. Everything is on-device; no
 * frame ever leaves the phone, and no preview is shown while reading.
 *
 * FAILS CLOSED (the state machine's contract): no front camera, a
 * camera-provider failure, or repeated detector errors all land the
 * [controller] in UNAVAILABLE/FAILED with a plain-words message, and
 * this class simply stops — normal reading is never touched.
 */
class MlKitEyeTracker(private val context: Context) {

    val controller = EyeTrackingController()
    private val detector = EyeTurnDetector()

    private val _state = MutableStateFlow(controller.state)
    val state: StateFlow<EyeState> = _state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Set by the reader: a detected gesture turns the page. */
    var onTurnCommand: ((TurnCommand) -> Unit)? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var faceDetector: FaceDetector? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var consecutiveErrors = 0
    private var running = false

    /** Toggle from the Labs setting / reader sheet. Permission handling
     * stays with the UI (Activity Result API); call
     * [onPermissionResult] with the outcome. */
    fun toggle(on: Boolean) {
        controller.toggle(on)
        if (!on) stopPipeline()
        syncState()
    }

    fun onPermissionResult(granted: Boolean) {
        controller.onPermissionResult(granted)
        syncState()
    }

    /** Starts camera + detector. Only valid after permission granted. */
    fun start(lifecycleOwner: LifecycleOwner) {
        if (controller.state != EyeState.STARTING && controller.state != EyeState.ACTIVE) {
            return
        }
        if (running) return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    failUnavailable("This device has no front camera for eye tracking.")
                    return@addListener
                }
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setMinFaceSize(0.25f)
                    .build()
                faceDetector = FaceDetection.getClient(options)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
                running = true
                controller.onDetectorReady()
                syncState()
            } catch (e: Exception) {
                failUnavailable(
                    "The camera couldn't be started for eye tracking. " +
                        "Tap and swipe page turns still work as always."
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        controller.toggle(false)
        stopPipeline()
        syncState()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        val mlDetector = faceDetector
        if (mediaImage == null || mlDetector == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(
            mediaImage, proxy.imageInfo.rotationDegrees
        )
        mlDetector.process(image)
            .addOnSuccessListener { faces ->
                consecutiveErrors = 0
                val face = faces.maxByOrNull {
                    it.boundingBox.width() * it.boundingBox.height()
                } ?: return@addOnSuccessListener
                val now = SystemClock.uptimeMillis()
                val command = detector.onEvent(
                    FaceEvent.Eyes(
                        leftOpen = face.leftEyeOpenProbability,
                        rightOpen = face.rightEyeOpenProbability,
                        atMs = now
                    )
                ) ?: detector.onEvent(
                    // ML Kit euler X is positive looking UP; the pure
                    // detector wants degrees looking DOWN.
                    FaceEvent.Pitch(
                        degreesDown = -face.headEulerAngleX,
                        atMs = now
                    )
                )
                if (command != null) onTurnCommand?.invoke(command)
            }
            .addOnFailureListener {
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    controller.onFailure(
                        "Eye tracking stopped — the face detector kept failing. " +
                            "Normal page turns are unaffected."
                    )
                    stopPipeline()
                    syncState()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun failUnavailable(reason: String) {
        controller.onUnavailable(reason)
        stopPipeline()
        syncState()
    }

    private fun stopPipeline() {
        running = false
        detector.reset()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        try {
            faceDetector?.close()
        } catch (_: Exception) {
        }
        faceDetector = null
    }

    private fun syncState() {
        _state.value = controller.state
        _message.value = controller.message
    }

    companion object {
        const val MAX_CONSECUTIVE_ERRORS = 12
    }
}
