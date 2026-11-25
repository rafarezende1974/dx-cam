package br.com.dxlabs.dxcam

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DX Cam v1 - Etapa 5: leitura de LUT .cube (o mesmo formato do
 * Premiere/Lumetri) e conversao para o atlas 2D que o shader usa.
 *
 * Atlas: textura (size*size) x size; o pixel (z*size + r, g) guarda a
 * cor de saida para a entrada (r, g, b=z). So LUT 3D; dominio assumido
 * 0..1 (o padrao dos .cube de camera).
 */
class LutData(
    val title: String,
    val size: Int,
    /** RGB em 0..1, ordem: indice = ((b*size + g)*size + r) * 3. */
    val rgb: FloatArray
)

object LutFile {

    fun parse(input: InputStream, fallbackTitle: String): LutData {
        var size = -1
        var title = fallbackTitle
        val values = ArrayList<Float>()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                line = reader.readLine()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val upper = trimmed.uppercase()
                when {
                    upper.startsWith("TITLE") -> {
                        val quoted = trimmed.substringAfter('"', "")
                            .substringBefore('"')
                        if (quoted.isNotEmpty()) title = quoted
                    }
                    upper.startsWith("LUT_3D_SIZE") -> {
                        size = trimmed.split(Regex("\\s+")).getOrNull(1)
                            ?.toIntOrNull() ?: -1
                    }
                    upper.startsWith("LUT_1D_SIZE") -> {
                        throw IllegalArgumentException(
                            "LUT 1D nao suportada; exporte como 3D."
                        )
                    }
                    upper.startsWith("DOMAIN_") ||
                        upper.startsWith("LUT_") -> {
                        // Outras diretivas conhecidas: ignoradas.
                    }
                    else -> {
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values.add(r)
                                values.add(g)
                                values.add(b)
                            }
                        }
                    }
                }
            }
        }
        if (size < 2 || size > 64) {
            throw IllegalArgumentException(
                "LUT_3D_SIZE invalido ou ausente (esperado 2..64)."
            )
        }
        val expected = size * size * size * 3
        if (values.size != expected) {
            throw IllegalArgumentException(
                "Arquivo incompleto: ${values.size / 3} cores, esperado " +
                    "${size * size * size} para tamanho $size."
            )
        }
        val rgb = FloatArray(expected) { index ->
            values[index].coerceIn(0f, 1f)
        }
        return LutData(title, size, rgb)
    }

    /** Atlas RGBA pronto para glTexImage2D (largura size*size, altura size). */
    fun buildAtlasRgba(data: LutData): ByteBuffer {
        val size = data.size
        val buffer = ByteBuffer.allocateDirect(size * size * size * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (g in 0 until size) {
            for (b in 0 until size) {
                for (r in 0 until size) {
                    val src = ((b * size + g) * size + r) * 3
                    buffer.put((data.rgb[src] * 255f + 0.5f).toInt().toByte())
                    buffer.put((data.rgb[src + 1] * 255f + 0.5f).toInt().toByte())
                    buffer.put((data.rgb[src + 2] * 255f + 0.5f).toInt().toByte())
                    buffer.put(255.toByte())
                }
            }
        }
        buffer.position(0)
        return buffer
    }
}
