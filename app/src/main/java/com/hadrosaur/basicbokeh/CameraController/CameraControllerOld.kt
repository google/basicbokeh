/*
package com.hadrosaur.basicbokeh

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.annotation.NonNull
import com.hadrosaur.basicbokeh.MainActivity.Companion.LOG_TAG
import com.hadrosaur.basicbokeh.MainActivity.Companion.SAVE_FILE
import java.util.*
import android.hardware.camera2.CameraAccessException
import com.hadrosaur.basicbokeh.MainActivity.Companion.cameraParams
import android.hardware.camera2.CaptureResult
import com.hadrosaur.basicbokeh.MainActivity.Companion.Logd


public val STATE_UNINITIALIZED = -1
public val STATE_PREVIEW = 0
public val STATE_WAITING_LOCK = 1
public val STATE_WAITING_PRECAPTURE = 2
public val STATE_WAITING_NON_PRECAPTURE = 3
public val STATE_PICTURE_TAKEN = 4

private fun createCameraPreviewSession(activity: Activity, camera: CameraDevice, params: CameraParams) {
    try {
        val texture = params.previewTextureView?.surfaceTexture

        if (null == texture)
            return

        val surface = Surface(texture)

        params.previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        params.previewBuilder?.addTarget(surface)

        // Here, we create a CameraCaptureSession for camera preview.
        camera.createCaptureSession(Arrays.asList(surface, params.imageReader?.getSurface()),
                object : CameraCaptureSession.StateCallback() {
                    override fun onReady(session: CameraCaptureSession?) {
                        //This may be the initial start or we may have cleared an in-progress capture and the pipeline is now clear
                        Logd( "In onReady: CaptureSession Ready/All requests are done!")

                        super.onReady(session)
                    }

                    override fun onConfigured(@NonNull cameraCaptureSession: CameraCaptureSession) {
                        // When the session is ready, we start displaying the preview.
                        try {
                            // Auto focus should be continuous for camera preview.
                            params.previewBuilder?.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                            // Flash is automatically enabled when necessary.
                            setAutoFlash(activity, camera, params.previewBuilder)

                            // Let's add a sepia effect for fun
                            if (params.hasSepia)
                                params.previewBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA)
                            // Or mono if we don't have sepia
                            else if (params.hasMono)
                                params.previewBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO)

                            if (params.hasSepia)
                                Logd( "WE HAVE SEPIA, setting it!!")

                            if (params.hasManualControl && params.minFocusDistance != MainActivity.FIXED_FOCUS_DISTANCE) {
                                //Request largest aperture
                                params.previewBuilder?.set(CaptureRequest.LENS_APERTURE, params.largestAperture)

                                //Focus on the closest point
                                params.previewBuilder?.set(CaptureRequest.LENS_FOCUS_DISTANCE, params.minFocusDistance)
                            } else {
                                //Do digital zoom
                            }

                            // Finally, we start displaying the camera preview.
                            cameraCaptureSession.setRepeatingRequest(params.previewBuilder?.build(),
                                    params.captureCallback, params.backgroundHandler)

                            params.captureSession = cameraCaptureSession

                        } catch (e: CameraAccessException) {
                            Logd( "Create Capture Session error: " + params.id)
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(
                            @NonNull cameraCaptureSession: CameraCaptureSession) {
                        Logd( "Camera preview initialization failed.")
                        Logd( "Trying again")
                        createCameraPreviewSession(activity, camera, params)
                        //TODO: fix endless loop potential.

                    }
                }, null
        )
    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }

}

class CaptureSessionCallback(val activity: Activity, internal var params: CameraParams) : CameraCaptureSession.CaptureCallback() {

    private fun process(result: CaptureResult) {
        when (params.state) {
            STATE_PREVIEW -> {
            }// We have nothing to do when the camera preview is working normally.
            STATE_WAITING_LOCK -> {
                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                Logd( "CaptureSessionCallback : STATE_WAITING_LOCK, afstate == " + afState)

                if (afState == null || afState == 0) {
                    params.state = STATE_PICTURE_TAKEN
                    captureStillPicture(activity, params)
                } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState
                        || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        params.state = STATE_PICTURE_TAKEN
                        captureStillPicture(activity, params)
                    } else {
                        runPrecaptureSequence(activity, params)
                    }
                }
            }
            STATE_WAITING_PRECAPTURE -> {
                Logd( "CaptureSessionCallback : STATE_WAITING_PRECAPTURE.")
                // CONTROL_AE_STATE can be null on some devices
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null ||
                        aeState == 0 ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                    params.state = STATE_WAITING_NON_PRECAPTURE
                }
            }
            STATE_WAITING_NON_PRECAPTURE -> {
                Logd( "CaptureSessionCallback : STATE_NON_PRECAPTURE.")
                // CONTROL_AE_STATE can be null on some devices
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == 0 || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    params.state = STATE_PICTURE_TAKEN
                    captureStillPicture(activity, params)
                }
            }
        }
    }


    override fun onCaptureProgressed(@NonNull session: CameraCaptureSession,
                                     @NonNull request: CaptureRequest,
                                     @NonNull partialResult: CaptureResult) {
        process(partialResult)
    }

    override fun onCaptureCompleted(@NonNull session: CameraCaptureSession,
                                    @NonNull request: CaptureRequest,
                                    @NonNull result: TotalCaptureResult) {
        process(result)
    }
}

class CameraStateCallback(internal var params: CameraParams, internal var activity: Activity) : CameraDevice.StateCallback() {

    override fun onOpened(@NonNull cameraDevice: CameraDevice) {
        Logd( "In CameraStateCallback onOpened: " + cameraDevice.id)
        params.isOpen = true
        createCameraPreviewSession(activity, cameraDevice, params)
//        shutterControl(params.shutter, true)
    }

    override fun onDisconnected(@NonNull cameraDevice: CameraDevice) {
        Logd( "In CameraStateCallback onDisconnected: " + params.id)
        closeCamera(params, activity)
        cameraDevice.close()
    }

    override fun onError(@NonNull cameraDevice: CameraDevice, error: Int) {
        Logd( "In CameraStateCallback onError: " + cameraDevice.id + " and error: " + error)

        if (CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE == error) {
            //Let's try to close an open camera and re-open this one
            Logd( "In CameraStateCallback too many cameras open, closing one...")
            closeACamera(activity)
            cameraDevice.close()
            openCamera(params, activity)
        } else if (CameraDevice.StateCallback.ERROR_CAMERA_DEVICE == error){
            Logd( "Fatal camera error, close and try to re-initialize...")
            closeCamera(params, activity)
            cameraDevice.close()
            openCamera(params, activity)
        } else {
            closeCamera(params, activity)
            cameraDevice.close()
        }
    }
}

fun openCamera(params: CameraParams?, activity: Activity) {
    if (null == params)
        return

    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
        Logd( "openCamera: " + params.id)

        manager.openCamera(params.id, params.cameraCallback, params.backgroundHandler)
    } catch (e: CameraAccessException) {
        Logd( "openCamera CameraAccessException: " + params.id)
        e.printStackTrace()
    } catch (e: SecurityException) {
        Logd( "openCamera SecurityException: " + params.id)
        e.printStackTrace()
    }

}


//Close the first open camera we find
fun closeACamera(activity: Activity) {
    var closedACamera = false
    Logd( "In closeACamera, looking for open camera.")
    for (tempCameraParams: CameraParams in cameraParams.values) {
        if (tempCameraParams.isOpen) {
            Logd( "In closeACamera, found open camera, closing: " + tempCameraParams.id)
            closedACamera = true
            closeCamera(tempCameraParams, activity)
            break
        }
    }

    // We couldn't find an open camera, let's close everything
    if (!closedACamera) {
        closeAllCameras(activity)
    }
}

fun closeAllCameras(activity: Activity) {
    Logd( "Closing all cameras.")
    for (tempCameraParams: CameraParams in cameraParams.values) {
        closeCamera(tempCameraParams, activity)
    }
}

fun closeCamera(params: CameraParams?, activity: Activity) {
    if (null == params)
        return

    params.captureSession?.let {
        Logd( "closeCamera: " + params.id)
        params.isOpen = false
        it.close()
        it.getDevice().close()
    }
}


class TextureListener(internal var params: CameraParams, internal val activity: Activity): TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {
//        Logd( "In surfaceTextureUpdated. Id: " + params.id)
//        openCamera(params, activity)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        Logd( "In surfaceTextureAvailable. Id: " + params.id)
        openCamera(params, activity)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        Logd( "In surfaceTextureSizeChanged. Id: " + params.id)
        val info = params.characteristics
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        //       val largestSize = Collections.max(Arrays.asList(*info?.getOutputSizes(ImageFormat.JPEG)),
        //               CompareSizesByArea())
//        val optimalSize = chooseBigEnoughSize(
//                info?.getOutputSizes(SurfaceHolder::class.java), width, height)
//        params.previewTextureView.setFixedSize(optimalSize.width,
//                optimalSize.height)
//        configureTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) : Boolean {
        Logd( "In surfaceTextureDestroyed. Id: " + params.id)
        closeCamera(params, activity)
        return true
    }
}

fun takePicture(activity: Activity, params: CameraParams) {
    lockFocus(activity, params)
}

fun lockFocus(activity: Activity, params: CameraParams) {
    try {
        Logd( "In lockFocus.")

        val camera = params.captureSession?.getDevice()
        if (null != camera) {
            params.previewBuilder?.addTarget(params.imageReader?.surface)
            setAutoFlash(activity, camera, params.previewBuilder)

            params.previewBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);

            // Tell #captureCallback to wait for the lock.
            params.state = STATE_WAITING_LOCK
            params.captureSession?.capture(params.previewBuilder?.build(), params.captureCallback,
                    params.backgroundHandler)
        }

    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }
}

fun runPrecaptureSequence(activity: Activity, params: CameraParams) {
    try {
        val camera = params.captureSession?.getDevice()
        if (null != camera) {
            setAutoFlash(activity, camera, params.previewBuilder)
            params.previewBuilder?.addTarget(params.imageReader?.getSurface())

            params.previewBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            params.state = STATE_WAITING_PRECAPTURE
            params.captureSession?.capture(params.previewBuilder?.build(), params.captureCallback,
                    params.backgroundHandler)
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }

}

fun captureStillPicture(activity: Activity, params: CameraParams) {
    try {
        Logd( "In captureStillPicture")

        val camera = params.captureSession?.getDevice()
        if (null != camera) {
            params.captureSession?.stopRepeating();
//            params.captureSession?.abortCaptures();

            params.captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            setAutoFlash(activity, camera, params.captureBuilder)
            params.captureBuilder?.addTarget(params.imageReader?.getSurface())
            params.captureBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

            //Let's add a sepia effect for fun
            if (params.hasSepia)
                params.captureBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA)
            // Or mono if we don't have sepia
            else if (params.hasMono)
                params.captureBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO)

            // Request face detection
            params.captureBuilder?.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL)

            // Orientation
            val rotation = activity.getWindowManager().getDefaultDisplay().getRotation()
            val capturedImageRotation = getOrientation(params, rotation)
            params.captureBuilder?.set(CaptureRequest.JPEG_ORIENTATION, capturedImageRotation)

            val CaptureCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(@NonNull session: CameraCaptureSession,
                                                @NonNull request: CaptureRequest,
                                                @NonNull result: TotalCaptureResult) {

                    val mode = result.get(CaptureResult.STATISTICS_FACE_DETECT_MODE)
                    val faces = result.get(CaptureResult.STATISTICS_FACES)
                    if (faces != null && mode != null) {
                        Logd( "faces : " + faces.size + " , mode : " + mode)
                        for (face in faces) {
                            val rect = face.bounds
                            Logd( "Bounds: bottom: " + rect.bottom + " left: " + rect.left + " right: " + rect.right + " top: " + rect.top)
                        }

                        if (faces.size > 0) {
                            params.hasFace = true
                            params.faceBounds = faces.first().bounds
//TODO                            expandBounds(faceBounds) //Include the whole head, not just the face
                            params.faceBounds.top -= 400
                            params.faceBounds.bottom += 400
                            params.faceBounds.right += 400
                            params.faceBounds.left -= 400
                        }
                    }

//                    Toast.makeText(activity, "Saved: " + SAVE_FILE, Toast.LENGTH_LONG)
//                    Logd( "Results: " + session.toString() + " || " + request.toString())
                    //                    Logd( "Saved photo to file: " + file.toString() + result.partialResults.toString())


                    unlockFocus(activity, params);
                }
            }

            params.captureSession?.capture(params.captureBuilder?.build(), CaptureCallback,
                    params.backgroundHandler)
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }
}

fun unlockFocus(activity: Activity, params: CameraParams) {
    try {
        val camera = params.captureSession?.device

        if (null != camera) {
            // Reset auto-focus
            params.previewBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            params.captureSession?.capture(params.previewBuilder?.build(), params.captureCallback,
                    params.backgroundHandler)

            // Restart the camera preview
            createCameraPreviewSession(activity, camera, params)
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }

}


*/