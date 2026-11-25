package br.com.dxlabs.dxcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * DX Cam Diag - Fase 0 (v0.1.0-diag).
 *
 * App de diagnostico do DX Cam: le as capacidades reais da camera do
 * aparelho (Camera2), grava clipes curtos de teste, tira uma foto RAW de
 * teste, mede a escrita no armazenamento e gera um relatorio em texto
 * para compartilhar (WhatsApp/e-mail). E esse relatorio que fecha a
 * lista de resolucoes, codecs e controles da v1 - ver TESTE-FASE-0.md.
 *
 * Uma tela so, de proposito: botoes de teste no topo e o relatorio
 * rolando embaixo. Nenhum dado sai do aparelho sem o toque em
 * Compartilhar.
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private lateinit var tester: CameraTester

    private val bgThread = HandlerThread("dxcam-bg")
    private lateinit var bgHandler: Handler

    private val report = StringBuilder()
    private var reportFile: File? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            probeAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bgThread.start()
        bgHandler = Handler(bgThread.looper)
        tester = CameraTester(this, bgHandler)
        buildUi()
        if (!hasCameraPermissions()) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
        probeAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgThread.quitSafely()
    }

    private fun hasCameraPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return camera && audio
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "DX Cam Diag - Fase 0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "1) Confira o diagnostico  2) Rode os testes  3) Compartilhe o relatorio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(subtitle)
        statusText = TextView(this).apply {
            text = "Lendo capacidades do aparelho..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)

        root.addView(button("Atualizar diagnóstico") { probeAll() })
        root.addView(button("Gravar teste 4K 24p H.265 (30 s)") {
            if (!ensurePermissions()) return@button
            tester.recordTest(
                label = "4k24_hevc",
                width = 3840, height = 2160, fps = 24,
                videoEncoder = MediaRecorder.VideoEncoder.HEVC,
                bitrateMbps = 50,
                durationMs = 30_000,
                outDir = videoDir(),
                status = { setStatus(it) },
                done = { appendSection(it) }
            )
        })
        root.addView(button("Gravar teste 4K 24p H.265 10-BIT HLG (30 s)") {
            if (!ensurePermissions()) return@button
            tester.recordTest(
                label = "4k24_hevc_hlg10",
                width = 3840, height = 2160, fps = 24,
                videoEncoder = MediaRecorder.VideoEncoder.HEVC,
                bitrateMbps = 50,
                durationMs = 30_000,
                outDir = videoDir(),
                hlg = true,
                manualSecondHalf = false,
                status = { setStatus(it) },
                done = { appendSection(it) }
            )
        })
        root.addView(button("Gravar teste 1080p 60p H.264 (30 s)") {
            if (!ensurePermissions()) return@button
            tester.recordTest(
                label = "1080p60_h264",
                width = 1920, height = 1080, fps = 60,
                videoEncoder = MediaRecorder.VideoEncoder.H264,
                bitrateMbps = 28,
                durationMs = 30_000,
                outDir = videoDir(),
                status = { setStatus(it) },
                done = { appendSection(it) }
            )
        })
        root.addView(button("Foto RAW de teste (DNG + JPEG)") {
            if (!ensurePermissions()) return@button
            tester.captureRawTest(
                outDir = picturesDir(),
                status = { setStatus(it) },
                done = { appendSection(it) }
            )
        })
        root.addView(button("Teste de escrita 128 MB (interno/SSD)") {
            setStatus("Medindo a escrita... pode levar alguns segundos.")
            bgHandler.post {
                tester.writeSpeedTest { appendSection(it) }
            }
        })
        root.addView(button("Salvar relatório") { saveReport() })
        root.addView(button("Compartilhar relatório (WhatsApp)") { shareReport() })

        reportText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(reportText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun ensurePermissions(): Boolean {
        if (hasCameraPermissions()) return true
        setStatus("Faltam permissoes de camera/microfone - pedindo de novo...")
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
        return false
    }

    private fun videoDir(): File =
        getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir

    private fun picturesDir(): File =
        getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir

    private fun documentsDir(): File =
        getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir

    private fun probeAll() {
        setStatus("Lendo capacidades do aparelho...")
        bgHandler.post {
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            val text = StringBuilder()
            text.append(ReportHeader.build(version))
            text.append(CameraProbe.buildReport(this))
            text.append(MediaProbe.buildReport(this))
            text.appendLine()
            text.appendLine("Bateria agora: ${CameraTester.batterySnapshot(this)}")
            text.appendLine()
            text.appendLine("(Rode os testes abaixo - cada um entra neste relatorio.)")
            runOnUiThread {
                report.clear()
                report.append(text)
                reportText.text = report.toString()
                setStatus("Diagnostico pronto. Rode os testes e compartilhe o relatorio.")
            }
        }
    }

    private fun appendSection(section: String) {
        runOnUiThread {
            if (report.isNotEmpty() && !report.endsWith("\n")) report.append("\n")
            report.append(section)
            if (!section.endsWith("\n")) report.append("\n")
            reportText.text = report.toString()
            setStatus("Teste concluido e somado ao relatorio.")
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun saveReport(): File {
        val file = File(documentsDir(), "relatorio-fase0-dxcam.txt")
        file.writeText(report.toString())
        reportFile = file
        setStatus("Relatorio salvo: ${file.absolutePath}")
        return file
    }

    private fun shareReport() {
        val file = saveReport()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DX Cam - Relatorio da Fase 0")
            putExtra(
                Intent.EXTRA_TEXT,
                "Segue o relatorio da Fase 0 do DX Cam (${android.os.Build.MODEL})."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar relatorio"))
    }
}
