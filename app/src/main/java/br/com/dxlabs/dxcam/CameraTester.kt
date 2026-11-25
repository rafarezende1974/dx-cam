package br.com.dxlabs.dxcam

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Range
import android.util.Rational
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executor

/**
 * DX Cam - Fase 0: testes que abrem a camera e gravam de verdade.
 *
 * v0.1.2-diag - o que o reteste da v0.1.1 ensinou e esta versao prova:
 * 1) FPS: os dois clipes da v0.1.1 sairam com 877 quadros em 30 s
 *    (~29,2 fps) tanto no teste de 24 fps quanto no de 60 fps. Causa: o
 *    pedido de captura nao fixava CONTROL_AE_TARGET_FPS_RANGE, entao a
 *    camera rodou na faixa padrao dela. Agora a faixa [fps, fps] vai no
 *    pedido, como a v1 fara.
 * 2) MANUAL EM VIDEO: na metade de cada gravacao padrao, o pedido troca
 *    para manual travado (AE/AF/AWB desligados, ISO 800, obturador
 *    1/(2*fps), foco a 2 m, ganhos de cor fixos) e o relatorio traz os
 *    valores LIDOS do ultimo quadro (CaptureResult), nao so os pedidos.
 * 3) 10-BIT: teste separado monta a sessao com o perfil de faixa
 *    dinamica HLG10 na saida do gravador (API 33+). O clipe SDR da
 *    v0.1.1 saiu BT.709/range limitado pelo caminho padrao do
 *    MediaRecorder; este teste mostra se o arquivo sai BT.2020/HLG.
 *
 * A foto RAW e o teste de escrita sao os mesmos da v0.1.1 (aprovados).
 */
class CameraTester(
    private val context: Context,
    private val bgHandler: Handler
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val bgExecutor = Executor { command -> bgHandler.post(command) }

    @Volatile
    var busy: Boolean = false
        private set

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var previewReader: ImageReader? = null
    private var rawReaderRef: ImageReader? = null
    private var jpegReaderRef: ImageReader? = null

    // Estado do teste ativo (um por vez, garantido pelo busy).
    private var stage: String = ""
    private var finished: Boolean = true
    private var activeTitle: String = ""
    private var activeDone: ((String) -> Unit)? = null
    private var watchdog: Runnable? = null

    @Volatile
    private var lastResult: TotalCaptureResult? = null
    private var manualNote: String = ""

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            completedSession: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastResult = result
        }
    }

    companion object {
        fun batterySnapshot(context: Context): String {
            val intent = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0) level * 100 / scale else -1
            return "bateria $pct%, temperatura da bateria ${String.format(Locale.US, "%.1f", temp)} C"
        }
    }

    private fun beginTest(title: String, timeoutMs: Long, done: (String) -> Unit) {
        busy = true
        finished = false
        stage = "iniciando"
        activeTitle = title
        activeDone = done
        lastResult = null
        manualNote = ""
        val runnable = Runnable {
            if (!finished) {
                val text = "\n=== $activeTitle ===\n" +
                    "FALHA: o teste travou na etapa \"$stage\" e foi encerrado pelo vigia " +
                    "apos ${timeoutMs / 1000} s. Feche e reabra o app e rode so este teste."
                finishTest(text)
            }
        }
        watchdog = runnable
        bgHandler.postDelayed(runnable, timeoutMs)
    }

    private fun finishTest(text: String) {
        if (finished) return
        finished = true
        watchdog?.let { bgHandler.removeCallbacks(it) }
        watchdog = null
        cleanupAll()
        busy = false
        val done = activeDone
        activeDone = null
        done?.invoke(text)
    }

    private fun failBusy(title: String, done: (String) -> Unit) {
        done("\n=== $title ===\nOCUPADO: outro teste em andamento.")
    }

    private fun cleanupAll() {
        try {
            session?.close()
        } catch (e: Exception) {
        }
        session = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
        }
        cameraDevice = null
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        for (reader in listOf(previewReader, rawReaderRef, jpegReaderRef)) {
            try {
                reader?.close()
            } catch (e: Exception) {
            }
        }
        previewReader = null
        rawReaderRef = null
        jpegReaderRef = null
    }

    private fun newPreviewReader(): ImageReader {
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.close()
            } catch (e: Exception) {
            }
        }, bgHandler)
        previewReader = reader
        return reader
    }

    // ---------------------------------------------------------------
    // Teste de gravacao (padrao SDR ou 10-bit HLG)
    // ---------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun recordTest(
        label: String,
        width: Int,
        height: Int,
        fps: Int,
        videoEncoder: Int,
        bitrateMbps: Int,
        durationMs: Long,
        outDir: File,
        hlg: Boolean = false,
        manualSecondHalf: Boolean = true,
        status: (String) -> Unit,
        done: (String) -> Unit
    ) {
        val title = "TESTE DE GRAVACAO $label"
        if (busy) {
            failBusy(title, done)
            return
        }
        beginTest(title, durationMs + 25_000, done)
        val cameraId = CameraProbe.pickMainBackCameraId(manager)
        if (cameraId == null) {
            finishTest("\n=== $title ===\nSem camera traseira.")
            return
        }
        outDir.mkdirs()
        val file = File(outDir, "teste_$label.mp4")
        if (file.exists()) file.delete()
        val batteryStart = batterySnapshot(context)
        status("Gravando teste $label (${durationMs / 1000} s)... nao feche o app.")

        val rec = MediaRecorder(context)
        recorder = rec
        try {
            stage = "preparando o gravador"
            val chars = manager.getCameraCharacteristics(cameraId)
            val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(192_000)
            rec.setAudioSamplingRate(48_000)
            rec.setVideoEncoder(videoEncoder)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(bitrateMbps * 1_000_000)
            rec.setOrientationHint(orientation)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
        } catch (e: Exception) {
            finishTest("\n=== $title ===\nFALHA ao preparar: ${e.message}")
            return
        }

        val surface: Surface = rec.surface
        val preview = newPreviewReader()
        stage = "abrindo a camera"
        status("Abrindo a camera para o teste $label...")
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    stage = "configurando a sessao"
                    status("Camera aberta, configurando a sessao...")
                    val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(newSession: CameraCaptureSession) {
                            session = newSession
                            stage = "gravando"
                            try {
                                val builder =
                                    device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                builder.addTarget(surface)
                                builder.addTarget(preview.surface)
                                builder.set(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_AUTO
                                )
                                builder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                )
                                builder.set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                                builder.set(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    Range(fps, fps)
                                )
                                newSession.setRepeatingRequest(
                                    builder.build(), resultCallback, bgHandler
                                )
                                rec.start()
                                status("Gravando $label... aguarde ${durationMs / 1000} s.")
                            } catch (e: Exception) {
                                finishTest(
                                    "\n=== $title ===\nFALHA ao iniciar: ${e.message}"
                                )
                                return
                            }
                            if (manualSecondHalf) {
                                bgHandler.postDelayed({
                                    if (!finished) switchToManual(
                                        device, newSession, surface, preview.surface, fps, status
                                    )
                                }, durationMs / 2)
                            }
                            bgHandler.postDelayed({
                                stopRecording(
                                    title, file, width, height, fps,
                                    bitrateMbps, batteryStart
                                )
                            }, durationMs)
                        }

                        override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                            val extra = if (hlg) {
                                " (sessao HLG10 recusada pelo aparelho - a v1 usaria " +
                                    "MediaCodec para o 10-bit)"
                            } else {
                                ""
                            }
                            finishTest("\n=== $title ===\nFALHA: sessao nao configurada.$extra")
                        }
                    }
                    try {
                        if (hlg) {
                            val outRecorder = OutputConfiguration(surface)
                            outRecorder.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                            val outPreview = OutputConfiguration(preview.surface)
                            val config = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(outRecorder, outPreview),
                                bgExecutor,
                                sessionCallback
                            )
                            device.createCaptureSession(config)
                        } else {
                            device.createCaptureSession(
                                listOf(surface, preview.surface),
                                sessionCallback,
                                bgHandler
                            )
                        }
                    } catch (e: Exception) {
                        finishTest("\n=== $title ===\nFALHA ao criar a sessao: ${e.message}")
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    finishTest("\n=== $title ===\nCamera desconectada na etapa \"$stage\".")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    finishTest("\n=== $title ===\nERRO de camera: $error (etapa \"$stage\")")
                }
            }, bgHandler)
        } catch (e: Exception) {
            finishTest("\n=== $title ===\nFALHA ao abrir a camera: ${e.message}")
        }
    }

    /** Troca o pedido repetido para manual travado (prova de controle em video). */
    private fun switchToManual(
        device: CameraDevice,
        activeSession: CameraCaptureSession,
        recordSurface: Surface,
        previewSurface: Surface,
        fps: Int,
        status: (String) -> Unit
    ) {
        try {
            stage = "gravando (manual)"
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(recordSurface)
            builder.addTarget(previewSurface)
            builder.set(
                CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO
            )
            builder.set(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF
            )
            builder.set(
                CaptureRequest.SENSOR_SENSITIVITY, 800
            )
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / (2L * fps)
            )
            builder.set(
                CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps
            )
            builder.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE, 0.5f // 0,5 dioptria = foco a 2 m
            )
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
                RggbChannelVector(1.9f, 1.0f, 1.0f, 1.7f)
            )
            activeSession.setRepeatingRequest(builder.build(), resultCallback, bgHandler)
            manualNote = "manual aplicado na metade da gravacao"
            status("Metade manual: ISO 800, obturador 1/${2 * fps}, foco a 2 m, branco fixo.")
        } catch (e: Exception) {
            manualNote = "FALHA ao aplicar o manual: ${e.message}"
        }
    }

    private fun stopRecording(
        title: String,
        file: File,
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int,
        batteryStart: String
    ) {
        stage = "parando e analisando"
        var stopError: String? = null
        try {
            session?.stopRepeating()
        } catch (e: Exception) {
        }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            stopError = e.message
        }
        val batteryEnd = batterySnapshot(context)
        val text = analyzeRecording(
            title, file, width, height, fps, bitrateMbps,
            batteryStart, batteryEnd, stopError
        )
        finishTest(text)
    }

    private fun analyzeRecording(
        title: String,
        file: File,
        reqWidth: Int,
        reqHeight: Int,
        reqFps: Int,
        reqBitrateMbps: Int,
        batteryStart: String,
        batteryEnd: String,
        stopError: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== $title ===")
        sb.appendLine("Pedido: ${reqWidth}x$reqHeight @ $reqFps fps, $reqBitrateMbps Mbps")
        sb.appendLine("Inicio: $batteryStart | Fim: $batteryEnd")
        if (stopError != null) sb.appendLine("Aviso ao parar: $stopError")
        if (manualNote.isNotEmpty()) sb.appendLine("Controle manual: $manualNote")
        val result = lastResult
        if (result != null) {
            val iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY)
            val expNs = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)
            val focus = result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE)
            val awb = result.get(TotalCaptureResult.CONTROL_AWB_MODE)
            sb.appendLine(
                "Ultimo quadro (lido do CaptureResult): ISO=$iso, " +
                    "obturador=${expNs?.let { "1/${Math.round(1_000_000_000.0 / it)} s" } ?: "?"}, " +
                    "foco=${focus ?: "?"} dioptrias, modo AWB=$awb (0=OFF/manual)"
            )
        }
        if (!file.exists() || file.length() == 0L) {
            sb.appendLine("Resultado: arquivo nao gerado ou vazio (${file.absolutePath})")
            return sb.toString()
        }
        val sizeMb = file.length() / 1_000_000.0
        sb.appendLine(
            "Arquivo: ${file.name} - ${String.format(Locale.US, "%.1f", sizeMb)} MB"
        )
        var durationSec = 0.0
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val bitrate =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull() ?: 0L
            val captureFps = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )
            retriever.release()
            durationSec = durationMs / 1000.0
            sb.appendLine(
                "Duracao real: ${String.format(Locale.US, "%.1f", durationSec)} s | " +
                    "resolucao no arquivo: ${w}x$h | " +
                    "bitrate (metadado): ${bitrate / 1_000_000} Mbps | " +
                    "fps (metadado): ${captureFps ?: "?"}"
            )
            if (durationSec > 0) {
                sb.appendLine(
                    "Bitrate efetivo (tamanho/duracao): " +
                        String.format(Locale.US, "%.1f", sizeMb * 8 / durationSec) + " Mbps"
                )
            }
        } catch (e: Exception) {
            sb.appendLine("Metadados: erro (${e.message})")
        }
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
                if (!mime.startsWith("video")) continue
                val colorStandard =
                    if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                        format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else -1
                val colorTransfer =
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else -1
                val colorRange =
                    if (format.containsKey(MediaFormat.KEY_COLOR_RANGE))
                        format.getInteger(MediaFormat.KEY_COLOR_RANGE) else -1
                sb.appendLine(
                    "Trilha de video: $mime | colorStandard=$colorStandard " +
                        "colorTransfer=$colorTransfer colorRange=$colorRange"
                )
                sb.appendLine(
                    "  (Android: standard 1=BT.709, 6=BT.2020; transfer 3=SDR, 8=HLG, " +
                        "7=PQ; range 2=limitado, 1=cheio. BT.2020+HLG/PQ = caminho 10-bit)"
                )
                extractor.selectTrack(i)
                var samples = 0
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    samples++
                    if (!extractor.advance()) break
                }
                val realFps = if (durationSec > 0) samples / durationSec else 0.0
                sb.appendLine(
                    "  Amostras (quadros) contadas: $samples " +
                        "(fps real ~${String.format(Locale.US, "%.1f", realFps)}, pedido $reqFps)"
                )
                extractor.unselectTrack(i)
            }
            extractor.release()
        } catch (e: Exception) {
            sb.appendLine("Analise de trilhas: erro (${e.message})")
        }
        sb.appendLine("Veredito: conferir o arquivo no Premiere para confirmar codec/cor.")
        return sb.toString()
    }

    // ---------------------------------------------------------------
    // Teste de foto RAW (DNG) + JPEG - igual ao da v0.1.1 (aprovado)
    // ---------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun captureRawTest(
        outDir: File,
        status: (String) -> Unit,
        done: (String) -> Unit
    ) {
        val title = "TESTE DE FOTO RAW"
        if (busy) {
            failBusy(title, done)
            return
        }
        beginTest(title, 30_000, done)
        val cameraId = CameraProbe.pickMainBackCameraId(manager)
        if (cameraId == null) {
            finishTest("\n=== $title ===\nSem camera traseira.")
            return
        }
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
        if (rawSizes.isEmpty()) {
            finishTest("\n=== $title ===\nRAW nao listado para a camera $cameraId.")
            return
        }
        val rawSize = rawSizes.maxByOrNull { it.width * it.height }!!
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
        val jpegSize = jpegSizes.filter { it.width == rawSize.width && it.height == rawSize.height }
            .firstOrNull() ?: jpegSizes.maxByOrNull { it.width * it.height } ?: rawSize

        outDir.mkdirs()
        status("Capturando foto RAW de teste (${rawSize.width}x${rawSize.height})...")
        val rawReader = ImageReader.newInstance(
            rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
        )
        val jpegReader = ImageReader.newInstance(
            jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2
        )
        rawReaderRef = rawReader
        jpegReaderRef = jpegReader
        val preview = newPreviewReader()

        var rawImage: Image? = null
        var jpegImage: Image? = null
        var captureResult: TotalCaptureResult? = null

        fun trySave() {
            if (finished) return
            val raw = rawImage ?: return
            val jpeg = jpegImage ?: return
            val result = captureResult ?: return
            val sb = StringBuilder()
            sb.appendLine()
            sb.appendLine("=== $title ===")
            try {
                val jpegFile = File(outDir, "teste_foto.jpg")
                val buffer = jpeg.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(jpegFile).use { it.write(bytes) }
                sb.appendLine(
                    "JPEG: ${jpegFile.name} - " +
                        String.format(Locale.US, "%.2f", jpegFile.length() / 1_000_000.0) + " MB"
                )
                val dngFile = File(outDir, "teste_foto.dng")
                DngCreator(chars, result).use { dng ->
                    FileOutputStream(dngFile).use { stream ->
                        dng.writeImage(stream, raw)
                    }
                }
                sb.appendLine(
                    "DNG: ${dngFile.name} - " +
                        String.format(Locale.US, "%.2f", dngFile.length() / 1_000_000.0) + " MB"
                )
                sb.appendLine(
                    "Veredito: DNG gerado. Abrir no Lightroom e conferir ISO/exposicao nos metadados."
                )
            } catch (e: Exception) {
                sb.appendLine("FALHA ao salvar: ${e.message}")
            } finally {
                try {
                    raw.close()
                    jpeg.close()
                } catch (e: Exception) {
                }
            }
            finishTest(sb.toString())
        }

        rawReader.setOnImageAvailableListener({ reader ->
            try {
                rawImage?.close()
                rawImage = reader.acquireLatestImage()
            } catch (e: Exception) {
            }
            trySave()
        }, bgHandler)
        jpegReader.setOnImageAvailableListener({ reader ->
            try {
                jpegImage?.close()
                jpegImage = reader.acquireLatestImage()
            } catch (e: Exception) {
            }
            trySave()
        }, bgHandler)

        stage = "abrindo a camera"
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    stage = "configurando a sessao"
                    try {
                        device.createCaptureSession(
                            listOf(rawReader.surface, jpegReader.surface, preview.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(newSession: CameraCaptureSession) {
                                    session = newSession
                                    stage = "aguardando estabilizar e capturando"
                                    try {
                                        val previewBuilder = device.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW
                                        )
                                        previewBuilder.addTarget(preview.surface)
                                        previewBuilder.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                        )
                                        previewBuilder.set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON
                                        )
                                        newSession.setRepeatingRequest(
                                            previewBuilder.build(), null, bgHandler
                                        )
                                    } catch (e: Exception) {
                                        // Segue para a captura mesmo sem previa continua.
                                    }
                                    bgHandler.postDelayed({
                                        if (finished) return@postDelayed
                                        try {
                                            val captureBuilder = device.createCaptureRequest(
                                                CameraDevice.TEMPLATE_STILL_CAPTURE
                                            )
                                            captureBuilder.addTarget(rawReader.surface)
                                            captureBuilder.addTarget(jpegReader.surface)
                                            captureBuilder.set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                            )
                                            captureBuilder.set(
                                                CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON
                                            )
                                            captureBuilder.set(
                                                CaptureRequest.JPEG_ORIENTATION, 90
                                            )
                                            newSession.capture(
                                                captureBuilder.build(),
                                                object :
                                                    CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureCompleted(
                                                        completedSession: CameraCaptureSession,
                                                        request: CaptureRequest,
                                                        result: TotalCaptureResult
                                                    ) {
                                                        captureResult = result
                                                        trySave()
                                                    }
                                                },
                                                bgHandler
                                            )
                                        } catch (e: Exception) {
                                            finishTest(
                                                "\n=== $title ===\nFALHA na captura: ${e.message}"
                                            )
                                        }
                                    }, 1500)
                                }

                                override fun onConfigureFailed(
                                    failedSession: CameraCaptureSession
                                ) {
                                    finishTest(
                                        "\n=== $title ===\nFALHA: sessao nao configurada."
                                    )
                                }
                            },
                            bgHandler
                        )
                    } catch (e: Exception) {
                        finishTest("\n=== $title ===\nFALHA ao criar a sessao: ${e.message}")
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    finishTest("\n=== $title ===\nCamera desconectada na etapa \"$stage\".")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    finishTest("\n=== $title ===\nERRO de camera: $error (etapa \"$stage\")")
                }
            }, bgHandler)
        } catch (e: Exception) {
            finishTest("\n=== $title ===\nFALHA ao abrir a camera: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    // Teste de escrita (interno e volumes externos/SSD) - igual ao aprovado
    // ---------------------------------------------------------------
    fun writeSpeedTest(done: (String) -> Unit) {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== TESTE DE ESCRITA (128 MB por volume) ===")
        val storageManager = context.getSystemService(StorageManager::class.java)
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        if (dirs.isEmpty()) {
            sb.appendLine("Nenhum diretorio de arquivos disponivel.")
            done(sb.toString())
            return
        }
        val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val totalBytes = 128L * 1024 * 1024
        for (dir in dirs) {
            val volume = try {
                storageManager.getStorageVolume(dir)
            } catch (e: Exception) {
                null
            }
            val kind = when {
                volume?.isPrimary == true -> "interno"
                volume?.isRemovable == true -> "removivel (SSD/cartao)"
                else -> "outro"
            }
            val file = File(dir, "dxcam_write_test.bin")
            try {
                val freeBefore = StatFs(dir.absolutePath).availableBytes
                val start = System.nanoTime()
                FileOutputStream(file).use { out ->
                    var written = 0L
                    while (written < totalBytes) {
                        out.write(chunk)
                        written += chunk.size
                    }
                    out.flush()
                    out.fd.sync()
                }
                val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
                val mbps = (totalBytes / 1_000_000.0) / elapsedSec
                sb.appendLine(
                    "- $kind: ${dir.absolutePath}"
                )
                sb.appendLine(
                    "  ${String.format(Locale.US, "%.1f", mbps)} MB/s sustentados, " +
                        String.format(
                            Locale.US, "%.1f", freeBefore / 1_000_000_000.0
                        ) + " GB livres antes do teste"
                )
            } catch (e: Exception) {
                sb.appendLine("- $kind: FALHA (${e.message}) em ${dir.absolutePath}")
            } finally {
                try {
                    file.delete()
                } catch (e: Exception) {
                }
            }
        }
        sb.appendLine(
            "Referencia: 4K H.265 a 50 Mbps pede ~6,3 MB/s sustentados; qualquer volume acima disso passa."
        )
        done(sb.toString())
    }
}
