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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Spectrum (photonvision-91). */
public class CalibrationCoverageTest {
    private static double[][] grid(double x0, double y0, double x1, double y1, int nx, int ny) {
        double[] x = new double[nx * ny], y = new double[nx * ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                x[j * nx + i] = x0 + (x1 - x0) * i / Math.max(1, nx - 1);
                y[j * nx + i] = y0 + (y1 - y0) * j / Math.max(1, ny - 1);
            }
        }
        return new double[][] {x, y};
    }

    @Test
    public void cellsAndBlocks() {
        var c = new CalibrationCoverage(1000, 800);
        assertEquals(0, c.cellOf(0, 0));
        assertEquals(9, c.cellOf(999, 0));
        assertEquals(79, c.cellOf(999.9, 799.9));
        assertEquals(-1, c.cellOf(1000, 10));
        assertEquals(-1, c.cellOf(-0.1, 10));
        assertEquals(0, CalibrationCoverage.blockOf(11));
        assertEquals(1, CalibrationCoverage.blockOf(18));
        assertEquals(2, CalibrationCoverage.blockOf(60));
        assertEquals(3, CalibrationCoverage.blockOf(79));
        assertEquals(-1, CalibrationCoverage.blockOf(12));
        assertTrue(CalibrationCoverage.isRing(0));
        assertTrue(CalibrationCoverage.isRing(19));
        assertFalse(CalibrationCoverage.isRing(11));
    }

    @Test
    public void countsCornersAndSnapshotsPerCell() {
        var c = new CalibrationCoverage(1000, 800);
        var g = grid(5, 5, 95, 95, 10, 10); // 100 corners, all in cell 0
        c.add(g[0], g[1]);
        c.add(g[0], g[1]);
        var s = c.summary();
        assertEquals(200, s.counts()[0]);
        assertEquals(2, s.snapshots()[0]);
        assertEquals(79, s.emptyCells());
        assertFalse(s.complete());
        assertEquals(200, s.blocks()[0]);
        assertArrayEquals(new double[] {5, 5, 1000 - 1 - 95, 800 - 1 - 95}, s.borderPx(), 1e-9);
        // two snapshots: cell 0 isn't met (needs 3), but the emptiest is another cell
        assertFalse(c.cellMet(0));
        assertTrue(s.nextCell() != 0);
    }

    @Test
    public void fullCoverageIsComplete() {
        var c = new CalibrationCoverage(1000, 800);
        // Every cell gets 7x7 corners per snapshot, three snapshots, corners right at the edges
        for (int k = 0; k < 3; k++) {
            var g = grid(0, 0, 999, 799, 70, 56);
            c.add(g[0], g[1]);
        }
        var s = c.summary();
        assertEquals(80, s.cellsMet());
        assertEquals(1.0, s.fraction(), 1e-9);
        assertTrue(s.blocksMet());
        assertTrue(s.borderMet());
        assertTrue(s.complete());
        assertEquals(-1, s.nextCell());
    }

    @Test
    public void emptiestPrefersCornerBlocks() {
        var c = new CalibrationCoverage(1000, 800);
        // The centre is covered, nothing at the edges: the next cell is in a corner block
        var g = grid(300, 300, 700, 500, 40, 20);
        c.add(g[0], g[1]);
        int next = c.summary().nextCell();
        assertTrue(CalibrationCoverage.blockOf(next) >= 0, "next cell " + next);
    }

    @Test
    public void gainCountsOnlyCellsShortOfTarget() {
        var c = new CalibrationCoverage(1000, 800);
        var centre = grid(450, 350, 549, 449, 10, 10); // cells 34, 35, 44, 45
        assertTrue(c.gain(centre[0], centre[1]) >= 100);
        for (int k = 0; k < 3; k++) c.add(centre[0], centre[1]);
        // The same corners again add nothing to cells already at target
        assertEquals(0, c.gain(centre[0], centre[1]));
        // A corner board does
        var corner = grid(0, 0, 90, 90, 6, 6);
        assertTrue(c.gain(corner[0], corner[1]) > CalibrationSession.MIN_GAIN);
    }
}
