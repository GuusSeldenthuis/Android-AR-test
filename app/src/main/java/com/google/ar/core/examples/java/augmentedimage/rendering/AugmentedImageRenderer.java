/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.augmentedimage.rendering;

import android.content.Context;

import com.google.ar.core.Anchor;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.Pose;
import com.google.ar.core.examples.java.augmentedimage.rendering.ObjectRenderer.BlendMode;

import java.io.IOException;

/**
 * Renders an augmented image.
 */
public class AugmentedImageRenderer
{
    private static final String TAG = "AugmentedImageRenderer";

    private static final float TINT_INTENSITY = 1.0f;
    private static final float TINT_ALPHA = 0.5f;
    private static final int TINT_COLORS_HEX = 0x00ff00;

    private final ObjectRenderer imageFrameUpperLeft = new ObjectRenderer();

    public AugmentedImageRenderer()
    {
    }

    public void createOnGlThread(Context context) throws IOException
    {

        imageFrameUpperLeft.createOnGlThread(
                context, "models/andy.obj", "models/andy.png");
        imageFrameUpperLeft.setMaterialProperties(0.1f, 0.0f, 0.5f, 0.1f);
        imageFrameUpperLeft.setBlendMode(BlendMode.SourceAlpha);
    }

    public void draw(
            float[] viewMatrix,
            float[] projectionMatrix,
            AugmentedImage augmentedImage,
            Anchor centerAnchor,
            float[] colorCorrectionRgba)
    {
//        Pose localBoundaryPose = centerAnchor.getPose();

        Pose localBoundaryPose = Pose.makeTranslation(
                augmentedImage.getExtentX() / 2.0f,
                0.0f,
                augmentedImage.getExtentZ() / 2.0f);

        Pose anchorPose = centerAnchor.getPose();
        Pose worldBoundaryPose = anchorPose.compose(localBoundaryPose);

        float scaleFactor = 1f;
        float[] modelMatrix = new float[16];

        worldBoundaryPose.toMatrix(modelMatrix, 0);
        imageFrameUpperLeft.updateModelMatrix(modelMatrix, scaleFactor);
        imageFrameUpperLeft.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, convertHexToColor(TINT_COLORS_HEX));
    }

    private static float[] convertHexToColor(int colorHex)
    {
        // colorHex is in 0xRRGGBB format
        float red = ((colorHex & 0xFF0000) >> 16) / 255.0f * TINT_INTENSITY;
        float green = ((colorHex & 0x00FF00) >> 8) / 255.0f * TINT_INTENSITY;
        float blue = (colorHex & 0x0000FF) / 255.0f * TINT_INTENSITY;
        return new float[]{red, green, blue, TINT_ALPHA};
    }
}
