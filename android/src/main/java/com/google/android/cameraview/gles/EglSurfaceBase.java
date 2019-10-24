/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.cameraview.gles;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EglSurfaceBase {
    protected static final String TAG = GlUtil.TAG;

    // EglCore object we're associated with.  It may be associated with multiple surfaces.
    protected EglCore mEglCore;

    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    private int mWidth = -1;
    private int mHeight = -1;
    static final int DEFAULT_THREAD_POOL_SIZE = 60;
    private int bufferSize = -1;
    private ByteBuffer buf;

    private ExecutorService currentExecutor = Executors.newWorkStealingPool(DEFAULT_THREAD_POOL_SIZE);
//    private ExecutorService currentExecutor = Executors.newSingleThreadExecutor();

    protected EglSurfaceBase(EglCore eglCore) {
        mEglCore = eglCore;
    }

    /**
     * Creates a window surface.
     * <p>
     * @param surface May be a Surface or SurfaceTexture.
     */
    public void createWindowSurface(Object surface) {
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("surface already created");
        }
        mEGLSurface = mEglCore.createWindowSurface(surface);

        // Don't cache width/height here, because the size of the underlying surface can change
        // out from under us (see e.g. HardwareScalerActivity).
        int targetWidth = mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
        int targetHeight = mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
        bufferSize = targetWidth * targetWidth * 4;
        buf = ByteBuffer.allocateDirect(bufferSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Creates an off-screen surface.
     */
    public void createOffscreenSurface(int width, int height) {
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("surface already created");
        }
        mEGLSurface = mEglCore.createOffscreenSurface(width, height);
        mWidth = width;
        mHeight = height;

        checkBufferSize();
    }

    private void checkBufferSize()
    {
        int targetSize = getWidth() * getHeight() * 4;
        if (targetSize != bufferSize)
        {
            bufferSize = targetSize;
            buf = ByteBuffer.allocateDirect(bufferSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /**
     * Returns the surface's width, in pixels.
     * <p>
     * If this is called on a window surface, and the underlying surface is in the process
     * of changing size, we may not see the new size right away (e.g. in the "surfaceChanged"
     * callback).  The size should match after the next buffer swap.
     */
    public int getWidth() {
        if (mWidth < 0) {
            return mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
        } else {
            return mWidth;
        }
    }

    /**
     * Returns the surface's height, in pixels.
     */
    public int getHeight() {
        if (mHeight < 0) {
            return mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
        } else {
            return mHeight;
        }
    }

    /**
     * Release the EGL surface.
     */
    public void releaseEglSurface() {
        mEglCore.releaseSurface(mEGLSurface);
        mEGLSurface = EGL14.EGL_NO_SURFACE;
        mWidth = mHeight = -1;
    }

    /**
     * Makes our EGL context and surface current.
     */
    public void makeCurrent() {
        mEglCore.makeCurrent(mEGLSurface);
    }

    /**
     * Makes our EGL context and surface current for drawing, using the supplied surface
     * for reading.
     */
    public void makeCurrentReadFrom(EglSurfaceBase readSurface) {
        mEglCore.makeCurrent(mEGLSurface, readSurface.mEGLSurface);
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    public boolean swapBuffers() {
        boolean result = mEglCore.swapBuffers(mEGLSurface);
        if (!result) {
            Log.d(TAG, "WARNING: swapBuffers() failed");
        }

        return result;
    }



    /**
     * Sends the presentation time stamp to EGL.
     *
     * @param nsecs Timestamp, in nanoseconds.
     */
    public void setPresentationTime(long nsecs) {
        mEglCore.setPresentationTime(mEGLSurface, nsecs);
    }



    public void base64Image(final Base64Callback callback)
    {
        if (!mEglCore.isCurrent(mEGLSurface)) {
            throw new RuntimeException("Expected EGL context/surface is not current");
        }
        checkBufferSize();
        GLES20.glReadPixels(0, 0, getWidth(), getHeight(),
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        GlUtil.checkGlError("glReadPixels");
        buf.rewind();
//        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
//        BufferedOutputStream bos = null;
        try {

            final Bitmap bmp_initial = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            bmp_initial.copyPixelsFromBuffer(buf);
            buf.rewind();
            buf.clear();
//            buf = null;
            currentExecutor.execute(new Runnable() {
                @Override
                public void run() {

                    final HashMap<String, String> dictionary = new HashMap<>();
                    ByteArrayOutputStream ba = new ByteArrayOutputStream();
                    ByteArrayOutputStream bat = new ByteArrayOutputStream();

                    Matrix matrix = new Matrix();
                    matrix.postRotate(180);
                    matrix.postScale(-1, 1, bmp_initial.getWidth()/2, bmp_initial.getHeight()/2);

                    Bitmap bmp = Bitmap.createBitmap(bmp_initial, 0, 0, bmp_initial.getWidth(), bmp_initial.getHeight(), matrix, true);
                    bmp_initial.recycle();
                    boolean _result = bmp.compress(Bitmap.CompressFormat.JPEG, 90, ba);
                    Bitmap thumbnail = Bitmap.createScaledBitmap(bmp, 200, 200, false);
                    bmp.recycle();
                    boolean _resultT = thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, bat);
                    thumbnail.recycle();
                    String base64Data = null;
                    String base64DataThumbnail = null;
                    base64Data = Base64.encodeToString(ba.toByteArray(), Base64.NO_WRAP);
                    base64DataThumbnail = Base64.encodeToString(bat.toByteArray(), Base64.NO_WRAP);
                    dictionary.put("frameData", base64Data);
                    dictionary.put("thumbnail", base64DataThumbnail);
                        try {
                            ba.flush();
                            bat.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }


                    ba.reset();
                    bat.reset();


                    // Get a handler that can be used to post to the main thread
                    Handler uiHandler = new Handler(Looper.getMainLooper());
                    Runnable myRunnable = new Runnable() {
                        @Override
                        public void run() {
                            callback.onResponse(dictionary);
                        } // This is your code
                    };
                    uiHandler.post(myRunnable);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();

        }
//        return dictionary;
    }

    private static byte[] readFileToByteArray(File file){
        FileInputStream fis = null;
        // Creating a byte array using the length of the file
        // file.length returns long which is cast to int
        byte[] bArray = new byte[(int) file.length()];
        try{
            fis = new FileInputStream(file);
            fis.read(bArray);
            fis.close();

        }catch(IOException ioExp){
            ioExp.printStackTrace();
        }
        return bArray;
    }

    /**
     * Saves the EGL surface to a file.
     * <p>
     * Expects that this object's EGL surface is current.
     */
    public void saveFrame(File file) throws IOException {
        if (!mEglCore.isCurrent(mEGLSurface)) {
            throw new RuntimeException("Expected EGL context/surface is not current");
        }

        // glReadPixels fills in a "direct" ByteBuffer with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  While the Bitmap
        // constructor that takes an int[] wants little-endian ARGB (blue/red swapped), the
        // Bitmap "copy pixels" method wants the same format GL provides.
        //
        // Ideally we'd have some way to re-use the ByteBuffer, especially if we're calling
        // here often.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside down relative to what appears on screen if the
        // typical GL conventions are used.

        String filename = file.toString();

        int width = getWidth();
        int height = getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        GLES20.glReadPixels(0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        GlUtil.checkGlError("glReadPixels");
        buf.rewind();

        BufferedOutputStream bos = null;
        try {
            bos = new BufferedOutputStream(new FileOutputStream(filename));
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
            bmp.recycle();
        } finally {
            if (bos != null) bos.close();
        }
        Log.d(TAG, "Saved " + width + "x" + height + " frame as '" + filename + "'");
    }
}
