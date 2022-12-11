package com.obstino.facecontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.Collections;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CameraCapture {
    private boolean debugOutput = false;
    private String TAG = "FaceControl.CameraCapture";
    private Context context;

    private CyclicBarrier cyclicBarrier;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private HandlerThread mBackgroundThread; // used for some background processing
    private Handler mBackgroundHandler; // -||-
    private CameraCaptureSession cameraCaptureSession;
    private Size finalSize;
    private Range<Integer> finalRange;
    private int sensorOrientationDegrees;
    private ImageReader imageReader;

    CameraCapture(Context context) {
        this.context = context;
        cyclicBarrier = new CyclicBarrier(2);
    }

    public boolean openCamera(ImageReader.OnImageAvailableListener onImageAvailableListener) {  // opens camera with default (kindof hardcoded) settings
        String[] idList;
        int frontId;
        CameraCharacteristics characteristics;

        //int MAX_WIDTH = 1088, MAX_HEIGHT = 1088;
        //int MAX_WIDTH = 960, MAX_HEIGHT = 720;
        int MAX_WIDTH = 640, MAX_HEIGHT = 480;

        //Context context = getApplicationContext();

        Log.i(TAG, "Inside Thr_Camera");
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            idList = cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            e.printStackTrace();
            Log.i(TAG, "getCameraIdList exception");
            return false;
        }

        frontId = -1;
        for (int k = 0; k < idList.length; k++) {
            Log.i(TAG, "Camera id found " + idList[k]);
            try {
                characteristics = cameraManager.getCameraCharacteristics(idList[k]);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                continue;
            }
            Integer lens = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lens == null)
                continue;
            if (lens == CameraMetadata.LENS_FACING_FRONT) {
                frontId = k;
                break;
            }
        }

        if (frontId == -1)
            return false;

        try {
            characteristics = cameraManager.getCameraCharacteristics(idList[frontId]);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }

        sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Log.i(TAG, "Sensor orientation = " + sensorOrientationDegrees);

        StreamConfigurationMap map;
        map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null)
            return false;

        Size[] outputSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (outputSizes == null)
            return false;

        Size desiredSize = new Size(MAX_WIDTH, MAX_HEIGHT);

        // Find the minimum size
        finalSize = outputSizes[0];
        for (int k = 0; k < outputSizes.length; k++) {
            Log.i(TAG, "Output size found " + outputSizes[k].toString());
            if (sizeToPixels(outputSizes[k]) < sizeToPixels(finalSize))
                finalSize = outputSizes[k];
        }
        // Find the largest size that is <= desiredSize (at least pixel-wise)
        for (int k = 0; k < outputSizes.length; k++) {
            if (sizeToPixels(outputSizes[k]) <= sizeToPixels(desiredSize) &&
                    sizeToPixels(outputSizes[k]) >= sizeToPixels(finalSize)) {
                finalSize = outputSizes[k];
            }
        }
        Log.i(TAG, "Final size is " + finalSize.toString());

        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if(ranges == null)
            return false;

        finalRange = ranges[0];  // start with first range
        for(int k = 1; k < ranges.length; k++) {    // find largest range
            Log.i(TAG, "Range found " + ranges[k].getLower() + " - " + ranges[k].getUpper());
            if((ranges[k].getLower() + ranges[k].getUpper()) > (finalRange.getLower() + finalRange.getUpper()))
                finalRange = ranges[k];
        }

        // start camera background thread; todo: make sure we don't ever run two such threads & that we close this thread at end maybe
        mBackgroundThread = new HandlerThread("CameraThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return false;

        cyclicBarrier.reset();
        try {
            cameraManager.openCamera(idList[frontId], stateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }

        try {
            cyclicBarrier.await(2L, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | InterruptedException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
            Log.i(TAG, "openCamera timeout");
            return false;
        }

        // Wait for cameraDevice to become set
        if(cameraDevice == null) {
            Log.i(TAG, "cameraDevice = null");
            return false;
        }

        Log.i(TAG, "cameraDevice opened");

        imageReader = ImageReader.newInstance(
                finalSize.getWidth(), finalSize.getHeight(),
                ImageFormat.JPEG,
                2); // I have no idea what maxImages=2 means LoL
        imageReader.setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler);

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }
        captureRequestBuilder.addTarget(imageReader.getSurface());

        cyclicBarrier.reset();
        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    cameraCaptureSessionCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }

        try {
            cyclicBarrier.await(2L, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | InterruptedException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
            Log.i(TAG, "createCaptureSession timeout");
            return false;
        }

        if(cameraCaptureSession == null)
            return false;

        Log.i(TAG, "cameraCaptureSession available");

        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, finalRange);

        return true;
    }

    public void startRepeatCapture() {
        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        try {
            cyclicBarrier.reset();
            cameraCaptureSession.stopRepeating();

            try {
                cyclicBarrier.await(2L, TimeUnit.SECONDS);
            } catch (BrokenBarrierException | InterruptedException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                e.printStackTrace();
                Log.i(TAG, "cameraCaptureSession.stopRepeating() timeout");
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        cameraCaptureSession.close();
        imageReader.close();
        cameraDevice.close();   // DIDNT PUT THIS BEFORE (!)

        mBackgroundThread.quitSafely();
    }

    public int getSensorOrientationDegrees() {
        return sensorOrientationDegrees;
    }

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                cyclicBarrier.await();
            } catch (BrokenBarrierException | InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
        }
    };

    private CameraCaptureSession.StateCallback cameraCaptureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            cameraCaptureSession = session;
            try {
                cyclicBarrier.await();
            } catch (BrokenBarrierException | InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
            super.onReady(session);
            try {
                cyclicBarrier.await(100, TimeUnit.MILLISECONDS);
            } catch (BrokenBarrierException | InterruptedException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                e.printStackTrace();
                Log.i(TAG, "cameraCaptureSessionCallback onReady timeout");
            }
        }
    };

    private CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            if(debugOutput)
                Log.i(TAG, "onCaptureStarted");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if(debugOutput)
                Log.i(TAG, "onCaptureCompleted");
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log.i(TAG, "onCaptureFailed");
        }
    };

    private int sizeToPixels(Size size) {
        return (size.getWidth() * size.getHeight());
    }
}
