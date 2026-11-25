package br.com.dxlabs.dxcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * DX Cam v1 - Etapa 4: nivel do microfone FORA da gravacao (em espera),
 * para os medidores de audio terem vida antes do REC. Durante a
 * gravacao quem alimenta os medidores e o MediaRecorder
 * (CameraEngine.currentMaxAmplitude), entao este leitor para.
 *
 * Le em thread propria, RMS por bloco, ataque rapido e queda suave.
 * Se o microfone estiver ocupado ou o aparelho recusar, fica inativo
 * em silencio (medidores parados, sem erro na tela).
 */
class AudioMeter {

    @Volatile
    var currentDb: Float = -60f
        private set

    @Volatile
    var available: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running: Boolean = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            var rec: AudioRecord? = null
            try {
                val rate = 48_000
                val minBuffer = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) {
                    running = false
                    return@Thread
                }
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, 4096)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    running = false
                    return@Thread
                }
                record = rec
                available = true
                rec.startRecording()
                val buffer = ShortArray(1024)
                while (running) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i].toDouble()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / read)
                    val db = if (rms > 0.5) {
                        (20.0 * log10(rms / 32767.0)).toFloat()
                    } else {
                        -60f
                    }.coerceIn(-60f, 0f)
                    // Ataque imediato, queda suave (~1,2 dB por bloco).
                    currentDb = if (db > currentDb) db
                    else maxOf(db, currentDb - 1.2f)
                }
            } catch (e: Exception) {
                // Microfone ocupado ou recusado: medidores ficam parados.
            } finally {
                available = false
                try {
                    rec?.stop()
                } catch (e: Exception) {
                }
                try {
                    rec?.release()
                } catch (e: Exception) {
                }
                if (record === rec) record = null
            }
        }
        thread?.start()
    }

    fun stop() {
        running = false
        thread = null
        currentDb = -60f
    }
}
