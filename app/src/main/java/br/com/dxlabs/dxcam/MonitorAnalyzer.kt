package br.com.dxlabs.dxcam

/**
 * DX Cam v1 - Etapa 4 (monitoramento): analise barata do plano Y da
 * previa (640x360, ~8 fps). Nao e GL: amostragem em CPU com descarte de
 * quadros no CameraEngine, custo medido para nao arriscar a gravacao.
 *
 * Produz, por quadro analisado:
 * - histograma de luminancia em 64 faixas;
 * - grade de celulas (48x27) com zebra (luminancia media no limiar ou
 *   acima) e peaking (gradiente forte = borda focada);
 * - percentual de amostras acima do limiar.
 */
object MonitorAnalyzer {

    const val COLS = 48
    const val ROWS = 27
    const val BINS = 64

    class Result(
        val histogram: IntArray,
        val zebra: BooleanArray,
        val peaking: BooleanArray,
        val overPercent: Float
    )

    fun analyze(
        y: ByteArray,
        width: Int,
        height: Int,
        zebraLuma: Int,
        wantZebra: Boolean,
        wantPeaking: Boolean
    ): Result {
        val histogram = IntArray(BINS)
        var over = 0
        var total = 0
        // Histograma: amostra 1 a cada 4 pixels nas duas dimensoes.
        var row = 0
        while (row < height) {
            val base = row * width
            var col = 0
            while (col < width) {
                val luma = y[base + col].toInt() and 0xFF
                histogram[(luma * BINS / 256).coerceIn(0, BINS - 1)] += 1
                if (luma >= zebraLuma) over += 1
                total += 1
                col += 4
            }
            row += 4
        }

        val zebra = BooleanArray(COLS * ROWS)
        val peaking = BooleanArray(COLS * ROWS)
        if (wantZebra || wantPeaking) {
            val cellW = width / COLS
            val cellH = height / ROWS
            for (r in 0 until ROWS) {
                val cy = (r * cellH + cellH / 2).coerceAtMost(height - 1)
                val dy = (cy + cellH / 2).coerceAtMost(height - 1)
                for (c in 0 until COLS) {
                    val cx = (c * cellW + cellW / 2).coerceAtMost(width - 1)
                    val dx = (cx + cellW / 2).coerceAtMost(width - 1)
                    val center = y[cy * width + cx].toInt() and 0xFF
                    val index = r * COLS + c
                    if (wantZebra && center >= zebraLuma) {
                        zebra[index] = true
                    }
                    if (wantPeaking) {
                        val right = y[cy * width + dx].toInt() and 0xFF
                        val down = y[dy * width + cx].toInt() and 0xFF
                        val gradient = kotlin.math.abs(center - right) +
                            kotlin.math.abs(center - down)
                        if (gradient > 56) peaking[index] = true
                    }
                }
            }
        }

        return Result(
            histogram = histogram,
            zebra = zebra,
            peaking = peaking,
            overPercent = if (total > 0) over * 100f / total else 0f
        )
    }
}
