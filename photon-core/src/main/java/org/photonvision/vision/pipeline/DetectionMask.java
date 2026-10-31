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

import io.avaje.jsonb.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SpectrumJetson (photonvision-40): areas of the image a pipeline should ignore, or the only areas
 * it should search, drawn as boxes on the dashboard stream (pv-mask-overlay). Boxes are fractions
 * of the image as displayed (after rotation), so they survive a resolution change. AprilCudaTag
 * applies it on the GPU right after thresholding (bos-07); object detection drops detections whose
 * centre is ignored.
 *
 * <p>Saved with the pipeline settings ({@code detectionMask}), as in 2026, so a 2026 Jetson's mask
 * loads.
 */
@Json
public class DetectionMask {
    /** 0 = off, 1 = ignore inside the boxes, 2 = search only inside the boxes. */
    public int mode = 0;

    public List<Box> boxes = new ArrayList<>();

    @Json
    public static class Box {
        public double x, y, w, h;

        public Box() {}

        public Box(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double nx, double ny) {
            return nx >= x && nx < x + w && ny >= y && ny < y + h;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Box b)) return false;
            return b.x == x && b.y == y && b.w == w && b.h == h;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, w, h);
        }
    }

    /** Whether the mask does anything: an "only inside" mask with no boxes counts as off. */
    public boolean active() {
        return (mode == 1 || mode == 2) && !boxes.isEmpty();
    }

    /** Whether a point, as fractions of the image, is ignored. */
    public boolean ignores(double nx, double ny) {
        if (!active()) return false;
        boolean inside = boxes.stream().anyMatch(b -> b.contains(nx, ny));
        return mode == 1 ? inside : !inside;
    }

    /** x, y, w, h of every box, for the detector library. */
    public double[] flatBoxes() {
        double[] r = new double[boxes.size() * 4];
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            r[i * 4] = b.x;
            r[i * 4 + 1] = b.y;
            r[i * 4 + 2] = b.w;
            r[i * 4 + 3] = b.h;
        }
        return r;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DetectionMask m)) return false;
        return m.mode == mode && Objects.equals(m.boxes, boxes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, boxes);
    }
}
