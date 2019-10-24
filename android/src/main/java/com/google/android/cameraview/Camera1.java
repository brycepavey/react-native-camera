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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.util.SparseArrayCompat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.facebook.react.bridge.NativeMap;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.google.android.cameraview.gles.Base64Callback;
import com.google.android.cameraview.gles.CameraUtils;
import com.google.android.cameraview.gles.CircularEncoder;
import com.google.android.cameraview.gles.EglCore;
import com.google.android.cameraview.gles.FullFrameRect;
import com.google.android.cameraview.gles.Texture2dProgram;
import com.google.android.cameraview.gles.WindowSurface;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;


@SuppressWarnings("deprecation")
class Camera1 extends CameraViewImpl implements MediaRecorder.OnInfoListener,
                                                MediaRecorder.OnErrorListener, Camera.PreviewCallback {

    private static final int INVALID_CAMERA_ID = -1;
    private static final String TAG = "Camera1";

    private static final SparseArrayCompat<String> FLASH_MODES = new SparseArrayCompat<>();

    static {
        FLASH_MODES.put(Constants.FLASH_OFF, Camera.Parameters.FLASH_MODE_OFF);
        FLASH_MODES.put(Constants.FLASH_ON, Camera.Parameters.FLASH_MODE_ON);
        FLASH_MODES.put(Constants.FLASH_TORCH, Camera.Parameters.FLASH_MODE_TORCH);
        FLASH_MODES.put(Constants.FLASH_AUTO, Camera.Parameters.FLASH_MODE_AUTO);
        FLASH_MODES.put(Constants.FLASH_RED_EYE, Camera.Parameters.FLASH_MODE_RED_EYE);
    }

    private static final SparseArrayCompat<String> WB_MODES = new SparseArrayCompat<>();

    static {
      WB_MODES.put(Constants.WB_AUTO, Camera.Parameters.WHITE_BALANCE_AUTO);
      WB_MODES.put(Constants.WB_CLOUDY, Camera.Parameters.WHITE_BALANCE_CLOUDY_DAYLIGHT);
      WB_MODES.put(Constants.WB_SUNNY, Camera.Parameters.WHITE_BALANCE_DAYLIGHT);
      WB_MODES.put(Constants.WB_SHADOW, Camera.Parameters.WHITE_BALANCE_SHADE);
      WB_MODES.put(Constants.WB_FLUORESCENT, Camera.Parameters.WHITE_BALANCE_FLUORESCENT);
      WB_MODES.put(Constants.WB_INCANDESCENT, Camera.Parameters.WHITE_BALANCE_INCANDESCENT);
    }


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

//    private Camera mCamera;
    private int mCameraPreviewThousandFps;

    private File mOutputFile;
    private File mProvisionalFile;
    private CircularEncoder mCircEncoder;
    private WindowSurface mEncoderSurface;
    private boolean mFileSaveInProgress;

    private Camera1.MainHandler mHandler;
//    private SurfaceTexture mPreviewTexture;
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
                mHandler.sendEmptyMessage(Camera1.MainHandler.MSG_FRAME_AVAILABLE);

        }
    };

    private SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback()
    {
        @Override   // SurfaceHolder.Callback
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated holder=" + holder);

            // Set up everything that requires an EGL context.

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
    private static class MainHandler extends Handler {
        public static final int MSG_BLINK_TEXT = 0;
        public static final int MSG_FRAME_AVAILABLE = 1;
        public static final int MSG_FILE_SAVE_COMPLETE = 2;
        public static final int MSG_OFFLINE_FILE_SAVE_COMPLETE = 4;
        public static final int MSG_BUFFER_STATUS = 3;

        public CircularEncoder.Callback circularEncoderCallback = new CircularEncoder.Callback(){

            // CircularEncoder.Callback, called on encoder thread
            @Override
            public void fileSaveComplete(int status) {
                sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
            }

            @Override
            public void updatedFileSaveComplete(int status) {
                sendMessage(obtainMessage(MSG_OFFLINE_FILE_SAVE_COMPLETE, status, 0, null));
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

        private WeakReference<Camera1> mWeakActivity;

        public MainHandler(Camera1 activity) {
            mWeakActivity = new WeakReference<Camera1>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            Camera1 activity = mWeakActivity.get();
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
                case MSG_OFFLINE_FILE_SAVE_COMPLETE: {
                    activity.offlineFileSaveCompleted(msg.arg1);
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
        purgeFrameArray(false);
        mFramesArray.addLast(frameMap);
    }

    private void purgeFrameArray(boolean precise)
    {
        if(mFramesArray.size() <= 2 || mSecondsOfVideo <= 1)
        {
            return;
        }

        long lastFrame = (long) mFramesArray.getLast().get("milliseconds");
        double targetTime = precise ? mSecondsOfVideo : Math.ceil(mSecondsOfVideo);
        double minimumTime = lastFrame - (targetTime) * 1000;

        while(mFramesArray.size() > 0 && (long)mFramesArray.getFirst().get("milliseconds") < minimumTime )
        {
            mFramesArray.popFirst();
        }
    }


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
        if (!mFileSaveInProgress && mIsRecording) {
            mEncoderSurface.makeCurrent();
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
            mFullFrameBlit.drawFrame(mTextureId, mTmpMatrix);
            mCircEncoder.frameAvailableSoon();

            final long timestamp = mCameraTexture.getTimestamp();
            mEncoderSurface.setPresentationTime(timestamp);
            mEncoderSurface.swapBuffers();
            mEncoderSurface.base64Image(new Base64Callback() {
                @Override
                public void onResponse(HashMap<String, String> base64Image) {
                    if (base64Image !=  null)
                    {
                        HashMap<String, Object> frameData = new HashMap<String, Object>();
                        frameData.putAll(base64Image);
                        frameData.put("milliseconds", timestamp/1000000);
                        Camera1.this.AddFrame(frameData);
                    }
                }
            });

        }

        mFrameNum++;
    }

    /**
     * The file save has completed.  We can resume recording.
     */
    private void fileSaveComplete(int status)
    {
        Log.d(TAG, "fileSaveComplete " + status);
        if (!mFileSaveInProgress) {
            throw new RuntimeException("WEIRD: got fileSaveCmplete when not in progress");
        }
        mFileSaveInProgress = false;

        String targetFile = Uri.fromFile(mOutputFile).toString();
        purgeFrameArray(true);
        List<HashMap<String, Object>> frames = mFramesArray.allItems();
        final HashMap lastItem = frames.get(frames.size()-1);
        final long lastMillisecond = (long)lastItem.get("milliseconds");
        frames.removeIf(new Predicate<HashMap<String, Object>>() {
            @Override
            public boolean test(HashMap<String, Object> n) {
                return (Long) n.get("milliseconds") >= lastMillisecond - mSecondsOfVideo;
            }
        });

        List<HashMap<String, Object>> filteredFrames = new ArrayList<HashMap<String, Object>>();

        for (int i = 0; i < frames.size(); i++) {

            // accessing each element of array
            HashMap current = frames.get(i);
            HashMap newMap = new HashMap(current);
            newMap.remove("frameData");
//            current.remove("frameData");
            filteredFrames.add(newMap);
        }

        JSONArray newArray = new JSONArray(filteredFrames);

        final WritableMap response = new WritableNativeMap();
        response.putString("type", "RecordedFrames");
        response.putString("filepath", targetFile);
        try {
            response.putArray("frames", NativeMapsUtility.convertJsonToArray(newArray));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        mCallback.onReceiveStream(response);
    }

    private void offlineFileSaveCompleted(int status)
    {
//        mCallback.on
    }

    private void updateBufferStatus(long durationUsec) {
        mSecondsOfVideo = durationUsec / 1000000.0f;
    }

    private void startPreview() {
        if (mCamera != null) {
//            Log.d(TAG, "starting camera preview");
            try {

                mCamera.setPreviewTexture(mCameraTexture);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
//            try {
//                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
//
////            mCamera.startPreview();
//        }
//
//        // TODO: adjust bit rate based on frame rate?     } catch (IOException e) {
//                e.printStackTrace();
//            }
            // TODO: adjust video width/height based on what we're getting from the camera preview?
            //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
        }

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

    private int mCameraId;

    private final AtomicBoolean isPictureCaptureInProgress = new AtomicBoolean(false);

    Camera mCamera;

    private Camera.Parameters mCameraParameters;

    private final Camera.CameraInfo mCameraInfo = new Camera.CameraInfo();

    private MediaRecorder mMediaRecorder;

    private String mVideoPath;

    private boolean mIsRecording;

    private final SizeMap mPreviewSizes = new SizeMap();
                                                    
    private boolean mIsPreviewActive = false;

    private final SizeMap mPictureSizes = new SizeMap();
                                                    
    private Size mPictureSize;

    private AspectRatio mAspectRatio;

    private boolean mShowingPreview;

    private boolean mAutoFocus;

    private int mFacing;

    private int mFlash;

    private int mDisplayOrientation;

    private int mDeviceOrientation;

    private int mOrientation = Constants.ORIENTATION_AUTO;

    private float mZoom;

    private int mWhiteBalance;

    private boolean mIsScanning;

    private Context mCurrentContext;

    private SurfaceTexture mPreviewTexture;

    Camera1(Callback callback, SurfaceViewPreview preview, Context context) {
        super(callback, preview);

        preview.getSurfaceHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
                mDisplaySurface = new WindowSurface(mEglCore, mPreview.getSurfaceHolder().getSurface(), false);
                mDisplaySurface.makeCurrent();

                mFullFrameBlit = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
                mTextureId = mFullFrameBlit.createTextureObject();
                mCameraTexture = new SurfaceTexture(mTextureId);
                mCameraTexture.setOnFrameAvailableListener(frameAvailableListener);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {


                if (mCamera != null) {
//                    setUpPreview();
//                    startPreview();

                    setUpPreview();

                    mIsPreviewActive = false;
                    adjustCameraParameters();
                }
            }

            @Override
            public void onSurfaceDestroyed() {
              stop();
            }
        });

        mCurrentContext = context;
        mHandler = new Camera1.MainHandler(this);
//        mHandler.sendEmptyMessageDelayed(MainHandler.MSG_BLINK_TEXT, 1500);
        String filePath = context.getFilesDir().getPath().toString() + "/temp.mp4";
        mOutputFile = new File(filePath);
        mSecondsOfVideo = 0.0f;
    }

    @Override
    boolean start() {
        chooseCamera();
        if (!openCamera()) {
            mCallback.onMountError();
            // returning false will result in invoking this method again
            return true;
        }
        if (mPreview.isReady()) {
            setUpPreview();
        }
        mShowingPreview = true;
        startCameraPreview();
        return true;
    }

    @Override
    void stop() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
        }
        mShowingPreview = false;
        if (mMediaRecorder != null) {
//            mMediaRecorder.stop();
//            mMediaRecorder.release();
//            mMediaRecorder = null;

            if (mIsRecording) {
                int deviceOrientation = displayOrientationToOrientationEnum(mDeviceOrientation);
                mCallback.onVideoRecorded(mVideoPath, mOrientation != Constants.ORIENTATION_AUTO ? mOrientation : deviceOrientation, deviceOrientation);
                mIsRecording = false;
            }
        }
        releaseCamera();
    }

    // Suppresses Camera#setPreviewTexture
    @SuppressLint("NewApi")
    void setUpPreview() {
//        try {
//            if (mPreviewTexture != null) {
//                mCamera.setPreviewTexture(mPreviewTexture);
//            } else if (mPreview.getOutputClass() == SurfaceHolder.class) {
//                final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
//                if (needsToStopPreview) {
//                    mCamera.stopPreview();
//                    mIsPreviewActive = false;
//                }
//                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
//                if (needsToStopPreview) {
//                    startCameraPreview();
//                }
//            } else {
//                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getSurfaceTexture());
//            }
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }




    }

    private void startCameraPreview() {
        startPreview();
        mCamera.startPreview();
        mIsPreviewActive = true;
//        if (mIsScanning) {
//            mCamera.setPreviewCallback(this);
//        }
    }
                                                    
    @Override
    public void resumePreview() {
        startCameraPreview();
    }

    @Override
    public void pausePreview() {
        mCamera.stopPreview();
        mIsPreviewActive = false;
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
        SizeMap idealAspectRatios = mPreviewSizes;
        for (AspectRatio aspectRatio : idealAspectRatios.ratios()) {
            if (mPictureSizes.sizes(aspectRatio) == null) {
                idealAspectRatios.remove(aspectRatio);
            }
        }
        return idealAspectRatios.ratios();
    }
                                                    
    @Override
    SortedSet<Size> getAvailablePictureSizes(AspectRatio ratio) {
        return mPictureSizes.sizes(ratio);
    }
    
    @Override
    void setPictureSize(Size size) {
        if (size == null) {
            if (mAspectRatio == null) {
                return;
            }
          SortedSet<Size> sizes = mPictureSizes.sizes(mAspectRatio);
          if(sizes != null && !sizes.isEmpty())
          {
            mPictureSize = sizes.last();
          }
        } else {
          mPictureSize = size;
        }
        if (mCameraParameters != null && mCamera != null) {
            mCameraParameters.setPictureSize(mPictureSize.getWidth(), mPictureSize.getHeight());
            mCamera.setParameters(mCameraParameters);
        }
    }
    
    @Override
    Size getPictureSize() {
        return mPictureSize;
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        if (mAspectRatio == null || !isCameraOpened()) {
            // Handle this later when camera is opened
            mAspectRatio = ratio;
            return true;
        } else if (!mAspectRatio.equals(ratio)) {
            final Set<Size> sizes = mPreviewSizes.sizes(ratio);
            if (sizes == null) {
                throw new UnsupportedOperationException(ratio + " is not supported");
            } else {
                mAspectRatio = ratio;
                adjustCameraParameters();
                return true;
            }
        }
        return false;
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
        if (setAutoFocusInternal(autoFocus)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    boolean getAutoFocus() {
        if (!isCameraOpened()) {
            return mAutoFocus;
        }
        String focusMode = mCameraParameters.getFocusMode();
        return focusMode != null && focusMode.contains("continuous");
    }

    @Override
    void setFlash(int flash) {
        if (flash == mFlash) {
            return;
        }
        if (setFlashInternal(flash)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    public void setFocusDepth(float value) {
        // not supported for Camera1
    }

    @Override
    float getFocusDepth() {
        return 0;
    }

    @Override
    void setZoom(float zoom) {
        if (zoom == mZoom) {
            return;
        }
        if (setZoomInternal(zoom)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    void generateVideo(ReadableMap options, Promise promise)
    {

    }

    List<HashMap<String, Object>> filterFrames(final double start, final double end)
    {
        List<HashMap<String, Object>> allItems = new ArrayList<HashMap<String, Object>>(mFramesArray.allItems());
        allItems.removeIf(new Predicate<HashMap<String, Object>>() {
            @Override
            public boolean test(HashMap<String, Object> stringObjectHashMap) {
                long lastFrame = (long) stringObjectHashMap.get("milliseconds");
                return !(lastFrame >= start && lastFrame <= end);
            }
        });

        return allItems;
    }

    @Override
    void generateProvisionalVideo(ReadableMap options, Promise promise)
    {
        if (!options.hasKey("startTimestamp") || !options.hasKey("endTimestamp"))
        {
            promise.reject("incomplete parameters", "one or more parameters are missing");
            return;
        }

        double startTimeStamp = options.getDouble("startTimestamp");
        double endTimeStamp = options.getDouble("endTimestamp");

        List frames = filterFrames(startTimeStamp, endTimeStamp);
//        mCircEncoder.shutdown();
//        try {
//            mCircEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 6000000, mCameraPreviewThousandFps / 1000, (int)Math.max( Math.ceil((endTimeStamp - startTimeStamp)/1000), 2), mHandler.circularEncoderCallback);
//        } catch (IOException ioe) {
//            promise.reject(ioe);
//            throw new RuntimeException(ioe);
//        }

        String filePath = this.mCurrentContext.getFilesDir().getPath().toString() + "/provisional.mp4";
        mProvisionalFile = new File(filePath);
        boolean result = mCircEncoder.saveCustomVideo(mProvisionalFile, frames);

        if(result)
        {
            WritableMap response = new WritableNativeMap();
            response.putString("type", "outputfile");
            response.putString("filepath", Uri.fromFile(mProvisionalFile).toString());
            promise.resolve(response);
        } else {
            promise.reject("UNABLE TO PRODUCE OUTPUTFILE", "Error producing output video file");
        }

//        NSDictionary *eventRecordedFrames = @{
////        @"type" : @"OutputFile",
////        @"filepath" : filePath
////    };
////        resolve(eventRecordedFrames);




    }

    @Override
    float getZoom() {
        return mZoom;
    }

    @Override
    public void setWhiteBalance(int whiteBalance) {
        if (whiteBalance == mWhiteBalance) {
            return;
        }
        if (setWhiteBalanceInternal(whiteBalance)) {
            mCamera.setParameters(mCameraParameters);
        }
    }

    @Override
    public int getWhiteBalance() {
        return mWhiteBalance;
    }

    @Override
    void setScanning(boolean isScanning) {
        if (isScanning == mIsScanning) {
            return;
        }
        setScanningInternal(isScanning);
    }

    @Override
    boolean getScanning() {
        return mIsScanning;
    }

    @Override
    void takePicture(final ReadableMap options) {
        if (!isCameraOpened()) {
            throw new IllegalStateException(
                    "Camera is not ready. Call start() before takePicture().");
        }
        if (!mIsPreviewActive) {
            throw new IllegalStateException("Preview is paused - resume it before taking a picture.");
        }
        if (getAutoFocus()) {
            mCamera.cancelAutoFocus();
            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    takePictureInternal(options);
                }
            });
        } else {
            takePictureInternal(options);
        }
    }

    int orientationEnumToRotation(int orientation) {
        switch(orientation) {
            case Constants.ORIENTATION_UP:
                return 0;
            case Constants.ORIENTATION_DOWN:
                return 180;
            case Constants.ORIENTATION_LEFT:
                return 270;
            case Constants.ORIENTATION_RIGHT:
                return 90;
            default:
                return Constants.ORIENTATION_UP;
        }
    }

    int displayOrientationToOrientationEnum(int rotation) {
        switch (rotation) {
            case 0:
                return Constants.ORIENTATION_UP;
            case 90:
                return Constants.ORIENTATION_RIGHT;
            case 180:
                return Constants.ORIENTATION_DOWN;
            case 270:
                return Constants.ORIENTATION_LEFT;
            default:
                return 1;
        }
    }

    void takePictureInternal(final ReadableMap options) {
        if (!isPictureCaptureInProgress.getAndSet(true)) {

            if (options.hasKey("orientation") && options.getInt("orientation") != Constants.ORIENTATION_AUTO) {
                mOrientation = options.getInt("orientation");
                int rotation = orientationEnumToRotation(mOrientation);
                mCameraParameters.setRotation(calcCameraRotation(rotation));
                mCamera.setParameters(mCameraParameters);
            }

            mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    isPictureCaptureInProgress.set(false);
                    camera.cancelAutoFocus();
                    if (options.hasKey("pauseAfterCapture") && !options.getBoolean("pauseAfterCapture")) {
                        camera.startPreview();
                        mIsPreviewActive = true;
                        if (mIsScanning) {
                            camera.setPreviewCallback(Camera1.this);
                        }
                    } else {
                        camera.stopPreview();
                        mIsPreviewActive = false;
                        camera.setPreviewCallback(null);
                    }

                    mOrientation = Constants.ORIENTATION_AUTO;
                    mCallback.onPictureTaken(data, displayOrientationToOrientationEnum(mDeviceOrientation));
                }
            });
        }
    }

    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile, int orientation) {
        if (!mIsRecording) {
            if (orientation != Constants.ORIENTATION_AUTO) {
                mOrientation = orientation;
            }
            setUpMediaRecorder(path, maxDuration, maxFileSize, recordAudio, profile);
            try {
                mMediaRecorder.prepare();
                mMediaRecorder.start();
                mIsRecording = true;
                return true;
            } catch (IOException e) {
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
//            if (mCamera != null) {
//                mCamera.lock();
//            }
            mIsRecording = false;
            mCallback.onFetchingStream();
            mFileSaveInProgress = true;
            mCircEncoder.saveVideo(mOutputFile);
        }
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        if (mDisplayOrientation == displayOrientation) {
            return;
        }
        mDisplayOrientation = displayOrientation;
        if (isCameraOpened()) {
            final boolean needsToStopPreview = mShowingPreview && Build.VERSION.SDK_INT < 14;
            if (needsToStopPreview) {
                mCamera.stopPreview();
                mIsPreviewActive = false;
            }
            mCamera.setDisplayOrientation(calcDisplayOrientation(displayOrientation));
            if (needsToStopPreview) {
                startCameraPreview();
            }
        }
    }

    @Override
    void setDeviceOrientation(int deviceOrientation) {
        if (mDeviceOrientation == deviceOrientation) {
            return;
        }
        mDeviceOrientation = deviceOrientation;
        if (isCameraOpened() && mOrientation == Constants.ORIENTATION_AUTO) {
            mCameraParameters.setRotation(calcCameraRotation(deviceOrientation));
            mCamera.setParameters(mCameraParameters);
         }
    }

    @Override
    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        try {
            if (mCamera == null) {
                mPreviewTexture = surfaceTexture;
                return;
            }

            mCamera.stopPreview();
            mIsPreviewActive = false;

            if (surfaceTexture == null) {
                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getSurfaceTexture());
            } else {
                mCamera.setPreviewTexture(surfaceTexture);
            }

            mPreviewTexture = surfaceTexture;
            startCameraPreview();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Size getPreviewSize() {
        Camera.Size cameraSize = mCameraParameters.getPreviewSize();
        return new Size(cameraSize.width, cameraSize.height);
    }

    /**
     * This rewrites {@link #mCameraId} and {@link #mCameraInfo}.
     */
    private void chooseCamera() {
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == mFacing) {
                mCameraId = i;
                return;
            }
        }
        mCameraId = INVALID_CAMERA_ID;
    }

    private boolean openCamera() {
        if (mCamera != null) {
            releaseCamera();
        }
        try {
            mCamera = Camera.open(mCameraId);
            mCameraParameters = mCamera.getParameters();
            // Supported preview sizes
            mPreviewSizes.clear();
            for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                mPreviewSizes.add(new Size(size.width, size.height));
            }

            mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(mCameraParameters, 30 * 1000);

            // Supported picture sizes;
            mPictureSizes.clear();
            for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                mPictureSizes.add(new Size(size.width, size.height));
            }
            // AspectRatio
            if (mAspectRatio == null) {
                mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;
            }
            adjustCameraParameters();
            mCamera.setDisplayOrientation(calcDisplayOrientation(mDisplayOrientation));
            mCallback.onCameraOpened();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private AspectRatio chooseAspectRatio() {
        AspectRatio r = null;
        for (AspectRatio ratio : mPreviewSizes.ratios()) {
            r = ratio;
            if (ratio.equals(Constants.DEFAULT_ASPECT_RATIO)) {
                return ratio;
            }
        }
        return r;
    }

    void adjustCameraParameters() {
        SortedSet<Size> sizes = mPreviewSizes.sizes(mAspectRatio);
        if (sizes == null) { // Not supported
            mAspectRatio = chooseAspectRatio();
            sizes = mPreviewSizes.sizes(mAspectRatio);
        }
        Size size = chooseOptimalSize(sizes);

        // Always re-apply camera parameters
        if (mPictureSize == null) {
            mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
        }
        if (mShowingPreview) {
            mCamera.stopPreview();
            mIsPreviewActive = false;
        }
        mCameraParameters.setPreviewSize(size.getWidth(), size.getHeight());
        mCameraParameters.setPictureSize(mPictureSize.getWidth(), mPictureSize.getHeight());
        if (mOrientation != Constants.ORIENTATION_AUTO) {
            mCameraParameters.setRotation(calcCameraRotation(orientationEnumToRotation(mOrientation)));
        } else {
            mCameraParameters.setRotation(calcCameraRotation(mDeviceOrientation));
        }

        setAutoFocusInternal(mAutoFocus);
        setFlashInternal(mFlash);
        setAspectRatio(mAspectRatio);
        setZoomInternal(mZoom);
        setWhiteBalanceInternal(mWhiteBalance);
        setScanningInternal(mIsScanning);
        mCamera.setParameters(mCameraParameters);
        if (mShowingPreview) {
            startCameraPreview();
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Size chooseOptimalSize(SortedSet<Size> sizes) {
        if (!mPreview.isReady()) { // Not yet laid out
            return sizes.first(); // Return the smallest size
        }
        int desiredWidth;
        int desiredHeight;
        final int surfaceWidth = mPreview.getWidth();
        final int surfaceHeight = mPreview.getHeight();
        if (isLandscape(mDisplayOrientation)) {
            desiredWidth = surfaceHeight;
            desiredHeight = surfaceWidth;
        } else {
            desiredWidth = surfaceWidth;
            desiredHeight = surfaceHeight;
        }
        Size result = null;
        for (Size size : sizes) { // Iterate from small to large
            if (desiredWidth <= size.getWidth() && desiredHeight <= size.getHeight()) {
                return size;

            }
            result = size;
        }
        return result;
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
            mPictureSize = null;
            mCallback.onCameraClosed();
        }
    }

    /**
     * Calculate display orientation
     * https://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
     *
     * This calculation is used for orienting the preview
     *
     * Note: This is not the same calculation as the camera rotation
     *
     * @param screenOrientationDegrees Screen orientation in degrees
     * @return Number of degrees required to rotate preview
     */
    private int calcDisplayOrientation(int screenOrientationDegrees) {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + screenOrientationDegrees) % 360) % 360;
        } else {  // back-facing
            return (mCameraInfo.orientation - screenOrientationDegrees + 360) % 360;
        }
    }

    /**
     * Calculate camera rotation
     *
     * This calculation is applied to the output JPEG either via Exif Orientation tag
     * or by actually transforming the bitmap. (Determined by vendor camera API implementation)
     *
     * Note: This is not the same calculation as the display orientation
     *
     * @param screenOrientationDegrees Screen orientation in degrees
     * @return Number of degrees to rotate image in order for it to view correctly.
     */
    private int calcCameraRotation(int screenOrientationDegrees) {
       if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
           return (mCameraInfo.orientation + screenOrientationDegrees) % 360;
       }
       // back-facing
       final int landscapeFlip = isLandscape(screenOrientationDegrees) ? 180 : 0;
       return (mCameraInfo.orientation + screenOrientationDegrees + landscapeFlip) % 360;
    }

    /**
     * Test if the supplied orientation is in landscape.
     *
     * @param orientationDegrees Orientation in degrees (0,90,180,270)
     * @return True if in landscape, false if portrait
     */
    private boolean isLandscape(int orientationDegrees) {
        return (orientationDegrees == Constants.LANDSCAPE_90 ||
                orientationDegrees == Constants.LANDSCAPE_270);
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setAutoFocusInternal(boolean autoFocus) {
        mAutoFocus = autoFocus;
        if (isCameraOpened()) {
            final List<String> modes = mCameraParameters.getSupportedFocusModes();
            if (autoFocus && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            } else {
                mCameraParameters.setFocusMode(modes.get(0));
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setFlashInternal(int flash) {
        if (isCameraOpened()) {
            List<String> modes = mCameraParameters.getSupportedFlashModes();
            String mode = FLASH_MODES.get(flash);
            if (modes != null && modes.contains(mode)) {
                mCameraParameters.setFlashMode(mode);
                mFlash = flash;
                return true;
            }
            String currentMode = FLASH_MODES.get(mFlash);
            if (modes == null || !modes.contains(currentMode)) {
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                return true;
            }
            return false;
        } else {
            mFlash = flash;
            return false;
        }
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setZoomInternal(float zoom) {
        if (isCameraOpened() && mCameraParameters.isZoomSupported()) {
            int maxZoom = mCameraParameters.getMaxZoom();
            int scaledValue = (int) (zoom * maxZoom);
            mCameraParameters.setZoom(scaledValue);
            mZoom = zoom;
            return true;
        } else {
            mZoom = zoom;
            return false;
        }
    }

    /**
     * @return {@code true} if {@link #mCameraParameters} was modified.
     */
    private boolean setWhiteBalanceInternal(int whiteBalance) {
        mWhiteBalance = whiteBalance;
        if (isCameraOpened()) {
            final List<String> modes = mCameraParameters.getSupportedWhiteBalance();
            String mode = WB_MODES.get(whiteBalance);
            if (modes != null && modes.contains(mode)) {
                mCameraParameters.setWhiteBalance(mode);
                return true;
            }
            String currentMode = WB_MODES.get(mWhiteBalance);
            if (modes == null || !modes.contains(currentMode)) {
                mCameraParameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    private void setScanningInternal(boolean isScanning) {
        mIsScanning = isScanning;
        if (isCameraOpened()) {
            if (mIsScanning) {
                mCamera.setPreviewCallback(this);
            } else {
                mCamera.setPreviewCallback(null);
            }
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Camera.Size previewSize = mCameraParameters.getPreviewSize();
        mCallback.onFramePreview(data, previewSize.width, previewSize.height, mDeviceOrientation);
    }

    private void setUpMediaRecorder(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();

        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        if (recordAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        }

        mMediaRecorder.setOutputFile(path);
        mVideoPath = path;

        if (CamcorderProfile.hasProfile(mCameraId, profile.quality)) {
            setCamcorderProfile(CamcorderProfile.get(mCameraId, profile.quality), recordAudio);
        } else {
            setCamcorderProfile(CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH), recordAudio);
        }

        mMediaRecorder.setOrientationHint(calcCameraRotation(mOrientation != Constants.ORIENTATION_AUTO ? orientationEnumToRotation(mOrientation) : mDeviceOrientation));

        if (maxDuration != -1) {
            mMediaRecorder.setMaxDuration(maxDuration);
        }
        if (maxFileSize != -1) {
            mMediaRecorder.setMaxFileSize(maxFileSize);
        }

        mMediaRecorder.setOnInfoListener(this);
        mMediaRecorder.setOnErrorListener(this);
    }

    private void stopMediaRecorder() {
        mIsRecording = false;
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }

        int deviceOrientation = displayOrientationToOrientationEnum(mDeviceOrientation);
        if (mVideoPath == null || !new File(mVideoPath).exists()) {
            mCallback.onVideoRecorded(null, mOrientation != Constants.ORIENTATION_AUTO ? mOrientation : deviceOrientation, deviceOrientation);
            return;
        }

        mCallback.onVideoRecorded(mVideoPath, mOrientation != Constants.ORIENTATION_AUTO ? mOrientation : deviceOrientation, deviceOrientation);
        mVideoPath = null;
    }

    private void setCamcorderProfile(CamcorderProfile profile, boolean recordAudio) {
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mMediaRecorder.setVideoEncoder(profile.videoCodec);
        if (recordAudio) {
            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
        }
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if ( what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
              what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            stopRecording();
        }
    }


    @Override
    public void onError(MediaRecorder mr, int what, int extra) {
        stopRecording();
    }
}
