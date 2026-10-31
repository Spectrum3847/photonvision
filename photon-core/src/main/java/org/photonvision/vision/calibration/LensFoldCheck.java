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

package org.photonvision.vision.calibration;

import io.avaje.jsonb.Json;

/**
 * Spectrum (photonvision-91): does a lens model fold inside the image? OpenCV's radial model maps
 * an undistorted radius r to a distorted one, r_d = r (1 + k1 r^2 + k2 r^4 + k3 r^6) / (1 + k4 r^2
 * + k5 r^4 + k6 r^6). Fitted to corners that never reached the image's edges, the 8-coefficient
 * model can turn over (r_d stops growing) before the image corner: past that radius two directions
 * map to the same pixel, undistortion has no answer and tags out there come out several pixels
 * wrong (SpectrumJetson tests/calibration-analysis). The model is good only if r_d keeps increasing
 * until it passes the image's farthest corner, with a margin.
 */
public final class LensFoldCheck {
    private LensFoldCheck() {}

    /** How far past the farthest image corner the curve must still be increasing. */
    public static final double MARGIN = 0.02;

    /**
     * @param folds true if the radial curve stops increasing (or its denominator reaches zero) before
     *     it reaches the image corner radius plus the margin
     * @param cornerRadiusPx the farthest image corner's distance from the principal point, pixels
     * @param maxRadiusPx how far out (pixels from the principal point) the model is still increasing;
     *     at least cornerRadiusPx (1 + MARGIN) when it doesn't fold
     */
    @Json
    public record Result(boolean folds, double cornerRadiusPx, double maxRadiusPx) {}

    /**
     * @param intrinsics row-major 3x3 camera matrix (fx at 0, cx at 2, fy at 4, cy at 5)
     * @param dist OpenCV distortion coefficients (k1, k2, p1, p2, k3, k4, k5, k6, ...), any length
     */
    public static Result check(double[] intrinsics, double[] dist, double width, double height) {
        double fx = intrinsics[0], cx = intrinsics[2], fy = intrinsics[4], cy = intrinsics[5];
        double k1 = at(dist, 0), k2 = at(dist, 1), k3 = at(dist, 4);
        double k4 = at(dist, 5), k5 = at(dist, 6), k6 = at(dist, 7);

        // The farthest image corner, in normalized (distorted) coordinates.
        double cornerNorm = 0;
        for (double u : new double[] {0, width - 1}) {
            for (double v : new double[] {0, height - 1}) {
                cornerNorm = Math.max(cornerNorm, Math.hypot((u - cx) / fx, (v - cy) / fy));
            }
        }
        double f = (fx + fy) / 2;
        double target = cornerNorm * (1 + MARGIN);

        double prev = 0;
        double step = 1e-3;
        for (double r = step; r <= 20; r += step) {
            double r2 = r * r;
            double num = 1 + r2 * (k1 + r2 * (k2 + r2 * k3));
            double den = 1 + r2 * (k4 + r2 * (k5 + r2 * k6));
            if (den <= 1e-9 || num <= 0) break;
            double rd = r * num / den;
            if (rd <= prev) break; // turned over
            prev = rd;
            if (rd >= target) return new Result(false, cornerNorm * f, rd * f);
        }
        return new Result(true, cornerNorm * f, prev * f);
    }

    public static Result check(CameraCalibrationCoefficients cal) {
        return check(
                cal.getIntrinsicsArr(),
                cal.getDistCoeffsArr(),
                cal.resolution.width,
                cal.resolution.height);
    }

    private static double at(double[] a, int i) {
        return a != null && i < a.length ? a[i] : 0;
    }
}
