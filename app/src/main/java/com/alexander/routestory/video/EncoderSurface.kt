package com.alexander.routestory.video

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface

/**
 * EGL window surface wired to a MediaCodec surface input: instead of the per-pixel
 * RGB->YUV conversion in Kotlin (the previous encode bottleneck), each frame bitmap is
 * uploaded as a GL texture and drawn straight into the hardware encoder surface.
 * Static photo-pause frames reuse the uploaded texture with a new presentation time.
 *
 * Runs on the single export thread; EGL calls must not interleave with other GL work.
 */
class EncoderSurface(surface: Surface) {

    private val inputSurface = surface
    private lateinit var display: EGLDisplay
    private lateinit var eglSurface: EGLSurface
    private lateinit var eglContext: EGLContext
    private var program = 0
    private var aPosition = 0
    private var textureId = 0

    private val quadCoords = floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    )
    private val vertexShader = """
        attribute vec2 aPosition;
        varying vec2 vTexCoord;
        void main() {
            // Flip Y: texImage2D stores bitmap row 0 (top) at t=0, while the encoder
            // surface maps clip-space +1 to the top line of the frame.
            vTexCoord = vec2((aPosition.x + 1.0) / 2.0, (1.0 - aPosition.y) / 2.0);
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()
    private val fragmentShader = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    /** Creates the EGL context and compiles the draw program; throws on failure. */
    fun prepare() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        // EGL_RECORDABLE_ANDROID (0x3142): lets the hardware encoder read this surface.
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0) && num[0] > 0
        ) { "eglChooseConfig failed" }
        eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
        setupProgram()
    }

    private fun setupProgram() {
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertexShader))
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed" }
        GLES20.glUseProgram(program)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
        val buffer = java.nio.ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(quadCoords).position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(aPosition)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed" }
        return shader
    }

    /** Renders [bitmap] into the encoder surface at [ptsUs] (microseconds). */
    fun drawBitmap(bitmap: Bitmap, ptsUs: Long) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        drawQuad(ptsUs)
    }

    /** Re-renders the previously uploaded texture with a new presentation time. */
    fun repeatFrame(ptsUs: Long) = drawQuad(ptsUs)

    private fun drawQuad(ptsUs: Long) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsUs * 1_000L)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
    }

    fun release() {
        runCatching {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
        }
        runCatching {
            if (::eglSurface.isInitialized && eglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
            }
        }
        runCatching {
            if (::eglContext.isInitialized && eglContext !== EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, eglContext)
            }
        }
        runCatching { EGL14.eglTerminate(display) }
        runCatching { inputSurface.release() }
    }
}
