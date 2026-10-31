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

/** Spectrum (photonvision-91): auto-capture decisions, quotas and guidance. */
public class CalibrationSessionTest {
    private static double[][] patch(double x0, double y0, int n, double step) {
        double[] x = new double[n * n], y = new double[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                x[j * n + i] = x0 + i * step;
                y[j * n + i] = y0 + j * step;
            }
        }
        return new double[][] {x, y};
    }

    private static CalibrationSession session() {
        // 1280x800, fx 900, a 12x9 board of 30 mm squares
        return new CalibrationSession(1280, 800, 900, 0.36, 0.27);
    }

    @Test
    public void wantsNewCoverageButNotDuplicates() {
        var s = session();
        var p = patch(500, 300, 8, 20);
        assertTrue(s.wants(p[0], p[1], 0.7, 5));
        s.add(p[0], p[1], 0.7, 5, true);
        // Same place, distance and tilt: a near-copy
        assertTrue(s.isDuplicate(570, 370, 0.7, 5));
        assertFalse(s.wants(p[0], p[1], 0.7, 5));
        // Same place, tilted: new
        assertTrue(s.wants(p[0], p[1], 0.7, 30));
    }

    @Test
    public void removeRebuildsCoverage() {
        var s = session();
        var p = patch(0, 0, 6, 10);
        s.add(p[0], p[1], 0.7, 0, false);
        assertEquals(36, s.summary().coverage().counts()[0]);
        s.remove(0);
        assertEquals(0, s.summary().coverage().counts()[0]);
        assertEquals(0, s.size());
    }

    @Test
    public void quotasAndGuidance() {
        var s = session();
        assertTrue(s.needFar());
        assertTrue(s.needTilt());
        var g = s.guidance();
        // Nothing yet: a corner block cell, far, tilted, hanging off the edge
        assertTrue(CalibrationCoverage.blockOf(g.cell()) >= 0);
        assertEquals(1.3, g.distanceM(), 1e-9);
        assertEquals(30, g.tiltDeg(), 1e-9);
        assertEquals(900 * 0.36 / 1.3, g.w(), 1e-6);
        assertTrue(g.text().contains("corner"));
        for (int k = 0; k < 5; k++) {
            var p = patch(100 + 30 * k, 100, 4, 10);
            s.add(p[0], p[1], 1.3, 0, false);
        }
        assertFalse(s.needFar());
        assertEquals(5, s.summary().far());
    }

    @Test
    public void tiltBounds() {
        assertTrue(CalibrationSession.isTilted(20));
        assertTrue(CalibrationSession.isTilted(45));
        assertFalse(CalibrationSession.isTilted(50));
        assertFalse(CalibrationSession.isTilted(-1));
        assertTrue(CalibrationSession.isFar(1.2));
        assertFalse(CalibrationSession.isFar(-1));
    }
}
