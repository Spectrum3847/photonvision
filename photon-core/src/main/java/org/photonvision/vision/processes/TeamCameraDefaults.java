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

package org.photonvision.vision.processes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.frame.FrameDivisor;
import org.photonvision.vision.pipeline.AdvancedPipelineSettings;
import org.photonvision.vision.pipeline.AprilTagCudaPipelineSettings;
import org.photonvision.vision.pipeline.AprilTagPipelineSettings;
import org.photonvision.vision.pipeline.CVPipelineSettings;
import org.photonvision.vision.pipeline.PipelineType;
import org.wpilib.util.PixelFormat;
import org.wpilib.vision.camera.VideoMode;

/**
 * SpectrumJetson: what a brand-new camera starts with on the Jetson, matching the tuned
 * TopLeft/TopRight cameras (Thriftiest Cam, OV9281). Stock PhotonVision gives a new camera a CPU
 * AprilTag pipeline at its first video mode (320x240 YUYV on these cameras). Change team defaults
 * here. Existing cameras keep their saved settings; only cameras with no pipelines use these.
 */
public final class TeamCameraDefaults {
    private TeamCameraDefaults() {}

    /**
     * UVC units of 100 us: 50 = 5 ms. Tuned on the bench 2026-09-24: less motion blur than 8.3 ms
     * (83, one 120 Hz mains-flicker period), detections still solid with the decision margin
     * cutoff at 15, and no measurable flicker under the shop lights (frame-to-frame brightness
     * change 0.6%, tests/flicker-check). Recheck flicker under event lighting.
     */
    public static final double EXPOSURE_RAW = 50;

    /** AprilTag decision-margin cutoff (PhotonVision's default 35), for the shorter exposure. */
    public static final int DECISION_MARGIN = 15;

    public static final int BRIGHTNESS = 100;
    public static final boolean AUTO_WHITE_BALANCE = false;
    public static final double WHITE_BALANCE_TEMP = 2800;

    /** Low Latency Mode (blockForFrames). Off: ~92 fps each with 2 cameras; on: ~60 fps, less CPU. */
    public static final boolean LOW_LATENCY_MODE = false;

    /** Only the browser preview stream; smaller saves CPU. */
    public static final FrameDivisor STREAM_DIVISOR = FrameDivisor.SIXTH;

    /**
     * Multi-tag solve on the Jetson (PhotonLib's multi-tag result). It only runs while 3D is on,
     * and 3D needs a calibration, so {@link #enable3dIfCalibrated} turns 3D on once there is one.
     */
    public static final boolean MULTI_TAG = true;

    public static final int PREFERRED_WIDTH = 1280;
    public static final int PREFERRED_HEIGHT = 800;

    /** The CUDA detector is only built for the Jetson. */
    public static PipelineType defaultPipelineType() {
        return isJetson() ? PipelineType.AprilTagCuda : PipelineType.AprilTag;
    }

    /**
     * Same check as Platform's private isJetsonSBC(); kept here so photon-targeting stays
     * identical to upstream.
     */
    static boolean isJetson() {
        try {
            return Files.readString(Path.of("/proc/device-tree/model")).contains("NVIDIA Jetson");
        } catch (Exception e) {
            return false;
        }
    }

    /** Applies the team defaults to a new camera's first pipeline. */
    public static void apply(CVPipelineSettings s, List<VideoMode> modes) {
        s.cameraAutoExposure = false;
        s.cameraExposureRaw = EXPOSURE_RAW;
        s.cameraBrightness = BRIGHTNESS;
        s.cameraAutoWhiteBalance = AUTO_WHITE_BALANCE;
        s.cameraWhiteBalanceTemp = WHITE_BALANCE_TEMP;
        s.blockForFrames = LOW_LATENCY_MODE;
        s.streamingFrameDivisor = STREAM_DIVISOR;
        if (s instanceof AprilTagCudaPipelineSettings a) {
            a.doMultiTarget = MULTI_TAG;
            a.decisionMargin = DECISION_MARGIN;
        }
        if (s instanceof AprilTagPipelineSettings a) {
            a.doMultiTarget = MULTI_TAG;
            a.decisionMargin = DECISION_MARGIN;
        }
        int mode = pickVideoMode(modes);
        if (mode >= 0) s.cameraVideoModeIndex = mode;
    }

    /**
     * Video mode indexes differ per camera, so pick by content: the preferred resolution in MJPEG
     * at its highest fps; else the largest MJPEG mode running at least 60 fps; else the largest
     * mode. Returns -1 if the camera reported no modes.
     */
    public static int pickVideoMode(List<VideoMode> modes) {
        return pickVideoMode(modes, PREFERRED_WIDTH, PREFERRED_HEIGHT);
    }

    /** Spectrum: {@link #pickVideoMode(List)} with a camera profile's preferred resolution. */
    public static int pickVideoMode(List<VideoMode> modes, int preferredWidth, int preferredHeight) {
        int best = -1;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < modes.size(); i++) {
            VideoMode m = modes.get(i);
            boolean mjpeg = m.pixelFormat == PixelFormat.MJPEG;
            boolean preferred = m.width == preferredWidth && m.height == preferredHeight;
            long score =
                    (preferred && mjpeg ? 1L << 50 : 0)
                            + (mjpeg && m.fps >= 60 ? 1L << 40 : 0)
                            + (long) m.width * m.height * 1000
                            + m.fps;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /**
     * Turns 3D (and multi-tag) on for an AprilTag pipeline whose resolution has a calibration. 3D
     * without a calibration doesn't work, which is why new cameras start with it off. Only ever
     * turns settings on. Returns true if it changed anything.
     */
    public static boolean enable3dIfCalibrated(
            CVPipelineSettings s,
            List<VideoMode> modes,
            List<CameraCalibrationCoefficients> calibrations) {
        if (!(s instanceof AprilTagCudaPipelineSettings || s instanceof AprilTagPipelineSettings)) {
            return false;
        }
        if (s.cameraVideoModeIndex < 0 || s.cameraVideoModeIndex >= modes.size()) return false;
        VideoMode m = modes.get(s.cameraVideoModeIndex);
        if ( calibrations == null) return false;
        boolean calibrated =
                calibrations.stream()
                        .anyMatch(
                                c ->
                                        c.resolution != null
                                                && (int) c.resolution.width == m.width
                                                && (int) c.resolution.height == m.height);
        if (!calibrated) return false;
        var a = (AdvancedPipelineSettings) s;
        boolean changed = !a.solvePNPEnabled;
        a.solvePNPEnabled = true;
        if (s instanceof AprilTagCudaPipelineSettings c && c.doMultiTarget != MULTI_TAG) {
            c.doMultiTarget = MULTI_TAG;
            changed = true;
        }
        if (s instanceof AprilTagPipelineSettings c && c.doMultiTarget != MULTI_TAG) {
            c.doMultiTarget = MULTI_TAG;
            changed = true;
        }
        return changed;
    }
}
