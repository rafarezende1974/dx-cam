package br.com.dxlabs.dxcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * DX Cam v1 - Etapa 5: previa por OpenGL ES 2.0 com LUT .cube aplicada
 * SO na imagem que o operador ve. A gravacao nao passa por aqui: a
 * sessao da camera alimenta a superficie do MediaRecorder direto, entao
 * o arquivo sai limpo (sem LUT), como definido no plano.
 *
 * O enquadramento replica a receita da previa comum (Camera2Basic):
 * a matriz de transformacao da SurfaceTexture orienta a imagem e um
 * zoom uniforme em torno do centro faz o preenchimento (crop).
 */
class LutPreviewView(context: Context) : GLSurfaceView(context) {

    private val renderer = LutRenderer(this)

    /** Chamado (na thread de GL) quando a superficie da camera existe. */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    private var lastAtlas: ByteBuffer? = null
    private var lastLutSize: Int = 0

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.onTextureReady = { surfaceTexture ->
            surfaceTexture.setDefaultBufferSize(
                renderer.bufferWidth, renderer.bufferHeight
            )
            // Contexto GL novo (volta do fundo, por exemplo): a textura
            // da LUT precisa subir de novo.
            val atlas = lastAtlas
            if (atlas != null && lastLutSize >= 2) {
                atlas.position(0)
                renderer.pendingLut = atlas
                renderer.pendingLutSize = lastLutSize
            }
            onSurfaceReady?.invoke(Surface(surfaceTexture))
        }
    }

    fun setBufferSize(width: Int, height: Int) {
        renderer.bufferWidth = width
        renderer.bufferHeight = height
    }

    fun setLut(atlasRgba: ByteBuffer, size: Int) {
        lastAtlas = atlasRgba
        lastLutSize = size
        atlasRgba.position(0)
        renderer.pendingLut = atlasRgba
        renderer.pendingLutSize = size
        requestRender()
    }

    fun setIntensity(intensity: Float) {
        renderer.intensity = intensity.coerceIn(0f, 1f)
        requestRender()
    }

    fun clearLut() {
        lastAtlas = null
        lastLutSize = 0
        renderer.pendingLut = null
        renderer.lutLoaded = false
        requestRender()
    }
}

private class LutRenderer(private val view: LutPreviewView) :
    GLSurfaceView.Renderer {

    var onTextureReady: ((SurfaceTexture) -> Unit)? = null

    @Volatile
    var pendingLut: ByteBuffer? = null

    @Volatile
    var pendingLutSize: Int = 0

    @Volatile
    var lutLoaded: Boolean = false

    @Volatile
    var intensity: Float = 1f

    @Volatile
    var bufferWidth: Int = 1920

    @Volatile
    var bufferHeight: Int = 1080

    private var surfaceTexture: SurfaceTexture? = null
    private var camTextureId: Int = 0
    private var lutTextureId: Int = 0
    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var texMatrixHandle: Int = 0
    private var zoomHandle: Int = 0
    private var camHandle: Int = 0
    private var lutHandle: Int = 0
    private var lutSizeHandle: Int = 0
    private var intensityHandle: Int = 0
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1

    private val texMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    private val texBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_TEXCOORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_TEXCOORDS)
            position(0)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        zoomHandle = GLES20.glGetUniformLocation(program, "uZoom")
        camHandle = GLES20.glGetUniformLocation(program, "uCam")
        lutHandle = GLES20.glGetUniformLocation(program, "uLut")
        lutSizeHandle = GLES20.glGetUniformLocation(program, "uLutSize")
        intensityHandle = GLES20.glGetUniformLocation(program, "uIntensity")

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        camTextureId = textures[0]
        lutTextureId = textures[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val texture = SurfaceTexture(camTextureId)
        texture.setOnFrameAvailableListener { view.requestRender() }
        surfaceTexture = texture
        onTextureReady?.invoke(texture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        uploadPendingLut()
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Mesmo zoom de preenchimento da previa comum (Camera2Basic):
        // max(altura/alturaBuffer, largura/larguraBuffer) com as
        // dimensoes trocadas, como na matriz da TextureView.
        val zoom = maxOf(
            viewHeight.toFloat() / bufferHeight.toFloat(),
            viewWidth.toFloat() / bufferWidth.toFloat()
        )
        GLES20.glUniform1f(zoomHandle, zoom)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)
        GLES20.glUniform1f(intensityHandle, if (lutLoaded) intensity else 0f)
        GLES20.glUniform1f(
            lutSizeHandle, if (lutLoaded) pendingLutSize.toFloat() else 32f
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTextureId)
        GLES20.glUniform1i(camHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        GLES20.glUniform1i(lutHandle, 1)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(
            texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun uploadPendingLut() {
        val atlas = pendingLut ?: return
        val size = pendingLutSize
        if (size < 2) return
        pendingLut = null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            size * size, size, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, atlas
        )
        lutLoaded = true
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        )
        private val QUAD_TEXCOORDS = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uZoom;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 mapped = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vTexCoord = (mapped - 0.5) / uZoom + 0.5;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uCam;
            uniform sampler2D uLut;
            uniform float uLutSize;
            uniform float uIntensity;
            vec3 applyLut(vec3 color) {
                float size = uLutSize;
                float blue = color.b * (size - 1.0);
                float slice0 = floor(blue);
                float slice1 = min(slice0 + 1.0, size - 1.0);
                float mixSlice = blue - slice0;
                vec2 uv0 = vec2(
                    (slice0 * size + color.r * (size - 1.0) + 0.5) / (size * size),
                    (color.g * (size - 1.0) + 0.5) / size
                );
                vec2 uv1 = vec2(
                    (slice1 * size + color.r * (size - 1.0) + 0.5) / (size * size),
                    (color.g * (size - 1.0) + 0.5) / size
                );
                vec3 sample0 = texture2D(uLut, uv0).rgb;
                vec3 sample1 = texture2D(uLut, uv1).rgb;
                return mix(sample0, sample1, mixSlice);
            }
            void main() {
                vec4 color = texture2D(uCam, vTexCoord);
                vec3 graded = applyLut(color.rgb);
                gl_FragColor = vec4(mix(color.rgb, graded, uIntensity), color.a);
            }
        """
    }
}
