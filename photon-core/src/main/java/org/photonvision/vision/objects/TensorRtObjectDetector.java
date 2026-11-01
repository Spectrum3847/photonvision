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

package org.photonvision.vision.objects;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.opencv.core.Mat;
import org.photonvision.vision.pipe.impl.NeuralNetworkPipeResult;

/**
 * SpectrumJetson (photonvision-100): a camera's handle on a TensorRT model's shared service
 * (TensorRtService). Frames can be grey (mono cameras: copied to all three input channels) or BGR,
 * any size; letterboxing happens on the GPU and boxes come back in the frame's pixels.
 */
public class TensorRtObjectDetector implements ObjectDetector {
    /** How long a synchronous detect waits (a batch is ~5-15 ms; the engine may be building). */
    private static final long DETECT_TIMEOUT_MS = 250;

    private final TensorRtModel model;
    private TensorRtService service;

    public TensorRtObjectDetector(TensorRtModel model) {
        this.model = model;
        this.service = TensorRtService.acquire(model);
    }

    @Override
    public TensorRtModel getModel() {
        return model;
    }

    @Override
    public List<String> getClasses() {
        return model.properties.labels();
    }

    public TensorRtService.State getState() {
        return service == null ? TensorRtService.State.FAILED : service.getState();
    }

    /** Queues a copy of the frame; the AprilTag side of a combined pipeline never waits on it. */
    public CompletableFuture<List<NeuralNetworkPipeResult>> detectAsync(
            Mat in, double nmsThresh, double boxThresh) {
        if (service == null || in.empty()) return CompletableFuture.completedFuture(List.of());
        return service.submit(in.clone(), boxThresh, nmsThresh);
    }

    @Override
    public List<NeuralNetworkPipeResult> detect(Mat in, double nmsThresh, double boxThresh) {
        try {
            return detectAsync(in, nmsThresh, boxThresh).get(DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public synchronized void release() {
        if (service != null) {
            service.release();
            service = null;
        }
    }
}
