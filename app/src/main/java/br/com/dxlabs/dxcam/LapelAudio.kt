package br.com.dxlabs.dxcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10

/**
 * DX Cam v2 - Etapa 4: celular como microfone de lapela sem fio.
 * Captura do microfone em PCM 16-bit 48 kHz mono e gravador WAV em
 * arquivo temporario (o cabecalho RIFF so fecha no fim; o arquivo
 * final nasce na copia para o destino, interno ou SSD).
 */
object LapelFormat {
    const val SAMPLE_RATE: Int = 48000
    const val CHUNK_BYTES: Int = 1920 // 20 ms de PCM 16-bit mono 48 kHz

    /** Pico do trecho em dBFS (-60..0). */
    fun peakDb(pcm: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i].toInt() and 0xFF) or
                (pcm[i + 1].toInt() shl 8)
            val value = kotlin.math.abs(sample.toShort().toInt())
            if (value > peak) peak = value
            i += 2
        }
        if (peak == 0) return -60f
        return (20.0 * log10(peak / 32767.0)).toFloat().coerceIn(-60f, 0f)
    }
}

class WavTempWriter(dir: File, baseName: String) {
    private val file: File = File(dir, "$baseName.tmpwav")
    private val raf: RandomAccessFile = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(pcm: ByteArray, length: Int) {
        try {
            raf.write(pcm, 0, length)
            dataBytes += length
        } catch (e: Exception) {
            // Disco cheio ou arquivo fechado: o trecho se perde, a
            // sessao continua.
        }
    }

    /** Fecha o cabecalho e devolve o arquivo WAV pronto. */
    @Synchronized
    fun finish(): File {
        try {
            val header = buildHeader(dataBytes)
            raf.seek(0)
            raf.write(header)
            raf.close()
        } catch (e: Exception) {
        }
        return file
    }

    private fun buildHeader(dataLength: Long): ByteArray {
        val header = ByteArray(44)
        fun putAscii(offset: Int, text: String) {
            for ((i, c) in text.withIndex()) {
                header[offset + i] = c.code.toByte()
            }
        }
        fun putInt(offset: Int, value: Long) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        val rate = LapelFormat.SAMPLE_RATE
        putAscii(0, "RIFF")
        putInt(4, 36 + dataLength)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, rate.toLong())
        putInt(28, rate * 2L) // byte rate
        putShort(32, 2) // block align
        putShort(34, 16) // bits
        putAscii(36, "data")
        putInt(40, dataLength)
        return header
    }
}

class MicCapture(
    private val onChunk: (ByteArray, Int) -> Unit
) {
    @Volatile
    var running: Boolean = false
        private set

    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            LapelFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                LapelFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, LapelFormat.CHUNK_BYTES * 4)
            )
        } catch (e: Exception) {
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            try {
                created.release()
            } catch (e: Exception) {
            }
            return false
        }
        record = created
        running = true
        created.startRecording()
        Thread {
            val buffer = ByteArray(LapelFormat.CHUNK_BYTES)
            while (running) {
                val read = try {
                    created.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    onChunk(buffer.copyOf(read), read)
                } else if (read < 0) {
                    break
                }
            }
        }.start()
        return true
    }

    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (e: Exception) {
        }
        try {
            record?.release()
        } catch (e: Exception) {
        }
        record = null
    }
}
