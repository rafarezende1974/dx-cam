package br.com.dxlabs.dxcam

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * DX Cam v1 - estado dos controles da camera e dos modos de video.
 *
 * Regras do plano que vivem aqui:
 * - Todo controle tem automatico ou manual; valor manual NUNCA e trocado
 *   em silencio (quem chama precisa avisar na tela - ver MainActivity).
 * - O estado inteiro persiste (SharedPreferences): reabrir o app volta
 *   com os ultimos valores, nao com padrao de fabrica.
 * - Obturador tem dois jeitos de pensar: ANGULO (padrao 180 graus,
 *   recalcula sozinho quando o fps muda) e VELOCIDADE (1/x fixo).
 */
data class VideoMode(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hevc: Boolean,
    val hdr: Boolean,
    val bitrateMbps: Int
) {
    val frameDurationNs: Long get() = 1_000_000_000L / fps
}

object VideoModes {
    // Lista fechada na Fase 0 (S24): 4K ate 30 fps, 1080p ate 60,
    // HDR 10-bit pelo perfil de faixa dinamica (saiu BT.2020/PQ).
    val ALL = listOf(
        VideoMode("4K 24p", 3840, 2160, 24, hevc = true, hdr = false, bitrateMbps = 50),
        VideoMode("4K 30p", 3840, 2160, 30, hevc = true, hdr = false, bitrateMbps = 50),
        VideoMode("4K 24p HDR", 3840, 2160, 24, hevc = true, hdr = true, bitrateMbps = 50),
        VideoMode("4K 30p HDR", 3840, 2160, 30, hevc = true, hdr = true, bitrateMbps = 50),
        VideoMode("1080 24p", 1920, 1080, 24, hevc = false, hdr = false, bitrateMbps = 20),
        VideoMode("1080 30p", 1920, 1080, 30, hevc = false, hdr = false, bitrateMbps = 20),
        VideoMode("1080 60p", 1920, 1080, 60, hevc = false, hdr = false, bitrateMbps = 28),
        VideoMode("720 30p", 1280, 720, 30, hevc = false, hdr = false, bitrateMbps = 12)
    )
}

enum class ShutterKind { ANGLE, SPEED }
enum class FocusMode { CONTINUOUS, TOUCH, MANUAL }

class CamState(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dxcam_state", Context.MODE_PRIVATE)

    var modeIndex: Int = prefs.getInt("modeIndex", 0)
        set(value) {
            field = value.coerceIn(0, VideoModes.ALL.lastIndex)
            save()
        }

    // ISO
    var isoAuto: Boolean = prefs.getBoolean("isoAuto", true)
        set(value) { field = value; save() }
    var iso: Int = prefs.getInt("iso", 800)
        set(value) { field = value; save() }
    var isoLocked: Boolean = prefs.getBoolean("isoLocked", false)
        set(value) { field = value; save() }

    // Obturador
    var shutterAuto: Boolean = prefs.getBoolean("shutterAuto", true)
        set(value) { field = value; save() }
    var shutterKind: ShutterKind =
        if (prefs.getString("shutterKind", "ANGLE") == "SPEED") ShutterKind.SPEED
        else ShutterKind.ANGLE
        set(value) { field = value; save() }
    var angleDeg: Float = prefs.getFloat("angleDeg", 180f)
        set(value) { field = value; save() }
    var speedIndex: Int = prefs.getInt("speedIndex", DEFAULT_SPEED_INDEX)
        set(value) { field = value.coerceIn(0, SPEED_STEPS.lastIndex); save() }
    var shutterLocked: Boolean = prefs.getBoolean("shutterLocked", false)
        set(value) { field = value; save() }

    // Branco
    var wbAuto: Boolean = prefs.getBoolean("wbAuto", true)
        set(value) { field = value; save() }
    var kelvin: Int = prefs.getInt("kelvin", 5600)
        set(value) { field = value.coerceIn(2300, 8000); save() }
    var tint: Int = prefs.getInt("tint", 0)
        set(value) { field = value.coerceIn(-100, 100); save() }
    var wbLocked: Boolean = prefs.getBoolean("wbLocked", false)
        set(value) { field = value; save() }

    // Foco
    var focusMode: FocusMode = when (prefs.getString("focusMode", "CONTINUOUS")) {
        "MANUAL" -> FocusMode.MANUAL
        "TOUCH" -> FocusMode.TOUCH
        else -> FocusMode.CONTINUOUS
    }
        set(value) { field = value; save() }
    var focusDiopters: Float = prefs.getFloat("focusDiopters", 0.5f)
        set(value) { field = value.coerceIn(0f, 10f); save() }
    var focusLocked: Boolean = prefs.getBoolean("focusLocked", false)
        set(value) { field = value; save() }

    // Exposicao (compensacao, so nos modos com AE ligado) - S24: +-2 EV
    var ev: Float = prefs.getFloat("ev", 0f)
        set(value) { field = value.coerceIn(-2f, 2f); save() }

    // Zoom (razao sobre a lente principal; 0,6 = ultra-wide logica)
    var zoomRatio: Float = prefs.getFloat("zoomRatio", 1f)
        set(value) { field = value.coerceIn(0.6f, 10f); save() }

    // Gravacao (Etapa 3): nivel de bitrate, destino e nomeacao.
    var bitrateLevel: Int = prefs.getInt("bitrateLevel", 1) // 0 ECO, 1 PADRAO, 2 ALTO
        set(value) { field = value.coerceIn(0, 2); save() }
    var destSsd: Boolean = prefs.getBoolean("destSsd", false)
        set(value) { field = value; save() }
    var treeUri: String = prefs.getString("treeUri", "") ?: ""
        set(value) { field = value; save() }
    var projectPrefix: String = prefs.getString("projectPrefix", "") ?: ""
        set(value) { field = value; save() }

    // Visual (v0.4.0): grade de tercos sobre o visor.
    var showGrid: Boolean = prefs.getBoolean("showGrid", false)
        set(value) { field = value; save() }

    // Monitoramento (Etapa 4): o que esta ligado e o limiar da zebra.
    var zebraOn: Boolean = prefs.getBoolean("zebraOn", true)
        set(value) { field = value; save() }
    var peakingOn: Boolean = prefs.getBoolean("peakingOn", false)
        set(value) { field = value; save() }
    var histoOn: Boolean = prefs.getBoolean("histoOn", true)
        set(value) { field = value; save() }
    var metersOn: Boolean = prefs.getBoolean("metersOn", true)
        set(value) { field = value; save() }
    var levelOn: Boolean = prefs.getBoolean("levelOn", false)
        set(value) { field = value; save() }
    var zebraIre: Int = prefs.getInt("zebraIre", 95)
        set(value) { field = value.coerceIn(70, 100); save() }

    val mode: VideoMode get() = VideoModes.ALL[modeIndex]

    /** Bitrate efetivo do modo atual conforme o nivel (sempre visivel). */
    fun effectiveBitrateMbps(): Int {
        val factor = when (bitrateLevel) {
            0 -> 0.5
            2 -> 1.6
            else -> 1.0
        }
        return (mode.bitrateMbps * factor).roundToInt().coerceAtLeast(2)
    }

    fun bitrateLevelLabel(): String = when (bitrateLevel) {
        0 -> "ECO"
        2 -> "ALTO"
        else -> "PADRÃO"
    }

    private fun save() {
        prefs.edit()
            .putInt("modeIndex", modeIndex)
            .putBoolean("isoAuto", isoAuto)
            .putInt("iso", iso)
            .putBoolean("isoLocked", isoLocked)
            .putBoolean("shutterAuto", shutterAuto)
            .putString("shutterKind", shutterKind.name)
            .putFloat("angleDeg", angleDeg)
            .putInt("speedIndex", speedIndex)
            .putBoolean("shutterLocked", shutterLocked)
            .putBoolean("wbAuto", wbAuto)
            .putInt("kelvin", kelvin)
            .putInt("tint", tint)
            .putBoolean("wbLocked", wbLocked)
            .putString("focusMode", focusMode.name)
            .putFloat("focusDiopters", focusDiopters)
            .putBoolean("focusLocked", focusLocked)
            .putFloat("ev", ev)
            .putFloat("zoomRatio", zoomRatio)
            .putInt("bitrateLevel", bitrateLevel)
            .putBoolean("destSsd", destSsd)
            .putString("treeUri", treeUri)
            .putString("projectPrefix", projectPrefix)
            .putBoolean("showGrid", showGrid)
            .putBoolean("zebraOn", zebraOn)
            .putBoolean("peakingOn", peakingOn)
            .putBoolean("histoOn", histoOn)
            .putBoolean("metersOn", metersOn)
            .putBoolean("levelOn", levelOn)
            .putInt("zebraIre", zebraIre)
            .apply()
    }

    /** Exposicao pedida pelo obturador manual, ja limitada ao quadro. */
    fun manualExposureNs(fps: Int): Long {
        val frameNs = 1_000_000_000L / fps
        val wanted = when (shutterKind) {
            ShutterKind.ANGLE -> (frameNs * (angleDeg / 360.0)).toLong()
            ShutterKind.SPEED -> SPEED_STEPS[speedIndex].second
        }
        return wanted.coerceIn(85_000L, frameNs)
    }

    /** Rotulo curto do obturador manual para a faixa de controles. */
    fun shutterLabel(fps: Int): String {
        if (shutterAuto) return "AUTO"
        return when (shutterKind) {
            ShutterKind.ANGLE -> "${formatAngle(angleDeg)}°"
            ShutterKind.SPEED -> SPEED_STEPS[speedIndex].first
        }
    }

    companion object {
        val ISO_STEPS = intArrayOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500,
            640, 800, 1000, 1250, 1600, 2000, 2500, 3200
        )
        val ANGLE_STEPS = floatArrayOf(
            360f, 270f, 180f, 172.8f, 144f, 90f, 45f, 22.5f, 11.25f
        )
        // (rotulo, nanossegundos) - do mais lento ao mais rapido
        val SPEED_STEPS: List<Pair<String, Long>> = listOf(
            "1/10" to 100_000_000L,
            "1/24" to 41_666_667L,
            "1/25" to 40_000_000L,
            "1/30" to 33_333_333L,
            "1/48" to 20_833_333L,
            "1/50" to 20_000_000L,
            "1/60" to 16_666_667L,
            "1/96" to 10_416_667L,
            "1/100" to 10_000_000L,
            "1/120" to 8_333_333L,
            "1/250" to 4_000_000L,
            "1/500" to 2_000_000L,
            "1/1000" to 1_000_000L,
            "1/2000" to 500_000L,
            "1/4000" to 250_000L,
            "1/8000" to 125_000L
        )
        const val DEFAULT_SPEED_INDEX = 5 // 1/50

        val WB_PRESETS = listOf(
            "Tungstênio" to 3200,
            "Fluorescente" to 4000,
            "Luz do dia" to 5600,
            "Nublado" to 6500,
            "Sombra" to 7500
        )

        fun formatAngle(angle: Float): String =
            if (angle % 1f == 0f) angle.toInt().toString()
            else String.format(java.util.Locale.US, "%.1f", angle)

        fun formatShutterNs(ns: Long): String {
            if (ns <= 0) return "?"
            val denom = Math.round(1_000_000_000.0 / ns)
            return "1/$denom"
        }

        fun formatFocusMeters(diopters: Float): String {
            if (diopters <= 0.01f) return "∞"
            val meters = 1.0 / diopters
            return if (meters >= 10) String.format(java.util.Locale.US, "%.0f m", meters)
            else String.format(java.util.Locale.US, "%.1f m", meters)
        }

        fun isoIndexOf(iso: Int): Int {
            var best = 0
            var bestDiff = Int.MAX_VALUE
            for ((i, step) in ISO_STEPS.withIndex()) {
                val diff = kotlin.math.abs(step - iso)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = i
                }
            }
            return best
        }

        fun angleIndexOf(angle: Float): Int {
            var best = 0
            var bestDiff = Float.MAX_VALUE
            for ((i, step) in ANGLE_STEPS.withIndex()) {
                val diff = kotlin.math.abs(step - angle)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = i
                }
            }
            return best
        }

        /**
         * Kelvin -> ganhos Rggb para AWB desligado. Aproximacao de
         * Tanner Helland para a cor da luz; os ganhos sao o inverso
         * normalizado pelo verde. Tint desloca o verde (magenta/verde).
         */
        fun kelvinToGains(kelvin: Int, tint: Int): RggbChannelVector {
            val t = kelvin / 100.0
            var r: Double
            var g: Double
            var b: Double
            if (t <= 66) {
                r = 255.0
                g = (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
                b = when {
                    t <= 19 -> 0.0
                    else -> (138.5177312231 * ln(t - 10) - 305.0447927307)
                        .coerceIn(0.0, 255.0)
                }
            } else {
                r = (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
                g = (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
                b = 255.0
            }
            val rn = (r / 255.0).coerceAtLeast(0.05)
            val gn = (g / 255.0).coerceAtLeast(0.05)
            val bn = (b / 255.0).coerceAtLeast(0.05)
            var gainR = (gn / rn)
            var gainG = 1.0
            var gainB = (gn / bn)
            // Tint: positivo = magenta (menos verde), negativo = verde.
            gainG *= (1.0 - tint * 0.003)
            val minGain = minOf(gainR, gainG, gainB)
            gainR /= minGain
            gainG /= minGain
            gainB /= minGain
            return RggbChannelVector(
                gainR.toFloat().coerceIn(0.5f, 8f),
                gainG.toFloat().coerceIn(0.5f, 8f),
                gainG.toFloat().coerceIn(0.5f, 8f),
                gainB.toFloat().coerceIn(0.5f, 8f)
            )
        }

        fun evStepsFor(ev: Float, step: Float): Int =
            (ev / step).roundToInt()
    }
}
