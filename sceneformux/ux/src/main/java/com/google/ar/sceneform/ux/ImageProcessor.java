package com.google.ar.sceneform.ux;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.PixelCopy;
import android.view.Surface;

import java.io.ByteArrayOutputStream;

public class ImageProcessor implements Runnable {
    private final String TAG = ImageProcessor.class.getSimpleName();
    public interface OnProceedListener {
        void onProcessed(byte[] bytes);
    }

    private final Surface surface;
    private final Size size;
    private final Handler handler;
    private final OnProceedListener onProceedListener;

    public ImageProcessor(Surface surface, Size size, Handler handler, OnProceedListener listener) {
        this.surface = surface;
        this.size = size;
        this.handler = handler;
        this.onProceedListener = listener;
    }

    @Override
    public void run() {
        if (surface == null) return;
        Bitmap surfaceBitmap = Bitmap.createBitmap(size.getHeight(), size.getWidth(), Bitmap.Config.ARGB_8888);
        PixelCopy.OnPixelCopyFinishedListener listener = resultId -> {
            if (resultId == PixelCopy.SUCCESS) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                // to unify with camera2 picture rotation
                Bitmap rotatedBitmap = rotateBitmap(surfaceBitmap, -90);
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos);
                byte[] byteArray = bos.toByteArray();
                onProceedListener.onProcessed(byteArray);
            } else {
                Log.e(TAG, "captureTexture(): Cannot save surface. Code = " + resultId);
            }
        };
        PixelCopy.request(surface, surfaceBitmap, listener, handler);
    }

    private Bitmap rotateBitmap(Bitmap target, Integer degree) {
        Matrix rotateMatrix = new Matrix();
        rotateMatrix.postRotate(degree);
        return Bitmap.createBitmap(target, 0, 0, target.getWidth(), target.getHeight(), rotateMatrix, true);
    }
}