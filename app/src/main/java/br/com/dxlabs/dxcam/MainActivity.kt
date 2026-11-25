package br.com.dxlabs.dxcam

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * DX Cam v1 - tela de camera.
 *
 * v0.4.0-visual: interface reconstruida no layout aprovado pelo Rafa
 * (linha Blackmagic Camera): parametros no topo (LENTE, FPS, OBTUR.,
 * IRIS, FOCO | timecode no centro | ISO, WB, TOM e o selo do modo),
 * monitoramento nos cantos inferiores (histograma a esquerda e
 * medidores de audio a direita sao a Etapa 4 - o lugar ja esta
 * reservado; no centro, tempo de gravacao restante, espaco e bateria),
 * REC e funcoes numa regua lateral e abas Camera/Midia/Configuracoes
 * na extrema direita. O painel de ajuste fino abre sobre o visor ao
 * tocar num parametro. Motor (CameraEngine) nao muda.
 */
class MainActivity : ComponentActivity(), CameraEngine.Listener {

    private enum class Control { ISO, SHUTTER, WB, FOCUS, EV, ZOOM }
    private enum class PanelKind { NONE, PARAM, MODE }

    private lateinit var state: CamState
    private lateinit var engine: CameraEngine
    private lateinit var textureView: TextureView
    private lateinit var gridView: GridView
    private lateinit var reticle: View
    private lateinit var timecodeText: TextView
    private lateinit var fpsValue: TextView
    private lateinit var irisValue: TextView
    private lateinit var wbValue: TextView
    private lateinit var wbBadge: TextView
    private lateinit var tomValue: TextView
    private lateinit var badgeRes: TextView
    private lateinit var badgeSub: TextView
    private lateinit var remainTimeText: TextView
    private lateinit var remainPctText: TextView
    private lateinit var remainFreeText: TextView
    private lateinit var batteryText: TextView
    private lateinit var barFill: View
    private lateinit var barRest: View
    private lateinit var warningText: TextView
    private lateinit var statusText: TextView
    private lateinit var panel: LinearLayout
    private lateinit var recCore: View
    private lateinit var gridButton: LinearLayout

    private val cellValues = mutableMapOf<Control, TextView>()
    private val cellNames = mutableMapOf<Control, TextView>()
    private var selected: Control? = null
    private var panelKind: PanelKind = PanelKind.NONE
    private var lastReadback: CameraEngine.Readback? = null

    private val bgThread = HandlerThread("dxcam-bg")
    private lateinit var bgHandler: Handler
    private val uiHandler = Handler(Looper.getMainLooper())

    private var surfaceReady = false
    private var engineOpened = false
    private var previewSizeUsed: Size = Size(1920, 1080)
    private var firstReadback = true
    private var recordStartMs: Long = 0
    private var hudTick: Runnable? = null
    private var hudTickCount = 0
    private var statusHide: Runnable? = null
    private var reticleHide: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            maybeOpenEngine()
        }

    private val treePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    state.treeUri = uri.toString()
                    state.destSsd = true
                    showStatus("Destino: pasta externa (SSD). Gravacoes vao para la.")
                } catch (e: Exception) {
                    showStatus("Nao consegui guardar o acesso a pasta: ${e.message}")
                }
                refreshStorage()
                if (panelKind == PanelKind.MODE) showPanel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bgThread.start()
        bgHandler = Handler(bgThread.looper)
        state = CamState(this)
        engine = CameraEngine(this, bgHandler, state, this)
        buildUi()
        applyImmersive()
        refreshAperture()
        if (!hasPermissions()) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onDestroy() {
        stopHudTick()
        engine.close()
        bgThread.quitSafely()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        textureView.post { adjustAspect() }
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

    private fun maybeOpenEngine() {
        if (engineOpened || !surfaceReady || !hasPermissions()) return
        engineOpened = true
        val texture = textureView.surfaceTexture ?: return
        val size = engine.previewSize()
        previewSizeUsed = size
        texture.setDefaultBufferSize(size.width, size.height)
        adjustAspect()
        engine.open(Surface(texture))
    }

    /** Iris e fixa no celular: le a abertura real da lente (informativa). */
    private fun refreshAperture() {
        var text = "f1,8"
        try {
            val manager = getSystemService(CameraManager::class.java)
            val id = CameraProbe.pickMainBackCameraId(manager)
            if (id != null) {
                val apertures = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val first = apertures?.minOrNull()
                if (first != null) {
                    text = "f" + String.format(Locale.US, "%.1f", first)
                        .replace('.', ',')
                }
            }
        } catch (e: Exception) {
            // Mantem f1,8 (abertura da lente principal do S24).
        }
        irisValue.text = text
    }

    // ---------------------------------------------------------------
    // Construcao da interface (layout v0.4.0)
    // ---------------------------------------------------------------
    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun label(
        text: String, sizeSp: Float, color: Int, bold: Boolean = false
    ): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
        if (bold) view.typeface = Typeface.DEFAULT_BOLD
        return view
    }

    private fun roundedBg(
        color: Int, radiusDp: Int, strokeColor: Int? = null
    ): GradientDrawable {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(radiusDp).toFloat()
        bg.setColor(color)
        if (strokeColor != null) bg.setStroke(dp(1), strokeColor)
        return bg
    }

    private fun smallButton(text: String): Button {
        val button = Button(this)
        button.text = text
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        button.setTextColor(Color.WHITE)
        button.background = roundedBg(0xFF1C2530.toInt(), 8)
        button.setPadding(dp(10), dp(4), dp(10), dp(4))
        return button
    }

    private fun chipButton(
        text: String, isSelected: Boolean, onClick: () -> Unit
    ): Button {
        val button = Button(this)
        button.text = text
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        button.setTextColor(if (isSelected) AMBER else Color.WHITE)
        button.background = roundedBg(
            0xFF1C2530.toInt(), 8, if (isSelected) AMBER else 0x44FFFFFF
        )
        button.setPadding(dp(9), dp(3), dp(9), dp(3))
        button.setOnClickListener { onClick() }
        return button
    }

    /** Celula de parametro do topo: nome pequeno em cima, valor embaixo. */
    private fun buildTopCell(
        name: String,
        control: Control?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.setPadding(dp(7), dp(2), dp(7), dp(2))
        val nameView = label(name, 8f, MUTED, bold = true)
        val valueView = label("-", 15f, Color.WHITE, bold = true)
        valueView.typeface = Typeface.MONOSPACE
        cell.addView(nameView)
        cell.addView(valueView)
        if (control != null) {
            cellNames[control] = nameView
            cellValues[control] = valueView
        }
        cell.setOnClickListener { onClick() }
        if (onLongClick != null) {
            cell.setOnLongClickListener {
                onLongClick()
                true
            }
        }
        return cell
    }

    private fun buildUi() {
        val rootRow = LinearLayout(this)
        rootRow.orientation = LinearLayout.HORIZONTAL
        rootRow.setBackgroundColor(Color.BLACK)

        // ---- Area da camera (visor + sobreposicoes) ----
        val stage = FrameLayout(this)
        stage.setBackgroundColor(Color.BLACK)
        rootRow.addView(
            stage,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )

        textureView = TextureView(this)
        stage.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture, width: Int, height: Int
            ) {
                surfaceReady = true
                maybeOpenEngine()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture, width: Int, height: Int
            ) {
                adjustAspect()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceReady = false
                engineOpened = false
                engine.close()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        installTouch()

        gridView = GridView(this)
        gridView.visibility = if (state.showGrid) View.VISIBLE else View.GONE
        stage.addView(
            gridView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        reticle = View(this)
        val reticleBg = GradientDrawable()
        reticleBg.shape = GradientDrawable.RECTANGLE
        reticleBg.cornerRadius = dp(8).toFloat()
        reticleBg.setColor(Color.TRANSPARENT)
        reticleBg.setStroke(dp(2), Color.WHITE)
        reticle.background = reticleBg
        reticle.visibility = View.GONE
        stage.addView(reticle, FrameLayout.LayoutParams(dp(76), dp(76)))

        buildTopBar(stage)
        buildBottomHud(stage)

        // Aviso (regra de ouro: valor travado nunca muda em silencio).
        warningText = label("", 13f, Color.BLACK, bold = true)
        warningText.setBackgroundColor(AMBER)
        warningText.setPadding(dp(12), dp(6), dp(12), dp(6))
        warningText.visibility = View.GONE
        val warningParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        warningParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        warningParams.topMargin = dp(104)
        stage.addView(warningText, warningParams)

        // Painel de ajuste fino / de modo, sobre o visor, acima do HUD.
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(8), dp(12), dp(6))
        panel.background = roundedBg(0xE6111419.toInt(), 12)
        panel.visibility = View.GONE
        val panelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        panelParams.gravity = Gravity.BOTTOM
        panelParams.bottomMargin = dp(92)
        panelParams.leftMargin = dp(10)
        panelParams.rightMargin = dp(10)
        stage.addView(panel, panelParams)

        // Status: pilula discreta que some sozinha.
        statusText = label("Abrindo a camera...", 12f, Color.WHITE)
        statusText.setPadding(dp(12), dp(6), dp(12), dp(6))
        statusText.background = roundedBg(0xCC111419.toInt(), 999)
        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        statusParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        statusParams.topMargin = dp(64)
        stage.addView(statusText, statusParams)

        buildRail(rootRow)
        buildTabs(rootRow)

        setContentView(rootRow)
        refreshModeUi()
        refreshHud()
        refreshStorage()
        refreshGridButton()
    }

    private fun buildTopBar(stage: FrameLayout) {
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.setBackgroundColor(0x8C000000.toInt())
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6))
        topBar.gravity = Gravity.CENTER_VERTICAL
        val topParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topParams.gravity = Gravity.TOP
        stage.addView(topBar, topParams)

        topBar.addView(buildTopCell("LENTE", Control.ZOOM, onClick = {
            togglePanel(Control.ZOOM)
        }))
        // FPS: toque curto alterna o fps dentro da mesma resolucao/HDR;
        // toque longo abre o painel de modo completo.
        val fpsCell = LinearLayout(this)
        fpsCell.orientation = LinearLayout.VERTICAL
        fpsCell.setPadding(dp(7), dp(2), dp(7), dp(2))
        fpsCell.addView(label("FPS", 8f, MUTED, bold = true))
        fpsValue = label("-", 15f, Color.WHITE, bold = true)
        fpsValue.typeface = Typeface.MONOSPACE
        fpsCell.addView(fpsValue)
        fpsCell.setOnClickListener { cycleFps() }
        fpsCell.setOnLongClickListener {
            toggleModePanel()
            true
        }
        topBar.addView(fpsCell)
        topBar.addView(buildTopCell("OBTUR.", Control.SHUTTER, onClick = {
            togglePanel(Control.SHUTTER)
        }, onLongClick = { toggleLock(Control.SHUTTER) }))
        val irisCell = LinearLayout(this)
        irisCell.orientation = LinearLayout.VERTICAL
        irisCell.setPadding(dp(7), dp(2), dp(7), dp(2))
        irisCell.addView(label("ÍRIS", 8f, MUTED, bold = true))
        irisValue = label("f1,8", 15f, MUTED, bold = true)
        irisValue.typeface = Typeface.MONOSPACE
        irisCell.addView(irisValue)
        topBar.addView(irisCell)
        topBar.addView(buildTopCell("FOCO", Control.FOCUS, onClick = {
            togglePanel(Control.FOCUS)
        }, onLongClick = { toggleLock(Control.FOCUS) }))

        timecodeText = label("00:00:00:00", 23f, Color.WHITE, bold = true)
        timecodeText.typeface = Typeface.MONOSPACE
        timecodeText.gravity = Gravity.CENTER
        val tcParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        topBar.addView(timecodeText, tcParams)

        topBar.addView(buildTopCell("ISO", Control.ISO, onClick = {
            togglePanel(Control.ISO)
        }, onLongClick = { toggleLock(Control.ISO) }))
        // WB: valor + selo azul "A" quando automatico (como na referencia).
        val wbCell = LinearLayout(this)
        wbCell.orientation = LinearLayout.VERTICAL
        wbCell.setPadding(dp(7), dp(2), dp(7), dp(2))
        val wbNameRow = LinearLayout(this)
        wbNameRow.orientation = LinearLayout.HORIZONTAL
        wbNameRow.gravity = Gravity.CENTER_VERTICAL
        val wbName = label("WB", 8f, MUTED, bold = true)
        wbNameRow.addView(wbName)
        wbBadge = label("A", 8f, Color.WHITE, bold = true)
        wbBadge.setBackgroundColor(BLUE)
        wbBadge.setPadding(dp(3), 0, dp(3), 0)
        val wbBadgeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        wbBadgeParams.leftMargin = dp(4)
        wbNameRow.addView(wbBadge, wbBadgeParams)
        wbCell.addView(wbNameRow)
        wbValue = label("-", 15f, Color.WHITE, bold = true)
        wbValue.typeface = Typeface.MONOSPACE
        wbCell.addView(wbValue)
        cellNames[Control.WB] = wbName
        cellValues[Control.WB] = wbValue
        wbCell.setOnClickListener { togglePanel(Control.WB) }
        wbCell.setOnLongClickListener {
            toggleLock(Control.WB)
            true
        }
        topBar.addView(wbCell)
        val tomCell = LinearLayout(this)
        tomCell.orientation = LinearLayout.VERTICAL
        tomCell.setPadding(dp(7), dp(2), dp(7), dp(2))
        tomCell.addView(label("TOM", 8f, MUTED, bold = true))
        tomValue = label("0", 15f, Color.WHITE, bold = true)
        tomValue.typeface = Typeface.MONOSPACE
        tomCell.addView(tomValue)
        tomCell.setOnClickListener { togglePanel(Control.WB) }
        topBar.addView(tomCell)

        // Selo do modo (abre o painel de modo: resolucao, codec, bitrate,
        // destino e projeto - o que era a linha de botoes da Etapa 3).
        val badge = LinearLayout(this)
        badge.orientation = LinearLayout.VERTICAL
        badge.gravity = Gravity.CENTER
        badge.setPadding(dp(8), dp(3), dp(8), dp(3))
        val badgeBg = GradientDrawable()
        badgeBg.shape = GradientDrawable.RECTANGLE
        badgeBg.cornerRadius = dp(6).toFloat()
        badgeBg.setColor(Color.TRANSPARENT)
        badgeBg.setStroke(dp(1), Color.WHITE)
        badge.background = badgeBg
        badgeRes = label("4K", 13f, Color.WHITE, bold = true)
        badgeRes.gravity = Gravity.CENTER
        badgeSub = label("16:9", 8.5f, MUTED)
        badgeSub.gravity = Gravity.CENTER
        badge.addView(badgeRes)
        badge.addView(badgeSub)
        val badgeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        badgeParams.leftMargin = dp(8)
        badge.setOnClickListener { toggleModePanel() }
        topBar.addView(badge, badgeParams)
    }

    private fun buildBottomHud(stage: FrameLayout) {
        val hud = LinearLayout(this)
        hud.orientation = LinearLayout.HORIZONTAL
        hud.gravity = Gravity.BOTTOM
        hud.setBackgroundColor(0x73000000)
        hud.setPadding(dp(12), dp(8), dp(12), dp(8))
        val hudParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hudParams.gravity = Gravity.BOTTOM
        stage.addView(hud, hudParams)

        // Esquerda: histograma (dado real e a Etapa 4; lugar reservado).
        val histo = LinearLayout(this)
        histo.orientation = LinearLayout.VERTICAL
        histo.addView(label("Rec.709", 9f, MUTED))
        val histoBox = FrameLayout(this)
        val histoBg = GradientDrawable()
        histoBg.shape = GradientDrawable.RECTANGLE
        histoBg.setColor(0x33000000)
        histoBg.setStroke(dp(1), 0x66FFFFFF)
        histoBox.background = histoBg
        val histoNote = label("histograma · Etapa 4", 8.5f, MUTED)
        histoNote.gravity = Gravity.CENTER
        histoBox.addView(
            histoNote,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        histo.addView(histoBox, LinearLayout.LayoutParams(dp(118), dp(38)))
        hud.addView(histo)

        // Centro: tempo restante estimado, barra de espaco e bateria.
        val storage = LinearLayout(this)
        storage.orientation = LinearLayout.VERTICAL
        storage.gravity = Gravity.CENTER_HORIZONTAL
        val storageParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        storageParams.leftMargin = dp(16)
        storageParams.rightMargin = dp(16)
        hud.addView(storage, storageParams)
        val remainRow = LinearLayout(this)
        remainRow.orientation = LinearLayout.HORIZONTAL
        remainRow.gravity = Gravity.CENTER_VERTICAL
        remainTimeText = label("--:--:--", 15f, Color.WHITE, bold = true)
        remainTimeText.typeface = Typeface.MONOSPACE
        remainRow.addView(remainTimeText)
        batteryText = label("", 10.5f, MUTED)
        val batteryParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        batteryParams.leftMargin = dp(10)
        remainRow.addView(batteryText, batteryParams)
        storage.addView(remainRow)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0x40FFFFFF)
        val barParams = LinearLayout.LayoutParams(dp(190), dp(4))
        barParams.topMargin = dp(4)
        barFill = View(this)
        barFill.setBackgroundColor(BLUE)
        barRest = View(this)
        bar.addView(barFill, LinearLayout.LayoutParams(0, dp(4), 0f))
        bar.addView(barRest, LinearLayout.LayoutParams(0, dp(4), 1f))
        storage.addView(bar, barParams)
        val freeRow = LinearLayout(this)
        freeRow.orientation = LinearLayout.HORIZONTAL
        val freeRowParams = LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT)
        freeRowParams.topMargin = dp(3)
        remainPctText = label("", 9.5f, MUTED)
        freeRow.addView(
            remainPctText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        remainFreeText = label("", 9.5f, MUTED)
        freeRow.addView(remainFreeText)
        storage.addView(freeRow, freeRowParams)

        // Direita: medidores de audio (dado real e a Etapa 4; lugar reservado).
        val meters = LinearLayout(this)
        meters.orientation = LinearLayout.VERTICAL
        meters.addView(label("Padrão · áudio Etapa 4", 9f, MUTED))
        for (channel in 1..2) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val rowParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowParams.topMargin = dp(3)
            row.addView(label(channel.toString(), 9f, MUTED))
            val meterBar = LinearLayout(this)
            meterBar.orientation = LinearLayout.HORIZONTAL
            val meterParams = LinearLayout.LayoutParams(dp(140), dp(8))
            meterParams.leftMargin = dp(5)
            val green = View(this)
            green.setBackgroundColor(0xFF25C33B.toInt())
            val yellow = View(this)
            yellow.setBackgroundColor(0xFFE8C51C.toInt())
            val rest = View(this)
            rest.setBackgroundColor(0x2EFFFFFF)
            val red = View(this)
            red.setBackgroundColor(0xFF7A2020.toInt())
            meterBar.addView(green, LinearLayout.LayoutParams(0, dp(8), 52f))
            meterBar.addView(yellow, LinearLayout.LayoutParams(0, dp(8), 9f))
            meterBar.addView(rest, LinearLayout.LayoutParams(0, dp(8), 32f))
            meterBar.addView(red, LinearLayout.LayoutParams(0, dp(8), 7f))
            row.addView(meterBar, meterParams)
            meters.addView(row, rowParams)
        }
        val scale = label("-45   -30   -20   -10   -6   -3   0   3", 7.5f, MUTED)
        val scaleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scaleParams.leftMargin = dp(14)
        scaleParams.topMargin = dp(2)
        meters.addView(scale, scaleParams)
        hud.addView(meters)
    }

    private fun railButton(
        glyph: String, caption: String, onClick: () -> Unit
    ): LinearLayout {
        val button = LinearLayout(this)
        button.orientation = LinearLayout.VERTICAL
        button.gravity = Gravity.CENTER
        button.setPadding(dp(2), dp(5), dp(2), dp(5))
        val glyphView = label(glyph, 19f, Color.WHITE)
        glyphView.gravity = Gravity.CENTER
        button.addView(glyphView)
        val captionView = label(caption, 7.5f, MUTED, bold = true)
        captionView.gravity = Gravity.CENTER
        button.addView(captionView)
        button.setOnClickListener { onClick() }
        return button
    }

    private fun buildRail(rootRow: LinearLayout) {
        val rail = LinearLayout(this)
        rail.orientation = LinearLayout.VERTICAL
        rail.gravity = Gravity.CENTER
        rail.setBackgroundColor(0xF20B1220.toInt())
        rail.setPadding(dp(2), dp(8), dp(2), dp(8))
        rootRow.addView(
            rail,
            LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        gridButton = railButton("⊞", "GUIAS") {
            state.showGrid = !state.showGrid
            gridView.visibility = if (state.showGrid) View.VISIBLE else View.GONE
            refreshGridButton()
            showStatus(
                if (state.showGrid) "Grade de tercos ligada." else "Grade desligada."
            )
        }
        rail.addView(gridButton)
        rail.addView(railButton("±", "EV") { togglePanel(Control.EV) })
        val spacer1 = View(this)
        rail.addView(spacer1, LinearLayout.LayoutParams(1, 0, 1f))

        // REC: anel branco com miolo vermelho (quadrado ao gravar).
        val rec = FrameLayout(this)
        val ring = View(this)
        val ringBg = GradientDrawable()
        ringBg.shape = GradientDrawable.OVAL
        ringBg.setColor(Color.TRANSPARENT)
        ringBg.setStroke(dp(3), Color.WHITE)
        ring.background = ringBg
        rec.addView(
            ring,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
        )
        recCore = View(this)
        recCore.background = recCoreDrawable(recording = false)
        rec.addView(
            recCore,
            FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
        )
        rec.setOnClickListener { onRecTapped() }
        rail.addView(rec, LinearLayout.LayoutParams(dp(60), dp(60)))

        val spacer2 = View(this)
        rail.addView(spacer2, LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(railButton("◯", "FOTO") {
            showStatus("Foto (RAW+JPEG) chega na Etapa 6.")
        })
        rail.addView(railButton("LUT", "LUT") {
            showStatus("LUT .cube chega na Etapa 5.")
        })
        rail.addView(railButton("◎", "NÍVEL") {
            showStatus("Nivel e monitoramento completo chegam na Etapa 4.")
        })
    }

    private fun recCoreDrawable(recording: Boolean): GradientDrawable {
        val bg = GradientDrawable()
        if (recording) {
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dp(6).toFloat()
        } else {
            bg.shape = GradientDrawable.OVAL
        }
        bg.setColor(RED)
        return bg
    }

    private fun refreshGridButton() {
        gridButton.setBackgroundColor(
            if (state.showGrid) 0x33FFB300 else Color.TRANSPARENT
        )
    }

    private fun buildTabs(rootRow: LinearLayout) {
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.VERTICAL
        tabs.gravity = Gravity.CENTER
        tabs.setBackgroundColor(Color.BLACK)
        tabs.setPadding(dp(4), dp(8), dp(4), dp(8))
        rootRow.addView(
            tabs,
            LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fun tab(glyph: String, caption: String, active: Boolean, onClick: () -> Unit) {
            val tabView = LinearLayout(this)
            tabView.orientation = LinearLayout.VERTICAL
            tabView.gravity = Gravity.CENTER
            tabView.setPadding(dp(2), dp(9), dp(2), dp(9))
            if (active) tabView.background = roundedBg(0x2E2E7CF6, 9)
            val color = if (active) BLUE_LIGHT else TAB_MUTED
            val glyphView = label(glyph, 19f, color)
            glyphView.gravity = Gravity.CENTER
            tabView.addView(glyphView)
            val captionView = label(caption, 9f, color, bold = active)
            captionView.gravity = Gravity.CENTER
            tabView.addView(captionView)
            tabView.setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(6)
            tabs.addView(tabView, params)
        }
        tab("▣", "Câmera", active = true) {
            panelKind = PanelKind.NONE
            selected = null
            showPanel()
        }
        tab("▶", "Mídia", active = false) {
            showStatus("Aba Midia chega na Etapa 7.")
        }
        tab("⚙", "Configurações", active = false) {
            showStatus("Configuracoes chegam na Etapa 7.")
        }
    }

    // ---------------------------------------------------------------
    // Toque na previa: foco por toque (com moldura) e pinca para zoom
    // ---------------------------------------------------------------
    private fun installTouch() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    state.zoomRatio = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(0.6f, 10f)
                    engine.applyState()
                    refreshHud()
                    if (selected == Control.ZOOM) showPanel()
                    return true
                }
            }
        )
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    if (textureView.width == 0) return false
                    if (state.focusMode == FocusMode.MANUAL) {
                        showStatus("Foco manual: o toque nao muda o foco.")
                        return true
                    }
                    if (state.focusMode != FocusMode.TOUCH) {
                        state.focusMode = FocusMode.TOUCH
                        showStatus("Foco por toque. Volte ao AF continuo no painel de FOCO.")
                    }
                    showReticle(event.x, event.y)
                    engine.tapFocus(
                        event.x / textureView.width, event.y / textureView.height
                    )
                    refreshHud()
                    return true
                }
            }
        )
        textureView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showReticle(x: Float, y: Float) {
        val params = reticle.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = (x - dp(38)).toInt().coerceAtLeast(0)
        params.topMargin = (y - dp(38)).toInt().coerceAtLeast(0)
        reticle.layoutParams = params
        reticle.visibility = View.VISIBLE
        reticleHide?.let { uiHandler.removeCallbacks(it) }
        val hide = Runnable { reticle.visibility = View.GONE }
        reticleHide = hide
        uiHandler.postDelayed(hide, 1400)
    }

    /**
     * Transformacao da previa - receita do Camera2Basic (Google): o
     * buffer da camera chega na orientacao do sensor (retrato), entao em
     * paisagem a matriz precisa girar 90 graus conforme o sentido
     * (ROTATION_90 ou ROTATION_270), senao a imagem aparece deitada,
     * como nos prints da v0.2.0.
     */
    private fun adjustAspect() {
        val width = textureView.width
        val height = textureView.height
        if (width == 0 || height == 0) return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val bufferRect = RectF(
            0f, 0f,
            previewSizeUsed.height.toFloat(), previewSizeUsed.width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(
                centerX - bufferRect.centerX(), centerY - bufferRect.centerY()
            )
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                height.toFloat() / previewSizeUsed.height,
                width.toFloat() / previewSizeUsed.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    // ---------------------------------------------------------------
    // Controles: nomes, estado manual/travado e trava por toque longo
    // ---------------------------------------------------------------
    private fun controlLabel(control: Control): String = when (control) {
        Control.ISO -> "ISO"
        Control.SHUTTER -> "OBTUR."
        Control.WB -> "WB"
        Control.FOCUS -> "FOCO"
        Control.EV -> "EV"
        Control.ZOOM -> "LENTE"
    }

    private fun isManual(control: Control): Boolean = when (control) {
        Control.ISO -> !state.isoAuto
        Control.SHUTTER -> !state.shutterAuto
        Control.WB -> !state.wbAuto
        Control.FOCUS -> state.focusMode == FocusMode.MANUAL
        Control.EV -> state.ev != 0f
        Control.ZOOM -> state.zoomRatio != 1f
    }

    private fun isLocked(control: Control): Boolean = when (control) {
        Control.ISO -> state.isoLocked
        Control.SHUTTER -> state.shutterLocked
        Control.WB -> state.wbLocked
        Control.FOCUS -> state.focusLocked
        else -> false
    }

    private fun toggleLock(control: Control) {
        when (control) {
            Control.ISO -> {
                if (!state.isoAuto) state.isoLocked = !state.isoLocked
                else return showStatus("Trave vale para valor manual: ajuste o ISO primeiro.")
            }
            Control.SHUTTER -> {
                if (!state.shutterAuto) state.shutterLocked = !state.shutterLocked
                else return showStatus("Trave vale para valor manual: ajuste o obturador primeiro.")
            }
            Control.WB -> {
                if (!state.wbAuto) state.wbLocked = !state.wbLocked
                else return showStatus("Trave vale para valor manual: ajuste o branco primeiro.")
            }
            Control.FOCUS -> {
                if (state.focusMode == FocusMode.MANUAL) {
                    state.focusLocked = !state.focusLocked
                } else return showStatus("Trave vale para o foco manual.")
            }
            else -> return showStatus("Este controle nao tem trava.")
        }
        showStatus(
            "${controlLabel(control)} ${if (isLocked(control)) "TRAVADO" else "destravado"}."
        )
        refreshHud()
    }

    // ---------------------------------------------------------------
    // Painel de ajuste fino e painel de modo
    // ---------------------------------------------------------------
    private fun togglePanel(control: Control) {
        if (panelKind == PanelKind.PARAM && selected == control) {
            panelKind = PanelKind.NONE
            selected = null
        } else {
            panelKind = PanelKind.PARAM
            selected = control
        }
        showPanel()
        refreshHud()
    }

    private fun toggleModePanel() {
        panelKind = if (panelKind == PanelKind.MODE) PanelKind.NONE else PanelKind.MODE
        selected = null
        showPanel()
        refreshHud()
    }

    private fun seekBar(
        max: Int, progress: Int, onChange: (Int) -> Unit
    ): SeekBar {
        val bar = SeekBar(this)
        bar.max = max
        bar.progress = progress
        bar.progressTintList = ColorStateList.valueOf(AMBER)
        bar.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        bar.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) onChange(value)
            }

            override fun onStartTrackingTouch(seek: SeekBar) {}
            override fun onStopTrackingTouch(seek: SeekBar) {}
        })
        return bar
    }

    private fun panelRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3), 0, dp(3))
        panel.addView(row)
        return row
    }

    private fun panelTitle(text: String) {
        val title = label(text, 10f, MUTED, bold = true)
        title.setPadding(0, dp(4), 0, dp(2))
        panel.addView(title)
    }

    private fun showPanel() {
        panel.removeAllViews()
        if (panelKind == PanelKind.NONE) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        if (panelKind == PanelKind.MODE) {
            showModePanel()
            return
        }
        val control = selected ?: return
        val fps = state.mode.fps
        when (control) {
            Control.ISO -> {
                val row = panelRow()
                val autoBtn = smallButton(if (state.isoAuto) "AUTO: LIGADO" else "AUTO: DESLIGADO")
                autoBtn.setOnClickListener {
                    state.isoAuto = !state.isoAuto
                    engine.applyState(); showPanel(); refreshHud()
                }
                row.addView(autoBtn)
                val valueLabel = label("ISO ${state.iso}", 13f, Color.WHITE, bold = true)
                val valueParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                valueParams.leftMargin = dp(12)
                row.addView(valueLabel, valueParams)
                val row2 = panelRow()
                row2.addView(
                    seekBar(CamState.ISO_STEPS.lastIndex, CamState.isoIndexOf(state.iso)) {
                        state.isoAuto = false
                        state.iso = CamState.ISO_STEPS[it]
                        valueLabel.text = "ISO ${state.iso}"
                        engine.applyState(); refreshHud()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            Control.SHUTTER -> {
                val row = panelRow()
                val autoBtn = smallButton(if (state.shutterAuto) "AUTO: LIGADO" else "AUTO: DESLIGADO")
                autoBtn.setOnClickListener {
                    state.shutterAuto = !state.shutterAuto
                    engine.applyState(); showPanel(); refreshHud()
                }
                row.addView(autoBtn)
                val kindBtn = smallButton(
                    if (state.shutterKind == ShutterKind.ANGLE) "MODO: ANGULO" else "MODO: VELOCIDADE"
                )
                kindBtn.setOnClickListener {
                    state.shutterAuto = false
                    state.shutterKind =
                        if (state.shutterKind == ShutterKind.ANGLE) ShutterKind.SPEED
                        else ShutterKind.ANGLE
                    engine.applyState(); showPanel(); refreshHud()
                }
                val kindParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                kindParams.leftMargin = dp(8)
                row.addView(kindBtn, kindParams)
                val valueLabel = label("", 13f, Color.WHITE, bold = true)
                val valueParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                valueParams.leftMargin = dp(12)
                row.addView(valueLabel, valueParams)
                fun updateLabel() {
                    val effective = CamState.formatShutterNs(state.manualExposureNs(fps))
                    valueLabel.text = when (state.shutterKind) {
                        ShutterKind.ANGLE ->
                            "${CamState.formatAngle(state.angleDeg)}° = $effective a ${fps}p"
                        ShutterKind.SPEED ->
                            "${CamState.SPEED_STEPS[state.speedIndex].first} (efetivo $effective)"
                    }
                }
                updateLabel()
                val row2 = panelRow()
                if (state.shutterKind == ShutterKind.ANGLE) {
                    row2.addView(
                        seekBar(
                            CamState.ANGLE_STEPS.lastIndex,
                            CamState.angleIndexOf(state.angleDeg)
                        ) {
                            state.shutterAuto = false
                            state.angleDeg = CamState.ANGLE_STEPS[it]
                            updateLabel(); engine.applyState(); refreshHud()
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                } else {
                    row2.addView(
                        seekBar(CamState.SPEED_STEPS.lastIndex, state.speedIndex) {
                            state.shutterAuto = false
                            state.speedIndex = it
                            updateLabel(); engine.applyState(); refreshHud()
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
            }
            Control.WB -> {
                val row = panelRow()
                val autoBtn = smallButton(if (state.wbAuto) "AUTO: LIGADO" else "AUTO: DESLIGADO")
                autoBtn.setOnClickListener {
                    state.wbAuto = !state.wbAuto
                    engine.applyState(); showPanel(); refreshHud()
                }
                row.addView(autoBtn)
                val valueLabel = label("${state.kelvin} K", 13f, Color.WHITE, bold = true)
                val valueParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                valueParams.leftMargin = dp(12)
                row.addView(valueLabel, valueParams)
                val row2 = panelRow()
                row2.addView(label("K", 11f, MUTED))
                row2.addView(
                    seekBar(114, (state.kelvin - 2300) / 50) {
                        state.wbAuto = false
                        state.kelvin = 2300 + it * 50
                        valueLabel.text = "${state.kelvin} K"
                        engine.applyState(); refreshHud()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val row3 = panelRow()
                row3.addView(label("TOM", 11f, MUTED))
                row3.addView(
                    seekBar(200, state.tint + 100) {
                        state.wbAuto = false
                        state.tint = it - 100
                        engine.applyState(); refreshHud()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val row4 = panelRow()
                for ((presetName, presetK) in CamState.WB_PRESETS) {
                    val chip = smallButton("$presetName ${presetK}")
                    chip.setOnClickListener {
                        state.wbAuto = false
                        state.kelvin = presetK
                        engine.applyState(); showPanel(); refreshHud()
                    }
                    val chipParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    chipParams.rightMargin = dp(6)
                    row4.addView(chip, chipParams)
                }
            }
            Control.FOCUS -> {
                val row = panelRow()
                val modes = listOf(
                    "AF CONTINUO" to FocusMode.CONTINUOUS,
                    "TOQUE" to FocusMode.TOUCH,
                    "MANUAL" to FocusMode.MANUAL
                )
                for ((modeName, focusMode) in modes) {
                    val chip = smallButton(
                        if (state.focusMode == focusMode) "[$modeName]" else modeName
                    )
                    chip.setOnClickListener {
                        state.focusMode = focusMode
                        engine.applyState(); showPanel(); refreshHud()
                    }
                    val chipParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    chipParams.rightMargin = dp(6)
                    row.addView(chip, chipParams)
                }
                val valueLabel = label(
                    CamState.formatFocusMeters(state.focusDiopters),
                    13f, Color.WHITE, bold = true
                )
                val valueParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                valueParams.leftMargin = dp(12)
                row.addView(valueLabel, valueParams)
                val row2 = panelRow()
                row2.addView(
                    seekBar(100, (state.focusDiopters * 10).roundToInt()) {
                        state.focusMode = FocusMode.MANUAL
                        state.focusDiopters = it / 10f
                        valueLabel.text = CamState.formatFocusMeters(state.focusDiopters)
                        engine.applyState(); refreshHud()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            Control.EV -> {
                val row = panelRow()
                val valueLabel = label(
                    String.format(Locale.US, "EV %+.1f", state.ev),
                    13f, Color.WHITE, bold = true
                )
                row.addView(valueLabel)
                val note = label(
                    "  (so age com ISO ou obturador em AUTO; faixa do aparelho: +-2)",
                    11f, MUTED
                )
                row.addView(note)
                val row2 = panelRow()
                row2.addView(
                    seekBar(40, ((state.ev + 2f) * 10).roundToInt()) {
                        state.ev = it / 10f - 2f
                        valueLabel.text =
                            String.format(Locale.US, "EV %+.1f", state.ev)
                        engine.applyState(); refreshHud()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            Control.ZOOM -> {
                val row = panelRow()
                val valueLabel = label(
                    String.format(Locale.US, "%.1fx", state.zoomRatio),
                    13f, Color.WHITE, bold = true
                )
                row.addView(valueLabel)
                for (chipRatio in floatArrayOf(0.6f, 1f, 2f, 5f, 10f)) {
                    val chip = smallButton(
                        String.format(Locale.US, "%.1fx", chipRatio)
                    )
                    chip.setOnClickListener {
                        state.zoomRatio = chipRatio
                        engine.applyState(); showPanel(); refreshHud()
                    }
                    val chipParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    chipParams.leftMargin = dp(6)
                    row.addView(chip, chipParams)
                }
                val row2 = panelRow()
                val minLog = ln(0.6)
                val maxLog = ln(10.0)
                val progress = ((ln(state.zoomRatio.toDouble()) - minLog) /
                    (maxLog - minLog) * 100).roundToInt()
                row2.addView(
                    seekBar(100, progress) {
                        val ratio = kotlin.math.exp(
                            minLog + (maxLog - minLog) * it / 100.0
                        ).toFloat()
                        state.zoomRatio = ratio
                        valueLabel.text = String.format(Locale.US, "%.1fx", ratio)
                        engine.applyState(); refreshHud()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }
    }

    /** Painel de modo: resolucao/codec/HDR, bitrate, destino e projeto. */
    private fun showModePanel() {
        panelTitle("MODO DE VÍDEO")
        val modes = VideoModes.ALL
        var index = 0
        while (index < modes.size) {
            val row = panelRow()
            for (offset in 0 until 4) {
                val modeIndex = index + offset
                if (modeIndex >= modes.size) break
                val mode = modes[modeIndex]
                val codec = if (mode.hevc) "H.265" else "H.264"
                val chip = chipButton(
                    "${mode.label} · $codec", state.modeIndex == modeIndex
                ) { selectMode(modeIndex) }
                val chipParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                chipParams.rightMargin = dp(6)
                row.addView(chip, chipParams)
            }
            index += 4
        }
        panelTitle("BITRATE (neste modo)")
        val bitrateRow = panelRow()
        val factors = listOf(0.5, 1.0, 1.6)
        val names = listOf("ECO", "PADRÃO", "ALTO")
        for (level in 0..2) {
            val mbps = (state.mode.bitrateMbps * factors[level])
                .roundToInt().coerceAtLeast(2)
            val chip = chipButton(
                "${names[level]} · $mbps Mbps", state.bitrateLevel == level
            ) { setBitrateLevel(level) }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            bitrateRow.addView(chip, chipParams)
        }
        panelTitle("DESTINO E PROJETO")
        val destRow = panelRow()
        val internalChip = chipButton("INTERNO", !state.destSsd) { onDestChip(false) }
        destRow.addView(internalChip)
        val ssdChip = chipButton("SSD", state.destSsd) { onDestChip(true) }
        ssdChip.setOnLongClickListener {
            pickSsdFolder()
            true
        }
        val ssdParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        ssdParams.leftMargin = dp(6)
        destRow.addView(ssdChip, ssdParams)
        val projectChip = chipButton(
            if (state.projectPrefix.isEmpty()) "PROJETO: nenhum"
            else "PROJETO: ${state.projectPrefix}",
            state.projectPrefix.isNotEmpty()
        ) { editProjectPrefix() }
        val projectParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        projectParams.leftMargin = dp(6)
        destRow.addView(projectChip, projectParams)
        val hint = label(
            "Arquivos: DXCam[_projeto]_data_hora.mp4 · toque longo no SSD troca a pasta.",
            10.5f, MUTED
        )
        hint.setPadding(0, dp(4), 0, dp(2))
        panel.addView(hint)
    }

    // ---------------------------------------------------------------
    // Atualizacao de textos (topo, HUD inferior, timecode)
    // ---------------------------------------------------------------
    private fun refreshModeUi() {
        val mode = state.mode
        badgeRes.text = when {
            mode.width >= 3840 -> "4K"
            mode.width >= 1920 -> "1080"
            else -> "720"
        }
        badgeSub.text = if (mode.hdr) "HDR 10-bit" else "16:9"
        badgeSub.setTextColor(if (mode.hdr) AMBER else MUTED)
        fpsValue.text = mode.fps.toString()
    }

    private fun refreshHud() {
        val fps = state.mode.fps
        val readback = lastReadback
        cellValues[Control.ISO]?.text =
            if (state.isoAuto) (readback?.iso?.toString() ?: "AUTO") else state.iso.toString()
        cellValues[Control.SHUTTER]?.text =
            if (state.shutterAuto) {
                readback?.exposureNs?.let { CamState.formatShutterNs(it) } ?: "AUTO"
            } else {
                state.shutterLabel(fps)
            }
        cellValues[Control.FOCUS]?.text = when (state.focusMode) {
            FocusMode.CONTINUOUS ->
                readback?.focusDiopters?.let { CamState.formatFocusMeters(it) } ?: "AF-C"
            FocusMode.TOUCH -> "TOQUE"
            FocusMode.MANUAL -> CamState.formatFocusMeters(state.focusDiopters)
        }
        cellValues[Control.ZOOM]?.text =
            "${(24 * state.zoomRatio).roundToInt()} mm"
        wbValue.text = if (state.wbAuto) {
            readback?.let { "AUTO" } ?: "AUTO"
        } else {
            "${state.kelvin}K"
        }
        wbBadge.visibility = if (state.wbAuto) View.VISIBLE else View.GONE
        tomValue.text = String.format(Locale.US, "%+d", state.tint)
        tomValue.setTextColor(
            if (state.tint != 0) AMBER else Color.WHITE
        )
        for (control in listOf(
            Control.ISO, Control.SHUTTER, Control.WB, Control.FOCUS, Control.ZOOM
        )) {
            val valueView = cellValues[control] ?: continue
            val nameView = cellNames[control] ?: continue
            valueView.setTextColor(
                when {
                    isLocked(control) -> AMBER
                    isManual(control) -> AMBER
                    panelKind == PanelKind.PARAM && selected == control -> BLUE_LIGHT
                    else -> Color.WHITE
                }
            )
            nameView.text = buildString {
                append(controlLabel(control))
                if (isLocked(control)) append(" · TRAV")
                else if (isManual(control)) append(" · MAN")
            }
            nameView.setTextColor(
                if (panelKind == PanelKind.PARAM && selected == control) {
                    BLUE_LIGHT
                } else {
                    MUTED
                }
            )
        }
    }

    private fun refreshStorage() {
        if (state.destSsd) {
            val granted = state.treeUri.isNotEmpty() &&
                contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == state.treeUri && it.isWritePermission
                }
            remainTimeText.text = "SSD"
            remainPctText.text = ""
            remainFreeText.text =
                if (granted) "destino externo" else "SSD: escolher pasta"
            (barFill.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (barRest.layoutParams as LinearLayout.LayoutParams).weight = 1f
            barFill.requestLayout()
            refreshBattery()
            return
        }
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            val usedPct = if (total > 0) {
                ((total - free).toDouble() / total * 100).toFloat()
            } else {
                0f
            }
            val bytesPerSec = state.effectiveBitrateMbps() * 1_000_000.0 / 8.0 + 24_000.0
            val remainSec = (free / bytesPerSec).toLong()
            remainTimeText.text = String.format(
                Locale.US, "%d:%02d:%02d",
                remainSec / 3600, (remainSec % 3600) / 60, remainSec % 60
            )
            remainPctText.text = String.format(Locale.US, "%.0f%% usado", usedPct)
            remainFreeText.text = String.format(
                Locale.US, "%.0f GB livres", free / 1_000_000_000.0
            )
            (barFill.layoutParams as LinearLayout.LayoutParams).weight = usedPct
            (barRest.layoutParams as LinearLayout.LayoutParams).weight = 100f - usedPct
            barFill.requestLayout()
        } catch (e: Exception) {
            remainTimeText.text = "--:--:--"
        }
        refreshBattery()
    }

    private fun refreshBattery() {
        try {
            val batteryManager = getSystemService(BatteryManager::class.java)
            val pct = batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            )
            batteryText.text = if (pct in 0..100) "BAT $pct%" else ""
        } catch (e: Exception) {
            batteryText.text = ""
        }
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        statusHide?.let { uiHandler.removeCallbacks(it) }
        val hide = Runnable { statusText.visibility = View.GONE }
        statusHide = hide
        uiHandler.postDelayed(hide, 4500)
    }

    private fun showWarning(message: String) {
        warningText.text = message
        warningText.visibility = View.VISIBLE
        uiHandler.postDelayed({ warningText.visibility = View.GONE }, 4500)
    }

    // ---------------------------------------------------------------
    // Modo, fps, bitrate, destino e gravacao
    // ---------------------------------------------------------------
    private fun selectMode(newIndex: Int) {
        if (engine.recording) {
            showStatus("Troca de modo so com a gravacao parada.")
            return
        }
        if (newIndex == state.modeIndex) return
        val oldMode = state.mode
        val newMode = VideoModes.ALL[newIndex]
        if (!state.shutterAuto && state.shutterKind == ShutterKind.SPEED) {
            val wanted = CamState.SPEED_STEPS[state.speedIndex].second
            if (wanted > newMode.frameDurationNs) {
                showWarning(
                    "Obturador ${CamState.SPEED_STEPS[state.speedIndex].first} nao cabe " +
                        "em ${newMode.fps}p: ajustado para " +
                        CamState.formatShutterNs(newMode.frameDurationNs) + "."
                )
            }
        }
        if (oldMode.hdr != newMode.hdr) {
            showStatus(
                if (newMode.hdr) "Modo HDR 10-bit (arquivo BT.2020/HDR)."
                else "Modo SDR."
            )
        }
        state.modeIndex = newIndex
        engine.applyState()
        refreshModeUi()
        refreshHud()
        refreshStorage()
        if (panelKind == PanelKind.MODE) showPanel()
        if (selected == Control.SHUTTER) showPanel()
    }

    /** Toque no FPS: alterna o fps dentro da mesma resolucao/HDR. */
    private fun cycleFps() {
        if (engine.recording) {
            showStatus("FPS so muda com a gravacao parada.")
            return
        }
        val current = state.mode
        val group = VideoModes.ALL.indices.filter { index ->
            val mode = VideoModes.ALL[index]
            mode.width == current.width && mode.height == current.height &&
                mode.hdr == current.hdr
        }
        if (group.size <= 1) {
            toggleModePanel()
            return
        }
        val position = group.indexOf(state.modeIndex)
        selectMode(group[(position + 1) % group.size])
    }

    private fun setBitrateLevel(level: Int) {
        if (engine.recording) {
            showStatus("Bitrate so muda com a gravacao parada.")
            return
        }
        state.bitrateLevel = level
        showStatus(
            "Bitrate ${state.bitrateLevelLabel()}: " +
                "${state.effectiveBitrateMbps()} Mbps neste modo."
        )
        refreshStorage()
        if (panelKind == PanelKind.MODE) showPanel()
    }

    private fun onDestChip(wantSsd: Boolean) {
        if (engine.recording) {
            showStatus("Destino so muda com a gravacao parada.")
            return
        }
        if (wantSsd && state.treeUri.isEmpty()) {
            pickSsdFolder()
            return
        }
        state.destSsd = wantSsd
        showStatus(
            if (wantSsd) "Destino: SSD (pasta escolhida)."
            else "Destino: interno (Movies/DXCam)."
        )
        refreshStorage()
        if (panelKind == PanelKind.MODE) showPanel()
    }

    private fun pickSsdFolder() {
        if (engine.recording) {
            showStatus("Destino so muda com a gravacao parada.")
            return
        }
        showStatus("Escolha a pasta do SSD onde os videos vao ser gravados.")
        treePickerLauncher.launch(null)
    }

    private fun editProjectPrefix() {
        val input = EditText(this)
        input.setText(state.projectPrefix)
        input.hint = "Ex.: EXPO2026, CASAMENTO_LIA"
        AlertDialog.Builder(this)
            .setTitle("Nome do projeto (entra no nome do arquivo)")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val cleaned = input.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                    .take(24)
                state.projectPrefix = cleaned
                showStatus(
                    if (cleaned.isEmpty()) {
                        "Sem projeto: arquivos DXCam_data_hora.mp4."
                    } else {
                        "Projeto '$cleaned': arquivos DXCam_${cleaned}_data_hora.mp4."
                    }
                )
                if (panelKind == PanelKind.MODE) showPanel()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onRecTapped() {
        if (!engineOpened) {
            showStatus("A camera ainda esta abrindo.")
            return
        }
        if (engine.isTransitioning) return
        if (engine.recording) engine.stopRecording() else engine.startRecording()
    }

    private fun startHudTick() {
        recordStartMs = SystemClock.elapsedRealtime()
        hudTickCount = 0
        val runnable = object : Runnable {
            override fun run() {
                val elapsedMs = SystemClock.elapsedRealtime() - recordStartMs
                val totalSec = elapsedMs / 1000
                val frames = ((elapsedMs % 1000) * state.mode.fps / 1000).toInt()
                timecodeText.text = String.format(
                    Locale.US, "%02d:%02d:%02d:%02d",
                    totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, frames
                )
                hudTickCount += 1
                if (hudTickCount % 20 == 0) refreshStorage()
                hudTick = this
                uiHandler.postDelayed(this, 50)
            }
        }
        hudTick = runnable
        uiHandler.post(runnable)
    }

    private fun stopHudTick() {
        hudTick?.let { uiHandler.removeCallbacks(it) }
        hudTick = null
        timecodeText.text = "00:00:00:00"
        timecodeText.setTextColor(Color.WHITE)
    }

    // ---------------------------------------------------------------
    // CameraEngine.Listener
    // ---------------------------------------------------------------
    override fun onReadback(readback: CameraEngine.Readback) {
        uiHandler.post {
            lastReadback = readback
            if (firstReadback) {
                firstReadback = false
                showStatus("Camera pronta. Toque num parametro no topo para ajustar.")
            }
            refreshHud()
        }
    }

    override fun onRecordingChanged(recording: Boolean) {
        uiHandler.post {
            recCore.background = recCoreDrawable(recording)
            if (recording) {
                timecodeText.setTextColor(RED)
                showStatus(
                    if (state.destSsd) "Gravando no SSD." else "Gravando em Movies/DXCam."
                )
                startHudTick()
            } else {
                showStatus(
                    if (state.destSsd) "Gravacao salva no SSD."
                    else "Gravacao salva em Movies/DXCam."
                )
                stopHudTick()
                refreshStorage()
            }
        }
    }

    override fun onFileSplit(part: Int) {
        uiHandler.post {
            showStatus(
                "Arquivo dividido sem perder quadros: parte $part gravando."
            )
        }
    }

    override fun onError(message: String) {
        uiHandler.post {
            showStatus(message)
            showWarning(message)
        }
    }

    /** Grade de tercos sobre o visor (botao GUIAS da regua lateral). */
    private class GridView(context: android.content.Context) : View(context) {
        private val paint = Paint().apply {
            color = 0x40FFFFFF
            strokeWidth = 1.5f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(w / 3, 0f, w / 3, h, paint)
            canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, paint)
            canvas.drawLine(0f, h / 3, w, h / 3, paint)
            canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, paint)
        }
    }

    companion object {
        private val AMBER = Color.rgb(255, 179, 0)
        private val RED = Color.rgb(255, 59, 48)
        private val MUTED = Color.rgb(158, 158, 158)
        private val BLUE = Color.rgb(46, 124, 246)
        private val BLUE_LIGHT = Color.rgb(76, 154, 255)
        private val TAB_MUTED = Color.rgb(126, 147, 172)
    }
}
