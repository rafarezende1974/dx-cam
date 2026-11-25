package br.com.dxlabs.dxcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * DX Cam v1 - nucleo de camera (Etapas 2 e 3).
 *
 * Sessao Camera2 com previa, pedido montado do CamState (manuais
 * travaveis) e gravacao completa:
 * - Destinos: interno (Movies/DXCam via MediaStore) ou pasta externa
 *   escolhida pelo usuario (SSD via SAF, permissao persistente).
 * - Bitrate em tres niveis (ECO/PADRAO/ALTO) sobre a base do modo.
 * - Nomeacao: DXCam[_projeto]_AAAAMMDD_HHMMSS[_parteN].mp4.
 * - Divisao automatica: ao aproximar de ~3,8 GB o gravador emenda o
 *   proximo arquivo sem perder quadros (setNextOutputFile), entao
 *   gravacao longa nao estoura limite de sistema de arquivos.
 * - HDR 10-bit pela sessao com perfil HLG10 (provado na Fase 0).
 * Monitoramento e a Etapa 4; LUT, a 5.
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
        fun onFileSplit(part: Int)
        fun onError(message: String)
        fun onInfo(message: String)
        /** Plano Y da previa (640x360, bytes compactos) para a Etapa 4. */
        fun onAnalysisFrame(y: ByteArray, width: Int, height: Int)
        /** Etapa 6: foto salva (JPEG e/ou DNG) no destino atual. */
        fun onPhotoSaved(message: String)
    }

    data class Readback(
        val iso: Int?,
        val exposureNs: Long?,
        val focusDiopters: Float?,
        val awbMode: Int?
    )

    /** Um arquivo de saida aberto (interno ou SSD), com seu fechamento. */
    private class OutputHandle(
        val name: String,
        val pfd: ParcelFileDescriptor,
        val finalize: () -> Unit,
        val delete: () -> Unit
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val bgExecutor = Executor { command -> bgHandler.post(command) }

    private var cameraId: String? = null
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null

    // Etapa 4: leitor YUV de baixa resolucao para o monitoramento.
    private var analysisReader: ImageReader? = null
    private var analysisSurface: Surface? = null
    private var activeTargets: List<Surface> = emptyList()
    private var lastAnalysisAt: Long = 0

    // Etapa 6: leitores de foto (JPEG + RAW) e o estado da captura.
    private var jpegReader: ImageReader? = null
    private var jpegSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var rawSurface: Surface? = null
    private var repeatingTargets: List<Surface> = emptyList()
    private var photoInSession: Boolean = false
    private var pendingPhoto: PendingPhoto? = null

    val rawSupported: Boolean get() = rawReader != null

    private class PendingPhoto(
        val baseName: String,
        val wantJpeg: Boolean,
        val wantRaw: Boolean,
        val orientationDegrees: Int,
        var result: TotalCaptureResult? = null,
        var jpegBytes: ByteArray? = null,
        var rawImage: Image? = null,
        var done: Boolean = false
    )

    /** A tela liga/desliga conforme zebra/peaking/histograma ativos. */
    @Volatile
    var monitoringEnabled: Boolean = true

    private var recorder: MediaRecorder? = null
    private val outputs = mutableListOf<OutputHandle>()
    private var currentOutput: OutputHandle? = null
    private var nextOutput: OutputHandle? = null
    private var recordBaseName: String = ""
    private var partIndex: Int = 1

    @Volatile
    var recording: Boolean = false
        private set

    @Volatile
    private var transitioning: Boolean = false

    private var isoRange: Range<Int> = Range(25, 3200)
    private var evRange: Range<Int> = Range(-20, 20)
    private var evStep: Float = 0.1f
    private var sensorOrientation: Int = 90
    private var activeArray: Rect = Rect(0, 0, 4080, 3060)

    @Volatile
    private var lastIso: Int? = null
    @Volatile
    private var lastExposureNs: Long? = null
    private var lastReadbackAt: Long = 0

    private var touchRegion: MeteringRectangle? = null

    val isTransitioning: Boolean get() = transitioning

    companion object {
        /** Divisao automatica: ~3,8 GB por arquivo (abaixo do limite FAT32). */
        const val SPLIT_BYTES: Long = 3_800_000_000L

        /** Formatos de foto (CamState.photoFormat). */
        const val PHOTO_RAW_JPEG: Int = 0
        const val PHOTO_JPEG_ONLY: Int = 1
        const val PHOTO_RAW_ONLY: Int = 2
    }

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

    /**
     * Quadros YUV da previa para o monitoramento: no maximo ~8 por
     * segundo e so com o monitoramento ligado; o resto e descartado.
     * A copia do plano Y roda nesta thread (fundo), nunca na tela.
     */
    private val analysisListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return@OnImageAvailableListener
        try {
            val now = System.currentTimeMillis()
            if (!monitoringEnabled || now - lastAnalysisAt < 120) return@OnImageAvailableListener
            lastAnalysisAt = now
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ByteArray(width * height)
            if (pixelStride == 1) {
                for (r in 0 until height) {
                    buffer.position(r * rowStride)
                    buffer.get(out, r * width, width)
                }
            } else {
                for (r in 0 until height) {
                    for (c in 0 until width) {
                        out[r * width + c] = buffer.get(r * rowStride + c * pixelStride)
                    }
                }
            }
            listener.onAnalysisFrame(out, width, height)
        } catch (e: Exception) {
            // Quadro ilegivel: descarta e segue (monitoramento nao pode
            // derrubar a camera).
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
            }
        }
    }

    private val jpegImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return@OnImageAvailableListener
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val pending = pendingPhoto
            if (pending != null && pending.wantJpeg && !pending.done) {
                pending.jpegBytes = bytes
                tryFinishPhoto()
            }
        } catch (e: Exception) {
            // Quadro ilegivel: o vigia da captura avisa e limpa.
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
            }
        }
    }

    private val rawImageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return@OnImageAvailableListener
        val pending = pendingPhoto
        if (pending != null && pending.wantRaw && !pending.done &&
            pending.rawImage == null
        ) {
            // O DNG precisa da imagem aberta ate o resultado chegar.
            pending.rawImage = image
            tryFinishPhoto()
        } else {
            try {
                image.close()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Etapa 5: troca a superficie de previa (TextureView comum <-> GL
     * com LUT). Recria so a sessao; o aparelho segue aberto. Durante a
     * gravacao recusa (a troca reabriria a sessao do gravador).
     */
    fun setPreviewSurface(surface: Surface): Boolean {
        if (recording || transitioning) return false
        previewSurface = surface
        if (device != null) {
            try {
                session?.close()
            } catch (e: Exception) {
            }
            session = null
            createSession(null)
        }
        return true
    }

    /** Amplitude maxima do microfone desde a ultima chamada (gravando). */
    fun currentMaxAmplitude(): Int {
        if (!recording) return 0
        return try {
            recorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    @SuppressLint("MissingPermission")
    fun open(surface: Surface) {
        previewSurface = surface
        try {
            val reader = ImageReader.newInstance(
                640, 360, ImageFormat.YUV_420_888, 2
            )
            reader.setOnImageAvailableListener(analysisListener, bgHandler)
            analysisReader = reader
            analysisSurface = reader.surface
        } catch (e: Exception) {
            // Sem leitor de analise a camera segue normal, sem monitoramento.
            analysisReader = null
            analysisSurface = null
        }
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
        // Etapa 6: leitores de foto nos maiores tamanhos (receita da
        // Fase 0: JPEG no tamanho do RAW quando existe, senao o maior).
        try {
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
            val rawSize = rawSizes.maxByOrNull { it.width * it.height }
            if (rawSize != null) {
                val reader = ImageReader.newInstance(
                    rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
                )
                reader.setOnImageAvailableListener(rawImageListener, bgHandler)
                rawReader = reader
                rawSurface = reader.surface
            }
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val jpegSize = jpegSizes.firstOrNull {
                rawSize != null && it.width == rawSize.width && it.height == rawSize.height
            } ?: jpegSizes.maxByOrNull { it.width * it.height }
            if (jpegSize != null) {
                val reader = ImageReader.newInstance(
                    jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2
                )
                reader.setOnImageAvailableListener(jpegImageListener, bgHandler)
                jpegReader = reader
                jpegSurface = reader.surface
            }
        } catch (e: Exception) {
            // Sem leitores de foto a camera segue; o botao FOTO avisa.
            jpegReader = null
            jpegSurface = null
            rawReader = null
            rawSurface = null
        }
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
        releaseRecorder()
        cleanupOutputs(delete = false)
        try {
            analysisReader?.close()
        } catch (e: Exception) {
        }
        analysisReader = null
        analysisSurface = null
        activeTargets = emptyList()
        repeatingTargets = emptyList()
        photoInSession = false
        try {
            pendingPhoto?.rawImage?.close()
        } catch (e: Exception) {
        }
        pendingPhoto = null
        try {
            jpegReader?.close()
            rawReader?.close()
        } catch (e: Exception) {
        }
        jpegReader = null
        jpegSurface = null
        rawReader = null
        rawSurface = null
    }

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
        if (repeatingTargets.isNotEmpty()) return repeatingTargets
        if (activeTargets.isNotEmpty()) return activeTargets
        val targets = mutableListOf<Surface>()
        previewSurface?.let { targets.add(it) }
        if (recording || transitioning) recorder?.surface?.let { targets.add(it) }
        return targets
    }

    private fun createSession(
        recorderSurface: Surface?,
        withAnalysis: Boolean = true,
        withPhoto: Boolean = recorderSurface == null
    ) {
        val dev = device ?: return
        val preview = previewSurface ?: return
        val targets = mutableListOf(preview)
        val analysis = analysisSurface
        if (withAnalysis && analysis != null) targets.add(analysis)
        // As saidas de foto entram na sessao, mas NUNCA no pedido
        // repetido (senao o JPEG/RAW receberiam video sem parar).
        val photoTargets = mutableListOf<Surface>()
        if (withPhoto) {
            jpegSurface?.let { photoTargets.add(it) }
            rawSurface?.let { photoTargets.add(it) }
        }
        targets.addAll(photoTargets)
        if (recorderSurface != null) targets.add(recorderSurface)
        activeTargets = targets
        repeatingTargets = targets - photoTargets.toSet()
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(newSession: CameraCaptureSession) {
                session = newSession
                photoInSession = withPhoto && photoTargets.isNotEmpty()
                try {
                    newSession.setRepeatingRequest(
                        buildRequest(dev, repeatingTargets), resultCallback, bgHandler
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
                        releaseRecorder()
                        cleanupOutputs(delete = true)
                        listener.onError("Falha ao gravar: ${e.message}")
                        createSession(null)
                    }
                } else {
                    transitioning = false
                }
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                if (recorderSurface != null && withAnalysis) {
                    // Sessao cheia demais (previa + analise + 4K): tenta
                    // de novo sem a analise. A gravacao manda; o
                    // monitoramento de imagem pausa nesta gravacao.
                    listener.onInfo(
                        "Monitoramento de imagem pausado nesta gravacao " +
                            "(sessao cheia); medidores seguem ativos."
                    )
                    createSession(recorderSurface, withAnalysis = false)
                    return
                }
                if (recorderSurface == null && withAnalysis && withPhoto) {
                    // Previa + analise + JPEG + RAW nao coube: a foto
                    // fica e o monitoramento de imagem pausa.
                    listener.onInfo(
                        "Monitoramento de imagem pausado (sessao cheia " +
                            "com a foto); medidores seguem ativos."
                    )
                    createSession(null, withAnalysis = false, withPhoto = true)
                    return
                }
                if (recorderSurface == null && withPhoto) {
                    listener.onInfo("Foto indisponivel nesta sessao.")
                    createSession(null, withAnalysis = withAnalysis, withPhoto = false)
                    return
                }
                if (recorderSurface == null && withAnalysis) {
                    createSession(null, withAnalysis = false, withPhoto = false)
                    return
                }
                transitioning = false
                if (recorderSurface != null) {
                    releaseRecorder()
                    cleanupOutputs(delete = true)
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
                val outputs = mutableListOf(
                    outRecorder, OutputConfiguration(preview)
                )
                if (withAnalysis && analysis != null) {
                    outputs.add(OutputConfiguration(analysis))
                }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
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
            val base = buildRequest(dev, currentTargets())
            activeSession.setRepeatingRequest(base, resultCallback, bgHandler)
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
    // Foto (Etapa 6): JPEG + RAW (DNG) com os controles da previa
    // ---------------------------------------------------------------
    fun capturePhoto(displayRotationDegrees: Int) {
        if (recording || transitioning) {
            listener.onInfo("Foto so com a gravacao parada.")
            return
        }
        val dev = device
        val activeSession = session
        if (dev == null || activeSession == null) {
            listener.onError("Camera ainda nao esta pronta.")
            return
        }
        if (pendingPhoto != null) {
            listener.onInfo("Aguarde a foto anterior salvar.")
            return
        }
        if (!photoInSession) {
            listener.onError("Foto indisponivel nesta sessao.")
            return
        }
        var wantJpeg = state.photoFormat != PHOTO_RAW_ONLY
        var wantRaw = state.photoFormat != PHOTO_JPEG_ONLY
        if (wantRaw && rawReader == null) {
            wantRaw = false
            wantJpeg = true
            listener.onInfo("Este aparelho nao expoe RAW; salvando so JPEG.")
        }
        val targets = mutableListOf<Surface>()
        if (wantJpeg) jpegSurface?.let { targets.add(it) }
        if (wantRaw) rawSurface?.let { targets.add(it) }
        if (targets.isEmpty() || (wantJpeg && jpegSurface == null)) {
            listener.onError("Nenhuma saida de foto disponivel.")
            return
        }
        val orientation = (sensorOrientation - displayRotationDegrees + 360) % 360
        val pending = PendingPhoto(
            baseName = buildBaseName(),
            wantJpeg = wantJpeg,
            wantRaw = wantRaw,
            orientationDegrees = orientation
        )
        pendingPhoto = pending
        try {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            for (target in targets) builder.addTarget(target)
            copyKeys(buildRequest(dev, targets), builder)
            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            activeSession.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        completedSession: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (pendingPhoto === pending) {
                            pending.result = result
                            tryFinishPhoto()
                        }
                    }

                    override fun onCaptureFailed(
                        failedSession: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        if (pendingPhoto === pending) {
                            pendingPhoto = null
                            try {
                                pending.rawImage?.close()
                            } catch (e: Exception) {
                            }
                            listener.onError("A captura da foto falhou.")
                        }
                    }
                },
                bgHandler
            )
            bgHandler.postDelayed({
                if (pendingPhoto === pending && !pending.done) {
                    pendingPhoto = null
                    try {
                        pending.rawImage?.close()
                    } catch (e: Exception) {
                    }
                    listener.onError("A foto nao completou a tempo; tente de novo.")
                }
            }, 6_000)
        } catch (e: Exception) {
            pendingPhoto = null
            listener.onError("Falha ao fotografar: ${e.message}")
        }
    }

    /** Salva quando JPEG, RAW e o resultado da captura ja chegaram. */
    private fun tryFinishPhoto() {
        val pending = pendingPhoto ?: return
        if (pending.done) return
        if (pending.wantJpeg && pending.jpegBytes == null) return
        if (pending.wantRaw && (pending.rawImage == null || pending.result == null)) {
            return
        }
        pending.done = true
        pendingPhoto = null
        val saved = mutableListOf<String>()
        val created = mutableListOf<OutputHandle>()
        try {
            if (pending.wantJpeg) {
                val out = createImageOutput(
                    pending.baseName + ".jpg", "image/jpeg"
                ) ?: throw IllegalStateException("sem destino para o JPEG")
                created.add(out)
                FileOutputStream(out.pfd.fileDescriptor).use { stream ->
                    stream.write(pending.jpegBytes!!)
                }
                out.finalize()
                try {
                    out.pfd.close()
                } catch (e: Exception) {
                }
                saved.add(out.name)
            }
            if (pending.wantRaw) {
                val characteristics = chars
                    ?: throw IllegalStateException("sem caracteristicas da camera")
                val out = createImageOutput(
                    pending.baseName + ".dng", "image/x-adobe-dng"
                ) ?: throw IllegalStateException("sem destino para o DNG")
                created.add(out)
                DngCreator(characteristics, pending.result!!).use { dng ->
                    dng.setOrientation(exifOrientation(pending.orientationDegrees))
                    FileOutputStream(out.pfd.fileDescriptor).use { stream ->
                        dng.writeImage(stream, pending.rawImage!!)
                    }
                }
                out.finalize()
                try {
                    out.pfd.close()
                } catch (e: Exception) {
                }
                saved.add(out.name)
            }
            listener.onPhotoSaved(
                "Foto salva: " + saved.joinToString(" + ") +
                    if (state.destSsd) " (SSD)" else " (Pictures/DXCam)"
            )
        } catch (e: Exception) {
            for (handle in created) {
                handle.delete()
                try {
                    handle.pfd.close()
                } catch (ignored: Exception) {
                }
            }
            listener.onError("Falha ao salvar a foto: ${e.message}")
        } finally {
            try {
                pending.rawImage?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** Cria o arquivo de imagem no destino atual (interno ou SSD). */
    private fun createImageOutput(name: String, mime: String): OutputHandle? {
        val resolver = context.contentResolver
        return try {
            if (state.destSsd && state.treeUri.isNotEmpty()) {
                val treeUri = Uri.parse(state.treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val docUri = DocumentsContract.createDocument(
                    resolver, parent, mime, name
                ) ?: return null
                val pfd = resolver.openFileDescriptor(docUri, "w") ?: return null
                OutputHandle(
                    name = name,
                    pfd = pfd,
                    finalize = { /* SAF nao tem estado pendente */ },
                    delete = {
                        try {
                            DocumentsContract.deleteDocument(resolver, docUri)
                        } catch (e: Exception) {
                        }
                    }
                )
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DXCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                val pfd = resolver.openFileDescriptor(uri, "w") ?: return null
                OutputHandle(
                    name = name,
                    pfd = pfd,
                    finalize = {
                        try {
                            val done = ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }
                            resolver.update(uri, done, null, null)
                        } catch (e: Exception) {
                        }
                    },
                    delete = {
                        try {
                            resolver.delete(uri, null, null)
                        } catch (e: Exception) {
                        }
                    }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------
    // Gravacao (Etapa 3): destinos, nomeacao e divisao automatica
    // ---------------------------------------------------------------
    private fun buildBaseName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = state.projectPrefix.trim()
        return if (prefix.isEmpty()) "DXCam_$stamp" else "DXCam_${prefix}_$stamp"
    }

    private fun partName(part: Int): String =
        if (part <= 1) "$recordBaseName.mp4" else "${recordBaseName}_parte$part.mp4"

    /** Cria o arquivo da parte [part] no destino atual (interno ou SSD). */
    private fun createOutput(part: Int): OutputHandle? {
        val resolver = context.contentResolver
        val name = partName(part)
        return try {
            if (state.destSsd && state.treeUri.isNotEmpty()) {
                val treeUri = Uri.parse(state.treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val docUri = DocumentsContract.createDocument(
                    resolver, parent, "video/mp4", name
                ) ?: return null
                val pfd = resolver.openFileDescriptor(docUri, "w") ?: return null
                OutputHandle(
                    name = name,
                    pfd = pfd,
                    finalize = { /* SAF nao tem estado pendente */ },
                    delete = {
                        try {
                            DocumentsContract.deleteDocument(resolver, docUri)
                        } catch (e: Exception) {
                        }
                    }
                )
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DXCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                val pfd = resolver.openFileDescriptor(uri, "w") ?: return null
                OutputHandle(
                    name = name,
                    pfd = pfd,
                    finalize = {
                        try {
                            val done = ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }
                            resolver.update(uri, done, null, null)
                        } catch (e: Exception) {
                        }
                    },
                    delete = {
                        try {
                            resolver.delete(uri, null, null)
                        } catch (e: Exception) {
                        }
                    }
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun startRecording() {
        if (recording || transitioning) return
        val mode = state.mode
        transitioning = true
        recordBaseName = buildBaseName()
        partIndex = 1
        val first = createOutput(1)
        if (first == null) {
            transitioning = false
            listener.onError(
                if (state.destSsd) {
                    "Nao consegui criar o arquivo no SSD. Confira a pasta " +
                        "(toque longo em SSD para escolher de novo)."
                } else {
                    "Nao foi possivel criar o arquivo de video."
                }
            )
            return
        }
        currentOutput = first
        outputs.add(first)
        try {
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
            rec.setVideoEncodingBitRate(state.effectiveBitrateMbps() * 1_000_000)
            rec.setOrientationHint(sensorOrientation)
            rec.setOutputFile(first.pfd.fileDescriptor)
            rec.setMaxFileSize(SPLIT_BYTES)
            // Sem a sobrecarga com Handler: ela nao existe na API publica
            // visivel ao compilador (erro de build da v0.3.0). Os eventos
            // sao raros (so nas divisoes de arquivo) e chegam na thread
            // principal, o que basta para preparar a proxima parte.
            rec.setOnInfoListener { _, what, _ ->
                onRecorderInfo(what)
            }
            rec.prepare()
        } catch (e: Exception) {
            transitioning = false
            releaseRecorder()
            cleanupOutputs(delete = true)
            listener.onError("Falha ao preparar a gravacao: ${e.message}")
            return
        }
        createSession(recorder?.surface)
    }

    private fun onRecorderInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (nextOutput == null) {
                    val next = createOutput(partIndex + 1)
                    if (next != null) {
                        try {
                            recorder?.setNextOutputFile(next.pfd.fileDescriptor)
                            nextOutput = next
                            outputs.add(next)
                        } catch (e: Exception) {
                            next.delete()
                            try {
                                next.pfd.close()
                            } catch (ignored: Exception) {
                            }
                        }
                    }
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                val finishedPart = currentOutput
                finishedPart?.finalize()
                val promoted = nextOutput
                if (promoted != null) {
                    currentOutput = promoted
                    nextOutput = null
                    partIndex += 1
                    listener.onFileSplit(partIndex)
                }
            }
        }
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
            // Arquivo pode sair curto; finaliza mesmo assim.
        }
        releaseRecorder()
        // A parte que estava preparada mas nunca usada sai vazia: apaga.
        nextOutput?.let { unused ->
            unused.delete()
            outputs.remove(unused)
        }
        nextOutput = null
        currentOutput?.finalize()
        for (handle in outputs) {
            try {
                handle.pfd.close()
            } catch (e: Exception) {
            }
        }
        outputs.clear()
        currentOutput = null
        listener.onRecordingChanged(false)
        createSession(null)
    }

    private fun releaseRecorder() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
    }

    private fun cleanupOutputs(delete: Boolean) {
        for (handle in outputs) {
            if (delete) handle.delete()
            try {
                handle.pfd.close()
            } catch (e: Exception) {
            }
        }
        outputs.clear()
        currentOutput = null
        nextOutput = null
    }
}
