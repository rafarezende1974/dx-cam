package br.com.dxlabs.dxcam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Range
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DX Cam - Fase 0: sondas de leitura (sem abrir a camera).
 *
 * CameraProbe le as CameraCharacteristics de cada lente e responde, com
 * numeros, o que o aparelho expoe: faixas de ISO e de obturador, foco
 * manual, branco manual, RAW, combinacoes de video e perfis 10-bit.
 * MediaProbe cobre encoders (HEVC Main10 de verdade), entradas de audio
 * e armazenamento (interno e volumes removiveis/SSD).
 *
 * Nada aqui abre a camera nem grava: e seguro rodar sem permissao.
 * Os testes que abrem a camera moram no CameraTester.
 */
object ReportHeader {
    fun build(appVersion: String): String {
        val sb = StringBuilder()
        sb.appendLine("DX CAM - RELATORIO DA FASE 0")
        sb.appendLine("App de diagnostico: $appVersion (pacote br.com.dxlabs.dxcam)")
        sb.appendLine(
            "Gerado em: " +
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
        )
        sb.appendLine(
            "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        )
        sb.appendLine(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "patch ${Build.VERSION.SECURITY_PATCH}"
        )
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        return sb.toString()
    }
}

object CameraProbe {

    // Valores AOSP de REQUEST_AVAILABLE_CAPABILITIES (estaveis).
    private val capabilityNames = mapOf(
        0 to "BACKWARD_COMPATIBLE",
        1 to "MANUAL_SENSOR",
        2 to "MANUAL_POST_PROCESSING",
        3 to "RAW",
        4 to "PRIVATE_REPROCESSING",
        5 to "READ_SENSOR_SETTINGS",
        6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING",
        8 to "DEPTH_OUTPUT",
        9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
        10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA",
        12 to "MONOCHROME",
        13 to "SECURE_IMAGE_DATA",
        14 to "DYNAMIC_RANGE_TEN_BIT",
        15 to "STREAM_USE_CASE",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR",
        17 to "REMOSAIC_REPROCESSING"
    )

    private fun hardwareLevelName(level: Int): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "desconhecido ($level)"
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONTAL"
        CameraCharacteristics.LENS_FACING_BACK -> "TRASEIRA"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNA"
        else -> "?"
    }

    private fun formatExposureTime(ns: Long): String {
        if (ns <= 0) return "$ns ns"
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.2f s", seconds)
        } else {
            "1/${Math.round(1_000_000_000.0 / ns)} s"
        }
    }

    /** Camera traseira principal: a logica traseira, ou a primeira traseira. */
    fun pickMainBackCameraId(manager: CameraManager): String? {
        var firstBack: String? = null
        for (id in manager.cameraIdList) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }
            if (chars.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                continue
            }
            if (firstBack == null) firstBack = id
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (caps?.contains(11) == true) return id // LOGICAL_MULTI_CAMERA
        }
        return firstBack
    }

    fun buildReport(context: Context): String {
        val manager = context.getSystemService(CameraManager::class.java)
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== CAMERAS (Camera2) ===")
        sb.appendLine("IDs: ${manager.cameraIdList.joinToString(", ")}")
        for (id in manager.cameraIdList) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                sb.appendLine()
                sb.appendLine("--- Camera $id: ERRO ao ler caracteristicas: ${e.message}")
                continue
            }
            appendCamera(sb, id, chars)
        }
        return sb.toString()
    }

    private fun appendCamera(
        sb: StringBuilder,
        id: String,
        chars: CameraCharacteristics
    ) {
        sb.appendLine()
        sb.appendLine(
            "--- Camera $id (${facingName(chars.get(CameraCharacteristics.LENS_FACING))}) ---"
        )
        val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        sb.appendLine("Nivel de hardware: ${level?.let { hardwareLevelName(it) }}")
        val physical = try {
            chars.physicalCameraIds
        } catch (e: Exception) {
            emptySet()
        }
        if (physical.isNotEmpty()) {
            sb.appendLine("Cameras fisicas (logica): ${physical.joinToString(", ")}")
        }
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList().orEmpty()
        sb.appendLine(
            "Capacidades: " +
                caps.joinToString(", ") { capabilityNames[it] ?: "CAP_$it" }
        )

        val isoRange: Range<Int>? =
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        sb.appendLine("ISO: ${isoRange?.let { "${it.lower} a ${it.upper}" } ?: "sem faixa declarada"}")
        val expRange: Range<Long>? =
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        sb.appendLine(
            "Obturador: " + (expRange?.let {
                "${formatExposureTime(it.lower)} a ${formatExposureTime(it.upper)} " +
                    "(${it.lower} a ${it.upper} ns)"
            } ?: "sem faixa declarada")
        )
        val maxFrameNs = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        sb.appendLine("Duracao maxima de quadro: ${maxFrameNs?.let { formatExposureTime(it) } ?: "?"}")
        val aeComp = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        sb.appendLine("Compensacao EV: ${aeComp?.toString() ?: "?"} (passo $aeStep)")

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        sb.appendLine("Modos AF: ${afModes?.joinToString(", ") ?: "?"}")
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        sb.appendLine(
            "Foco manual: " + when {
                minFocus == null -> "?"
                minFocus > 0f -> "SIM - distancia minima ${String.format(Locale.US, "%.2f", 1f / minFocus)} m (dioptria max $minFocus)"
                else -> "NAO (foco fixo)"
            }
        )
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        val awbOff = awbModes?.contains(CameraCharacteristics.CONTROL_AWB_MODE_OFF) == true
        sb.appendLine("Modos AWB: ${awbModes?.joinToString(", ") ?: "?"} (OFF/manual = $awbOff)")
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        sb.appendLine("Aberturas: ${apertures?.joinToString(", ") { "f/$it" } ?: "?"}")
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        sb.appendLine(
            "Distancias focais: ${focals?.joinToString(", ") { String.format(Locale.US, "%.1f mm", it) } ?: "?"}"
        )
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        sb.appendLine("Estabilizacao optica (modos): ${ois?.joinToString(", ") ?: "?"}")
        val videoStab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        sb.appendLine("Estabilizacao de video (modos): ${videoStab?.joinToString(", ") ?: "?"}")
        val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        sb.appendLine("Zoom: faixa de razao $zoomRange, digital max ${maxZoom ?: "?"}x")
        val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        sb.appendLine("Flash: ${flash ?: false}")
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        sb.appendLine(
            "Sensor: ${sensorSize?.let { String.format(Locale.US, "%.2f x %.2f mm", it.width, it.height) } ?: "?"}"
        )
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sb.appendLine("Matriz ativa: ${activeArray?.let { "${it.width()} x ${it.height()}" } ?: "?"}")

        // Perfis de faixa dinamica (10-bit de verdade passa por aqui).
        try {
            val profiles: DynamicRangeProfiles? =
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            if (profiles != null) {
                val names = profiles.supportedProfiles.joinToString(", ") { profileName(it) }
                sb.appendLine("Perfis de faixa dinamica: $names")
            } else {
                sb.appendLine("Perfis de faixa dinamica: nao declarados")
            }
        } catch (e: Exception) {
            sb.appendLine("Perfis de faixa dinamica: erro (${e.message})")
        }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            sb.appendLine("Mapa de tamanhos: indisponivel")
            return
        }
        val recorderSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
        sb.appendLine("Tamanhos de video (MediaRecorder): ${recorderSizes.size}")
        val keySizes = listOf(
            Size(3840, 2160), Size(2560, 1440), Size(1920, 1080),
            Size(1280, 720), Size(2160, 3840), Size(1080, 1920)
        )
        for (size in keySizes) {
            if (recorderSizes.contains(size)) {
                val minNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                val fps = if (minNs > 0) Math.round(10_000_000_000.0 / minNs) / 10.0 else 0.0
                sb.appendLine("  ${size.width}x${size.height}: sim, ate ~$fps fps")
            } else {
                sb.appendLine("  ${size.width}x${size.height}: NAO listado")
            }
        }
        val hsSizes = map.highSpeedVideoSizes.orEmpty()
        if (hsSizes.isNotEmpty()) {
            sb.appendLine("Alta velocidade (slow motion):")
            for (size in hsSizes.take(6)) {
                val ranges = map.getHighSpeedVideoFpsRangesFor(size)
                sb.appendLine(
                    "  ${size.width}x${size.height}: ${ranges?.joinToString(", ") { "${it.lower}-${it.upper} fps" }}"
                )
            }
        }
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
        sb.appendLine(
            "RAW (DNG): " + if (rawSizes.isEmpty()) {
                "NAO suportado"
            } else {
                "sim - maior ${rawSizes.maxByOrNull { it.width * it.height }?.let { "${it.width}x${it.height}" }}"
            }
        )
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG).orEmpty()
        sb.appendLine(
            "JPEG: maior ${jpegSizes.maxByOrNull { it.width * it.height }?.let { "${it.width}x${it.height}" } ?: "nenhum"}"
        )

        // Vereditos rapidos da lente.
        val hasManualSensor = caps.contains(1)
        val hasRaw = caps.contains(3) && rawSizes.isNotEmpty()
        sb.appendLine(
            "Veredito: ISO manual=${simNao(hasManualSensor && isoRange != null)}, " +
                "obturador manual=${simNao(hasManualSensor && expRange != null)}, " +
                "foco manual=${simNao((minFocus ?: 0f) > 0f)}, " +
                "branco manual=${simNao(awbOff && caps.contains(2))}, " +
                "RAW=${simNao(hasRaw)}"
        )
    }

    private fun profileName(profile: Long): String = when (profile) {
        DynamicRangeProfiles.STANDARD -> "STANDARD (8-bit)"
        DynamicRangeProfiles.HLG10 -> "HLG10 (10-bit)"
        DynamicRangeProfiles.HDR10 -> "HDR10 (10-bit)"
        DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS (10-bit)"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_REF"
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_OEM"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_REF"
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "DOLBY_VISION_8B_OEM"
        else -> "PERFIL_$profile"
    }

    private fun simNao(v: Boolean) = if (v) "SIM" else "NAO"
}

object MediaProbe {

    // Valores AOSP de CodecProfileLevel para HEVC.
    private val hevcProfileNames = mapOf(
        1 to "Main",
        2 to "Main10",
        4 to "MainStillPicture",
        4096 to "Main10HDR10",
        8192 to "Main10HDR10Plus"
    )

    fun buildReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== ENCODERS DE VIDEO ===")
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var hevcMain10 = false
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (mime != "video/hevc" && mime != "video/avc" && mime != "video/av01") continue
                val caps = info.getCapabilitiesForType(mime)
                val profiles = caps.profileLevels
                    ?.map { it.profile }
                    ?.distinct()
                    .orEmpty()
                val profileText = if (mime == "video/hevc") {
                    profiles.joinToString(", ") { hevcProfileNames[it] ?: "perfil $it" }
                } else {
                    profiles.joinToString(", ") { "$it" }
                }
                if (mime == "video/hevc" && profiles.contains(2)) hevcMain10 = true
                sb.appendLine("${info.name} [$mime] hw=${info.isHardwareAccelerated}")
                sb.appendLine("  Perfis: $profileText")
                if (mime == "video/hevc" || mime == "video/avc") {
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        sb.appendLine("  Bitrate suportado: ${vc.bitrateRange.lower / 1_000_000} a ${vc.bitrateRange.upper / 1_000_000} Mbps")
                        for ((w, h, fps) in listOf(
                            Triple(3840, 2160, 24.0), Triple(3840, 2160, 30.0),
                            Triple(3840, 2160, 60.0), Triple(1920, 1080, 60.0)
                        )) {
                            val ok = try {
                                vc.areSizeAndRateSupported(w, h, fps)
                            } catch (e: Exception) {
                                false
                            }
                            sb.appendLine("  ${w}x$h @ ${fps.toInt()} fps: ${if (ok) "sim" else "NAO"}")
                        }
                    }
                }
            }
        }
        sb.appendLine("Veredito encoder: HEVC Main10 (10-bit) = ${if (hevcMain10) "SIM" else "NAO encontrado"}")

        sb.appendLine()
        sb.appendLine("=== AUDIO ===")
        val audioManager = context.getSystemService(AudioManager::class.java)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        sb.appendLine("Entradas de audio ativas (${inputs.size}):")
        for (device in inputs) {
            sb.appendLine("  - ${deviceTypeName(device.type)}: ${device.productName} (id ${device.id})")
        }
        try {
            val mics = audioManager.microphones
            sb.appendLine("Microfones (${mics.size}):")
            for (mic in mics) {
                sb.appendLine(
                    "  - ${mic.description} tipo=${deviceTypeName(mic.type)} " +
                        "local=${mic.location} grupo=${mic.group} endereco=${mic.address}"
                )
            }
        } catch (e: Exception) {
            sb.appendLine("Microfones: erro (${e.message})")
        }
        for (rate in listOf(44100, 48000, 96000)) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                android.media.AudioFormat.CHANNEL_IN_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            sb.appendLine("AudioRecord $rate Hz estereo 16-bit: ${if (min > 0) "ok (buffer $min)" else "NAO"}")
        }

        sb.appendLine()
        sb.appendLine("=== ARMAZENAMENTO ===")
        val storageManager = context.getSystemService(StorageManager::class.java)
        for (volume in storageManager.storageVolumes) {
            val dir = try {
                volume.directory
            } catch (e: Exception) {
                null
            }
            val freeText = dir?.let {
                try {
                    val stat = StatFs(it.absolutePath)
                    val totalGb = stat.totalBytes / 1_000_000_000.0
                    val freeGb = stat.availableBytes / 1_000_000_000.0
                    String.format(Locale.US, "%.1f GB livres de %.1f GB", freeGb, totalGb)
                } catch (e: Exception) {
                    "?"
                }
            } ?: "?"
            sb.appendLine(
                "- ${volume.getDescription(context)}: removivel=${volume.isRemovable} " +
                    "primario=${volume.isPrimary} estado=${volume.state} $freeText"
            )
            sb.appendLine("  Caminho: ${dir?.absolutePath ?: "?"}")
        }
        val state = Environment.getExternalStorageState()
        sb.appendLine("Estado do armazenamento externo: $state")
        return sb.toString()
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "microfone interno"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "fone de ouvido (alto-falante de chamada)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "alto-falante"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "fone com fio e mic"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "fone com fio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headset USB"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "acessorio USB"
        else -> "tipo $type"
    }
}
