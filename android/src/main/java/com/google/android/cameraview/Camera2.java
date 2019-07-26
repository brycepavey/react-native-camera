/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.cameraview;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.util.CircularArray;
import android.util.Base64;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.Toast;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.google.android.cameraview.gles.CameraUtils;
import com.google.android.cameraview.gles.CircularEncoder;
import com.google.android.cameraview.gles.EglCore;
import com.google.android.cameraview.gles.FullFrameRect;
import com.google.android.cameraview.gles.Texture2dProgram;
import com.google.android.cameraview.gles.WindowSurface;
import com.xuggle.ferry.IBuffer;
import com.xuggle.ferry.RefCounted;
import com.xuggle.mediatool.IMediaWriter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IRational;
import com.xuggle.xuggler.IVideoPicture;

//import org.jcodec.api.SequenceEncoder;
import org.jcodec.api.android.AndroidSequenceEncoder;

import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Rational;
import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Native;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.security.spec.ECField;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;

import static com.xuggle.ferry.IBuffer.make;

@SuppressWarnings("MissingPermission")
@TargetApi(21)
class CameraXX extends CameraViewImpl {

    private static final String TAG = "Camera2";

    private static final SparseIntArray INTERNAL_FACINGS = new SparseIntArray();

    static {
        INTERNAL_FACINGS.put(Constants.FACING_BACK, CameraCharacteristics.LENS_FACING_BACK);
        INTERNAL_FACINGS.put(Constants.FACING_FRONT, CameraCharacteristics.LENS_FACING_FRONT);
    }

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    private final CameraManager mCameraManager;
    private boolean requestingCamera = false;
    private CameraBufferArray<HashMap<String, Object>> mFramesArray = new CameraBufferArray<>(1000);


    private static final int VIDEO_WIDTH = 720;  // dimensions for 720p video
    private static final int VIDEO_HEIGHT = 1280;
    private static final int DESIRED_PREVIEW_FPS = 30;

    private EglCore mEglCore;
    private WindowSurface mDisplaySurface;
    private SurfaceTexture mCameraTexture;  // receives the output from the camera preview
    private FullFrameRect mFullFrameBlit;
    private final float[] mTmpMatrix = new float[16];
    private int mTextureId;
    private int mFrameNum;

    private Camera mCamera;
    private int mCameraPreviewThousandFps;

    private File mOutputFile;
    private CircularEncoder mCircEncoder;
    private WindowSurface mEncoderSurface;
    private boolean mFileSaveInProgress;

    private MainHandler mHandler;
    private SurfaceTexture mPreviewTexture;
    private float mSecondsOfVideo;

//    private final CameraDevice.StateCallback mCameraDeviceCallback
//            = new CameraDevice.StateCallback() {
//
//        @Override
//        public void onOpened(@NonNull CameraDevice camera) {
//            mCamera = camera;
//            mCallback.onCameraOpened();
//            startCaptureSession();
//            requestingCamera = false;
//        }
//
//        @Override
//        public void onClosed(@NonNull CameraDevice camera) {
//            mCallback.onCameraClosed();
//            requestingCamera = false;
//        }
//
//        @Override
//        public void onDisconnected(@NonNull CameraDevice camera) {
//            mCamera = null;
//            requestingCamera = false;
//        }
//
//        @Override
//        public void onError(@NonNull CameraDevice camera, int error) {
//            Log.e(TAG, "onError: " + camera.getId() + " (" + error + ")");
//            mCamera = null;
//            requestingCamera = false;
//        }
//
//    };

    private SurfaceTexture.OnFrameAvailableListener frameAvailableListener = new SurfaceTexture.OnFrameAvailableListener()
    {
        @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            Log.d(TAG, "frame available");
            surfaceTexture.getTimestamp();
            mHandler.sendEmptyMessage(MainHandler.MSG_FRAME_AVAILABLE);
        }
    };

    private SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback()
    {
        @Override   // SurfaceHolder.Callback
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated holder=" + holder);

            // Set up everything that requires an EGL context.
            //
            // We had to wait until we had a surface because you can't make an EGL context current
            // without one, and creating a temporary 1x1 pbuffer is a waste of time.
            //
            // The display surface that we use for the SurfaceView, and the encoder surface we
            // use for video, use the same EGL context.
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            mDisplaySurface = new WindowSurface(mEglCore, holder.getSurface(), false);
            mDisplaySurface.makeCurrent();

            mFullFrameBlit = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
            mTextureId = mFullFrameBlit.createTextureObject();
            mCameraTexture = new SurfaceTexture(mTextureId);
            mCameraTexture.setOnFrameAvailableListener(frameAvailableListener);

            startPreview();
        }

        @Override   // SurfaceHolder.Callback
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                    " holder=" + holder);
        }

        @Override   // SurfaceHolder.Callback
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "surfaceDestroyed holder=" + holder);
        }
    };

    /**
     * Custom message handler for main UI thread.
     * <p>
     * Used to handle camera preview "frame available" notifications, and implement the
     * blinking "recording" text.  Receives callback messages from the encoder thread.
     */
    private static class MainHandler extends Handler{
        public static final int MSG_BLINK_TEXT = 0;
        public static final int MSG_FRAME_AVAILABLE = 1;
        public static final int MSG_FILE_SAVE_COMPLETE = 2;
        public static final int MSG_BUFFER_STATUS = 3;

        public CircularEncoder.Callback circularEncoderCallback = new CircularEncoder.Callback(){

            // CircularEncoder.Callback, called on encoder thread
            @Override
            public void fileSaveComplete(int status) {
                sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
            }

            // CircularEncoder.Callback, called on encoder thread
            @Override
            public void bufferStatus(long totalTimeMsec) {
                sendMessage(obtainMessage(MSG_BUFFER_STATUS,
                        (int) (totalTimeMsec >> 32), (int) totalTimeMsec));
            }

            @Override
            public void clearingBuffer(long timeCleared) {

            }


        };

        private WeakReference<CameraXX> mWeakActivity;

        public MainHandler(CameraXX activity) {
            mWeakActivity = new WeakReference<CameraXX>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            CameraXX activity = mWeakActivity.get();
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_BLINK_TEXT: {
//                    TextView tv = (TextView) activity.findViewById(R.id.recording_text);
//
//                    // Attempting to make it blink by using setEnabled() doesn't work --
//                    // it just changes the color.  We want to change the visibility.
//                    int visibility = tv.getVisibility();
//                    if (visibility == View.VISIBLE) {
//                        visibility = View.INVISIBLE;
//                    } else {
//                        visibility = View.VISIBLE;
//                    }
//                    tv.setVisibility(visibility);
//
//                    int delay = (visibility == View.VISIBLE) ? 1000 : 200;
                    sendEmptyMessageDelayed(MSG_BLINK_TEXT, 1000);
                    break;
                }
                case MSG_FRAME_AVAILABLE: {
                    activity.drawFrame();
                    break;
                }
                case MSG_FILE_SAVE_COMPLETE: {
                    activity.fileSaveComplete(msg.arg1);
                    break;
                }
                case MSG_BUFFER_STATUS: {
                    long duration = (((long) msg.arg1) << 32) |
                            (((long) msg.arg2) & 0xffffffffL);
                    activity.updateBufferStatus(duration);
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }

    private void AddFrame(HashMap frameMap)
    {
        if (mFramesArray.size() >= 1000)
        {
            mFramesArray.removeFromStart(1001- mFramesArray.size());
        }
        mFramesArray.addLast(frameMap);
    }

//    private final CameraCaptureSession.StateCallback mSessionCallback
//            = new CameraCaptureSession.StateCallback() {
//
//        @Override
//        public void onConfigured(@NonNull CameraCaptureSession session) {
//            if (mCamera == null) {
//                return;
//            }
//            mCaptureSession = session;
//            mInitialCropRegion = mPreviewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION);
//
////            updateAutoFocus();
////            updateFlash();
////            updateFocusDepth();
////            updateWhiteBalance();
////            updateZoom();
//
////            try {
////                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
////                        mCaptureCallback, null);
////            } catch (CameraAccessException e) {
////                Log.e(TAG, "Failed to start camera preview because it couldn't access camera", e);
////            } catch (IllegalStateException e) {
////                Log.e(TAG, "Failed to start camera preview.", e);
////            }
//        }
//
//        @Override
//        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
//            Log.e(TAG, "Failed to configure capture session.");
//        }
//
//        @Override
//        public void onClosed(@NonNull CameraCaptureSession session) {
//            if (mCaptureSession != null && mCaptureSession.equals(session)) {
//                mCaptureSession = null;
//            }
//        }
//
//    };

//    PictureCaptureCallback mCaptureCallback = new PictureCaptureCallback() {
//
//        @Override
//        public void onPrecaptureRequired() {
//            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
//                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
//            setState(STATE_PRECAPTURE);
//            try {
//                mCaptureSession.capture(mPreviewRequestBuilder.build(), this, null);
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
//                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
//            } catch (CameraAccessException e) {
//                Log.e(TAG, "Failed to run precapture sequence.", e);
//            }
//        }
//
//        @Override
//        public void onReady() {
//            captureStillPicture();
//        }
//
//    };

//    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
//            = new ImageReader.OnImageAvailableListener() {
//
//        @Override
//        public void onImageAvailable(ImageReader reader) {
//            try (Image image = reader.acquireNextImage()) {
//                Image.Plane[] planes = image.getPlanes();
//                if (planes.length > 0) {
//                    ByteBuffer buffer = planes[0].getBuffer();
//                    byte[] data = new byte[buffer.remaining()];
//                    buffer.get(data);
//                    if (image.getFormat() == ImageFormat.JPEG) {
//                        // @TODO: implement deviceOrientation
//                        mCallback.onPictureTaken(data, 0);
//                    } else {
//                        mCallback.onFramePreview(data, image.getWidth(), image.getHeight(), mDisplayOrientation);
//                    }
//                    image.close();
//                }
//            }
//        }
//
//    };
//
//    private final ImageReader.OnImageAvailableListener mOnFrameAvailableListener
//            = new ImageReader.OnImageAvailableListener() {
//
//        @Override
//        public void onImageAvailable(ImageReader reader) {
//
////            if(!mIsRecording)
////            {
////                return;
////            }
//
//            try (Image image = reader.acquireNextImage()) {
//                Image.Plane[] planes = image.getPlanes();
//                if (planes.length > 0) {
//                    ByteBuffer buffer = planes[0].getBuffer();
//                    byte[] data = new byte[buffer.remaining()];
//                    buffer.get(data);
//                    String base64Data = Base64.encodeToString(data, Base64.NO_WRAP);
//                    HashMap<String, Object> frameData = new HashMap<String, Object>();
//                    frameData.put("frameData", base64Data);
//                    long timestamp = image.getTimestamp();
//                    frameData.put("milliseconds", timestamp/1000000);
//                    frameData.put("thumbnail", base64Data);
//                    if (mIsRecording)
//                    {
//                        AddFrame(frameData);
//                    }
//
//                    image.close();
//                }
//            }
//        }
//
//    };


    private String mCameraId;

    private CameraCharacteristics mCameraCharacteristics;

//    CameraDevice mCamera;
    CameraCaptureSession mCaptureSession;

    CaptureRequest.Builder mPreviewRequestBuilder;

    Set<String> mAvailableCameras = new HashSet<>();

//    private ImageReader mStillImageReader;
//
//    private ImageReader mScanImageReader;

    private ImageReader mFrameReader;

    private int mImageFormat;

    private MediaRecorder mMediaRecorder;

    private String mVideoPath;

    private boolean mIsRecording;

    private final SizeMap mPreviewSizes = new SizeMap();

    private final SizeMap mPictureSizes = new SizeMap();

    private Size mPictureSize;

    private int mFacing;

    private Context mCurrentContext;

    private AspectRatio mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;

    private AspectRatio mInitialRatio;

    private boolean mAutoFocus;

    private int mFlash;

    private int mDisplayOrientation;

    private int mDeviceOrientation;

    private float mFocusDepth;

    private float mZoom;

    private int mWhiteBalance;

    private boolean mIsScanning;

    private Surface mPreviewSurface;

    private Rect mInitialCropRegion;

    /******************************************************************
     * Draws a frame onto the SurfaceView and the encoder surface.
     * <p>
     * This will be called whenever we get a new preview frame from the camera.  This runs
     * on the UI thread, which ordinarily isn't a great idea -- you really want heavy work
     * to be on a different thread -- but we're really just throwing a few things at the GPU.
     * The upside is that we don't have to worry about managing state changes between threads.
     * <p>
     * If there was a pending frame available notification when we shut down, we might get
     * here after onPause().
     *******************************************************************/
    private void drawFrame() {
        //Log.d(TAG, "drawFrame");
        if (mEglCore == null) {
            Log.d(TAG, "Skipping drawFrame after shutdown");
            return;
        }

        // Latch the next frame from the camera.
        mDisplaySurface.makeCurrent();
        mCameraTexture.updateTexImage();
        mCameraTexture.getTransformMatrix(mTmpMatrix);

        // Fill the SurfaceView with it.
        View sv = mPreview.getView();
        int viewWidth = sv.getWidth();
        int viewHeight = sv.getHeight();
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
        mDisplaySurface.swapBuffers();

        // Send it to the video encoder.
        if (!mFileSaveInProgress) {
            mEncoderSurface.makeCurrent();
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
            mCircEncoder.frameAvailableSoon();

            long timestamp = mCameraTexture.getTimestamp();
            mEncoderSurface.setPresentationTime(timestamp);
            mEncoderSurface.swapBuffers();
            HashMap base64Image = mEncoderSurface.base64Image();
            if (base64Image !=  null)
            {
                HashMap<String, Object> frameData = new HashMap<String, Object>();
                frameData.putAll(base64Image);
                frameData.put("milliseconds", timestamp/1000000);

            }
        }

        mFrameNum++;
    }

    /**
     * The file save has completed.  We can resume recording.
     */
    private void fileSaveComplete(int status) {
        Log.d(TAG, "fileSaveComplete " + status);
        if (!mFileSaveInProgress) {
            throw new RuntimeException("WEIRD: got fileSaveCmplete when not in progress");
        }
        mFileSaveInProgress = false;
    }

    private void updateBufferStatus(long durationUsec) {
        mSecondsOfVideo = durationUsec / 1000000.0f;
    }

    private void startPreview() {
        if (mCamera != null) {
//            Log.d(TAG, "starting camera preview");
//            try {
//
//                mCamera.setPreviewTexture(mCameraTexture);
//            } catch (IOException ioe) {
//                throw new RuntimeException(ioe);
//            }
            try {
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
            } catch (IOException e) {
                e.printStackTrace();
            }
            mCamera.startPreview();
        }

        // TODO: adjust bit rate based on frame rate?
        // TODO: adjust video width/height based on what we're getting from the camera preview?
        //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
        try {
            mCircEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 6000000,
                    mCameraPreviewThousandFps / 1000, 60, mHandler.circularEncoderCallback);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mEncoderSurface = new WindowSurface(mEglCore, mCircEncoder.getInputSurface(), true);
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewFps to the expected frame rate (which might actually be variable).
     */
    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

                Camera.Parameters mCameraParameters = mCamera.getParameters();
                // Supported preview sizes
                mPreviewSizes.clear();
                for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                    mPreviewSizes.add(new Size(size.width, size.height));
                }
                // Supported picture sizes;
                mPictureSizes.clear();
                for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                    mPictureSizes.add(new Size(size.width, size.height));
                }
                // AspectRatio
                if (mAspectRatio == null) {
                    mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;
                }


        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        mCamera.setParameters(parms);

        Camera.Size cameraPreviewSize = parms.getPreviewSize();
        String previewFacts = cameraPreviewSize.width + "x" + cameraPreviewSize.height +
                " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps";
        Log.i(TAG, "Camera config: " + previewFacts);

//        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.continuousCapture_afl);
//
//        Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
//
//        if(display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
//            layout.setAspectRatio((double) cameraPreviewSize.height / cameraPreviewSize.width);
//        } else if(display.getRotation() == Surface.ROTATION_270) {
//            layout.setAspectRatio((double) cameraPreviewSize.height / cameraPreviewSize.width);
//            mCamera.setDisplayOrientation(180);
//        } else {
//            // Set the preview aspect ratio.
//            layout.setAspectRatio((double) cameraPreviewSize.width / cameraPreviewSize.height);
//        }

    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    CameraXX(Callback callback, SurfaceViewPreview preview, Context context) {
        super(callback, preview);

        mCurrentContext = context;
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
//        mCameraManager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
//            @Override
//            public void onCameraAvailable(@NonNull String cameraId) {
//                super.onCameraAvailable(cameraId);
//                mAvailableCameras.add(cameraId);
//            }
//
//            @Override
//            public void onCameraUnavailable(@NonNull String cameraId) {
//                super.onCameraUnavailable(cameraId);
//                mAvailableCameras.remove(cameraId);
//            }
//        }, null);
//        mImageFormat = mIsScanning ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;



        SurfaceHolder sh = preview.getSurfaceHolder();
        sh.addCallback(this.surfaceHolderCallback);

        mHandler = new MainHandler(this);
//        mHandler.sendEmptyMessageDelayed(MainHandler.MSG_BLINK_TEXT, 1500);
        String filePath = mCurrentContext.getFilesDir().getPath().toString() + "/temp.mp4";
        mOutputFile = new File(filePath);
        mSecondsOfVideo = 0.0f;

        openCamera(preview.getWidth(),preview.getHeight(),30);
//        mPreview.setCallback(new PreviewImpl.Callback() {
//            @Override
//            public void onSurfaceChanged() {
//                startCaptureSession();
//            }
//
//            @Override
//            public void onSurfaceDestroyed() {
//                stop();
//            }
//        });
    }

    @Override
    boolean start() {
        if (!chooseCameraIdByFacing()) {
            mAspectRatio = mInitialRatio;
            return false;
        }
        collectCameraInfo();
        setAspectRatio(mInitialRatio);
        mInitialRatio = null;
//        prepareStillImageReader();
//        prepareScanImageReader();

        if (mCamera != null || requestingCamera)
        {
            return true;
        }
//        prepareFrameImageReader();
//        startOpeningCamera();
        openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS);
        return true;
    }

    @Override
    void stop() {

        releaseCamera();

        if (mCircEncoder != null) {
            mCircEncoder.shutdown();
            mCircEncoder = null;
        }
        if (mCameraTexture != null) {
            mCameraTexture.release();
            mCameraTexture = null;
        }
        if (mDisplaySurface != null) {
            mDisplaySurface.release();
            mDisplaySurface = null;
        }
        if (mFullFrameBlit != null) {
            mFullFrameBlit.release(false);
            mFullFrameBlit = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }

//        if (mCaptureSession != null) {
//            mCaptureSession.close();
//            mCaptureSession = null;
//        }
//        if (mCamera != null) {
//            mCamera.close();
//            mCamera = null;
//        }
//
//        if (mFrameReader != null){
//            mFrameReader.close();
//            mFrameReader = null;
//        }
//
//        if (mMediaRecorder != null) {
//            mMediaRecorder.stop();
//            mMediaRecorder.reset();
//            mMediaRecorder.release();
//            mMediaRecorder = null;
//
//            if (mIsRecording) {
//                // @TODO: implement videoOrientation and deviceOrientation calculation
//                mCallback.onVideoRecorded(mVideoPath, 0, 0);
//                mIsRecording = false;
//            }
//        }
    }

    @Override
    void generateVideo(ReadableMap options, Promise promise) {

    }

    @Override
    void generateProvisionalVideo(ReadableMap options, Promise promise) {

    }

    

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    Set<AspectRatio> getSupportedAspectRatios() {
        return mPreviewSizes.ratios();
    }

    @Override
    SortedSet<Size> getAvailablePictureSizes(AspectRatio ratio) {
        return mPictureSizes.sizes(ratio);
    }

    @Override
    void setPictureSize(Size size) {
        if (mCaptureSession != null) {
            try {
                mCaptureSession.stopRepeating();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            mCaptureSession.close();
            mCaptureSession = null;
        }

        if (mFrameReader != null) {
            mFrameReader.close();
        }

        if (mCameraCharacteristics == null)
        {
            chooseCameraIdByFacing();
        }

        if (size == null) {
          if (mAspectRatio == null) {
            return;
          }

          if (mPictureSizes.isEmpty())
          {
              collectCameraInfo();
          }
          mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
        } else {
          mPictureSize = size;
        }
//        prepareStillImageReader();
//        prepareFrameImageReader();
//        startCaptureSession();
    }

    @Override
    Size getPictureSize() {
        return mPictureSize;
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        if (ratio != null && mPreviewSizes.isEmpty()) {
            mInitialRatio = ratio;
            return false;
        }
        if (ratio == null || ratio.equals(mAspectRatio) ||
                !mPreviewSizes.ratios().contains(ratio)) {
            // TODO: Better error handling
            return false;
        }
        mAspectRatio = ratio;
//        prepareStillImageReader();
//        prepareFrameImageReader();
//        prepareScanImageReader();
//        if (mCaptureSession != null) {
//            mCaptureSession.close();
//            mCaptureSession = null;
////            startCaptureSession();
//        }
        return true;
    }

    @Override
    AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        mAutoFocus = autoFocus;
        if (mPreviewRequestBuilder != null) {
//            updateAutoFocus();
            if (mCaptureSession != null) {
//                try {
////                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
////                            mCaptureCallback, null);
//                } catch (CameraAccessException e) {
//                    mAutoFocus = !mAutoFocus; // Revert
//                }
            }
        }
    }

    @Override
    boolean getAutoFocus() {
        return mAutoFocus;
    }

    @Override
    void setFlash(int flash) {
        if (mFlash == flash) {
            return;
        }
        int saved = mFlash;
        mFlash = flash;
        if (mPreviewRequestBuilder != null) {
//            updateFlash();
//            if (mCaptureSession != null) {
//                try {
//                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
//                            mCaptureCallback, null);
//                } catch (CameraAccessException e) {
//                    mFlash = saved; // Revert
//                }
//            }
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    void takePicture(ReadableMap options) {
//        mCaptureCallback.setOptions(options);
//
//        if (mAutoFocus) {
//            lockFocus();
//        } else {
//            captureStillPicture();
//        }
    }

    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile, int orientation) {
        if (!mIsRecording) {
//            setUpMediaRecorder(path, maxDuration, maxFileSize, false, profile);
            try {
//                mMediaRecorder.prepare();
//
//                if (mCaptureSession != null) {
//                    mCaptureSession.close();
//                    mCaptureSession = null;
//                }
//
//                startCaptureSession();

//                Size size = chooseOptimalSize();
//                mPreview.setBufferSize(size.getWidth(), size.getHeight());
//                Surface surface = getPreviewSurface();
//                Surface mMediaRecorderSurface = mMediaRecorder.getSurface();
//
//                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
//                mPreviewRequestBuilder.addTarget(surface);
//                mPreviewRequestBuilder.addTarget(mFrameReader.getSurface());
////                mPreviewRequestBuilder.addTarget(mMediaRecorderSurface);
//                mCamera.createCaptureSession(Arrays.asList(surface, mFrameReader.getSurface()), mSessionCallback, null);
//                mMediaRecorder.start();
                mIsRecording = true;
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    @Override
    void stopRecording() {
        if (mIsRecording) {
//            stopMediaRecorder();

            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }

            mCallback.onFetchingStream();



//            final HandlerThread thread = new HandlerThread("ProcessingVideoThread");
//            thread.setPriority(HandlerThread.MAX_PRIORITY);
//            thread.start();
//            Handler detachedHandler = new Handler(thread.getLooper());
//
//            detachedHandler.post(new Runnable() {
//                @Override
//                public void run() {



                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

                    List<HashMap<String, Object>> frames = mFramesArray.allItems();

                    try {
                        String filePath = mCurrentContext.getFilesDir().getPath().toString() + "/temp.mp4";
                        File videoFile = new File(filePath);
                        if(videoFile.exists())
                        {
                            videoFile.delete();
                            videoFile = new File(filePath);
                        }

                        JSONArray newArray = new JSONArray(frames);
//                        FileChannelWrapper out = null;

                        final IMediaWriter writer = ToolFactory.makeWriter(filePath);
                        writer.addVideoStream(0, 0, ICodec.ID.CODEC_ID_MPEG4,
                                200, 200);
                        final long frameRate =25/1000;
                        long startTime = System.nanoTime();
//                        while (indexVideo<1597) {


//                        }


                        try {
//                            out = NIOUtils.writableFileChannel(videoFile.getAbsolutePath());
//                            AndroidSequenceEncoder encoder = new AndroidSequenceEncoder(out, Rational.R(30, 1));
                            long nextFrameTime = 0;
                            for (int i = 0; i < frames.size(); i++) {
                                HashMap<String, Object> targetMap = frames.get(i);
                                long timestamp = (long) targetMap.get("milliseconds");

                                byte[] decodedString = Base64.decode( (String)targetMap.get("frameData"), Base64.DEFAULT);
//                                Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                                IBuffer buffer =  IBuffer.make(null, decodedString, 0, decodedString.length);
                                IVideoPicture newImage = IVideoPicture.make(buffer, IPixelFormat.Type.ARGB, 200,200);
                                newImage.setTimeStamp(timestamp);
                                writer.encodeVideo(0, newImage);

//                                encoder.encodeImage(decodedByte);
//                                decodedByte.recycle();
//                                decodedByte = null;
                            }
                            writer.close();
//                            encoder.finish();
                        } catch (Exception e){
                            Log.e("ERROR", e.getLocalizedMessage());
                        }
                        finally {
//                            NIOUtils.closeQuietly(out);
                            writer.close();
                        }

                        String targetFile = Uri.fromFile(videoFile).toString();

                        final WritableMap response = new WritableNativeMap();
                        response.putString("type", "RecordedFrames");
                        response.putString("filepath", targetFile);
                        response.putArray("frames", NativeMapsUtility.convertJsonToArray(newArray));

                        Handler mainHandler = new Handler(mCurrentContext.getMainLooper());

                        Runnable myRunnable = new Runnable() {
                            @Override
                            public void run() {
                                mCallback.onReceiveStream(response);
                            } // This is your code
                        };
                        mainHandler.post(myRunnable);

//                        thread.quit();
                    } catch (Exception e)
                    {

                    }
//                }
//            });


//            startCaptureSession();
        }
    }

    @Override
    public void setFocusDepth(float value) {
        if (mFocusDepth == value) {
            return;
        }
        float saved = mFocusDepth;
        mFocusDepth = value;
        if (mCaptureSession != null) {
//            updateFocusDepth();
//            try {
//                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
//                        mCaptureCallback, null);
//            } catch (CameraAccessException e) {
//                mFocusDepth = saved;  // Revert
//            }
        }
    }

    @Override
    float getFocusDepth() {
        return mFocusDepth;
    }

    @Override
    public void setZoom(float zoom) {
      if (mZoom == zoom) {
          return;
      }
      float saved = mZoom;
      mZoom = zoom;
      if (mCaptureSession != null) {
//          updateZoom();
//          try {
//              mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
//                  mCaptureCallback, null);
//          } catch (CameraAccessException e) {
//              mZoom = saved;  // Revert
//          }
      }
    }

    @Override
    float getZoom() {
        return mZoom;
    }

    @Override
    public void setWhiteBalance(int whiteBalance) {
        if (mWhiteBalance == whiteBalance) {
            return;
        }
        int saved = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        if (mCaptureSession != null) {
//            updateWhiteBalance();
//            try {
//                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
//                    mCaptureCallback, null);
//            } catch (CameraAccessException e) {
//                mWhiteBalance = saved;  // Revert
//            }
        }
    }

    @Override
    public int getWhiteBalance() {
        return mWhiteBalance;
    }

    @Override
    void setScanning(boolean isScanning) {
//        if (mIsScanning == isScanning) {
//            return;
//        }
//        mIsScanning = isScanning;
//        if (!mIsScanning) {
//            mImageFormat = ImageFormat.JPEG;
//        } else {
//            mImageFormat = ImageFormat.YUV_420_888;
//        }
//        if (mCaptureSession != null) {
//            mCaptureSession.close();
//            mCaptureSession = null;
//        }
//        startCaptureSession();
    }

    @Override
    boolean getScanning() {
        return mIsScanning;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        mPreview.setDisplayOrientation(mDisplayOrientation);
    }


    @Override
    void setDeviceOrientation(int deviceOrientation) {
        mDeviceOrientation = deviceOrientation;
        mPreview.setDisplayOrientation(mDeviceOrientation);
    }

    /**
     * <p>Chooses a camera ID by the specified camera facing ({@link #mFacing}).</p>
     * <p>This rewrites {@link #mCameraId}, {@link #mCameraCharacteristics}, and optionally
     * {@link #mFacing}.</p>
     */
    private boolean chooseCameraIdByFacing() {
        try {
            int internalFacing = INTERNAL_FACINGS.get(mFacing);
            final String[] ids = mCameraManager.getCameraIdList();
            if (ids.length == 0) { // No camera
                throw new RuntimeException("No camera available.");
            }
            for (String id : ids) {
                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(id);
                Integer level = characteristics.get(
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level == null ||
                        level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    continue;
                }
                Integer internal = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (internal == null) {
                    throw new NullPointerException("Unexpected state: LENS_FACING null");
                }
                if (internal == internalFacing) {
                    mCameraId = id;
                    mCameraCharacteristics = characteristics;
                    return true;
                }
            }
            // Not found
            mCameraId = ids[0];
            mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            Integer level = mCameraCharacteristics.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if (level == null ||
                    level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                return false;
            }
            Integer internal = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            if (internal == null) {
                throw new NullPointerException("Unexpected state: LENS_FACING null");
            }
            for (int i = 0, count = INTERNAL_FACINGS.size(); i < count; i++) {
                if (INTERNAL_FACINGS.valueAt(i) == internal) {
                    mFacing = INTERNAL_FACINGS.keyAt(i);
                    return true;
                }
            }
            // The operation can reach here when the only camera device is an external one.
            // We treat it as facing back.
            mFacing = Constants.FACING_BACK;
            return true;
        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera devices", e);
        }
    }

    /**
     * <p>Collects some information from {@link #mCameraCharacteristics}.</p>
     * <p>This rewrites {@link #mPreviewSizes}, {@link #mPictureSizes}, and optionally,
     * {@link #mAspectRatio}.</p>
     */
    private void collectCameraInfo() {
        StreamConfigurationMap map = mCameraCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            throw new IllegalStateException("Failed to get configuration map: " + mCameraId);
        }
        mPreviewSizes.clear();
        for (android.util.Size size : map.getOutputSizes(mPreview.getOutputClass())) {
            int width = size.getWidth();
            int height = size.getHeight();
            if (width <= MAX_PREVIEW_WIDTH && height <= MAX_PREVIEW_HEIGHT) {
                mPreviewSizes.add(new Size(width, height));
            }
        }
//        mPictureSizes.clear();
//        collectPictureSizes(mPictureSizes, map);
//        if (mPictureSize == null) {
//            mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
//        }
        for (AspectRatio ratio : mPreviewSizes.ratios()) {
            if (!mPictureSizes.ratios().contains(ratio)) {
                mPreviewSizes.remove(ratio);
            }
        }

        if (!mPreviewSizes.ratios().contains(mAspectRatio)) {
            mAspectRatio = mPreviewSizes.ratios().iterator().next();
        }
    }

    protected void collectPictureSizes(SizeMap sizes, StreamConfigurationMap map) {
        for (android.util.Size size : map.getOutputSizes(mImageFormat)) {
            mPictureSizes.add(new Size(size.getWidth(), size.getHeight()));
        }
    }

//    private void prepareStillImageReader() {
//        if (mStillImageReader != null) {
//            mStillImageReader.close();
//        }
//        mStillImageReader = ImageReader.newInstance(mPictureSize.getWidth(), mPictureSize.getHeight(),
//                ImageFormat.JPEG, 1);
//        mStillImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
//    }

//    private void prepareFrameImageReader() {
//        if (mFrameReader != null) {
//            mFrameReader.close();
//        }
//        mFrameReader = ImageReader.newInstance(
//                200,
//                200, //mPictureSize.getWidth(), mPictureSize.getHeight(),
//                ImageFormat.JPEG,
//                1);
//        HandlerThread thread = new HandlerThread("MyHandlerThread");
//        thread.start();
//        Handler handler = new Handler(thread.getLooper());
//        mFrameReader.setOnImageAvailableListener(mOnFrameAvailableListener, handler);
//    }

//    private void prepareScanImageReader() {
//        if (mScanImageReader != null) {
//            mScanImageReader.close();
//        }
//        Size largest = mPreviewSizes.sizes(mAspectRatio).last();
//        mScanImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
//                ImageFormat.YUV_420_888, 1);
//        mScanImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
//    }

    /**
     * <p>Starts opening a camera device.</p>
     * <p>The result will be processed in {@link #mCameraDeviceCallback}.</p>
     */
//    private void startOpeningCamera() {
//        try {
//            mCameraManager.openCamera(mCameraId, mCameraDeviceCallback, null);
//            requestingCamera = true;
//        } catch (CameraAccessException e) {
//            throw new RuntimeException("Failed to open camera: " + mCameraId, e);
//        }
//    }

    /**
     * <p>Starts a capture session for camera preview.</p>
     * <p>This rewrites {@link #mPreviewRequestBuilder}.</p>
     * <p>The result will be continuously processed in {@link #mSessionCallback}.</p>
     */
//    void startCaptureSession() {
//        if (!isCameraOpened() || !mPreview.isReady() || mFrameReader == null)//|| mStillImageReader == null || mScanImageReader == null)
//        {
//            return;
//        }
//        Size previewSize = chooseOptimalSize();
//        mPreview.setBufferSize(previewSize.getWidth(), previewSize.getHeight());
//        Surface surface = getPreviewSurface();
////        try {
////            mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
//            mPreviewRequestBuilder.addTarget(surface);
//
////            if (mIsScanning) {
////                mPreviewRequestBuilder.addTarget(mScanImageReader.getSurface());
////            }
//            mPreviewRequestBuilder.addTarget(mFrameReader.getSurface());
//            mCamera.createCaptureSession(Arrays.asList(surface, mFrameReader.getSurface()), mSessionCallback, null);
//        } catch (CameraAccessException e) {
//            mCallback.onMountError();
//        }
//    }

    @Override
    public void resumePreview() {
//        unlockFocus();
        if (mCamera == null) {
            // Ideally, the frames from the camera are at the same resolution as the input to
            // the video encoder so we don't have to scale.
            openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS);
        }

        if (mEglCore != null) {
            startPreview();
        }
    }

    @Override
    public void pausePreview() {

//        super.onPause();

        releaseCamera();

        if (mCircEncoder != null) {
            mCircEncoder.shutdown();
            mCircEncoder = null;
        }
        if (mCameraTexture != null) {
            mCameraTexture.release();
            mCameraTexture = null;
        }
        if (mDisplaySurface != null) {
            mDisplaySurface.release();
            mDisplaySurface = null;
        }
        if (mFullFrameBlit != null) {
            mFullFrameBlit.release(false);
            mFullFrameBlit = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
        Log.d(TAG, "onPause() done");

//        try {
//            mCaptureSession.stopRepeating();
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }


    }

    public Surface getPreviewSurface() {
        if (mPreviewSurface != null) {
            return mPreviewSurface;
        }
        return mPreview.getSurface();
    }

    @Override
    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != null) {
            Surface previewSurface = new Surface(surfaceTexture);
            mPreviewSurface = previewSurface;
        } else {
            mPreviewSurface = null;
        }

        // it may be called from another thread, so make sure we're in main looper
//        Handler handler = new Handler(Looper.getMainLooper());
//        handler.post(new Runnable() {
//            @Override
//            public void run() {
////                if (mCaptureSession != null) {
////                    mCaptureSession.close();
////                    mCaptureSession = null;
////                }
////                startCaptureSession();
//
//            }
//        });

        try {
            if (mCamera == null) {
                mPreviewTexture = surfaceTexture;
                return;
            }

            mCamera.stopPreview();
//            mIsPreviewActive = false;

            if (surfaceTexture == null) {
                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getSurfaceTexture());
            } else {
                mCamera.setPreviewTexture(surfaceTexture);
            }

            mPreviewTexture = surfaceTexture;
            startPreview();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Size getPreviewSize() {
        return new Size(mPreview.getWidth(), mPreview.getHeight());
    }

    /**
     * Chooses the optimal preview size based on {@link #mPreviewSizes} and the surface size.
     *
     * @return The picked size for camera preview.
     */
//    private Size chooseOptimalSize() {
//        int surfaceLonger, surfaceShorter;
//        final int surfaceWidth = mPreview.getWidth();
//        final int surfaceHeight = mPreview.getHeight();
//        if (surfaceWidth < surfaceHeight) {
//            surfaceLonger = surfaceHeight;
//            surfaceShorter = surfaceWidth;
//        } else {
//            surfaceLonger = surfaceWidth;
//            surfaceShorter = surfaceHeight;
//        }
//        SortedSet<Size> candidates = mPreviewSizes.sizes(mAspectRatio);
//
//        // Pick the smallest of those big enough
//        for (Size size : candidates) {
//            if (size.getWidth() >= surfaceLonger && size.getHeight() >= surfaceShorter) {
//                return size;
//            }
//        }
//        // If no size is big enough, pick the largest one.
//        return candidates.last();
//    }

    /**
     * Updates the internal state of auto-focus to {@link #mAutoFocus}.
     */
//    void updateAutoFocus() {
//        if (mAutoFocus) {
//            int[] modes = mCameraCharacteristics.get(
//                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
//            // Auto focus is not supported
//            if (modes == null || modes.length == 0 ||
//                    (modes.length == 1 && modes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF)) {
//                mAutoFocus = false;
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                        CaptureRequest.CONTROL_AF_MODE_OFF);
//            } else {
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//            }
//        } else {
//            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                    CaptureRequest.CONTROL_AF_MODE_OFF);
//        }
//    }

    /**
     * Updates the internal state of flash to {@link #mFlash}.
     */
//    void updateFlash() {
//        switch (mFlash) {
//            case Constants.FLASH_OFF:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                        CaptureRequest.CONTROL_AE_MODE_ON);
//                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                        CaptureRequest.FLASH_MODE_OFF);
//                break;
//            case Constants.FLASH_ON:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
//                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                        CaptureRequest.FLASH_MODE_OFF);
//                break;
//            case Constants.FLASH_TORCH:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                        CaptureRequest.CONTROL_AE_MODE_ON);
//                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                        CaptureRequest.FLASH_MODE_TORCH);
//                break;
//            case Constants.FLASH_AUTO:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                        CaptureRequest.FLASH_MODE_OFF);
//                break;
//            case Constants.FLASH_RED_EYE:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
//                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                        CaptureRequest.FLASH_MODE_OFF);
//                break;
//        }
//    }

    /**
     * Updates the internal state of focus depth to {@link #mFocusDepth}.
     */
//    void updateFocusDepth() {
//        if (mAutoFocus) {
//          return;
//        }
//        Float minimumLens = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
//        if (minimumLens == null) {
//          throw new NullPointerException("Unexpected state: LENS_INFO_MINIMUM_FOCUS_DISTANCE null");
//        }
//        float value = mFocusDepth * minimumLens;
//        mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, value);
//    }

    /**
     * Updates the internal state of zoom to {@link #mZoom}.
     */
//    void updateZoom() {
//        float maxZoom = mCameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
//        float scaledZoom = mZoom * (maxZoom - 1.0f) + 1.0f;
//        Rect currentPreview = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
//        if (currentPreview != null) {
//            int currentWidth = currentPreview.width();
//            int currentHeight = currentPreview.height();
//            int zoomedWidth = (int) (currentWidth / scaledZoom);
//            int zoomedHeight = (int) (currentHeight / scaledZoom);
//            int widthOffset = (currentWidth - zoomedWidth) / 2;
//            int heightOffset = (currentHeight - zoomedHeight) / 2;
//
//            Rect zoomedPreview = new Rect(
//                currentPreview.left + widthOffset,
//                currentPreview.top + heightOffset,
//                currentPreview.right - widthOffset,
//                currentPreview.bottom - heightOffset
//            );
//
//            // ¯\_(ツ)_/¯ for some devices calculating the Rect for zoom=1 results in a bit different
//            // Rect that device claims as its no-zoom crop region and the preview freezes
//            if (scaledZoom != 1.0f) {
//                mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomedPreview);
//            } else {
//                mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, mInitialCropRegion);
//            }
//        }
//    }

    /**
     * Updates the internal state of white balance to {@link #mWhiteBalance}.
     */
//    void updateWhiteBalance() {
//        switch (mWhiteBalance) {
//            case Constants.WB_AUTO:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
//                    CaptureRequest.CONTROL_AWB_MODE_AUTO);
//                break;
//            case Constants.WB_CLOUDY:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
//                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
//                break;
//            case Constants.WB_FLUORESCENT:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
//                    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
//                break;
//            case Constants.WB_INCANDESCENT:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
//                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
//                break;
//            case Constants.WB_SHADOW:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
//                    CaptureRequest.CONTROL_AWB_MODE_SHADE);
//                break;
//            case Constants.WB_SUNNY:
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
//                    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
//                break;
//        }
//    }

    /**
     * Locks the focus as the first step for a still image capture.
     */
//    private void lockFocus() {
//        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                CaptureRequest.CONTROL_AF_TRIGGER_START);
//        try {
//            mCaptureCallback.setState(PictureCaptureCallback.STATE_LOCKING);
//            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
//        } catch (CameraAccessException e) {
//            Log.e(TAG, "Failed to lock focus.", e);
//        }
//    }

    /**
     * Captures a still picture.
     */
//    void captureStillPicture() {
//        try {
//            CaptureRequest.Builder captureRequestBuilder = mCamera.createCaptureRequest(
//                    CameraDevice.TEMPLATE_STILL_CAPTURE);
////            if (mIsScanning) {
////                mImageFormat = ImageFormat.JPEG;
////                captureRequestBuilder.removeTarget(mScanImageReader.getSurface());
////            }
//            captureRequestBuilder.addTarget(mFrameReader.getSurface());
//            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
//                    mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE));
//            switch (mFlash) {
//                case Constants.FLASH_OFF:
//                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                            CaptureRequest.CONTROL_AE_MODE_ON);
//                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                            CaptureRequest.FLASH_MODE_OFF);
//                    break;
//                case Constants.FLASH_ON:
//                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
//                    break;
//                case Constants.FLASH_TORCH:
//                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                            CaptureRequest.CONTROL_AE_MODE_ON);
//                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
//                            CaptureRequest.FLASH_MODE_TORCH);
//                    break;
//                case Constants.FLASH_AUTO:
//                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//                    break;
//                case Constants.FLASH_RED_EYE:
//                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
//                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//                    break;
//            }
//            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOutputRotation());
//            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, mPreviewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));
//            // Stop preview and capture a still picture.
//            mCaptureSession.stopRepeating();
//            mCaptureSession.capture(captureRequestBuilder.build(),
//                    new CameraCaptureSession.CaptureCallback() {
//                        @Override
//                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
//                                @NonNull CaptureRequest request,
//                                @NonNull TotalCaptureResult result) {
//                            if (mCaptureCallback.getOptions().hasKey("pauseAfterCapture")
//                              && !mCaptureCallback.getOptions().getBoolean("pauseAfterCapture")) {
//                                unlockFocus();
//                            }
//                        }
//                    }, null);
//        } catch (CameraAccessException e) {
//            Log.e(TAG, "Cannot capture a still picture.", e);
//        }
//    }

//    private int getOutputRotation() {
//        @SuppressWarnings("ConstantConditions")
//        int sensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
//        return (sensorOrientation +
//                mDisplayOrientation * (mFacing == Constants.FACING_FRONT ? 1 : -1) +
//                360) % 360;
//    }

//    private void setUpMediaRecorder(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
//        mMediaRecorder = new MediaRecorder();
//
//        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
//        if (recordAudio) {
//            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
//        }
//
//        mMediaRecorder.setOutputFile(path);
//        mVideoPath = path;
//
//        if (CamcorderProfile.hasProfile(Integer.parseInt(mCameraId), profile.quality)) {
//            setCamcorderProfile(profile, recordAudio);
//        } else {
//            setCamcorderProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH), recordAudio);
//        }
//
//        mMediaRecorder.setOrientationHint(getOutputRotation());
//
//        if (maxDuration != -1) {
//            mMediaRecorder.setMaxDuration(maxDuration);
//        }
//        if (maxFileSize != -1) {
//            mMediaRecorder.setMaxFileSize(maxFileSize);
//        }
//
//        mMediaRecorder.setOnInfoListener(this);
//        mMediaRecorder.setOnErrorListener(this);
//    }

//    private void setCamcorderProfile(CamcorderProfile profile, boolean recordAudio) {
//        mMediaRecorder.setOutputFormat(profile.fileFormat);
//        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
//        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
//        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
//        mMediaRecorder.setVideoEncoder(profile.videoCodec);
//        if (recordAudio) {
//            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
//            mMediaRecorder.setAudioChannels(profile.audioChannels);
//            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
//            mMediaRecorder.setAudioEncoder(profile.audioCodec);
//        }
//    }

//    private void stopMediaRecorder() {
//        mIsRecording = false;
//        try {
//            mCaptureSession.stopRepeating();
//            mCaptureSession.abortCaptures();
//            mMediaRecorder.stop();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        mMediaRecorder.reset();
//        mMediaRecorder.release();
//        mMediaRecorder = null;
//
//        if (mVideoPath == null || !new File(mVideoPath).exists()) {
//            // @TODO: implement videoOrientation and deviceOrientation calculation
//            mCallback.onVideoRecorded(null, 0 , 0);
//            return;
//        }
//        // @TODO: implement videoOrientation and deviceOrientation calculation
//        mCallback.onVideoRecorded(mVideoPath, 0, 0);
//        mVideoPath = null;
//    }

    /**
     * Unlocks the auto-focus and restart camera preview. This is supposed to be called after
     * capturing a still picture.
     */
//    void unlockFocus() {
//        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
//        try {
//            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
//            updateAutoFocus();
//            updateFlash();
//            if (mIsScanning) {
//                mImageFormat = ImageFormat.YUV_420_888;
//                startCaptureSession();
//            } else {
//                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                        CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
//                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
//                        null);
//                mCaptureCallback.setState(PictureCaptureCallback.STATE_PREVIEW);
//            }
//        } catch (CameraAccessException e) {
//            Log.e(TAG, "Failed to restart camera preview.", e);
//        }
//    }

    /**
     * Called when an something occurs while recording.
     */
//    public void onInfo(MediaRecorder mr, int what, int extra) {
//        if ( what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
//            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
//            stopRecording();
//        }
//    }

    /**
     * Called when an error occurs while recording.
     */
//    public void onError(MediaRecorder mr, int what, int extra) {
//        stopRecording();
//    }

    /**
     * A {@link CameraCaptureSession.CaptureCallback} for capturing a still picture.
     */
//    private static abstract class PictureCaptureCallback
//            extends CameraCaptureSession.CaptureCallback {
//
//        static final int STATE_PREVIEW = 0;
//        static final int STATE_LOCKING = 1;
//        static final int STATE_LOCKED = 2;
//        static final int STATE_PRECAPTURE = 3;
//        static final int STATE_WAITING = 4;
//        static final int STATE_CAPTURING = 5;
//
//        private int mState;
//        private ReadableMap mOptions = null;
//
//        PictureCaptureCallback() {
//        }
//
//        void setState(int state) {
//            mState = state;
//        }
//
//        void setOptions(ReadableMap options) { mOptions = options; }
//
//        ReadableMap getOptions() { return mOptions; }
//
//        @Override
//        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
//                @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
//            process(partialResult);
//        }
//
//        @Override
//        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
//                @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
//            process(result);
//        }
//
//        private void process(@NonNull CaptureResult result) {
//            switch (mState) {
//                case STATE_LOCKING: {
//                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
//                    if (af == null) {
//                        break;
//                    }
//                    if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
//                            af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
//                        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
//                        if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
//                            setState(STATE_CAPTURING);
//                            onReady();
//                        } else {
//                            setState(STATE_LOCKED);
//                            onPrecaptureRequired();
//                        }
//                    }
//                    break;
//                }
//                case STATE_PRECAPTURE: {
//                    Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
//                    if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
//                            ae == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED ||
//                            ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
//                        setState(STATE_WAITING);
//                    }
//                    break;
//                }
//                case STATE_WAITING: {
//                    Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
//                    if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
//                        setState(STATE_CAPTURING);
//                        onReady();
//                    }
//                    break;
//                }
//            }
//        }
//
//        /**
//         * Called when it is ready to take a still picture.
//         */
//        public abstract void onReady();
//
//        /**
//         * Called when it is necessary to run the precapture sequence.
//         */
//        public abstract void onPrecaptureRequired();
//
//    }

}
