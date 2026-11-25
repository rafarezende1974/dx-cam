package br.com.dxlabs.dxcam

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * DX Cam v1 - tela de camera (Etapa 2: nucleo).
 *
 * Visor em tela cheia, faixa manual na base (ISO, obturador, branco,
 * foco, EV, zoom) com os valores REAIS lidos do quadro, painel de ajuste
 * fino sobre o visor ao tocar num controle, trava por toque longo e
 * botao GRAVAR com o caminho provado na Fase 0 (interno, Movies/DXCam;
 * HDR 10-bit nos modos HDR). Monitoramento e a Etapa 4, LUT a 5, foto a
 * 6 e destinos/bitrate/nomeacao avancados a Etapa 3.
 */
class MainActivity : ComponentActivity(), CameraEngine.Listener {

    private enum class Control { ISO, SHUTTER, WB, FOCUS, EV, ZOOM }

    private lateinit var state: CamState
    private lateinit var engine: CameraEngine
    private lateinit var textureView: TextureView
    private lateinit var modeButton: Button
    private lateinit var bitrateButton: Button
    private lateinit var destButton: Button
    private lateinit var spaceText: TextView
    private lateinit var timerText: TextView
    private lateinit var warningText: TextView
    private lateinit var statusText: TextView
    private lateinit var panel: LinearLayout
    private lateinit var recButton: Button

    private val cellValues = mutableMapOf<Control, TextView>()
    private val cellNames = mutableMapOf<Control, TextView>()
    private var selected: Control? = null
    private var lastReadback: CameraEngine.Readback? = null

    private val bgThread = HandlerThread("dxcam-bg")
    private lateinit var bgHandler: Handler
    private val uiHandler = Handler(Looper.getMainLooper())

    private var surfaceReady = false
    private var engineOpened = false
    private var previewSizeUsed: Size = Size(1920, 1080)
    private var firstReadback = true
    private var recordStartMs: Long = 0
    private var timerRunnable: Runnable? = null

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
                refreshTop()
                updateSpace()
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
        if (!hasPermissions()) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onDestroy() {
        stopTimer()
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
        // Troca de sentido na paisagem (90 <-> 270): reaplica a rotacao.
        textureView.post { adjustAspect() }
    }

    /** Tela cheia de verdade: some com as barras do sistema (visor de cinema). */
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

    // ---------------------------------------------------------------
    // Construcao da interface
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

    private fun smallButton(text: String): Button {
        val button = Button(this)
        button.text = text
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        button.setTextColor(Color.WHITE)
        button.setBackgroundColor(0x33FFFFFF)
        button.setPadding(dp(10), dp(4), dp(10), dp(4))
        return button
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        textureView = TextureView(this)
        root.addView(
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

        // Barra superior: marca, modo e espaco livre.
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.setBackgroundColor(0xB0000000.toInt())
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        topBar.gravity = Gravity.CENTER_VERTICAL
        val topParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topParams.gravity = Gravity.TOP
        root.addView(topBar, topParams)

        val brand = label("DX CAM", 15f, AMBER, bold = true)
        topBar.addView(brand)
        modeButton = smallButton("")
        modeButton.setOnClickListener { cycleMode() }
        modeButton.setOnLongClickListener {
            editProjectPrefix()
            true
        }
        val modeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        modeParams.leftMargin = dp(12)
        topBar.addView(modeButton, modeParams)
        bitrateButton = smallButton("")
        bitrateButton.setOnClickListener { cycleBitrate() }
        val bitrateParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bitrateParams.leftMargin = dp(8)
        topBar.addView(bitrateButton, bitrateParams)
        destButton = smallButton("")
        destButton.setOnClickListener { onDestTapped() }
        destButton.setOnLongClickListener {
            pickSsdFolder()
            true
        }
        val destParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        destParams.leftMargin = dp(8)
        topBar.addView(destButton, destParams)
        timerText = label("", 14f, RED, bold = true)
        timerText.typeface = Typeface.MONOSPACE
        timerText.visibility = View.GONE
        val timerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        timerParams.leftMargin = dp(12)
        topBar.addView(timerText, timerParams)
        val spacer = View(this)
        topBar.addView(
            spacer,
            LinearLayout.LayoutParams(0, 1, 1f)
        )
        spaceText = label("", 12f, MUTED)
        topBar.addView(spaceText)

        // Aviso (regra de ouro: valor travado nunca muda em silencio).
        warningText = label("", 13f, Color.BLACK, bold = true)
        warningText.setBackgroundColor(AMBER)
        warningText.setPadding(dp(12), dp(6), dp(12), dp(6))
        warningText.visibility = View.GONE
        val warningParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        warningParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        warningParams.topMargin = dp(52)
        root.addView(warningText, warningParams)

        // Base: painel de ajuste + faixa manual + acoes.
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(0xB0000000.toInt())
        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bottomParams.gravity = Gravity.BOTTOM
        root.addView(bottom, bottomParams)

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(8), dp(12), dp(4))
        panel.visibility = View.GONE
        bottom.addView(panel)

        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.setPadding(dp(4), dp(6), dp(4), dp(2))
        bottom.addView(strip)
        for (control in Control.values()) {
            strip.addView(buildCell(control), LinearLayout.LayoutParams(0, dp(52), 1f))
        }

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.gravity = Gravity.CENTER_VERTICAL
        actionRow.setPadding(dp(12), dp(2), dp(12), dp(10))
        bottom.addView(actionRow)
        statusText = label("Abrindo a camera...", 12f, MUTED)
        actionRow.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        recButton = Button(this)
        recButton.text = "●"
        recButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        recButton.setTextColor(RED)
        val recBg = GradientDrawable()
        recBg.shape = GradientDrawable.OVAL
        recBg.setColor(0x33FFFFFF)
        recBg.setStroke(dp(2), Color.WHITE)
        recButton.background = recBg
        recButton.setOnClickListener { onRecTapped() }
        actionRow.addView(recButton, LinearLayout.LayoutParams(dp(64), dp(64)))

        setContentView(root)
        refreshTop()
        refreshStrip()
        updateSpace()
    }

    private fun buildCell(control: Control): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        val name = label(controlLabel(control), 9f, MUTED, bold = true)
        name.gravity = Gravity.CENTER
        val value = label("-", 14f, Color.WHITE, bold = true)
        value.typeface = Typeface.MONOSPACE
        value.gravity = Gravity.CENTER
        cell.addView(name)
        cell.addView(value)
        cellNames[control] = name
        cellValues[control] = value
        cell.setOnClickListener {
            selected = if (selected == control) null else control
            showPanel()
            refreshStrip()
        }
        cell.setOnLongClickListener {
            toggleLock(control)
            true
        }
        return cell
    }

    private fun controlLabel(control: Control): String = when (control) {
        Control.ISO -> "ISO"
        Control.SHUTTER -> "OBTURADOR"
        Control.WB -> "BRANCO"
        Control.FOCUS -> "FOCO"
        Control.EV -> "EV"
        Control.ZOOM -> "ZOOM"
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
        refreshStrip()
    }

    // ---------------------------------------------------------------
    // Toque na previa: foco por toque e pinca para zoom
    // ---------------------------------------------------------------
    private fun installTouch() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    state.zoomRatio = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(0.6f, 10f)
                    engine.applyState()
                    refreshStrip()
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
                    engine.tapFocus(
                        event.x / textureView.width, event.y / textureView.height
                    )
                    refreshStrip()
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
    // Painel de ajuste fino
    // ---------------------------------------------------------------
    private fun seekBar(
        max: Int, progress: Int, onChange: (Int) -> Unit
    ): SeekBar {
        val bar = SeekBar(this)
        bar.max = max
        bar.progress = progress
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

    private fun showPanel() {
        panel.removeAllViews()
        val control = selected
        if (control == null) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        val fps = state.mode.fps
        when (control) {
            Control.ISO -> {
                val row = panelRow()
                val autoBtn = smallButton(if (state.isoAuto) "AUTO: LIGADO" else "AUTO: DESLIGADO")
                autoBtn.setOnClickListener {
                    state.isoAuto = !state.isoAuto
                    engine.applyState(); showPanel(); refreshStrip()
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
                        engine.applyState(); refreshStrip()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            Control.SHUTTER -> {
                val row = panelRow()
                val autoBtn = smallButton(if (state.shutterAuto) "AUTO: LIGADO" else "AUTO: DESLIGADO")
                autoBtn.setOnClickListener {
                    state.shutterAuto = !state.shutterAuto
                    engine.applyState(); showPanel(); refreshStrip()
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
                    engine.applyState(); showPanel(); refreshStrip()
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
                            updateLabel(); engine.applyState(); refreshStrip()
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                } else {
                    row2.addView(
                        seekBar(CamState.SPEED_STEPS.lastIndex, state.speedIndex) {
                            state.shutterAuto = false
                            state.speedIndex = it
                            updateLabel(); engine.applyState(); refreshStrip()
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
                    engine.applyState(); showPanel(); refreshStrip()
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
                        engine.applyState(); refreshStrip()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val row3 = panelRow()
                row3.addView(label("TINT", 11f, MUTED))
                row3.addView(
                    seekBar(200, state.tint + 100) {
                        state.wbAuto = false
                        state.tint = it - 100
                        engine.applyState()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                val row4 = panelRow()
                for ((presetName, presetK) in CamState.WB_PRESETS) {
                    val chip = smallButton("$presetName ${presetK}")
                    chip.setOnClickListener {
                        state.wbAuto = false
                        state.kelvin = presetK
                        engine.applyState(); showPanel(); refreshStrip()
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
                        engine.applyState(); showPanel(); refreshStrip()
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
                        engine.applyState(); refreshStrip()
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
                        engine.applyState(); refreshStrip()
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
                        engine.applyState(); showPanel(); refreshStrip()
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
                        engine.applyState(); refreshStrip()
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // Atualizacao de textos
    // ---------------------------------------------------------------
    private fun refreshTop() {
        val mode = state.mode
        val codec = if (mode.hevc) "H.265" else "H.264"
        modeButton.text =
            "${mode.label} · $codec · ${state.effectiveBitrateMbps()} Mbps"
        bitrateButton.text = state.bitrateLevelLabel()
        destButton.text = if (state.destSsd) "SSD" else "INTERNO"
    }

    private fun cycleBitrate() {
        if (engine.recording) {
            showStatus("Bitrate so muda com a gravacao parada.")
            return
        }
        state.bitrateLevel = (state.bitrateLevel + 1) % 3
        showStatus(
            "Bitrate ${state.bitrateLevelLabel()}: " +
                "${state.effectiveBitrateMbps()} Mbps neste modo."
        )
        refreshTop()
    }

    private fun onDestTapped() {
        if (engine.recording) {
            showStatus("Destino so muda com a gravacao parada.")
            return
        }
        if (state.treeUri.isEmpty()) {
            pickSsdFolder()
            return
        }
        state.destSsd = !state.destSsd
        showStatus(
            if (state.destSsd) "Destino: SSD (pasta escolhida)."
            else "Destino: interno (Movies/DXCam)."
        )
        refreshTop()
        updateSpace()
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
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refreshStrip() {
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
        cellValues[Control.WB]?.text =
            if (state.wbAuto) "AUTO" else "${state.kelvin}K"
        cellValues[Control.FOCUS]?.text = when (state.focusMode) {
            FocusMode.CONTINUOUS ->
                readback?.focusDiopters?.let { CamState.formatFocusMeters(it) } ?: "AF-C"
            FocusMode.TOUCH -> "TOQUE"
            FocusMode.MANUAL -> CamState.formatFocusMeters(state.focusDiopters)
        }
        cellValues[Control.EV]?.text = String.format(Locale.US, "%+.1f", state.ev)
        cellValues[Control.ZOOM]?.text =
            "${(24 * state.zoomRatio).roundToInt()} mm"
        for (control in Control.values()) {
            val valueView = cellValues[control] ?: continue
            val nameView = cellNames[control] ?: continue
            valueView.setTextColor(
                when {
                    isLocked(control) -> AMBER
                    isManual(control) -> AMBER
                    selected == control -> Color.CYAN
                    else -> Color.WHITE
                }
            )
            nameView.text = buildString {
                append(controlLabel(control))
                if (isLocked(control)) append(" · TRAV")
                else if (isManual(control)) append(" · MAN")
            }
            nameView.setTextColor(if (selected == control) Color.CYAN else MUTED)
        }
    }

    private fun updateSpace() {
        if (state.destSsd) {
            val granted = contentResolver.persistedUriPermissions.any {
                it.uri.toString() == state.treeUri && it.isWritePermission
            }
            spaceText.text = if (granted) "SSD pronto" else "SSD: escolher pasta"
            return
        }
        try {
            val free = StatFs(Environment.getExternalStorageDirectory().path)
                .availableBytes
            spaceText.text = String.format(
                Locale.US, "%.0f GB livres", free / 1_000_000_000.0
            )
        } catch (e: Exception) {
            spaceText.text = ""
        }
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    private fun showWarning(message: String) {
        warningText.text = message
        warningText.visibility = View.VISIBLE
        uiHandler.postDelayed({ warningText.visibility = View.GONE }, 4500)
    }

    // ---------------------------------------------------------------
    // Modo e gravacao
    // ---------------------------------------------------------------
    private fun cycleMode() {
        if (engine.recording) {
            showStatus("Troca de modo so com a gravacao parada.")
            return
        }
        val oldMode = state.mode
        val newIndex = (state.modeIndex + 1) % VideoModes.ALL.size
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
        refreshTop()
        refreshStrip()
        if (selected == Control.SHUTTER) showPanel()
    }

    private fun onRecTapped() {
        if (!engineOpened) {
            showStatus("A camera ainda esta abrindo.")
            return
        }
        if (engine.isTransitioning) return
        if (engine.recording) engine.stopRecording() else engine.startRecording()
    }

    private fun startTimer() {
        recordStartMs = SystemClock.elapsedRealtime()
        timerText.visibility = View.VISIBLE
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
                timerText.text = String.format(
                    Locale.US, "● REC %02d:%02d", elapsed / 60, elapsed % 60
                )
                timerRunnable = this
                uiHandler.postDelayed(this, 500)
            }
        }
        timerRunnable = runnable
        uiHandler.post(runnable)
    }

    private fun stopTimer() {
        timerRunnable?.let { uiHandler.removeCallbacks(it) }
        timerRunnable = null
        timerText.visibility = View.GONE
    }

    // ---------------------------------------------------------------
    // CameraEngine.Listener
    // ---------------------------------------------------------------
    override fun onReadback(readback: CameraEngine.Readback) {
        uiHandler.post {
            lastReadback = readback
            if (firstReadback) {
                firstReadback = false
                showStatus("Camera pronta. Toque num controle da faixa para ajustar.")
            }
            refreshStrip()
        }
    }

    override fun onRecordingChanged(recording: Boolean) {
        uiHandler.post {
            if (recording) {
                recButton.setTextColor(Color.WHITE)
                recButton.text = "■"
                showStatus(
                    if (state.destSsd) "Gravando no SSD." else "Gravando em Movies/DXCam."
                )
                startTimer()
            } else {
                recButton.setTextColor(RED)
                recButton.text = "●"
                showStatus(
                    if (state.destSsd) "Gravacao salva no SSD."
                    else "Gravacao salva em Movies/DXCam."
                )
                stopTimer()
                updateSpace()
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

    companion object {
        private val AMBER = Color.rgb(255, 179, 0)
        private val RED = Color.rgb(255, 59, 48)
        private val MUTED = Color.rgb(158, 158, 158)
    }
}
