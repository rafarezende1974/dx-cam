package br.com.dxlabs.dxcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * DX Cam v1 - nucleo de camera (Etapa 2).
 *
 * Dono da sessao Camera2: previa em tela cheia, pedido de captura montado
 * a partir do CamState (manuais travaveis) e gravacao basica para
 * Movies/DXCam pelo MediaStore - o caminho provado na Fase 0, incluindo
 * a sessao HDR 10-bit (perfil HLG10 -> arquivo BT.2020/PQ no S24).
 * Destinos extras (SSD), niveis de bitrate e nomeacao avancada sao a
 * Etapa 3; monitoramento e a Etapa 4.
 */
class CameraEngine(
    private val context: Context,
    private val bgHandler: Handler,
    private val state: CamState,
    private val listener: Listener
) {
    interface Listener {
        fun onReadback(readback: Readback)
        fun onRecordingChanged(recording: Boolean)
        fun onError(message: String)
    }

    data class Readback(
        val iso: Int?,
        val exposureNs: Long?,
        val focusDiopters: Float?,
        val awbMode: Int?
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val bgExecutor = Executor { command -> bgHandler.post(command) }

    private var cameraId: String? = null
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null

    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordPfd: ParcelFileDescriptor? = null

    @Volatile
    var recording: Boolean = false
        private set

    @Volatile
    private var transitioning: Boolean = false

    // Faixas reais do aparelho (lidas das caracteristicas).
    private var isoRange: Range<Int> = Range(25, 3200)
    private var evRange: Range<Int> = Range(-20, 20)
    private var evStep: Float = 0.1f
    private var sensorOrientation: Int = 90
    private var activeArray: Rect = Rect(0, 0, 4080, 3060)

    // Ultimos valores automaticos lidos - viram o ponto de partida quando
    // so um dos controles de exposicao esta manual.
    @Volatile
    private var lastIso: Int? = null
    @Volatile
    private var lastExposureNs: Long? = null
    private var lastReadbackAt: Long = 0

    private var touchRegion: MeteringRectangle? = null

    val isTransitioning: Boolean get() = transitioning

    fun previewSize(): Size {
        val id = CameraProbe.pickMainBackCameraId(manager) ?: return Size(1920, 1080)
        val map = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val candidates = sizes.filter {
            it.width * 9 == it.height * 16 && it.width <= 1920
        }
        return candidates.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    @SuppressLint("MissingPermission")
    fun open(surface: Surface) {
        previewSurface = surface
        val id = CameraProbe.pickMainBackCameraId(manager)
        if (id == null) {
            listener.onError("Sem camera traseira.")
            return
        }
        cameraId = id
        val c = manager.getCameraCharacteristics(id)
        chars = c
        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(25, 3200)
        evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(-20, 20)
        evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat() ?: 0.1f
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 4080, 3060)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    device = opened
                    createSession(null)
                }

                override fun onDisconnected(opened: CameraDevice) {
                    opened.close()
                    device = null
                    listener.onError("Camera desconectada.")
                }

                override fun onError(opened: CameraDevice, error: Int) {
                    opened.close()
                    device = null
                    listener.onError("Erro de camera: $error")
                }
            }, bgHandler)
        } catch (e: Exception) {
            listener.onError("Falha ao abrir a camera: ${e.message}")
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (e: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (e: Exception) {
        }
        device = null
        releaseRecorder(deleteOutput = false)
    }

    /** Reaplica o estado atual no pedido repetido (sem reconstruir a sessao). */
    fun applyState() {
        val activeSession = session ?: return
        val dev = device ?: return
        try {
            activeSession.setRepeatingRequest(
                buildRequest(dev, currentTargets()), resultCallback, bgHandler
            )
        } catch (e: Exception) {
            listener.onError("Falha ao aplicar controles: ${e.message}")
        }
    }

    private fun currentTargets(): List<Surface> {
        val targets = mutableListOf<Surface>()
        previewSurface?.let { targets.add(it) }
        if (recording || transitioning) recorder?.surface?.let { targets.add(it) }
        return targets
    }

    private fun createSession(recorderSurface: Surface?) {
        val dev = device ?: return
        val preview = previewSurface ?: return
        val targets = mutableListOf(preview)
        if (recorderSurface != null) targets.add(recorderSurface)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                session = newSession
                try {
                    newSession.setRepeatingRequest(
                        buildRequest(dev, targets), resultCallback, bgHandler
                    )
                } catch (e: Exception) {
                    listener.onError("Falha ao iniciar a previa: ${e.message}")
                    transitioning = false
                    return
                }
                if (recorderSurface != null) {
                    try {
                        recorder?.start()
                        recording = true
                        transitioning = false
                        listener.onRecordingChanged(true)
                    } catch (e: Exception) {
                        transitioning = false
                        releaseRecorder(deleteOutput = true)
                        listener.onError("Falha ao gravar: ${e.message}")
                        createSession(null)
                    }
                } else {
                    transitioning = false
                }
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                transitioning = false
                if (recorderSurface != null) {
                    releaseRecorder(deleteOutput = true)
                    listener.onError("Sessao de gravacao nao configurada.")
                    createSession(null)
                } else {
                    listener.onError("Sessao de previa nao configurada.")
                }
            }
        }
        try {
            session?.close()
            session = null
            if (recorderSurface != null && state.mode.hdr) {
                val outRecorder = OutputConfiguration(recorderSurface)
                outRecorder.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                val outPreview = OutputConfiguration(preview)
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outRecorder, outPreview),
                    bgExecutor,
                    callback
                )
                dev.createCaptureSession(config)
            } else {
                dev.createCaptureSession(targets, callback, bgHandler)
            }
        } catch (e: Exception) {
            transitioning = false
            listener.onError("Falha ao criar a sessao: ${e.message}")
        }
    }

    private fun buildRequest(
        dev: CameraDevice,
        targets: List<Surface>
    ): CaptureRequest {
        val mode = state.mode
        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        for (target in targets) builder.addTarget(target)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        // --- Exposicao ---
        val frameNs = mode.frameDurationNs
        if (state.isoAuto && state.shutterAuto) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
            )
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.fps, mode.fps)
            )
            val steps = CamState.evStepsFor(state.ev, evStep)
                .coerceIn(evRange.lower, evRange.upper)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF
            )
            val iso = if (state.isoAuto) (lastIso ?: 400) else state.iso
            builder.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                iso.coerceIn(isoRange.lower, isoRange.upper)
            )
            val exposure = if (state.shutterAuto) {
                (lastExposureNs ?: (frameNs / 2)).coerceIn(85_000L, frameNs)
            } else {
                state.manualExposureNs(mode.fps)
            }
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        }

        // --- Foco ---
        when (state.focusMode) {
            FocusMode.CONTINUOUS -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            FocusMode.TOUCH -> {
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO
                )
                touchRegion?.let {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
                }
            }
            FocusMode.MANUAL -> {
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
                )
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDiopters)
            }
        }

        // --- Branco ---
        if (state.wbAuto) {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            val one = Rational(1, 1)
            val zero = Rational(0, 1)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                ColorSpaceTransform(
                    arrayOf(one, zero, zero, zero, one, zero, zero, zero, one)
                )
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                CamState.kelvinToGains(state.kelvin, state.tint)
            )
        }

        // --- Zoom ---
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, state.zoomRatio)
        return builder.build()
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            completedSession: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastIso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: lastIso
            lastExposureNs = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)
                ?: lastExposureNs
            val now = System.currentTimeMillis()
            if (now - lastReadbackAt >= 300) {
                lastReadbackAt = now
                listener.onReadback(
                    Readback(
                        iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY),
                        exposureNs = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME),
                        focusDiopters = result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE),
                        awbMode = result.get(TotalCaptureResult.CONTROL_AWB_MODE)
                    )
                )
            }
        }
    }

    /** Toque na previa: define a regiao de foco/fotometria e dispara o AF. */
    fun tapFocus(normalizedX: Float, normalizedY: Float) {
        if (state.focusMode == FocusMode.MANUAL) return
        val size = 200
        val cx = (normalizedX * activeArray.width()).toInt()
        val cy = (normalizedY * activeArray.height()).toInt()
        val rect = Rect(
            (cx - size / 2).coerceIn(0, activeArray.width() - size),
            (cy - size / 2).coerceIn(0, activeArray.height() - size),
            (cx + size / 2).coerceIn(size, activeArray.width()),
            (cy + size / 2).coerceIn(size, activeArray.height())
        )
        touchRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        val dev = device ?: return
        val activeSession = session ?: return
        try {
            // 1) O pedido repetido passa a carregar a regiao tocada.
            val base = buildRequest(dev, currentTargets())
            activeSession.setRepeatingRequest(base, resultCallback, bgHandler)
            // 2) Um disparo unico com o gatilho de AF sobre as mesmas chaves.
            val shot = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (target in currentTargets()) shot.addTarget(target)
            copyKeys(base, shot)
            shot.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            activeSession.capture(shot.build(), null, bgHandler)
        } catch (e: Exception) {
            listener.onError("Falha no foco por toque: ${e.message}")
        }
    }

    private fun copyKeys(from: CaptureRequest, to: CaptureRequest.Builder) {
        // Copia chave a chave as configuracoes relevantes do pedido base.
        fun <T> copy(key: CaptureRequest.Key<T>) {
            val value = from.get(key)
            if (value != null) to.set(key, value)
        }
        copy(CaptureRequest.CONTROL_MODE)
        copy(CaptureRequest.CONTROL_AE_MODE)
        copy(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        copy(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
        copy(CaptureRequest.SENSOR_SENSITIVITY)
        copy(CaptureRequest.SENSOR_EXPOSURE_TIME)
        copy(CaptureRequest.SENSOR_FRAME_DURATION)
        copy(CaptureRequest.CONTROL_AF_MODE)
        copy(CaptureRequest.CONTROL_AF_REGIONS)
        copy(CaptureRequest.CONTROL_AE_REGIONS)
        copy(CaptureRequest.LENS_FOCUS_DISTANCE)
        copy(CaptureRequest.CONTROL_AWB_MODE)
        copy(CaptureRequest.COLOR_CORRECTION_MODE)
        copy(CaptureRequest.COLOR_CORRECTION_TRANSFORM)
        copy(CaptureRequest.COLOR_CORRECTION_GAINS)
        copy(CaptureRequest.CONTROL_ZOOM_RATIO)
    }

    // ---------------------------------------------------------------
    // Gravacao basica (Movies/DXCam via MediaStore) - caminho da Fase 0
    // ---------------------------------------------------------------
    fun startRecording() {
        if (recording || transitioning) return
        val mode = state.mode
        transitioning = true
        val resolver = context.contentResolver
        val name = "DXCam_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DXCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            transitioning = false
            listener.onError("Nao foi possivel criar o arquivo de video.")
            return
        }
        recordUri = uri
        try {
            val pfd = resolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("sem descritor de arquivo")
            recordPfd = pfd
            val rec = MediaRecorder(context)
            recorder = rec
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(192_000)
            rec.setAudioSamplingRate(48_000)
            rec.setVideoEncoder(
                if (mode.hevc) MediaRecorder.VideoEncoder.HEVC
                else MediaRecorder.VideoEncoder.H264
            )
            rec.setVideoSize(mode.width, mode.height)
            rec.setVideoFrameRate(mode.fps)
            rec.setVideoEncodingBitRate(mode.bitrateMbps * 1_000_000)
            rec.setOrientationHint(sensorOrientation)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.prepare()
        } catch (e: Exception) {
            transitioning = false
            releaseRecorder(deleteOutput = true)
            listener.onError("Falha ao preparar a gravacao: ${e.message}")
            return
        }
        createSession(recorder?.surface)
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        try {
            session?.stopRepeating()
        } catch (e: Exception) {
        }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Arquivo pode sair curto; o MediaStore finaliza mesmo assim.
        }
        releaseRecorder(deleteOutput = false)
        finalizeRecordUri()
        listener.onRecordingChanged(false)
        createSession(null)
    }

    private fun releaseRecorder(deleteOutput: Boolean) {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        try {
            recordPfd?.close()
        } catch (e: Exception) {
        }
        recordPfd = null
        if (deleteOutput) {
            recordUri?.let {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (e: Exception) {
                }
            }
            recordUri = null
        }
    }

    private fun finalizeRecordUri() {
        val uri = recordUri ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
        }
        recordUri = null
    }
}
