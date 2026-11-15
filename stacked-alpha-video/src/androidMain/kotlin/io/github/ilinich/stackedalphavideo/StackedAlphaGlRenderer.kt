package io.github.ilinich.stackedalphavideo

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "StackedAlphaVideo"

/**
 * Vertex shader: maps one quad over the output and computes two texture coordinates per vertex,
 * one into the top (RGB) half of the frame and one into the bottom (matte) half. The decoder's
 * transform matrix is applied afterwards, so crop and orientation stay correct.
 */
private const val VERTEX_SHADER = """
uniform mat4 surfaceTransformMatrix;
attribute vec4 positionInSurface;
attribute vec4 positionInTexture;
varying vec2 rgbTextureCoordinates;
varying vec2 alphaTextureCoordinates;
void main() {
  gl_Position = positionInSurface;
  // GL texture space has y = 0 at the bottom, so the top half of the picture is y in [0.5, 1].
  float rgbY = positionInTexture.y * 0.5 + 0.5;
  float alphaY = positionInTexture.y * 0.5;
  rgbTextureCoordinates = (surfaceTransformMatrix * vec4(positionInTexture.x, rgbY, 0.0, 1.0)).xy;
  alphaTextureCoordinates = (surfaceTransformMatrix * vec4(positionInTexture.x, alphaY, 0.0, 1.0)).xy;
}
"""

/**
 * Fragment shader: colour from the top half, alpha from the green channel of the bottom half
 * (green carries the most luma bits in YUV, so it is the cleanest grayscale after decoding).
 * Output is premultiplied, which is what the Android compositor expects from a non-opaque view.
 */
private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES videoTexture;
varying vec2 rgbTextureCoordinates;
varying vec2 alphaTextureCoordinates;
void main() {
  vec3 rgb = texture2D(videoTexture, rgbTextureCoordinates).rgb;
  float alpha = texture2D(videoTexture, alphaTextureCoordinates).g;
  gl_FragColor = vec4(rgb * alpha, alpha);
}
"""

/** Full-screen quad as a triangle strip: x, y, z, u, v per vertex. */
private val QUAD = floatArrayOf(
    -1f, -1f, 0f, 0f, 0f,
    1f, -1f, 0f, 1f, 0f,
    -1f, 1f, 0f, 0f, 1f,
    1f, 1f, 0f, 1f, 1f,
)
private const val POSITION_COMPONENTS = 3
private const val TEXTURE_COMPONENTS = 2
private const val VERTEX_STRIDE_BYTES = (POSITION_COMPONENTS + TEXTURE_COMPONENTS) * Float.SIZE_BYTES

/**
 * Owns an EGL context, the external-OES texture the decoder writes into, and the shader that
 * composites it. Every method must run on the thread behind [glHandler].
 */
internal class StackedAlphaGlRenderer(
    private val glHandler: Handler,
    private val onVideoSurfaceReady: (Surface) -> Unit,
    private val onFirstFrameDrawn: () -> Unit,
) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var positionHandle = 0
    private var textureCoordinateHandle = 0
    private var transformHandle = 0
    private var textureId = 0

    private var videoTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val transform = FloatArray(16)
    private var firstFrameDrawn = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD)
            position(0)
        }

    fun start(output: SurfaceTexture, width: Int, height: Int) {
        if (!initEgl(output)) {
            Log.e(TAG, "EGL setup failed")
            stop()
            return
        }
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            stop()
            return
        }
        positionHandle = GLES20.glGetAttribLocation(program, "positionInSurface")
        textureCoordinateHandle = GLES20.glGetAttribLocation(program, "positionInTexture")
        transformHandle = GLES20.glGetUniformLocation(program, "surfaceTransformMatrix")

        textureId = createExternalTexture()
        val texture = SurfaceTexture(textureId).apply {
            // Draw exactly once per decoded frame instead of running a render loop.
            setOnFrameAvailableListener({ drawFrame() }, glHandler)
        }
        videoTexture = texture
        val surface = Surface(texture)
        videoSurface = surface
        onVideoSurfaceReady(surface)
        resize(width = width, height = height)
    }

    fun resize(width: Int, height: Int) {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        GLES20.glViewport(0, 0, width, height)
    }

    fun stop() {
        videoSurface?.release()
        videoSurface = null
        videoTexture?.release()
        videoTexture = null
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (program != 0) GLES20.glDeleteProgram(program)
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        program = 0
        textureId = 0
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private fun drawFrame() {
        val texture = videoTexture ?: return
        if (eglSurface == EGL14.EGL_NO_SURFACE || program == 0) return
        // The texture can be abandoned between the callback being posted and running.
        val updated = runCatching {
            texture.updateTexImage()
            texture.getTransformMatrix(transform)
        }.isSuccess
        if (!updated) return

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(transformHandle, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        quad.position(0)
        GLES20.glVertexAttribPointer(
            positionHandle, POSITION_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, quad,
        )
        GLES20.glEnableVertexAttribArray(positionHandle)
        quad.position(POSITION_COMPONENTS)
        GLES20.glVertexAttribPointer(
            textureCoordinateHandle, TEXTURE_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, quad,
        )
        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, eglSurface)

        if (!firstFrameDrawn) {
            firstFrameDrawn = true
            onFirstFrameDrawn()
        }
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun initEgl(output: SurfaceTexture): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

        // An 8-bit alpha channel in the window surface is what lets the result be transparent.
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            Log.e(TAG, "No EGL config with an alpha channel")
            return false
        }
        val config = configs[0] ?: return false

        eglContext = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
    }
}

private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (vertexShader == 0 || fragmentShader == 0) return 0
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    // The program keeps the compiled code; the shader objects are no longer needed.
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteProgram(program)
        return 0
    }
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
        GLES20.glDeleteShader(shader)
        return 0
    }
    return shader
}
