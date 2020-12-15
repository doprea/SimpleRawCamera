package com.dan.simplerawcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.dan.simplerawcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.Exception
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.schedule
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        const val REQUEST_PERMISSIONS = 1

        const val HISTOGRAM_BITMAP_WIDTH = 64
        const val HISTOGRAM_BITMAP_HEIGHT = 50

        const val MANUAL_MIN_SPEED_PREVIEW = 62500000L // 1/16 sec

        const val FOCUS_REGION_SIZE_PERCENT = 5

        const val FOCUS_TYPE_CONTINOUS = 0
        const val FOCUS_TYPE_CLICK = 1
        const val FOCUS_TYPE_HYPERFOCAL = 2
        const val FOCUS_TYPE_MANUAL = 3
        const val FOCUS_TYPE_MAX = 4

        fun getBestResolution( targetWidth: Int, targetRatio: Float, sizes: Array<Size> ): Size {
            var bestSize = sizes.last()

            for (size in sizes) {
                if (size.width > targetWidth) continue
                val ratio = size.width.toFloat() / size.height
                if (abs(ratio - targetRatio) < 0.2) {
                    if (bestSize.width < size.width)
                        bestSize = size
                }
            }

            return bestSize
        }

        fun calculateExpDeviation( visibleIso: Int, visibleSpeed: Long, expectedIso: Int, expectedSpeed: Long ): Float {
            var deltaExpIso: Float = (expectedIso - visibleIso).toFloat() / expectedIso
            var deltaExpSpeed: Float = (expectedSpeed - visibleSpeed).toFloat() / expectedSpeed
            return deltaExpIso + deltaExpSpeed
        }
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private val mCameraManager: CameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val mCameraList: ArrayList<CameraHandler> by lazy { CameraHandler.getValidCameras(mCameraManager) }
    private var mCameraIndex = 0
    private lateinit var mCameraHandler: CameraHandler
    private var mCameraDevice: CameraDevice? = null
    private var mCameraCaptureSession: CameraCaptureSession? = null
    private var mCaptureRequestBuilderPreview: CaptureRequest.Builder? = null
    private var mCaptureRequestBuilderPhoto: CaptureRequest.Builder? = null
    private var mCaptureRequest: CaptureRequest? = null

    private val mImageReaderHisto = ImageReader.newInstance(100, 100, ImageFormat.YUV_420_888, 1)
    private val mImageReaderHistoListener = object: ImageReader.OnImageAvailableListener {
        private var isBusy = false

        override fun onImageAvailable(imageReader: ImageReader?) {
            if (null == imageReader) return
            val image = imageReader.acquireLatestImage() ?: return

            if (!isBusy) {
                isBusy = true
                val imageW = image.width
                val imageH = image.height

                val yPlane = image.planes[0]
                val yPlaneBuffer = yPlane.buffer
                val yBytes = ByteArray(yPlaneBuffer.capacity())
                yPlaneBuffer.get(yBytes)

                val rowStride = yPlane.rowStride

                GlobalScope.launch(Dispatchers.Main) {
                    val values = IntArray(HISTOGRAM_BITMAP_WIDTH)
                    for (line in 0 until imageH) {
                        var index = line * rowStride
                        for (column in 0 until imageW) {
                            var yValue = yBytes[index].toInt()
                            if (yValue < 0) yValue += 256
                            values[(HISTOGRAM_BITMAP_WIDTH - 1) * yValue / 255]++
                            index++
                        }
                    }

                    var maxHeight = 10
                    for (value in values)
                        maxHeight = max(maxHeight, value)
                    maxHeight++

                    val color = Color.rgb(192, 192, 192)
                    val colors = IntArray(HISTOGRAM_BITMAP_WIDTH * HISTOGRAM_BITMAP_HEIGHT)

                    for (x in values.indices) {
                        val value = values[x]
                        val fill =
                            HISTOGRAM_BITMAP_HEIGHT - 1 - (HISTOGRAM_BITMAP_HEIGHT - 1) * value / maxHeight

                        var y = 0
                        while (y < fill) {
                            colors[x + y * HISTOGRAM_BITMAP_WIDTH] = 0
                            y++
                        }
                        while (y < HISTOGRAM_BITMAP_HEIGHT) {
                            colors[x + y * HISTOGRAM_BITMAP_WIDTH] = color
                            y++
                        }
                    }

                    val bitmap = Bitmap.createBitmap(
                        colors,
                        0,
                        HISTOGRAM_BITMAP_WIDTH,
                        HISTOGRAM_BITMAP_WIDTH,
                        HISTOGRAM_BITMAP_HEIGHT,
                        Bitmap.Config.ARGB_8888
                    )

                    runOnUiThread {
                        mBinding.imgHistogram.setImageBitmap(bitmap)
                        isBusy = false
                    }
                }
            }

            image.close()
        }
    }

    private lateinit var mImageReaderJpeg: ImageReader
    private val mImageReaderJpegListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader?) {
            if (null == imageReader) return
            val image = imageReader.acquireLatestImage() ?: return
            image.close()
        }
    }

    private lateinit var mImageReaderDng: ImageReader
    private val mImageReaderDngListener = object: ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(imageReader: ImageReader?) {
            if (null == imageReader) return
            val image = imageReader.acquireLatestImage() ?: return
            image.close()
        }
    }

    private var mIsoValue = 100
    private var mIsoIsManual = false
    private var mIsoMeasuredValue = 100

    private var mSpeedValueNumerator = 10
    private var mSpeedValueDenominator = 128
    private var mSpeedIsManual = false
    private var mSpeedMeasuredValue = 1L

    private var mExposureCompensationValue = 0

    private var mFocusType = FOCUS_TYPE_CONTINOUS
    private var mFocusClick = false
    private var mFocusClickPosition = Point(0,0)

    private var mRotatedPreviewWidth = 4
    private var mRotatedPreviewHeight = 3

    private var mFirstCall = true

    private val mSurfaceHolderCallback = object: SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            selectCamera(mCameraIndex)

            if (mFirstCall) {
                mFirstCall = false
                Timer().schedule(500) {
                    runOnUiThread {
                        selectCamera(mCameraIndex)
                    }
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
        }
    }

    private val mCameraCaptureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {}

        override fun onConfigured(session: CameraCaptureSession) {
            val cameraDevice = mCameraDevice ?: return

            mCameraCaptureSession = session

            val captureRequestBuilderPreview = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilderPreview.addTarget(mBinding.surfaceView.holder.surface)
            captureRequestBuilderPreview.addTarget(mImageReaderHisto.surface)
            mCaptureRequestBuilderPreview = captureRequestBuilderPreview

            val captureRequestBuilderPhoto = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
            captureRequestBuilderPhoto.addTarget(mImageReaderDng.surface)
            captureRequestBuilderPhoto.addTarget(mImageReaderJpeg.surface)
            mCaptureRequestBuilderPhoto = captureRequestBuilderPhoto

            setupCaptureRequest()
        }
    }

    private fun getCaptureEA() : Triple<Int, Long, Float> {
        if (!mIsoIsManual && !mSpeedIsManual)
            return Triple(mIsoMeasuredValue, mSpeedMeasuredValue, 0f)

        if (mIsoIsManual && mSpeedIsManual) {
            val manualSpeed = speedToNanoseconds(mSpeedValueNumerator, mSpeedValueDenominator)
            return Triple(
                mIsoValue,
                manualSpeed,
                calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, mIsoValue, manualSpeed))
        }

        if (mIsoIsManual) {
            val isoRatio = mIsoMeasuredValue.toFloat() / mIsoValue

            var suggestedSpeed = (mSpeedMeasuredValue * isoRatio).toLong()
            if (suggestedSpeed < mCameraHandler.speedRange.lower)
                suggestedSpeed = mCameraHandler.speedRange.lower
            else if (suggestedSpeed > mCameraHandler.speedRange.upper)
                suggestedSpeed = mCameraHandler.speedRange.upper

            return Triple(
                mIsoValue,
                suggestedSpeed,
                calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, mIsoValue, suggestedSpeed))
        }

        val manualSpeed = speedToNanoseconds(mSpeedValueNumerator, mSpeedValueDenominator)
        val speedRatio = manualSpeed / mSpeedMeasuredValue

        var suggestedIso = (mIsoMeasuredValue * speedRatio).toInt()
        if (suggestedIso < mCameraHandler.isoRange.lower)
            suggestedIso = mCameraHandler.isoRange.lower
        else if (suggestedIso > mCameraHandler.isoRange.upper)
            suggestedIso = mCameraHandler.isoRange.upper

        return Triple(
            suggestedIso,
            mSpeedMeasuredValue,
            calculateExpDeviation(mIsoMeasuredValue, mSpeedMeasuredValue, suggestedIso, manualSpeed))
    }

    private val mCameraCaptureSessionCaptureCallback = object: CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            super.onCaptureCompleted(session, request, result)

            mIsoMeasuredValue = result.get(CaptureResult.SENSOR_SENSITIVITY) as Int
            mSpeedMeasuredValue = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) as Long

            val captureEA = getCaptureEA()
            mBinding.txtExpDelta.text = "%.2f".format(captureEA.third)

            if (mIsoIsManual && mSpeedIsManual) return

            if (!mIsoIsManual)
                showIso(captureEA.first)

            if (!mSpeedIsManual) {
                val speed = captureEA.second
                if (speed >= 1000000000L)
                    showSpeed((speed/100000000L).toInt(), 1)
                else
                    showSpeed(1, (1000000000L / speed).toInt())
            }
        }
    }

    private val mCameraDeviceStateCallback = object: CameraDevice.StateCallback() {
        override fun onDisconnected(p0: CameraDevice) {}

        override fun onError(p0: CameraDevice, p1: Int) {}

        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice

            val sizes = mCameraHandler.streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
            if (null == sizes || 0 == sizes.size) throw Exception("No sizes available")
            val previewSize = getBestResolution(
                mBinding.surfaceView.width,
                mCameraHandler.resolutionWidth.toFloat() / mCameraHandler.resolutionHeight,
                sizes )

            mRotatedPreviewWidth = if (mCameraHandler.areDimensionsSwapped) previewSize.height else previewSize.width
            mRotatedPreviewHeight = if (mCameraHandler.areDimensionsSwapped) previewSize.width else previewSize.height

            mBinding.surfaceView.holder.setFixedSize(mRotatedPreviewWidth, mRotatedPreviewHeight)
            cameraDevice.createCaptureSession(
                mutableListOf(
                    mBinding.surfaceView.holder.surface,
                    mImageReaderHisto.surface,
                    mImageReaderJpeg.surface,
                    mImageReaderDng.surface,
                ),
                mCameraCaptureSessionStateCallback,
                Handler { true }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!askPermissions())
            onPermissionsAllowed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    private fun showSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(requestCode, permissions, grantResults)
        }
    }

    private fun exitApp() {
        setResult(0)
        finish()
        exitProcess(0)
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(msg)
            .setIcon(android.R.drawable.stat_notify_error)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> exitApp() }
            .show()
    }

    private fun handleRequestPermissions(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for ( result in grantResults ) {
                if (result != PackageManager.PERMISSION_GRANTED ) {
                    allowedAll = false
                    break
                }
            }
        }

        if( allowedAll ) {
            onPermissionsAllowed()
        } else {
            fatalError("Permissions are mandatory !")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onPermissionsAllowed() {
        if (mCameraList.size <= 0) {
            fatalError("No valid camera found !")
            return
        }

        if (1 == mCameraList.size) {
            mBinding.txtCamera.isVisible = false
            mBinding.btnCamera.isVisible = false
        }

        mImageReaderHisto.setOnImageAvailableListener(mImageReaderHistoListener, Handler{true})

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)

        setContentView(mBinding.root)

        mBinding.surfaceView.holder.addCallback( mSurfaceHolderCallback )

        mBinding.btnCamera.setOnClickListener {
            selectCamera((mCameraIndex + 1) % mCameraList.size)
        }

        mCameraHandler = mCameraList[0]

        SeekBarDirectionTracker.track( mBinding.seekBarIso ) { delta, isFinal -> trackIso( delta, isFinal ) }
        SeekBarDirectionTracker.track( mBinding.seekBarSpeed ) { delta, isFinal -> trackSpeed( delta, isFinal ) }
        SeekBarDirectionTracker.track( mBinding.seekBarExpComponsation ) { delta, isFinal -> trackExpComponsation( delta, isFinal ) }

        mBinding.seekBarFocus.setOnSeekBarChangeListener( object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, user: Boolean) {
                setupCaptureRequest()
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
            }
        })

        mBinding.txtIso.setOnClickListener {
            mIsoIsManual = !mIsoIsManual
            updateSliders()
        }

        mBinding.txtSpeed.setOnClickListener {
            mSpeedIsManual = !mSpeedIsManual
            updateSliders()
        }

        mBinding.txtFocus.setOnClickListener {
            if (mCameraHandler.focusAllowManual) {
                mFocusType = (mFocusType + 1) % FOCUS_TYPE_MAX
                mFocusClick = false
                showFocus()
                setupCaptureRequest()
            }
        }

        mBinding.surfaceView.setOnTouchListener { view, motionEvent ->
            if (mCameraHandler.focusAllowManual && FOCUS_TYPE_CLICK == mFocusType) {
                if (MotionEvent.ACTION_DOWN == motionEvent.actionMasked) {
                    mFocusClickPosition.x = (100 * motionEvent.x / view.width).toInt()
                    mFocusClickPosition.y = (100 * motionEvent.y / view.height).toInt()
                    mFocusClick = true
                    setupCaptureRequest()
                }
            }

            false
        }
    }

    private fun trackIso( delta: Int, isFinal: Boolean) {
        if (!mIsoIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = mIsoValue

        while (counter > 0) {
            if (increase) {
                if (value >= mCameraHandler.isoRange.upper) break
                value *= 2
            } else {
                if (value <= mCameraHandler.isoRange.lower) break
                value /= 2
            }
            counter -= 1
        }

        showIso(value)

        if (isFinal) {
            mIsoValue = value
            setupCaptureRequest()
        }
    }

    private fun showIso( value: Int ) {
        mBinding.txtIso.text = "${value} ISO"
    }

    private fun trackExpComponsation( delta: Int, isFinal: Boolean) {
        if (mIsoIsManual && mSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var value = mExposureCompensationValue

        while (counter > 0) {
            if (increase) {
                if (value >= mCameraHandler.exposureCompensantionRange.upper) break
                value++
            } else {
                if (value <= mCameraHandler.exposureCompensantionRange.lower) break
                value--
            }
            counter -= 1
        }

        showExpComponsation(value)

        if (isFinal) {
            mExposureCompensationValue = value
            setupCaptureRequest()
        }
    }

    private fun showExpComponsation( value: Int ) {
        var exp = "Exp: "

        if (value >= 0) {
            exp += "+${value}"
        } else {
            exp += value.toString()
        }

        mBinding.txtExpComponsation.text = exp
    }

    private fun speedToNanoseconds( numerator: Int, denominator: Int ): Long = 100000000L * numerator / denominator

    private fun trackSpeed( delta: Int, isFinal: Boolean) {
        if (!mSpeedIsManual) return

        val increase = delta > 0
        var counter = abs(delta)
        var numerator = mSpeedValueNumerator
        var denominator = mSpeedValueDenominator

        while (counter > 0) {
            val speed = speedToNanoseconds(numerator, denominator)

            if (increase) {
                if ((2*speed) > mCameraHandler.speedRange.upper || numerator >= 40) break
                if (denominator > 1) {
                    denominator /= 2
                } else {
                    numerator += 10
                }
            } else {
                if ((speed/2) < mCameraHandler.speedRange.lower || denominator >= 32768) break
                if (numerator > 10) {
                    numerator -= 10
                } else {
                    denominator *= 2
                }
            }
            counter -= 1
        }

        showSpeed(numerator, denominator)

        if (isFinal) {
            mSpeedValueNumerator = numerator
            mSpeedValueDenominator = denominator
            setupCaptureRequest()
        }
    }

    private fun showSpeed( numerator: Int, denominator: Int ) {
        if (1 == denominator) {
            val rest = numerator % 10
            if (0 == rest)
                mBinding.txtSpeed.text = "${numerator/10}\""
            else
                mBinding.txtSpeed.text = "${numerator/10}.${rest}\""
        } else {
            val roundedDenominator =
                if (denominator >= 1000)
                    (denominator / 1000) * 1000
                else if (denominator >= 500)
                    (denominator / 100) * 100
                else if (128 == denominator)
                    125
                else if (denominator >= 30)
                    (denominator / 10) * 10
                else if (16 == denominator)
                    15
                else
                    denominator

            mBinding.txtSpeed.text = "1/${roundedDenominator}"
        }
    }

    private fun showFocus() {
        if (mCameraHandler.focusAllowManual) {
            when(mFocusType) {
                FOCUS_TYPE_CLICK -> {
                    mBinding.txtFocus.text = "Focus: Click"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }

                FOCUS_TYPE_HYPERFOCAL -> {
                    mBinding.txtFocus.text = "Focus: Hyperfocal"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }

                FOCUS_TYPE_MANUAL -> {
                    mBinding.txtFocus.text = "Focus: Manual"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.VISIBLE
                }

                else -> {
                    mBinding.txtFocus.text = "Focus: Auto"
                    mBinding.txtFocus.visibility = View.VISIBLE
                    mBinding.seekBarFocus.visibility = View.INVISIBLE
                }
            }

        } else {
            mBinding.txtFocus.visibility = View.INVISIBLE
            mBinding.seekBarFocus.visibility = View.INVISIBLE
        }
    }

    private fun updateSliders() {
        mBinding.seekBarIso.visibility = if (mIsoIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.seekBarSpeed.visibility = if (mSpeedIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.txtExpComponsation.visibility = if (!mIsoIsManual || !mSpeedIsManual) View.VISIBLE else View.INVISIBLE
        mBinding.seekBarExpComponsation.visibility = mBinding.txtExpComponsation.visibility

        if (mIsoIsManual)
            showIso(mIsoValue)

        if (mSpeedIsManual)
            showSpeed(mSpeedValueNumerator, mSpeedValueDenominator)

        showFocus()
        showExpComponsation(mExposureCompensationValue)

        setupCaptureRequest()
    }

    @SuppressLint("MissingPermission")
    private fun selectCamera(index: Int) {
        mCameraIndex = index
        mCameraHandler = mCameraList[index]

        val cameraCaptureSession = mCameraCaptureSession
        if (null != cameraCaptureSession) {
            cameraCaptureSession.close()
            mCameraCaptureSession = null
        }

        val cameraDevice = mCameraDevice
        if (null != cameraDevice) {
            cameraDevice.close()
            mCameraDevice = null
        }

        mCaptureRequest = null

        mBinding.txtCamera.text = index.toString()

        val set = ConstraintSet()
        set.clone(mBinding.layoutView)
        set.setDimensionRatio(
            mBinding.layoutWithRatio.getId(),
            "${mCameraHandler.resolutionWidth}:${mCameraHandler.resolutionHeight}"
        )
        set.applyTo(mBinding.layoutView)

        mImageReaderJpeg = ImageReader.newInstance( mCameraHandler.resolutionWidth, mCameraHandler.resolutionHeight, ImageFormat.JPEG, 1 )
        mImageReaderJpeg.setOnImageAvailableListener(mImageReaderJpegListener, Handler{true})

        mImageReaderDng = ImageReader.newInstance( mCameraHandler.resolutionWidth, mCameraHandler.resolutionHeight, ImageFormat.RAW_SENSOR, 1 )
        mImageReaderDng.setOnImageAvailableListener(mImageReaderDngListener, Handler{true})

        updateSliders()

        mCameraManager.openCamera(mCameraHandler.id, mCameraDeviceStateCallback, Handler { true } )
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun setupCaptureRequest(preview: Boolean = true) {
        val captureRequestBuilder = mCaptureRequestBuilderPreview ?: return
        val cameraCaptureSession = mCameraCaptureSession ?: return

        if (mCameraHandler.supportLensStabilisation)
            captureRequestBuilder.set( CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON )

        captureRequestBuilder.set( CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY )
        captureRequestBuilder.set( CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO )
        captureRequestBuilder.set( CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO )
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        if (!mIsoIsManual || !mSpeedIsManual) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mExposureCompensationValue * mCameraHandler.exposureCompensantionMulitplyFactor)
        } else {
            var manualSpeed = speedToNanoseconds(mSpeedValueNumerator, mSpeedValueDenominator)
            var manualISO = mIsoValue

            if (preview && manualSpeed > MANUAL_MIN_SPEED_PREVIEW) {
                while (manualSpeed > MANUAL_MIN_SPEED_PREVIEW) {
                    if ((2*manualISO) > mCameraHandler.isoRange.upper)
                        break

                    manualISO *= 2
                    manualSpeed /= 2
                }

                manualSpeed = min(MANUAL_MIN_SPEED_PREVIEW, manualSpeed)
            }

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualSpeed)
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualISO)
        }

        if (mCameraHandler.focusAllowManual) {
            when(mFocusType) {
                FOCUS_TYPE_HYPERFOCAL -> {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mCameraHandler.focusHyperfocalDistance)
                    mBinding.frameView.hideFocusZone()
                }

                FOCUS_TYPE_CLICK -> {
                    if (mFocusClick) {
                        mFocusClick = false
                        val delta = mCameraHandler.resolutionWidth * FOCUS_REGION_SIZE_PERCENT / 100
                        val x = mCameraHandler.resolutionWidth * mFocusClickPosition.x / 100
                        val y = mCameraHandler.resolutionWidth * mFocusClickPosition.y / 100
                        val x1 = max( 0, x - delta )
                        val y1 = max( 0, y - delta )
                        val x2 = min( mCameraHandler.resolutionWidth, x + delta )
                        val y2 = min( mCameraHandler.resolutionHeight, y + delta )

                        if (y2 > y1 && x2 > x1) {
                            val rectangle = MeteringRectangle( x1, y1, x2 - x1, y2 - y1, MeteringRectangle.METERING_WEIGHT_MAX)
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rectangle))
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        }

                        mBinding.frameView.showFocusZone( Rect(
                            mFocusClickPosition.x - FOCUS_REGION_SIZE_PERCENT,
                            mFocusClickPosition.y - FOCUS_REGION_SIZE_PERCENT,
                            mFocusClickPosition.x + FOCUS_REGION_SIZE_PERCENT,
                            mFocusClickPosition.y + FOCUS_REGION_SIZE_PERCENT
                        ) )
                    }
                }

                FOCUS_TYPE_MANUAL -> {
                    val distance = mCameraHandler.focusRange.lower +
                            (100 - mBinding.seekBarFocus.progress) * (mCameraHandler.focusRange.upper - mCameraHandler.focusRange.lower) / 100
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    mBinding.frameView.hideFocusZone()
                }

                else -> {
                    val rectangle = MeteringRectangle( 0, 0, mCameraHandler.resolutionWidth, mCameraHandler.resolutionHeight, MeteringRectangle.METERING_WEIGHT_MAX)
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rectangle))
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    mBinding.frameView.hideFocusZone()
                }
            }
        }

        val captureRequest = captureRequestBuilder.build()
        mCaptureRequest = captureRequest

        cameraCaptureSession.setRepeatingRequest(
            captureRequest,
            mCameraCaptureSessionCaptureCallback,
            Handler { true }
        )
    }
}