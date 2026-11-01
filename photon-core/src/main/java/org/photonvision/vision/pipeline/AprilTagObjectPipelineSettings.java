/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.pipeline;

import org.photonvision.common.configuration.NeuralNetworkModelManager;
import org.photonvision.common.configuration.NeuralNetworkModelsSettings;
import org.photonvision.vision.objects.Model;

/**
 * SpectrumJetson (photonvision-100): "AprilTag + Object", the GPU AprilTag pipeline plus a TensorRT
 * object model on a share of the same frames. Every AprilTagCuda setting applies to the tag side
 * unchanged; the object side's results go to the camera's sibling "<camera>-objects" table.
 */
public class AprilTagObjectPipelineSettings extends AprilTagCudaPipelineSettings {
    /** Minimum confidence for an object detection (0-1). */
    public double confidence = 0.5;

    /** Non-maximum suppression IoU threshold (0-1). */
    public double nms = 0.45;

    /** The object model (a TensorRT ONNX model from Settings > Object Detection). */
    public NeuralNetworkModelsSettings.ModelProperties model;

    /**
     * "Object FPS max": at most this many of the processed frames a second also go to the model (0 =
     * every processed frame). The tag side is capped separately by tagFpsMax.
     */
    public int objectFpsMax = 15;

    /** Class names to keep, comma-separated; empty keeps every class. */
    public String objectClasses = "";

    /** At most this many objects per result (best first). */
    public int objectMaxTargets = 20;

    public AprilTagObjectPipelineSettings() {
        super();
        pipelineType = PipelineType.AprilTagObject;
        model =
                NeuralNetworkModelManager.getInstance()
                        .getDefaultModel()
                        .map(Model::getProperties)
                        .orElse(null);
    }
}
