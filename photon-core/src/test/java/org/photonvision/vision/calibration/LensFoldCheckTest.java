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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Spectrum (photonvision-91). */
public class LensFoldCheckTest {
    // fx = fy = 500, 1280x800, principal point in the middle: the corner is at r = 1.509
    private static final double[] K = {500, 0, 639.5, 0, 500, 399.5, 0, 0, 1};

    @Test
    public void noDistortionNeverFolds() {
        var r = LensFoldCheck.check(K, new double[8], 1280, 800);
        assertFalse(r.folds());
        assertEquals(Math.hypot(639.5, 399.5), r.cornerRadiusPx(), 1e-6);
        assertTrue(r.maxRadiusPx() >= r.cornerRadiusPx() * (1 + LensFoldCheck.MARGIN));
    }

    @Test
    public void mildBarrelReachesTheCorner() {
        // r(1 - 0.05 r^2) peaks at r = 2.58 (r_d 1.72), past the corner's 1.51
        var r = LensFoldCheck.check(K, new double[] {-0.05, 0, 0, 0, 0, 0, 0, 0}, 1280, 800);
        assertFalse(r.folds());
    }

    @Test
    public void strongBarrelFoldsInsideTheImage() {
        // r(1 - 0.5 r^2) peaks at r = 0.816, r_d 0.544: 272 px, well inside the 754 px corner
        var r = LensFoldCheck.check(K, new double[] {-0.5, 0, 0, 0, 0, 0, 0, 0}, 1280, 800);
        assertTrue(r.folds());
        assertEquals(0.544 * 500, r.maxRadiusPx(), 1.0);
    }

    @Test
    public void rationalModelFoldIsCaught() {
        // A degenerate 8-coefficient fit (large k2 and k5, as in the analysis): fine in the centre,
        // turns over before the corner
        double[] d = {0.1, 2.7, 0, 0, -1.0, 0.1, 2.9, 0.5};
        var r = LensFoldCheck.check(K, d, 1280, 800);
        // whatever it does, the check must agree with a brute-force scan of r_d(r)
        double prev = 0;
        boolean folds = true;
        double target = Math.hypot(639.5, 399.5) / 500 * (1 + LensFoldCheck.MARGIN);
        for (double x = 1e-4; x < 20; x += 1e-4) {
            double x2 = x * x;
            double num = 1 + x2 * (d[0] + x2 * (d[1] + x2 * d[4]));
            double den = 1 + x2 * (d[5] + x2 * (d[6] + x2 * d[7]));
            if (den <= 0 || num <= 0) break;
            double rd = x * num / den;
            if (rd <= prev) break;
            prev = rd;
            if (rd >= target) {
                folds = false;
                break;
            }
        }
        assertEquals(folds, r.folds());
    }

    @Test
    public void offCentrePrincipalPointUsesTheFarthestCorner() {
        double[] k = {500, 0, 400, 0, 500, 300, 0, 0, 1};
        var r = LensFoldCheck.check(k, new double[5], 1280, 800);
        assertEquals(Math.hypot(879, 499), r.cornerRadiusPx(), 1e-6);
    }
}
