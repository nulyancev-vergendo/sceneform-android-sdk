package com.google.ar.sceneform.ux;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.exceptions.UnavailableException;

import java.util.EnumSet;
import java.util.List;

public class SharedCameraFragment extends ArFragment {
    public interface OnCameraClosedListener {
        void onCameraClosed();
    }
    private final String TAG = SharedCameraFragment.class.getSimpleName();
    private String cameraId;
    private CameraManager cameraManager;
    private CameraDevice sharedCameraDevice;
    private SharedCamera sharedCamera;
    private Session arSession;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;
    private Size gpuTextureSize;
    private CaptureRequest.Builder captureRequestBuilder;
    private final ConditionVariable safeToDestroyFragment = new ConditionVariable();
    private OnCameraClosedListener onCameraClosedListener;

    public SharedCameraFragment() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initSharedCamera();
    }

    private void initSharedCamera() {
        try {
            getArSceneView().setSharedCameraMode(true);
            cameraManager = (CameraManager) requireActivity().getSystemService(Context.CAMERA_SERVICE);
            arSession = createSharedSession();
            sharedCamera = arSession.getSharedCamera();
            gpuTextureSize = arSession
                    .getSupportedCameraConfigs(new CameraConfigFilter(arSession)).get(0)
                    .getTextureSize();
            cameraId = arSession.getCameraConfig().getCameraId();
            setUpCameraStateCallback();
        } catch (UnavailableException e) {
            e.printStackTrace();
            handleSessionException(e);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    private void setUpCameraStateCallback() throws CameraAccessException {
        CameraDevice.StateCallback wrappedCallback = sharedCamera.createARDeviceStateCallback(
                new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice cameraDevice) {
                        Log.d(TAG, "onOpened: camera opened");
                        sharedCameraDevice = cameraDevice;
                        getArSceneView().setupSession(arSession);
                        try {
                            createCameraPreviewSession();
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                        Log.d(TAG, "onDisconnected: camera disconnected");
                    }

                    @Override
                    public void onError(@NonNull CameraDevice cameraDevice, int i) {
                        Log.d(TAG, "onError: camera state error. error id: " + i);
                    }

                    @Override
                    public void onClosed(@NonNull CameraDevice camera) {
                        safeToDestroyFragment.open();
                    }
                }, cameraHandler
        );
        cameraManager.openCamera(cameraId, wrappedCallback, cameraHandler);
    }

    private void createCameraPreviewSession() throws CameraAccessException {
        captureRequestBuilder = sharedCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        List<Surface> targets = sharedCamera.getArCoreSurfaces();
        for (Surface surface : targets) {
            captureRequestBuilder.addTarget(surface);
        }
        CameraCaptureSession.StateCallback wrappedCallback = sharedCamera.createARSessionStateCallback(
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        Log.d(TAG, "onCaptureSessionConfigured: camera capture session configured");
                        try {
                            setRepeatingCaptureRequest(cameraCaptureSession);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onActive(@NonNull CameraCaptureSession session) {
                        Log.d(TAG, "onCaptureSessionActive");
                        getArSceneView().resumeAsync(ContextCompat.getMainExecutor(requireContext()));
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        Log.d(TAG, "onCaptureSessionConfigureFailed: camera capture session configure failed");
                    }
                }, cameraHandler
        );
        sharedCameraDevice.createCaptureSession(targets, wrappedCallback, cameraHandler);
    }

    private void setRepeatingCaptureRequest(CameraCaptureSession cameraCaptureSession) throws CameraAccessException {
        cameraCaptureSession.setRepeatingRequest(
                captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        getArSceneView().setShouldDrawFrame(true);
//                        Log.d(TAG, "onCaptureCompleted");
                    }
                }, cameraHandler);
    }

    public void captureTexture(ImageProcessor.OnProceedListener onCaptureComplete) {
        if (sharedCamera == null) {
            return;
        }
        Surface surface = sharedCamera.getArCoreSurfaces().get(0);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new ImageProcessor(surface, gpuTextureSize, handler, onCaptureComplete));
    }

    private Session createSharedSession() throws UnavailableException {
        Session session = new Session(requireContext(), EnumSet.of(Session.Feature.SHARED_CAMERA));
        Config config = session.getConfig();
        config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
        config.setFocusMode(Config.FocusMode.AUTO);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        session.configure(config);
        return session;
    }

    @Override
    public void onDestroy() {
        sharedCameraDevice.close();
        waitUntilCameraClosing();
        cameraThread.quitSafely();
        if (onCameraClosedListener != null)
            onCameraClosedListener.onCameraClosed();
        super.onDestroy();
    }

    private void waitUntilCameraClosing() {
        safeToDestroyFragment.close();
        safeToDestroyFragment.block();
    }

    public void setOnCameraClosedListener(OnCameraClosedListener onClosed) {
        onCameraClosedListener = onClosed;
    }
}