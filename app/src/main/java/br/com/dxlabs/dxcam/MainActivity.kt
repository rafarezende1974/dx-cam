package br.com.dxlabs.dxcam

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
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
    private enum class PanelKind { NONE, PARAM, MODE, MONITOR, LUT, PHOTO }

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
    private lateinit var levelButton: LinearLayout
    private lateinit var overlayView: MonitorOverlay
    private lateinit var histoView: HistogramView
    private lateinit var histoNote: TextView
    private lateinit var meterView1: MeterView
    private lateinit var meterView2: MeterView

    private val audioMeter = AudioMeter()
    private var sensorManager: SensorManager? = null
    private var meterTick: Runnable? = null
    private var peakDb: Float = -60f

    // Etapa 5 (LUT): previa GL criada sob demanda, sobre a TextureView.
    private lateinit var stage: FrameLayout
    private var lutView: LutPreviewView? = null
    private var lutSurface: android.view.Surface? = null
    private var glPreviewActive: Boolean = false
    private var lutCachePath: String? = null
    private var lutCacheAtlas: java.nio.ByteBuffer? = null
    private var lutCacheSize: Int = 0
    private lateinit var lutButton: LinearLayout

    // Etapa 6 (foto): flash do obturador sobre o visor.
    private lateinit var flashView: View

    // Etapa 7: abas Midia e Configuracoes em telas sobre o visor.
    private enum class TabKind { CAMERA, MEDIA, SETTINGS }
    private var currentTab: TabKind = TabKind.CAMERA
    private val tabViews =
        mutableMapOf<TabKind, Triple<LinearLayout, TextView, TextView>>()
    private lateinit var rootFrame: FrameLayout
    private lateinit var mediaView: LinearLayout
    private lateinit var mediaList: LinearLayout
    private lateinit var mediaHint: TextView
    private lateinit var settingsView: ScrollView
    private lateinit var settingsContainer: LinearLayout
    private lateinit var viewerView: LinearLayout
    private lateinit var viewerTitle: TextView
    private var viewerVideo: VideoView? = null
    private var viewerImage: ImageView? = null
    private var viewerNote: TextView? = null
    private var viewerItem: MediaItem? = null
    private var mediaSourceSsd: Boolean = false

    private data class MediaItem(
        val name: String,
        val uri: Uri,
        val video: Boolean,
        val dng: Boolean,
        val mime: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val dateMs: Long,
        val fromSsd: Boolean
    )

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

    private val lutImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLut(uri)
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

    override fun onResume() {
        super.onResume()
        lutView?.onResume()
        startMeterTick()
        if (state.levelOn) registerLevelSensor()
        if (state.metersOn && !engine.recording) audioMeter.start()
    }

    override fun onPause() {
        stopMeterTick()
        unregisterLevelSensor()
        audioMeter.stop()
        lutView?.onPause()
        super.onPause()
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
        stage = FrameLayout(this)
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

        // Etapa 4: zebra, peaking e nivel desenhados sobre o visor.
        overlayView = MonitorOverlay(this)
        stage.addView(
            overlayView,
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

        // Etapa 6: flash do obturador (some/aparece num piscar).
        flashView = View(this)
        flashView.setBackgroundColor(Color.BLACK)
        flashView.alpha = 0f
        flashView.isClickable = false
        stage.addView(
            flashView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

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

        // Etapa 7: moldura raiz para as abas cobrirem visor + regua,
        // deixando a coluna de abas sempre visivel a direita.
        rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(Color.BLACK)
        rootFrame.addView(
            rootRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        buildMediaView()
        buildSettingsView()
        buildViewerView()
        setContentView(rootFrame)
        refreshModeUi()
        refreshHud()
        refreshStorage()
        refreshGridButton()
        updateMonitoringFlags()
        refreshLevelButton()
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

        // Esquerda: histograma real (Etapa 4). Toque abre o painel de
        // monitoramento (zebra, peaking, medidores e limiar).
        val histo = LinearLayout(this)
        histo.orientation = LinearLayout.VERTICAL
        histo.addView(label("Rec.709", 9f, MUTED))
        val histoBox = FrameLayout(this)
        val histoBg = GradientDrawable()
        histoBg.shape = GradientDrawable.RECTANGLE
        histoBg.setColor(0x33000000)
        histoBg.setStroke(dp(1), 0x66FFFFFF)
        histoBox.background = histoBg
        histoView = HistogramView(this)
        histoBox.addView(
            histoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        histoNote = label("histograma desligado", 8.5f, MUTED)
        histoNote.gravity = Gravity.CENTER
        histoBox.addView(
            histoNote,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        histoBox.setOnClickListener { toggleMonitorPanel() }
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

        // Direita: medidores de audio reais (Etapa 4). O microfone e
        // mono: os dois canais mostram o mesmo nivel. Toque abre o
        // painel de monitoramento.
        val meters = LinearLayout(this)
        meters.orientation = LinearLayout.VERTICAL
        meters.addView(label("Padrão", 9f, MUTED))
        meterView1 = MeterView(this)
        meterView2 = MeterView(this)
        for ((channel, meterView) in listOf(1 to meterView1, 2 to meterView2)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val rowParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowParams.topMargin = dp(3)
            row.addView(label(channel.toString(), 9f, MUTED))
            val meterParams = LinearLayout.LayoutParams(dp(140), dp(8))
            meterParams.leftMargin = dp(5)
            row.addView(meterView, meterParams)
            meters.addView(row, rowParams)
        }
        val scale = label("-45   -30   -20   -10   -6   -3   0   3", 7.5f, MUTED)
        val scaleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scaleParams.leftMargin = dp(14)
        scaleParams.topMargin = dp(2)
        meters.addView(scale, scaleParams)
        meters.setOnClickListener { toggleMonitorPanel() }
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
        val fotoButton = railButton("◯", "FOTO") { capturePhoto() }
        fotoButton.setOnLongClickListener {
            togglePhotoPanel()
            true
        }
        rail.addView(fotoButton)
        lutButton = railButton("LUT", "LUT") { toggleLutPanel() }
        rail.addView(lutButton)
        levelButton = railButton("◎", "NÍVEL") {
            state.levelOn = !state.levelOn
            applyLevelState()
            showStatus(
                if (state.levelOn) "Nivel ligado: alinhe a linha ao horizonte."
                else "Nivel desligado."
            )
        }
        rail.addView(levelButton)
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

    private fun refreshLevelButton() {
        levelButton.setBackgroundColor(
            if (state.levelOn) 0x33FFB300 else Color.TRANSPARENT
        )
    }

    // ---------------------------------------------------------------
    // Etapa 4: nivel (sensor de rotacao), medidores e flags
    // ---------------------------------------------------------------
    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val remapped = FloatArray(9)
            when (display?.rotation ?: Surface.ROTATION_0) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
                )
                else -> System.arraycopy(matrix, 0, remapped, 0, 9)
            }
            val orientation = FloatArray(3)
            SensorManager.getOrientation(remapped, orientation)
            val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
            overlayView.levelAngleDeg = -rollDeg
            overlayView.invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerLevelSensor() {
        if (sensorManager == null) {
            sensorManager = getSystemService(SensorManager::class.java)
        }
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return
        sensorManager?.registerListener(
            rotationListener, sensor, SensorManager.SENSOR_DELAY_UI,
            uiHandler
        )
    }

    private fun unregisterLevelSensor() {
        try {
            sensorManager?.unregisterListener(rotationListener)
        } catch (e: Exception) {
        }
    }

    private fun applyLevelState() {
        overlayView.showLevel = state.levelOn
        overlayView.invalidate()
        refreshLevelButton()
        if (state.levelOn) registerLevelSensor() else unregisterLevelSensor()
    }

    private fun amplitudeToDb(amplitude: Int): Float {
        if (amplitude <= 0) return -60f
        return (20.0 * kotlin.math.log10(amplitude / 32767.0))
            .toFloat().coerceIn(-60f, 0f)
    }

    private fun startMeterTick() {
        stopMeterTick()
        peakDb = -60f
        val runnable = object : Runnable {
            override fun run() {
                val db = when {
                    !state.metersOn -> -60f
                    engine.recording -> amplitudeToDb(engine.currentMaxAmplitude())
                    else -> audioMeter.currentDb
                }
                peakDb = if (db > peakDb) db else maxOf(db, peakDb - 0.3f)
                meterView1.db = db
                meterView1.peakDb = peakDb
                meterView2.db = db
                meterView2.peakDb = peakDb
                meterView1.invalidate()
                meterView2.invalidate()
                meterTick = this
                uiHandler.postDelayed(this, 100)
            }
        }
        meterTick = runnable
        uiHandler.post(runnable)
    }

    private fun stopMeterTick() {
        meterTick?.let { uiHandler.removeCallbacks(it) }
        meterTick = null
    }

    /** Liga/desliga os canais de monitoramento conforme o estado. */
    private fun updateMonitoringFlags() {
        engine.monitoringEnabled =
            state.zebraOn || state.peakingOn || state.histoOn
        overlayView.showZebra = state.zebraOn
        overlayView.showPeaking = state.peakingOn
        overlayView.showLevel = state.levelOn
        overlayView.invalidate()
        histoView.visibility = if (state.histoOn) View.VISIBLE else View.GONE
        histoNote.visibility = if (state.histoOn) View.GONE else View.VISIBLE
        if (!state.histoOn) {
            histoView.bins = null
            histoView.invalidate()
        }
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
        fun tab(
            kind: TabKind, glyph: String, caption: String, onClick: () -> Unit
        ) {
            val tabView = LinearLayout(this)
            tabView.orientation = LinearLayout.VERTICAL
            tabView.gravity = Gravity.CENTER
            tabView.setPadding(dp(2), dp(9), dp(2), dp(9))
            val glyphView = label(glyph, 19f, TAB_MUTED)
            glyphView.gravity = Gravity.CENTER
            tabView.addView(glyphView)
            val captionView = label(caption, 9f, TAB_MUTED)
            captionView.gravity = Gravity.CENTER
            tabView.addView(captionView)
            tabView.setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(6)
            tabs.addView(tabView, params)
            tabViews[kind] = Triple(tabView, glyphView, captionView)
        }
        tab(TabKind.CAMERA, "▣", "Câmera") { selectTab(TabKind.CAMERA) }
        tab(TabKind.MEDIA, "▶", "Mídia") { selectTab(TabKind.MEDIA) }
        tab(TabKind.SETTINGS, "⚙", "Configurações") { selectTab(TabKind.SETTINGS) }
        refreshTabs()
    }

    private fun refreshTabs() {
        for ((kind, views) in tabViews) {
            val active = kind == currentTab
            val (container, glyphView, captionView) = views
            container.background =
                if (active) roundedBg(0x2E2E7CF6, 9) else null
            val color = if (active) BLUE_LIGHT else TAB_MUTED
            glyphView.setTextColor(color)
            captionView.setTextColor(color)
            captionView.typeface = Typeface.create(
                Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    private fun selectTab(kind: TabKind) {
        if (kind != TabKind.CAMERA && engine.recording) {
            showStatus("Termine a gravacao para trocar de aba.")
            return
        }
        currentTab = kind
        if (kind == TabKind.CAMERA) {
            panelKind = PanelKind.NONE
            selected = null
            showPanel()
        }
        if (kind != TabKind.MEDIA) closeViewer()
        mediaView.visibility = if (kind == TabKind.MEDIA) View.VISIBLE else View.GONE
        settingsView.visibility =
            if (kind == TabKind.SETTINGS) View.VISIBLE else View.GONE
        refreshTabs()
        if (kind == TabKind.MEDIA) loadMedia()
        if (kind == TabKind.SETTINGS) buildSettings()
    }

    // ---------------------------------------------------------------
    // Etapa 7: aba Midia (grade + visualizador) e Configuracoes
    // ---------------------------------------------------------------
    private fun overlayParams(): FrameLayout.LayoutParams {
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        params.rightMargin = dp(78)
        return params
    }

    private fun buildMediaView() {
        mediaView = LinearLayout(this)
        mediaView.orientation = LinearLayout.VERTICAL
        mediaView.setBackgroundColor(0xFF0B1220.toInt())
        mediaView.setPadding(dp(12), dp(10), dp(12), dp(10))
        mediaView.visibility = View.GONE
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = label("MÍDIA — material do DX Cam", 14f, Color.WHITE, bold = true)
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val internalChip = chipButton("INTERNO", !mediaSourceSsd) {
            mediaSourceSsd = false
            rebuildMediaHeader()
            loadMedia()
        }
        internalChip.tag = "media-src-internal"
        header.addView(internalChip)
        val ssdChip = chipButton("SSD", mediaSourceSsd) {
            if (state.treeUri.isEmpty()) {
                showStatus("SSD ainda nao escolhido: defina no painel do modo ou nas Configuracoes.")
            } else {
                mediaSourceSsd = true
                rebuildMediaHeader()
                loadMedia()
            }
        }
        ssdChip.tag = "media-src-ssd"
        val ssdParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        ssdParams.leftMargin = dp(6)
        header.addView(ssdChip, ssdParams)
        val refreshButton = smallButton("ATUALIZAR")
        refreshButton.setOnClickListener { loadMedia() }
        val refreshParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        refreshParams.leftMargin = dp(6)
        header.addView(refreshButton, refreshParams)
        mediaView.addView(header)
        mediaHint = label("", 11f, MUTED)
        mediaHint.setPadding(0, dp(4), 0, dp(4))
        mediaView.addView(mediaHint)
        val scroll = ScrollView(this)
        mediaList = LinearLayout(this)
        mediaList.orientation = LinearLayout.VERTICAL
        scroll.addView(mediaList)
        mediaView.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        rootFrame.addView(mediaView, overlayParams())
    }

    /** Recria a tela de midia para os chips de origem refletirem a escolha. */
    private fun rebuildMediaHeader() {
        rootFrame.removeView(mediaView)
        buildMediaView()
        mediaView.visibility = View.VISIBLE
    }

    private fun loadMedia() {
        mediaList.removeAllViews()
        mediaHint.text = "Carregando..."
        val fromSsd = mediaSourceSsd
        Thread {
            val items = try {
                if (fromSsd) loadSsdItems() else loadInternalItems()
            } catch (e: Exception) {
                emptyList()
            }
            uiHandler.post {
                if (currentTab == TabKind.MEDIA && mediaSourceSsd == fromSsd) {
                    renderMediaItems(items, fromSsd)
                }
            }
        }.start()
    }

    private fun loadInternalItems(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Movies/DXCam%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol).toString()
                )
                items.add(
                    MediaItem(
                        name = cursor.getString(nameCol) ?: "video",
                        uri = uri,
                        video = true,
                        dng = false,
                        mime = "video/mp4",
                        sizeBytes = cursor.getLong(sizeCol),
                        durationMs = cursor.getLong(durCol),
                        dateMs = cursor.getLong(dateCol) * 1000,
                        fromSsd = false
                    )
                )
            }
        }
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/DXCam%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: "foto"
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol).toString()
                )
                items.add(
                    MediaItem(
                        name = name,
                        uri = uri,
                        video = false,
                        dng = name.lowercase().endsWith(".dng"),
                        mime = cursor.getString(mimeCol) ?: "image/jpeg",
                        sizeBytes = cursor.getLong(sizeCol),
                        durationMs = 0,
                        dateMs = cursor.getLong(dateCol) * 1000,
                        fromSsd = false
                    )
                )
            }
        }
        return items.sortedByDescending { it.dateMs }.take(60)
    }

    private fun loadSsdItems(): List<MediaItem> {
        if (state.treeUri.isEmpty()) return emptyList()
        val treeUri = Uri.parse(state.treeUri)
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, treeDocId
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val items = mutableListOf<MediaItem>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: ""
                val lower = name.lowercase()
                val isVideo = mime.startsWith("video/") || lower.endsWith(".mp4")
                val isDng = lower.endsWith(".dng")
                val isImage = mime.startsWith("image/") || isDng ||
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                if (!isVideo && !isImage) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, cursor.getString(idCol)
                )
                items.add(
                    MediaItem(
                        name = name,
                        uri = docUri,
                        video = isVideo,
                        dng = isDng,
                        mime = if (isDng) "image/x-adobe-dng" else mime,
                        sizeBytes = if (cursor.isNull(sizeCol)) 0 else cursor.getLong(sizeCol),
                        durationMs = 0,
                        dateMs = if (cursor.isNull(dateCol)) 0 else cursor.getLong(dateCol),
                        fromSsd = true
                    )
                )
            }
        }
        return items.sortedByDescending { it.dateMs }.take(60)
    }

    private fun renderMediaItems(items: List<MediaItem>, fromSsd: Boolean) {
        mediaList.removeAllViews()
        mediaHint.text = if (items.isEmpty()) {
            if (fromSsd) {
                "Nenhum arquivo do DX Cam nesta pasta do SSD ainda."
            } else {
                "Nenhum video ou foto do DX Cam ainda. Grave ou fotografe e volte aqui."
            }
        } else {
            "${items.size} itens ${if (fromSsd) "no SSD" else "no aparelho"} " +
                "— toque para conferir."
        }
        var index = 0
        while (index < items.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (offset in 0 until 3) {
                val itemIndex = index + offset
                if (itemIndex >= items.size) {
                    row.addView(
                        View(this),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    continue
                }
                row.addView(
                    mediaCard(items[itemIndex]),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            mediaList.addView(row)
            index += 3
        }
    }

    private fun mediaCard(item: MediaItem): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(4), dp(4), dp(4), dp(8))
        val thumb = ImageView(this)
        thumb.setBackgroundColor(0xFF1B2740.toInt())
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.tag = item.uri.toString()
        card.addView(thumb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(84)
        ))
        val name = label(item.name, 10f, Color.WHITE)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        name.setPadding(0, dp(3), 0, 0)
        card.addView(name)
        val meta = StringBuilder()
        if (item.video) {
            meta.append("VÍDEO")
            if (item.durationMs > 0) meta.append(" · ").append(formatDuration(item.durationMs))
        } else if (item.dng) {
            meta.append("RAW (DNG)")
        } else {
            meta.append("FOTO")
        }
        if (item.sizeBytes > 0) meta.append(" · ").append(formatSize(item.sizeBytes))
        val metaView = label(meta.toString(), 9f, MUTED)
        card.addView(metaView)
        card.setOnClickListener { openViewer(item) }
        Thread {
            val bitmap = loadThumb(item)
            if (bitmap != null) {
                uiHandler.post {
                    if (thumb.tag == item.uri.toString()) {
                        thumb.setImageBitmap(bitmap)
                    }
                }
            }
        }.start()
        return card
    }

    private fun loadThumb(item: MediaItem): Bitmap? {
        return try {
            when {
                !item.fromSsd -> contentResolver.loadThumbnail(
                    item.uri, Size(480, 270), null
                )
                item.dng -> null
                item.video -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                        }
                        retriever.getFrameAtTime(
                            0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } finally {
                        retriever.release()
                    }
                }
                else -> {
                    contentResolver.openInputStream(item.uri)?.use { input ->
                        val bounds = BitmapFactory.Options()
                        bounds.inJustDecodeBounds = true
                        BitmapFactory.decodeStream(input, null, bounds)
                        null
                    }
                    contentResolver.openInputStream(item.uri)?.use { input ->
                        val options = BitmapFactory.Options()
                        options.inSampleSize = 4
                        BitmapFactory.decodeStream(input, null, options)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(
            Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60
        )
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes >= 1_000_000_000) {
            String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        } else {
            String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        }
    }

    private fun buildViewerView() {
        viewerView = LinearLayout(this)
        viewerView.orientation = LinearLayout.VERTICAL
        viewerView.setBackgroundColor(Color.BLACK)
        viewerView.setPadding(dp(12), dp(10), dp(12), dp(10))
        viewerView.visibility = View.GONE
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        viewerTitle = label("", 12f, Color.WHITE, bold = true)
        viewerTitle.maxLines = 1
        viewerTitle.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        header.addView(
            viewerTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val openButton = smallButton("ABRIR FORA")
        openButton.setOnClickListener { openExternal() }
        header.addView(openButton)
        val closeButton = smallButton("FECHAR")
        closeButton.setOnClickListener { closeViewer() }
        val closeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        closeParams.leftMargin = dp(6)
        header.addView(closeButton, closeParams)
        viewerView.addView(header)
        val content = FrameLayout(this)
        viewerView.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        val video = VideoView(this)
        video.visibility = View.GONE
        content.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        viewerVideo = video
        val image = ImageView(this)
        image.visibility = View.GONE
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        content.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        viewerImage = image
        val note = label("", 13f, MUTED)
        note.gravity = Gravity.CENTER
        note.visibility = View.GONE
        content.addView(
            note,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        viewerNote = note
        rootFrame.addView(viewerView, overlayParams())
    }

    private fun openViewer(item: MediaItem) {
        viewerItem = item
        viewerTitle.text = item.name
        viewerVideo?.visibility = View.GONE
        viewerImage?.visibility = View.GONE
        viewerNote?.visibility = View.GONE
        viewerView.visibility = View.VISIBLE
        when {
            item.video -> {
                val video = viewerVideo ?: return
                video.visibility = View.VISIBLE
                val controller = MediaController(this)
                controller.setAnchorView(video)
                video.setMediaController(controller)
                video.setVideoURI(item.uri)
                video.setOnErrorListener { _, _, _ ->
                    showStatus("Nao consegui tocar este video aqui; use ABRIR FORA.")
                    true
                }
                video.start()
            }
            item.dng -> {
                val note = viewerNote ?: return
                note.text = "RAW (DNG) nao tem previa aqui.\n" +
                    "Use ABRIR FORA para ver no Lightroom ou na galeria."
                note.visibility = View.VISIBLE
            }
            else -> {
                val image = viewerImage ?: return
                image.visibility = View.VISIBLE
                image.setImageBitmap(null)
                Thread {
                    val bitmap = try {
                        contentResolver.openInputStream(item.uri)?.use { input ->
                            val options = BitmapFactory.Options()
                            options.inSampleSize = 2
                            BitmapFactory.decodeStream(input, null, options)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    uiHandler.post {
                        if (viewerItem?.uri == item.uri) {
                            image.setImageBitmap(bitmap)
                            if (bitmap == null) {
                                showStatus("Nao consegui abrir a previa; use ABRIR FORA.")
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun closeViewer() {
        if (!::viewerView.isInitialized) return
        try {
            viewerVideo?.stopPlayback()
        } catch (e: Exception) {
        }
        viewerView.visibility = View.GONE
        viewerItem = null
    }

    private fun openExternal() {
        val item = viewerItem ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(item.uri, item.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            showStatus("Nenhum app externo abriu este arquivo.")
        }
    }

    private fun buildSettingsView() {
        settingsView = ScrollView(this)
        settingsView.setBackgroundColor(0xFF0B1220.toInt())
        settingsView.visibility = View.GONE
        settingsContainer = LinearLayout(this)
        settingsContainer.orientation = LinearLayout.VERTICAL
        settingsContainer.setPadding(dp(14), dp(10), dp(14), dp(16))
        settingsView.addView(settingsContainer)
        rootFrame.addView(settingsView, overlayParams())
    }

    private fun settingsSection(title: String) {
        val view = label(title, 11f, BLUE_LIGHT, bold = true)
        view.setPadding(0, dp(12), 0, dp(4))
        settingsContainer.addView(view)
    }

    private fun sRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3), 0, dp(3))
        settingsContainer.addView(row)
        return row
    }

    private fun buildSettings() {
        settingsContainer.removeAllViews()
        val title = label("CONFIGURAÇÕES", 14f, Color.WHITE, bold = true)
        settingsContainer.addView(title)

        settingsSection("GRAVAÇÃO — destino, bitrate e projeto")
        val destRow = sRow()
        val internalChip = chipButton("INTERNO", !state.destSsd) {
            onDestChip(false)
            buildSettings()
        }
        destRow.addView(internalChip)
        val ssdChip = chipButton(
            if (state.treeUri.isEmpty()) "SSD (escolher pasta)" else "SSD",
            state.destSsd
        ) {
            onDestChip(true)
            buildSettings()
        }
        val ssdParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        ssdParams.leftMargin = dp(6)
        destRow.addView(ssdChip, ssdParams)
        if (state.treeUri.isNotEmpty()) {
            val changeButton = smallButton("TROCAR PASTA DO SSD")
            changeButton.setOnClickListener { pickSsdFolder() }
            val changeParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            changeParams.leftMargin = dp(6)
            destRow.addView(changeButton, changeParams)
        }
        val bitrateRow = sRow()
        val levels = listOf("ECO", "PADRÃO", "ALTO")
        for ((level, labelText) in levels.withIndex()) {
            val chip = chipButton(labelText, state.bitrateLevel == level) {
                setBitrateLevel(level)
                buildSettings()
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            bitrateRow.addView(chip, chipParams)
        }
        val bitrateInfo = label(
            "Bitrate atual: ${state.effectiveBitrateMbps()} Mbps no modo " +
                state.mode.label + ".",
            11f, MUTED
        )
        settingsContainer.addView(bitrateInfo)
        val projectRow = sRow()
        projectRow.addView(
            label(
                "Projeto: " +
                    if (state.projectPrefix.isEmpty()) "(nenhum)"
                    else state.projectPrefix,
                12f, Color.WHITE
            )
        )
        val projectButton = smallButton("EDITAR")
        projectButton.setOnClickListener {
            editProjectPrefix()
            buildSettings()
        }
        val projectParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        projectParams.leftMargin = dp(8)
        projectRow.addView(projectButton, projectParams)

        settingsSection("FOTO — formato")
        val photoRow = sRow()
        val formats = listOf("RAW + JPEG", "SÓ JPEG", "SÓ RAW (DNG)")
        for ((format, labelText) in formats.withIndex()) {
            val chip = chipButton(labelText, state.photoFormat == format) {
                state.photoFormat = format
                buildSettings()
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            photoRow.addView(chip, chipParams)
        }

        settingsSection("MONITORAMENTO — padroes do visor")
        val monitorRow = sRow()
        val toggles = listOf(
            Triple("ZEBRA", state.zebraOn) { state.zebraOn = !state.zebraOn },
            Triple("PEAKING", state.peakingOn) { state.peakingOn = !state.peakingOn },
            Triple("HISTOGRAMA", state.histoOn) { state.histoOn = !state.histoOn },
            Triple("MEDIDORES", state.metersOn) { state.metersOn = !state.metersOn },
            Triple("NÍVEL", state.levelOn) { state.levelOn = !state.levelOn },
            Triple("GUIAS", state.showGrid) { state.showGrid = !state.showGrid }
        )
        for ((labelText, isOn, toggle) in toggles) {
            val chip = chipButton(labelText, isOn) {
                toggle()
                updateMonitoringFlags()
                applyLevelState()
                gridView.visibility =
                    if (state.showGrid) View.VISIBLE else View.GONE
                refreshGridButton()
                refreshLevelButton()
                buildSettings()
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            monitorRow.addView(chip, chipParams)
        }
        val zebraRow = sRow()
        zebraRow.addView(label("Zebra ${state.zebraIre} IRE", 12f, Color.WHITE))
        val zebraSeek = seekBar(30, state.zebraIre - 70) {
            state.zebraIre = it + 70
            buildSettings()
        }
        val zebraParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        zebraParams.leftMargin = dp(10)
        zebraRow.addView(zebraSeek, zebraParams)

        settingsSection("LUT — previa com look (.cube)")
        val lutRow = sRow()
        val lutNames = state.lutPaths().map { File(it).nameWithoutExtension }
        lutRow.addView(
            label(
                when {
                    lutNames.isEmpty() -> "Nenhuma LUT importada."
                    state.lutOn -> "Ativa: " +
                        (lutNames.getOrNull(state.lutActive) ?: "-")
                    else -> "Importadas: ${lutNames.size} (desligada)"
                },
                12f, Color.WHITE
            )
        )
        val lutImport = smallButton("IMPORTAR .CUBE")
        lutImport.setOnClickListener {
            lutImportLauncher.launch(arrayOf("*/*"))
        }
        val lutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lutParams.leftMargin = dp(8)
        lutRow.addView(lutImport, lutParams)
        val lutIntensityRow = sRow()
        lutIntensityRow.addView(
            label("Intensidade ${state.lutIntensity}%", 12f, Color.WHITE)
        )
        val lutSeek = seekBar(100, state.lutIntensity) {
            state.lutIntensity = it
            lutView?.setIntensity(it / 100f)
            buildSettings()
        }
        val lutSeekParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        lutSeekParams.leftMargin = dp(10)
        lutIntensityRow.addView(lutSeek, lutSeekParams)

        settingsSection("SOBRE")
        val free = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            formatSize(stat.availableBytes)
        } catch (e: Exception) {
            "-"
        }
        val about = label(
            "DX Cam v0.9.0-rastro — DX Labs\n" +
                "Camera de cinema para o S24: video manual, foto RAW, " +
                "monitoramento e LUT so na previa.\n" +
                "Espaco livre no aparelho: $free.",
            12f, MUTED
        )
        settingsContainer.addView(about)
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

    private fun toggleMonitorPanel() {
        panelKind =
            if (panelKind == PanelKind.MONITOR) PanelKind.NONE else PanelKind.MONITOR
        selected = null
        showPanel()
        refreshHud()
    }

    /** Painel da Etapa 4: ligas do monitoramento e limiar da zebra. */
    private fun showMonitorPanel() {
        panelTitle("MONITORAMENTO")
        val row = panelRow()
        val toggles = listOf(
            Triple("ZEBRA", state.zebraOn) { state.zebraOn = !state.zebraOn },
            Triple("PEAKING", state.peakingOn) { state.peakingOn = !state.peakingOn },
            Triple("HISTOGRAMA", state.histoOn) { state.histoOn = !state.histoOn },
            Triple("MEDIDORES", state.metersOn) { state.metersOn = !state.metersOn }
        )
        for ((name, isOn, toggle) in toggles) {
            val chip = chipButton("$name: ${if (isOn) "LIGADO" else "DESLIGADO"}", isOn) {
                toggle()
                updateMonitoringFlags()
                if (name == "MEDIDORES") {
                    if (state.metersOn && !engine.recording) audioMeter.start()
                    else audioMeter.stop()
                }
                showPanel()
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            row.addView(chip, chipParams)
        }
        val row2 = panelRow()
        val ireLabel = label(
            "Zebra a partir de ${state.zebraIre} IRE", 12f, Color.WHITE, bold = true
        )
        row2.addView(ireLabel)
        val row3 = panelRow()
        row3.addView(
            seekBar(6, (state.zebraIre - 70) / 5) {
                state.zebraIre = 70 + it * 5
                ireLabel.text = "Zebra a partir de ${state.zebraIre} IRE"
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val note = label(
            "Analise leve da previa (~8 fps). Se a sessao de gravacao " +
                "encher, a imagem do monitoramento pausa e a gravacao segue.",
            10.5f, MUTED
        )
        note.setPadding(0, dp(4), 0, dp(2))
        panel.addView(note)
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
        if (panelKind == PanelKind.MONITOR) {
            showMonitorPanel()
            return
        }
        if (panelKind == PanelKind.LUT) {
            showLutPanel()
            return
        }
        if (panelKind == PanelKind.PHOTO) {
            showPhotoPanel()
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

    // ---------------------------------------------------------------
    // Etapa 6: foto (JPEG + RAW/DNG) com os controles da previa
    // ---------------------------------------------------------------
    private fun capturePhoto() {
        if (engine.trailing) {
            engine.stopTrail(save = true)
            showStatus("Rastro encerrado: salvando o que somou.")
            return
        }
        if (engine.recording) {
            showStatus("Foto so com a gravacao parada.")
            return
        }
        flashView.animate().cancel()
        flashView.animate()
            .alpha(0.55f)
            .setDuration(60)
            .withEndAction {
                flashView.animate().alpha(0f).setDuration(160).start()
            }
            .start()
        engine.capturePhoto(displayRotationDegrees())
    }

    private fun displayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        val rotation = try {
            display?.rotation ?: windowManager.defaultDisplay.rotation
        } catch (e: Exception) {
            android.view.Surface.ROTATION_90
        }
        return when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun togglePhotoPanel() {
        panelKind = if (panelKind == PanelKind.PHOTO) PanelKind.NONE else PanelKind.PHOTO
        selected = null
        showPanel()
        refreshHud()
    }

    private fun showPhotoPanel() {
        panelTitle("FOTO — toque no FOTO dispara; segurar abre este painel")
        val row = panelRow()
        val options = listOf(
            CameraEngine.PHOTO_RAW_JPEG to "RAW + JPEG",
            CameraEngine.PHOTO_JPEG_ONLY to "SÓ JPEG",
            CameraEngine.PHOTO_RAW_ONLY to "SÓ RAW (DNG)"
        )
        for ((format, labelText) in options) {
            val chip = chipButton(labelText, state.photoFormat == format) {
                state.photoFormat = format
                showPanel()
                showStatus(
                    "Formato da foto: $labelText. Destino: " +
                        if (state.destSsd) "SSD" else "Pictures/DXCam."
                )
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            row.addView(chip, chipParams)
        }
        val note = label(
            "A foto usa os controles manuais da prévia (ISO, obturador, " +
                "foco, branco e zoom). O DNG tem ~25 MB; o JPEG sai no " +
                "mesmo tamanho do sensor RAW. O destino segue o do " +
                "vídeo (INTERNO/SSD no painel do modo).",
            12f, MUTED
        )
        note.setPadding(0, dp(6), 0, 0)
        panel.addView(note)
        // Etapa 8: rastro por empilhamento (o sensor expoe no maximo
        // 1/10 por quadro; segundos de rastro = muitos quadros somados).
        panelTitle("RASTRO — longa exposição por empilhamento")
        val durationRow = panelRow()
        for (seconds in listOf(1, 2, 5, 10, 20, 30)) {
            val chip = chipButton(
                "${seconds}s", state.trailDurationSec == seconds
            ) {
                state.trailDurationSec = seconds
                showPanel()
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            durationRow.addView(chip, chipParams)
        }
        val blendRow = panelRow()
        val luzChip = chipButton("LUZ (trilhas)", state.trailBlend == 0) {
            state.trailBlend = 0
            showPanel()
        }
        blendRow.addView(luzChip)
        val moveChip = chipButton("MOVIMENTO (borrão)", state.trailBlend == 1) {
            state.trailBlend = 1
            showPanel()
        }
        val moveParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        moveParams.leftMargin = dp(6)
        blendRow.addView(moveChip, moveParams)
        val startRow = panelRow()
        val startButton = smallButton(
            if (engine.trailing) "PARAR E SALVAR O RASTRO" else "INICIAR RASTRO"
        )
        startButton.setOnClickListener {
            if (engine.trailing) {
                engine.stopTrail(save = true)
            } else {
                panelKind = PanelKind.NONE
                showPanel()
                engine.startTrail()
            }
        }
        startRow.addView(startButton)
        val trailNote = label(
            "LUZ: o pixel mais claro vence — trilhas de farol, escrita " +
                "com luz, fogos. MOVIMENTO: media dos quadros — agua " +
                "sedosa e borrao de movimento. Durante o rastro, tocar " +
                "em FOTO para e salva. Sai em JPEG ate 1920px de largura.",
            11f, MUTED
        )
        trailNote.setPadding(0, dp(4), 0, 0)
        panel.addView(trailNote)
    }

    // ---------------------------------------------------------------
    // Etapa 5: LUT .cube (so na previa; o arquivo grava limpo)
    // ---------------------------------------------------------------
    private fun toggleLutPanel() {
        panelKind = if (panelKind == PanelKind.LUT) PanelKind.NONE else PanelKind.LUT
        selected = null
        showPanel()
        refreshHud()
    }

    private fun refreshLutButton() {
        if (::lutButton.isInitialized) {
            lutButton.setBackgroundColor(
                if (state.lutOn) 0x33FFB300 else Color.TRANSPARENT
            )
        }
    }

    private fun ensureLutView(): LutPreviewView {
        val existing = lutView
        if (existing != null) return existing
        val view = LutPreviewView(this)
        view.setBufferSize(previewSizeUsed.width, previewSizeUsed.height)
        view.onSurfaceReady = { surface ->
            lutSurface = surface
            if (state.lutOn && !glPreviewActive) {
                if (engine.setPreviewSurface(surface)) {
                    glPreviewActive = true
                }
            }
        }
        view.visibility = View.GONE
        stage.addView(
            view,
            1,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        lutView = view
        return view
    }

    private fun applyLutState() {
        refreshLutButton()
        if (engine.recording) {
            // A troca de superficie reabre a sessao: nunca no meio da
            // gravacao. O estado fica salvo e aplica ao parar.
            return
        }
        val paths = state.lutPaths()
        val activeOk = state.lutActive in paths.indices
        if (state.lutOn && activeOk) {
            val view = ensureLutView()
            view.visibility = View.VISIBLE
            loadLutIntoView(paths[state.lutActive])
            view.setIntensity(state.lutIntensity / 100f)
            val surface = lutSurface
            if (surface != null && !glPreviewActive) {
                if (engine.setPreviewSurface(surface)) {
                    glPreviewActive = true
                }
            }
        } else {
            if (glPreviewActive) {
                val texture = textureView.surfaceTexture
                if (texture != null) {
                    engine.setPreviewSurface(android.view.Surface(texture))
                }
                glPreviewActive = false
            }
            lutView?.visibility = View.GONE
        }
    }

    private fun loadLutIntoView(path: String) {
        val cachedAtlas = lutCacheAtlas
        if (lutCachePath == path && cachedAtlas != null) {
            cachedAtlas.position(0)
            lutView?.setLut(cachedAtlas, lutCacheSize)
            return
        }
        Thread {
            try {
                val data = FileInputStream(File(path)).use { input ->
                    LutFile.parse(input, File(path).nameWithoutExtension)
                }
                val atlas = LutFile.buildAtlasRgba(data)
                lutCachePath = path
                lutCacheAtlas = atlas
                lutCacheSize = data.size
                uiHandler.post {
                    if (state.lutOn &&
                        state.lutPaths().getOrNull(state.lutActive) == path
                    ) {
                        atlas.position(0)
                        lutView?.setLut(atlas, data.size)
                    }
                }
            } catch (e: Exception) {
                uiHandler.post {
                    showStatus("Nao consegui ler a LUT: ${e.message}")
                }
            }
        }.start()
    }

    private fun importLut(uri: android.net.Uri) {
        showStatus("Importando a LUT...")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("arquivo ilegivel")
                val data = LutFile.parse(
                    ByteArrayInputStream(bytes), "LUT"
                )
                val dir = File(filesDir, "luts")
                dir.mkdirs()
                val base = data.title
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                    .take(32)
                    .ifEmpty { "LUT" }
                var file = File(dir, "$base.cube")
                var counter = 2
                while (file.exists()) {
                    file = File(dir, "${base}_$counter.cube")
                    counter += 1
                }
                file.writeBytes(bytes)
                val paths = state.lutPaths().toMutableList()
                paths.add(file.absolutePath)
                state.lutFiles = paths.joinToString("\n")
                state.lutActive = paths.lastIndex
                state.lutOn = true
                uiHandler.post {
                    applyLutState()
                    if (panelKind == PanelKind.LUT) showPanel()
                    showStatus("LUT '${data.title}' ativa na previa.")
                }
            } catch (e: Exception) {
                uiHandler.post {
                    showStatus("Nao importei a LUT: ${e.message}")
                }
            }
        }.start()
    }

    private fun showLutPanel() {
        panelTitle("LUT .CUBE — só na prévia; o arquivo grava limpo")
        val paths = state.lutPaths()
        if (paths.isEmpty()) {
            val empty = label(
                "Nenhuma LUT importada ainda. Importe os .cube que voce " +
                    "usa no Premiere.",
                12f, MUTED
            )
            empty.setPadding(0, dp(4), 0, dp(4))
            panel.addView(empty)
        } else {
            var index = 0
            while (index < paths.size) {
                val row = panelRow()
                for (offset in 0 until 3) {
                    val lutIndex = index + offset
                    if (lutIndex >= paths.size) break
                    val name = File(paths[lutIndex]).nameWithoutExtension
                    val chip = chipButton(
                        name, state.lutActive == lutIndex && state.lutOn
                    ) {
                        if (engine.recording) {
                            showStatus("LUT so muda com a gravacao parada.")
                        } else {
                            state.lutActive = lutIndex
                            state.lutOn = true
                            applyLutState()
                            showPanel()
                        }
                    }
                    val chipParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    chipParams.rightMargin = dp(6)
                    row.addView(chip, chipParams)
                }
                index += 3
            }
        }
        val row = panelRow()
        val toggleChip = chipButton(
            if (state.lutOn) "LUT: LIGADA" else "LUT: DESLIGADA", state.lutOn
        ) {
            if (engine.recording) {
                showStatus("LUT so muda com a gravacao parada.")
            } else if (state.lutPaths().isEmpty()) {
                showStatus("Importe uma LUT primeiro.")
            } else {
                state.lutOn = !state.lutOn
                if (state.lutOn && state.lutActive !in state.lutPaths().indices) {
                    state.lutActive = 0
                }
                applyLutState()
                showPanel()
            }
        }
        row.addView(toggleChip)
        val intensityLabel = label(
            "Intensidade ${state.lutIntensity}%", 12f, Color.WHITE, bold = true
        )
        val intensityParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        intensityParams.leftMargin = dp(12)
        row.addView(intensityLabel, intensityParams)
        val row2 = panelRow()
        row2.addView(
            seekBar(100, state.lutIntensity) {
                state.lutIntensity = it
                intensityLabel.text = "Intensidade $it%"
                lutView?.setIntensity(it / 100f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val row3 = panelRow()
        val importButton = smallButton("IMPORTAR .CUBE")
        importButton.setOnClickListener {
            lutImportLauncher.launch(arrayOf("*/*"))
        }
        row3.addView(importButton)
        if (paths.isNotEmpty() && state.lutActive in paths.indices) {
            val removeButton = smallButton("REMOVER A ATIVA")
            removeButton.setOnClickListener {
                val removed = paths[state.lutActive]
                try {
                    File(removed).delete()
                } catch (e: Exception) {
                }
                val remaining = paths.toMutableList()
                remaining.removeAt(state.lutActive)
                state.lutFiles = remaining.joinToString("\n")
                state.lutActive = if (remaining.isEmpty()) -1 else 0
                if (remaining.isEmpty()) state.lutOn = false
                lutCachePath = null
                lutCacheAtlas = null
                applyLutState()
                showPanel()
                showStatus("LUT removida.")
            }
            val removeParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            removeParams.leftMargin = dp(8)
            row3.addView(removeButton, removeParams)
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
        // Etapa 8: time lapse (1 quadro a cada intervalo; toca no fps do modo).
        panelTitle("TIME LAPSE — intervalo entre quadros")
        val tlRow = panelRow()
        val tlOptions = listOf(
            0f to "DESLIGADO",
            0.5f to "0,5s",
            1f to "1s",
            2f to "2s",
            5f to "5s",
            10f to "10s",
            30f to "30s"
        )
        for ((seconds, labelText) in tlOptions) {
            val chip = chipButton(labelText, state.timelapseSec == seconds) {
                if (engine.recording) {
                    showStatus("Time lapse so muda com a gravacao parada.")
                } else {
                    state.timelapseSec = seconds
                    showPanel()
                    if (seconds > 0f) {
                        val factor = (state.mode.fps * seconds).roundToInt()
                        showStatus(
                            "Time lapse $labelText: video ${factor}x mais " +
                                "rapido. A previa fica em ritmo de quadro " +
                                "durante a gravacao."
                        )
                    } else {
                        showStatus("Time lapse desligado.")
                    }
                }
            }
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.rightMargin = dp(6)
            tlRow.addView(chip, chipParams)
        }
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
                if (state.lutOn) applyLutState()
                refreshLutButton()
            }
            refreshHud()
        }
    }

    override fun onRecordingChanged(recording: Boolean) {
        uiHandler.post {
            recCore.background = recCoreDrawable(recording)
            if (recording) {
                // Medidores passam a vir do MediaRecorder; o leitor de
                // espera para para nao disputar o microfone.
                audioMeter.stop()
                peakDb = -60f
                timecodeText.setTextColor(RED)
                showStatus(
                    when {
                        state.timelapseSec > 0f ->
                            "Time lapse ${state.timelapseSec}s gravando " +
                                if (state.destSsd) "no SSD." else "em Movies/DXCam."
                        state.destSsd -> "Gravando no SSD."
                        else -> "Gravando em Movies/DXCam."
                    }
                )
                startHudTick()
            } else {
                if (state.metersOn) audioMeter.start()
                showStatus(
                    if (state.destSsd) "Gravacao salva no SSD."
                    else "Gravacao salva em Movies/DXCam."
                )
                stopHudTick()
                refreshStorage()
                if (state.lutOn) applyLutState()
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

    override fun onPhotoSaved(message: String) {
        uiHandler.post { showStatus(message) }
    }

    private var lastTrailStatusAt: Long = 0

    override fun onTrailProgress(frames: Int, elapsedMs: Long, totalMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTrailStatusAt < 900) return
        lastTrailStatusAt = now
        val percent = if (totalMs > 0) {
            (elapsedMs * 100 / totalMs).coerceIn(0, 100)
        } else {
            0
        }
        uiHandler.post {
            if (engine.trailing) {
                showStatus(
                    "Rastro: $percent% · $frames quadros somados — " +
                        "toque em FOTO para parar e salvar."
                )
            }
        }
    }

    override fun onTrailSaved(message: String) {
        uiHandler.post { showStatus(message) }
    }

    override fun onError(message: String) {
        uiHandler.post {
            showStatus(message)
            showWarning(message)
        }
    }

    override fun onInfo(message: String) {
        uiHandler.post { showStatus(message) }
    }

    /**
     * Etapa 4: a analise roda aqui (thread de fundo do motor) e so o
     * desenho vai para a tela.
     */
    override fun onAnalysisFrame(y: ByteArray, width: Int, height: Int) {
        if (!state.zebraOn && !state.peakingOn && !state.histoOn) return
        val result = MonitorAnalyzer.analyze(
            y, width, height,
            zebraLuma = state.zebraIre * 255 / 100,
            wantZebra = state.zebraOn,
            wantPeaking = state.peakingOn
        )
        uiHandler.post {
            if (state.histoOn) {
                histoView.bins = result.histogram
                histoView.invalidate()
            }
            overlayView.zebraCells = result.zebra
            overlayView.peakCells = result.peaking
            overlayView.invalidate()
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

    /** Histograma de luminancia (64 faixas) da Etapa 4. */
    private class HistogramView(context: android.content.Context) : View(context) {
        var bins: IntArray? = null
        private val paint = Paint()
        private val hotPaint = Paint().apply { color = 0xFFFF6B5E.toInt() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val data = bins ?: return
            val max = data.maxOrNull() ?: 0
            if (max <= 0 || width == 0 || height == 0) return
            val barWidth = width.toFloat() / data.size
            for (i in data.indices) {
                val h = data[i].toFloat() / max * (height - 2)
                paint.color = 0xD9FFFFFF.toInt()
                val usePaint = if (i >= data.size - 6) hotPaint else paint
                canvas.drawRect(
                    i * barWidth, height - h,
                    (i + 1) * barWidth - 0.5f, height.toFloat(),
                    usePaint
                )
            }
        }
    }

    /** Medidor de audio com zonas verde/amarela/vermelha e marca de pico. */
    private class MeterView(context: android.content.Context) : View(context) {
        var db: Float = -60f
        var peakDb: Float = -60f
        private val trackPaint = Paint().apply { color = 0x2EFFFFFF }
        private val greenPaint = Paint().apply { color = 0xFF25C33B.toInt() }
        private val yellowPaint = Paint().apply { color = 0xFFE8C51C.toInt() }
        private val redPaint = Paint().apply { color = 0xFFE33E3E.toInt() }
        private val peakPaint = Paint().apply { color = Color.WHITE }

        private fun fraction(valueDb: Float): Float =
            ((valueDb + 45f) / 48f).coerceIn(0f, 1f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, trackPaint)
            val fill = fraction(db) * w
            val greenEnd = fraction(-10f) * w
            val yellowEnd = fraction(-3f) * w
            if (fill > 0f) {
                canvas.drawRect(0f, 0f, minOf(fill, greenEnd), h, greenPaint)
            }
            if (fill > greenEnd) {
                canvas.drawRect(greenEnd, 0f, minOf(fill, yellowEnd), h, yellowPaint)
            }
            if (fill > yellowEnd) {
                canvas.drawRect(yellowEnd, 0f, fill, h, redPaint)
            }
            val peakX = fraction(peakDb) * w
            if (peakDb > -59f) {
                canvas.drawRect(peakX - 1f, 0f, peakX + 1f, h, peakPaint)
            }
        }
    }

    /**
     * Sobreposicao da Etapa 4: zebra (celulas acima do limiar), peaking
     * (bordas fortes) e a linha do nivel com o angulo em graus.
     */
    private class MonitorOverlay(context: android.content.Context) : View(context) {
        var zebraCells: BooleanArray? = null
        var peakCells: BooleanArray? = null
        var showZebra: Boolean = true
        var showPeaking: Boolean = false
        var showLevel: Boolean = false
        var levelAngleDeg: Float = 0f

        private val zebraFill = Paint().apply { color = 0x30FF3B30 }
        private val zebraLine = Paint().apply {
            color = 0xA0FF3B30.toInt()
            strokeWidth = 2f
        }
        private val peakPaint = Paint().apply { color = 0xFF40FFA0.toInt() }
        private val levelPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 3f
        }
        private val levelDot = Paint().apply { color = AMBER_STATIC }
        private val levelText = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w == 0f || h == 0f) return
            val cellW = w / MonitorAnalyzer.COLS
            val cellH = h / MonitorAnalyzer.ROWS
            val zebra = zebraCells
            if (showZebra && zebra != null) {
                for (r in 0 until MonitorAnalyzer.ROWS) {
                    for (c in 0 until MonitorAnalyzer.COLS) {
                        if (!zebra[r * MonitorAnalyzer.COLS + c]) continue
                        val x = c * cellW
                        val y = r * cellH
                        canvas.drawRect(x, y, x + cellW, y + cellH, zebraFill)
                        canvas.drawLine(x, y + cellH, x + cellW, y, zebraLine)
                    }
                }
            }
            val peaks = peakCells
            if (showPeaking && peaks != null) {
                for (r in 0 until MonitorAnalyzer.ROWS) {
                    for (c in 0 until MonitorAnalyzer.COLS) {
                        if (!peaks[r * MonitorAnalyzer.COLS + c]) continue
                        val cx = c * cellW + cellW / 2
                        val cy = r * cellH + cellH / 2
                        canvas.drawRect(
                            cx - cellW * 0.32f, cy - 1.5f,
                            cx + cellW * 0.32f, cy + 1.5f,
                            peakPaint
                        )
                    }
                }
            }
            if (showLevel) {
                val cx = w / 2
                val cy = h / 2
                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(levelAngleDeg)
                val half = w * 0.30f
                canvas.drawLine(-half, 0f, -dp8, 0f, levelPaint)
                canvas.drawLine(dp8, 0f, half, 0f, levelPaint)
                canvas.restore()
                canvas.drawCircle(cx, cy, 5f, levelDot)
                val text = String.format(
                    Locale.US, "%.0f°", kotlin.math.abs(levelAngleDeg)
                )
                canvas.drawText(text, cx, cy + 44f, levelText)
            }
        }

        private val dp8: Float = 10f

        companion object {
            private val AMBER_STATIC = Color.rgb(255, 179, 0)
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
