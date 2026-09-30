package com.omarea.benchmark.workload

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.widget.FrameLayout
import com.omarea.benchmark.BenchScenario
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU: fullscreen quad with a heavy fragment shader, continuous rendering.
 * Work counter = rendered frames.
 */
class GpuWorkload : BenchmarkWorkload {
    override val scenario = BenchScenario.GPU

    private var view: GLSurfaceView? = null
    private val frames = AtomicLong(0)

    override fun attach(activity: Activity, container: FrameLayout) {
        container.removeAllViews()
        val glView = GLSurfaceView(activity).apply {
            setEGLContextClientVersion(2)
            setRenderer(Renderer(frames))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        view = glView
        container.addView(
            glView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    override fun start() {
        frames.set(0)
        view?.onResume()
    }

    override fun stop() {
        view?.onPause()
    }

    override fun workUnits(): Long = frames.get()

    private class Renderer(private val frames: AtomicLong) : GLSurfaceView.Renderer {
        private var program = 0
        private var quad: FloatBuffer = ByteBuffer
            .allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD)
                position(0)
            }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(VERTEX, FRAGMENT)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val position = GLES20.glGetAttribLocation(program, "aPos")
            val time = GLES20.glGetUniformLocation(program, "uTime")
            GLES20.glUseProgram(program)
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glUniform1f(time, System.nanoTime().toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            frames.incrementAndGet()
        }

        private fun buildProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            return program
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private companion object {
            val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

            const val VERTEX = """
                attribute vec2 aPos;
                void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
            """

            // Fill-rate heavy: nested loop with trig per pixel.
            const val FRAGMENT = """
                precision mediump float;
                uniform float uTime;
                void main() {
                    vec2 uv = gl_FragCoord.xy / vec2(1080.0, 2160.0);
                    float acc = 0.0;
                    float t = uTime * 0.001;
                    for (int i = 0; i < 24; i++) {
                        float fi = float(i);
                        acc += sin(uv.x * fi + t) * cos(uv.y * fi - t);
                        acc += sqrt(abs(sin((uv.x + uv.y) * fi * 0.5)));
                    }
                    acc = fract(acc * 0.1);
                    gl_FragColor = vec4(acc, acc * 0.6, acc * 0.3, 1.0);
                }
            """
        }
    }
}
