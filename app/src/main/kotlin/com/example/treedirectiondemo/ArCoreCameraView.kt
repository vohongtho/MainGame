package com.example.treedirectiondemo

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * Minimal ARCore camera surface used by Tree Navigator.
 *
 * ARCore owns the camera and visual-inertial world tracking. A target tree is represented by a
 * real ARCore Anchor, so GPS updates never move the visual marker while AR tracking is healthy.
 */
class ArCoreCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    data class FrameState(
        val tracking: Boolean,
        val anchorReady: Boolean,
        val screenX: Float?,
        val screenY: Float?,
        val inFront: Boolean,
        val distanceMeters: Float?,
        val cameraTrackingState: TrackingState
    )

    var onFrameState: ((FrameState) -> Unit)? = null

    private var session: Session? = null
    private var targetAnchor: Anchor? = null
    private var latestFrame: Frame? = null
    private var textureId = -1
    private var program = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var displayRotation = 0
    private var pendingAnchorDistanceMeters: Float? = null

    private val quadVertices = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )
    private val quadBuffer: FloatBuffer = floatBuffer(quadVertices)
    private val transformedUv = FloatArray(8)
    private val transformedUvBuffer: FloatBuffer = floatBuffer(FloatArray(8))

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun attachSession(arSession: Session) {
        session = arSession
        queueEvent {
            if (textureId != -1) {
                arSession.setCameraTextureName(textureId)
                arSession.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
            }
        }
    }

    fun detachSession() {
        queueEvent {
            targetAnchor?.detach()
            targetAnchor = null
            latestFrame = null
        }
        session = null
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
        queueEvent { session?.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight) }
    }

    /** Places the anchor on the horizontal plane in front of the current AR camera. */
    fun placeTargetAhead(distanceMeters: Float) {
        pendingAnchorDistanceMeters = distanceMeters
    }

    fun clearTargetAnchor() {
        queueEvent {
            targetAnchor?.detach()
            targetAnchor = null
            pendingAnchorDistanceMeters = null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        textureId = createExternalTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        session?.setCameraTextureName(textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        session?.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return
        if (textureId == -1) return

        try {
            arSession.setCameraTextureName(textureId)
            val frame = arSession.update()
            latestFrame = frame
            drawCameraBackground(frame)

            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                pendingAnchorDistanceMeters?.let { distance ->
                    createAnchorAhead(arSession, frame, distance)
                    pendingAnchorDistanceMeters = null
                }
            }

            emitFrameState(frame)
        } catch (_: Throwable) {
            // Session lifecycle can transition while GLSurfaceView is drawing. The Activity owns
            // user-visible error handling; skipping one frame is safer than crashing the render loop.
        }
    }

    private fun createAnchorAhead(arSession: Session, frame: Frame, distance: Float) {
        targetAnchor?.detach()
        val pose = frame.camera.pose
        val translation = pose.translation
        val zAxis = FloatArray(3)
        pose.getZAxis(zAxis, 0)

        // ARCore camera looks along -Z. Remove vertical pitch so the synthetic tree is placed on
        // the horizontal direction the user is facing, rather than above/below ground.
        var fx = -zAxis[0]
        var fz = -zAxis[2]
        val len = sqrt(fx * fx + fz * fz).coerceAtLeast(0.0001f)
        fx /= len
        fz /= len

        val targetPose = Pose.makeTranslation(
            translation[0] + fx * distance,
            translation[1],
            translation[2] + fz * distance
        )
        targetAnchor = arSession.createAnchor(targetPose)
    }

    private fun emitFrameState(frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val anchor = targetAnchor
        if (!tracking || anchor == null || anchor.trackingState != TrackingState.TRACKING) {
            post {
                onFrameState?.invoke(
                    FrameState(
                        tracking = tracking,
                        anchorReady = anchor != null,
                        screenX = null,
                        screenY = null,
                        inFront = false,
                        distanceMeters = null,
                        cameraTrackingState = camera.trackingState
                    )
                )
            }
            return
        }

        val anchorT = anchor.pose.translation
        val cameraT = camera.pose.translation
        val dx = anchorT[0] - cameraT[0]
        val dy = anchorT[1] - cameraT[1]
        val dz = anchorT[2] - cameraT[2]
        val distance = sqrt(dx * dx + dy * dy + dz * dz)

        val world = floatArrayOf(anchorT[0], anchorT[1], anchorT[2], 1f)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val cameraSpace = FloatArray(4)
        val clip = FloatArray(4)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 250f)
        Matrix.multiplyMV(cameraSpace, 0, view, 0, world, 0)
        Matrix.multiplyMV(clip, 0, projection, 0, cameraSpace, 0)

        val inFront = clip[3] > 0.0001f
        val x = if (inFront) ((clip[0] / clip[3]) + 1f) * 0.5f else null
        val y = if (inFront) 1f - (((clip[1] / clip[3]) + 1f) * 0.5f) else null

        post {
            onFrameState?.invoke(
                FrameState(
                    tracking = true,
                    anchorReady = true,
                    screenX = x,
                    screenY = y,
                    inFront = inFront,
                    distanceMeters = distance,
                    cameraTrackingState = camera.trackingState
                )
            )
        }
    }

    private fun drawCameraBackground(frame: Frame) {
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadVertices,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedUv
        )
        transformedUvBuffer.position(0)
        transformedUvBuffer.put(transformedUv)
        transformedUvBuffer.position(0)
        quadBuffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "a_Position")
        val uv = GLES20.glGetAttribLocation(program, "a_TexCoord")
        val texture = GLES20.glGetUniformLocation(program, "u_Texture")

        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, transformedUvBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fragmentShader)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}
