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

import org.photonvision.vision.pipeline.AprilTagCudaPipelineSettings;
import org.photonvision.vision.pipeline.AprilTagPipelineSettings;
import org.photonvision.vision.pipeline.CVPipeline;
import org.photonvision.vision.pipeline.CVPipelineSettings;
import org.photonvision.vision.pipeline.ObjectDetectionPipelineSettings;

/**
 * SpectrumJetson (photonvision-100): a pipeline's own frame-rate cap ("Tag FPS max" on AprilTag
 * pipelines, "Object FPS max" on Object Detection pipelines), enforced before the frame is grabbed,
 * so the frames in between are never decoded or processed. The camera keeps its mode.
 *
 * <p>Frames are taken on a fixed schedule (every 1/max seconds), not "1/max after the last grab":
 * the grab then waits for the camera's next frame, and with a 122 fps camera that would turn a 60
 * fps cap into ~48. Idle mode's and deep idle's periods still apply; the longer period wins. With
 * no cap set, VisionRunner uses IdleMode exactly as before.
 */
final class FrameRateCap {
    private long nextDueNanos = 0;

    /** The pipeline's cap as a period in ns; 0 = none. */
    static long capPeriodNanos(CVPipeline<?, ?> pipeline) {
        if (pipeline == null) return 0;
        CVPipelineSettings s = pipeline.getSettings();
        int fps = 0;
        // A combined pipeline (an AprilTagCudaPipelineSettings) caps by its tag rate: every frame
        // processed goes to the tag detector; objects take a share of those.
        if (s instanceof AprilTagCudaPipelineSettings a) fps = a.tagFpsMax;
        else if (s instanceof AprilTagPipelineSettings a) fps = a.tagFpsMax;
        else if (s instanceof ObjectDetectionPipelineSettings o) fps = o.objectFpsMax;
        return fps > 0 ? 1_000_000_000L / fps : 0;
    }

    /** Sleeps until the next scheduled frame; false if interrupted. */
    boolean await(long capPeriodNanos) {
        while (true) {
            long idle = IdleMode.active() ? IdleMode.periodNanos() : 0;
            long period = Math.max(capPeriodNanos, idle);
            if (period <= 0) {
                nextDueNanos = 0;
                return true;
            }
            long now = System.nanoTime();
            // First frame, or fell more than a period behind (a slow frame, a period change):
            // restart the schedule now rather than racing to catch up.
            if (nextDueNanos == 0 || now - nextDueNanos > period) nextDueNanos = now;
            long wait = nextDueNanos - now;
            if (wait <= 0) {
                nextDueNanos += period;
                return true;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(5, wait / 1_000_000)), 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
