package com.example.treedirectiondemo

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
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ARCore camera + world-anchor projection.
 *
 * Contract:
 * - Target replacement is atomic on the GL thread.
 * - GPS never repositions a locked AR anchor.
 * - screenX/screenY are null unless ARCore produced a real on-screen projection.
 * - distanceMeters is the horizontal camera -> anchor distance in AR world space.
 */
class ArCoreCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    enum class TargetState {
        NONE,
        REQUESTED,
        WAITING_FOR_STABLE_TRACKING,
        LOCKED,
        TRACKING_LOST
    }

    data class FrameState(
        val tracking: Boolean,
        val anchorReady: Boolean,
        val targetState: TargetState,
        val screenX: Float?,
        val screenY: Float?,
        val inFront: Boolean,
        val distanceMeters: Float?,
        val horizontalAngleDeg: Double?,
        val verticalAngleDeg: Double?,
        val movementSinceAnchorMeters: Float?,
        val trackingFailureReason: String,
        val stableTrackingFrames: Int,
        val cameraTrackingState: TrackingState
    )

    var onFrameState: ((FrameState) -> Unit)? = null

    private var session: Session? = null
    private var targetAnchor: Anchor? = null
    private var textureId = -1
    private var program = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var displayRotation = 0

    // GL-thread owned target request. Never mutate this directly from the Activity thread.
    private var pendingAnchor: Pair<Float, Float>? = null
    private var targetState: TargetState = TargetState.NONE
    private var consecutiveTrackingFrames = 0
    private var anchorOriginCameraTranslation: FloatArray? = null

    private var displayX: Float? = null
    private var displayY: Float? = null

    private val quadVertices = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
    private val quadBuffer = floatBuffer(quadVertices)
    private val transformedUv = FloatArray(8)
    private val transformedUvBuffer = floatBuffer(FloatArray(8))

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,8,16,0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun attachSession(arSession: Session) {
        session = arSession
        queueEvent {
            consecutiveTrackingFrames = 0
            if (textureId != -1) arSession.setCameraTextureName(textureId)
            arSession.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
        }
    }

    fun detachSession() {
        queueEvent { resetTargetOnGlThread(TargetState.NONE) }
        session = null
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
        queueEvent { session?.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight) }
    }

    /**
     * Atomically removes the previous anchor and queues a new target request.
     * This replaces the old clearTargetAnchor()+placeTargetAhead() sequence, which could race and
     * delete the new pending request before ARCore ever created it.
     */
    fun replaceTargetAhead(distanceMeters: Float, elevationOffsetMeters: Float = 0f) {
        val request = distanceMeters.coerceIn(1f, 150f) to elevationOffsetMeters.coerceIn(-20f,20f)
        queueEvent {
            targetAnchor?.detach()
            targetAnchor = null
            anchorOriginCameraTranslation = null
            resetDisplayProjection()
            pendingAnchor = request
            targetState = if (consecutiveTrackingFrames >= REQUIRED_STABLE_TRACKING_FRAMES) {
                TargetState.REQUESTED
            } else {
                TargetState.WAITING_FOR_STABLE_TRACKING
            }
        }
    }

    fun clearTargetAnchor() {
        queueEvent { resetTargetOnGlThread(TargetState.NONE) }
    }

    private fun resetTargetOnGlThread(state: TargetState) {
        targetAnchor?.detach()
        targetAnchor = null
        pendingAnchor = null
        anchorOriginCameraTranslation = null
        targetState = state
        resetDisplayProjection()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f,0f,0f,1f)
        textureId = createExternalTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        session?.setCameraTextureName(textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0,0,surfaceWidth,surfaceHeight)
        session?.setDisplayGeometry(displayRotation,surfaceWidth,surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (textureId == -1) return
        try {
            s.setCameraTextureName(textureId)
            val frame = s.update()
            drawCameraBackground(frame)

            val tracking = frame.camera.trackingState == TrackingState.TRACKING
            if (tracking) {
                consecutiveTrackingFrames = (consecutiveTrackingFrames + 1).coerceAtMost(300)
            } else {
                consecutiveTrackingFrames = 0
                resetDisplayProjection()
                if (targetAnchor != null) targetState = TargetState.TRACKING_LOST
                else if (pendingAnchor != null) targetState = TargetState.WAITING_FOR_STABLE_TRACKING
            }

            val request = pendingAnchor
            if (request != null) {
                targetState = if (consecutiveTrackingFrames >= REQUIRED_STABLE_TRACKING_FRAMES) {
                    TargetState.REQUESTED
                } else {
                    TargetState.WAITING_FOR_STABLE_TRACKING
                }
                if (consecutiveTrackingFrames >= REQUIRED_STABLE_TRACKING_FRAMES) {
                    createAnchorAhead(s, frame, request.first, request.second)
                    pendingAnchor = null
                    targetState = TargetState.LOCKED
                }
            } else if (targetAnchor != null && tracking && targetAnchor?.trackingState == TrackingState.TRACKING) {
                targetState = TargetState.LOCKED
            }

            emitFrameState(frame)
        } catch (_: Throwable) {
            // Do not manufacture tracking data if ARCore is transitioning lifecycle state.
        }
    }

    private fun createAnchorAhead(s: Session, frame: Frame, distance: Float, elevation: Float) {
        targetAnchor?.detach()
        val pose = frame.camera.pose
        val t = pose.translation
        val z = pose.zAxis
        var fx = -z[0]
        var fz = -z[2]
        val len = sqrt(fx*fx + fz*fz).coerceAtLeast(0.0001f)
        fx /= len
        fz /= len
        targetAnchor = s.createAnchor(Pose.makeTranslation(
            t[0] + fx * distance,
            t[1] + elevation,
            t[2] + fz * distance
        ))
        anchorOriginCameraTranslation = t.copyOf()
        resetDisplayProjection()
    }

    private fun emitFrameState(frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val failureReason = camera.trackingFailureReason.name
        val anchor = targetAnchor

        if (!tracking || anchor == null || anchor.trackingState != TrackingState.TRACKING) {
            post {
                onFrameState?.invoke(
                    FrameState(
                        tracking = tracking,
                        anchorReady = false,
                        targetState = targetState,
                        screenX = null,
                        screenY = null,
                        inFront = false,
                        distanceMeters = null,
                        horizontalAngleDeg = null,
                        verticalAngleDeg = null,
                        movementSinceAnchorMeters = movementSinceAnchor(camera.pose.translation),
                        trackingFailureReason = failureReason,
                        stableTrackingFrames = consecutiveTrackingFrames,
                        cameraTrackingState = camera.trackingState
                    )
                )
            }
            return
        }

        val a = anchor.pose.translation
        val ct = camera.pose.translation
        val dx = a[0]-ct[0]
        val dy = a[1]-ct[1]
        val dz = a[2]-ct[2]
        val horizontalDistance = sqrt(dx*dx + dz*dz)

        val world = floatArrayOf(a[0],a[1],a[2],1f)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val cameraSpace = FloatArray(4)
        val clip = FloatArray(4)
        camera.getViewMatrix(view,0)
        camera.getProjectionMatrix(projection,0,0.05f,250f)
        Matrix.multiplyMV(cameraSpace,0,view,0,world,0)
        Matrix.multiplyMV(clip,0,projection,0,cameraSpace,0)

        val inFront = cameraSpace[2] < -0.001f && clip[3] > 0.0001f
        val horizontal = Math.toDegrees(atan2(cameraSpace[0].toDouble(), (-cameraSpace[2]).toDouble()))
        val ground = sqrt(cameraSpace[0]*cameraSpace[0] + cameraSpace[2]*cameraSpace[2]).coerceAtLeast(0.0001f)
        val vertical = Math.toDegrees(atan2(cameraSpace[1].toDouble(), ground.toDouble()))

        var screenX: Float? = null
        var screenY: Float? = null
        if (inFront) {
            val rawX = ((clip[0]/clip[3])+1f)*0.5f
            val rawY = 1f-(((clip[1]/clip[3])+1f)*0.5f)
            if (rawX in -0.02f..1.02f && rawY in -0.02f..1.02f) {
                val stable = stabilizeProjection(rawX, rawY)
                screenX = stable.first
                screenY = stable.second
            } else {
                resetDisplayProjection()
            }
        } else {
            resetDisplayProjection()
        }

        post {
            onFrameState?.invoke(
                FrameState(
                    tracking = true,
                    anchorReady = true,
                    targetState = TargetState.LOCKED,
                    screenX = screenX,
                    screenY = screenY,
                    inFront = inFront,
                    distanceMeters = horizontalDistance,
                    horizontalAngleDeg = horizontal,
                    verticalAngleDeg = vertical,
                    movementSinceAnchorMeters = movementSinceAnchor(ct),
                    trackingFailureReason = failureReason,
                    stableTrackingFrames = consecutiveTrackingFrames,
                    cameraTrackingState = camera.trackingState
                )
            )
        }
    }

    private fun stabilizeProjection(rawX: Float, rawY: Float): Pair<Float, Float> {
        val px = displayX
        val py = displayY
        if (px == null || py == null) {
            displayX = rawX
            displayY = rawY
            return rawX to rawY
        }
        val delta = hypot((rawX-px).toDouble(), (rawY-py).toDouble()).toFloat()
        val alpha = when {
            delta < 0.0012f -> 0f
            delta > 0.045f -> 1f
            delta > 0.015f -> 0.82f
            else -> 0.58f
        }
        displayX = px + (rawX-px)*alpha
        displayY = py + (rawY-py)*alpha
        return displayX!! to displayY!!
    }

    private fun movementSinceAnchor(cameraTranslation: FloatArray): Float? {
        val origin = anchorOriginCameraTranslation ?: return null
        val dx = cameraTranslation[0]-origin[0]
        val dy = cameraTranslation[1]-origin[1]
        val dz = cameraTranslation[2]-origin[2]
        return sqrt(dx*dx + dy*dy + dz*dz)
    }

    private fun resetDisplayProjection() {
        displayX = null
        displayY = null
    }

    private fun drawCameraBackground(frame: Frame) {
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadVertices,
            Coordinates2d.TEXTURE_NORMALIZED, transformedUv
        )
        transformedUvBuffer.position(0)
        transformedUvBuffer.put(transformedUv)
        transformedUvBuffer.position(0)
        quadBuffer.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program,"a_Position")
        val uv = GLES20.glGetAttribLocation(program,"a_TexCoord")
        val texture = GLES20.glGetUniformLocation(program,"u_Texture")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,quadBuffer)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,transformedUvBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(texture,0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1,ids,0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createProgram(v:String,f:String):Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER,v)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER,f)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it,vs)
            GLES20.glAttachShader(it,fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun compileShader(type:Int,source:String):Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it,source)
        GLES20.glCompileShader(it)
    }

    private fun floatBuffer(v:FloatArray):FloatBuffer =
        ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(v)
            position(0)
        }

    companion object {
        private const val REQUIRED_STABLE_TRACKING_FRAMES = 30
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord;
            void main(){ gl_Position=a_Position; v_TexCoord=a_TexCoord; }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES u_Texture; varying vec2 v_TexCoord;
            void main(){ gl_FragColor=texture2D(u_Texture,v_TexCoord); }
        """
    }
}
