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

package org.photonvision.jni;

import java.io.File;

/**
 * SpectrumJetson (photonvision-100): TensorRT YOLO object detection on the Jetson's GPU
 * (libspectrumtrt.so, built from SpectrumJetson's detector/trt_yolo.cu and TensorRtYoloJNI.cu,
 * installed to /usr/lib by 07-build-gpu-detector.sh).
 *
 * <p>Loaded lazily, the first time a TensorRT model is used: TensorRT is a large library and most
 * cameras never run object detection. Without the library (the SystemCore, a PC) the TensorRT
 * backend simply isn't offered.
 */
public final class TensorRtJNI {
    private static final String LIB_PATH =
            System.getenv().getOrDefault("SPECTRUM_TRT_LIB", "/usr/lib/libspectrumtrt.so");
    private static boolean loaded = false;

    private TensorRtJNI() {}

    /** Whether the TensorRT backend is installed (without loading it). */
    public static boolean isInstalled() {
        return new File(LIB_PATH).isFile();
    }

    /** Loads the native library; true if it's usable. */
    public static synchronized boolean load() {
        if (!loaded) {
            try {
                System.load(LIB_PATH);
                loaded = true;
            } catch (Throwable t) {
                System.err.println("Could not load " + LIB_PATH + ": " + t);
            }
        }
        return loaded;
    }

    /**
     * Loads a model: an ONNX (YOLOv8/YOLO11 detect export, built into a TensorRT engine and cached in
     * cacheDir on first use, which takes minutes) or a ready-built .engine. Blocks; 0 on error (see
     * {@link #lastError()}).
     */
    public static native long createModel(
            String modelPath, String cacheDir, int width, int height, int maxBatch, boolean fp16);

    /** {input width, input height, max batch, number of classes (-1: NMS in the model)}. */
    public static native int[] modelInfo(long ptr);

    /**
     * Runs frames (grey CV_8UC1 or BGR CV_8UC3 Mats, any size) as one batch. They're letterboxed to
     * the input size on the GPU.
     *
     * @return per frame: count, then [x, y, w, h, classId, confidence] per detection in that frame's
     *     pixels, best first; null on error
     */
    public static native float[] detectBatch(
            long ptr, long[] matPtrs, int n, double conf, double iou, int maxDet);

    public static native void destroyModel(long ptr);

    /** Why the last createModel/detectBatch on this thread failed. */
    public static native String lastError();
}
