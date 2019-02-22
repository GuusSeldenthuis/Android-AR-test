/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.google.ar.core.examples.java.augmentedimage;

import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This app extends the HelloAR Java app to include image tracking functionality.
 */
public class AugmentedImageActivity extends AppCompatActivity implements GLSurfaceView.Renderer
{
    private static final String TAG = "Seldenthuis";

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    private ImageView fitToScanView;
    private RequestManager glideRequestManager;

    private boolean installRequested;

    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;

    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final AugmentedImageRenderer augmentedImageRenderer = new AugmentedImageRenderer();

    // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
    // the
    // database.
    private final Map<Integer, Pair<AugmentedImage, Anchor>> augmentedImageMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate()");

        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);

        fitToScanView = findViewById(R.id.image_view_fit_to_scan);
        glideRequestManager = Glide.with(this);
        glideRequestManager
                .load(Uri.parse("file:///android_asset/fit_to_scan.png"))
                .into(fitToScanView);

        installRequested = false;
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        Log.d(TAG, "onResume()");

        if (this.session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                this.session = new Session(/* context = */ this);
            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (Exception e) {
                message = "This device does not support AR";
                exception = e;
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            Config config = new Config(session);
            if (!setupAugmentedImageDatabase(config)) {
                messageSnackbarHelper.showError(this, "Could not setup augmented image database");
            }
            this.session.configure(config);
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            this.session.resume();
        } catch (CameraNotAvailableException e) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            this.messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.");
            this.session = null;
            return;
        }
        this.surfaceView.onResume();
        this.displayRotationHelper.onResume();

        this.fitToScanView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        Log.d(TAG, "onPause()");

        if (this.session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            this.displayRotationHelper.onPause();
            this.surfaceView.onPause();
            this.session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results)
    {
        Log.d(TAG, "onRequestPermissionsResult()");

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                    this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            this.finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG, "onWindowFocusChanged()");

        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config)
    {
        Log.d(TAG, "onSurfaceCreated()");

        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            this.backgroundRenderer.createOnGlThread(/*context=*/ this);
            this.augmentedImageRenderer.createOnGlThread(/*context=*/ this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height)
    {
        Log.d(TAG, "onSurfaceChanged()");

        this.displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl)
    {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (this.session == null) {
            Log.d(TAG, "onDrawFrame: session is null.");
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        this.displayRotationHelper.updateSessionIfNeeded(session);

        try {
            this.session.setCameraTextureName(this.backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = this.session.update();
            Camera camera = frame.getCamera();

            // If frame is ready, render camera preview image to the GL surface.
            this.backgroundRenderer.draw(frame);

            // If not tracking, don't draw 3d objects.
            if (camera.getTrackingState() == TrackingState.PAUSED) {
                return;
            }

            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);

            // Compute lighting from average intensity of the image.
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // Visualize augmented images.
            this.drawAugmentedImages(frame, projmtx, viewmtx, colorCorrectionRgba);
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void drawAugmentedImages(
            Frame frame, float[] projmtx, float[] viewmtx, float[] colorCorrectionRgba)
    {
        Collection<AugmentedImage> updatedAugmentedImages =
                frame.getUpdatedTrackables(AugmentedImage.class);

        // Iterate to update augmentedImageMap, remove elements we cannot draw.
        for (AugmentedImage augmentedImage : updatedAugmentedImages) {
            switch (augmentedImage.getTrackingState()) {
                case PAUSED:
                    // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
                    // but not yet tracked.
                    String text = String.format("Detected Image %s.", augmentedImage.getName());
                    this.messageSnackbarHelper.showMessage(this, text);
                    break;

                case TRACKING:
                    // Have to switch to UI Thread to update View.
                    this.runOnUiThread(
                            () -> this.fitToScanView.setVisibility(View.GONE));

                    // Create a new anchor for newly found images.
                    if (!this.augmentedImageMap.containsKey(augmentedImage.getIndex())) {
                        Anchor centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());
                        this.augmentedImageMap.put(
                                augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchor));
                    }
                    break;

                case STOPPED:
                    this.augmentedImageMap.remove(augmentedImage.getIndex());
                    break;

                default:
                    break;
            }
        }

        // Draw all images in augmentedImageMap
        for (Pair<AugmentedImage, Anchor> pair : this.augmentedImageMap.values()) {
            AugmentedImage augmentedImage = pair.first;
            Anchor centerAnchor = this.augmentedImageMap.get(augmentedImage.getIndex()).second;
            switch (augmentedImage.getTrackingState()) {
                case TRACKING:
                    this.augmentedImageRenderer.draw(
                            viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
                    break;
                default:
                    break;
            }
        }
    }

    private boolean setupAugmentedImageDatabase(Config config)
    {
        Log.d(TAG, "setupAugmentedImageDatabase()");
        AugmentedImageDatabase augmentedImageDatabase;

        // This is an alternative way to initialize an AugmentedImageDatabase instance,
        // load a pre-existing augmented image database.
        try (InputStream is = getAssets().open("testguus.imgdb")) {
            augmentedImageDatabase = AugmentedImageDatabase.deserialize(this.session, is);
            Log.d(TAG, String.format("setupAugmentedImageDatabase: %s images loaded!", augmentedImageDatabase.getNumImages()));
        } catch (IOException e) {
            Log.e(TAG, "IO exception loading augmented image database.", e);
            return false;
        }

        config.setAugmentedImageDatabase(augmentedImageDatabase);
        return true;
    }
}
