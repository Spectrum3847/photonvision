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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.wpilib.math.geometry.Pose3d;

/** Spectrum (photonvision-91): per-corner flags and residuals stay on their corners. */
public class CornerAlignmentTest {
    @Test
    public void boardIndexedFlagsGoToTheirDetections() {
        // An 11x8 board (88 corners); mrcal flagged corners 3 and 40 as outliers
        boolean[] byId = new boolean[88];
        java.util.Arrays.fill(byId, true);
        byId[3] = false;
        byId[40] = false;
        // A partial view: detections are corners 40, 2, 3, 41 in that order
        int[] ids = {40, 2, 3, 41};
        assertArrayEquals(
                new boolean[] {false, true, false, true}, CornerAlignment.perDetection(byId, ids, 4));
        // Upstream read byId[i] by detection index: corners 0..3 -> {true, true, true, false}, wrong
    }

    @Test
    public void wholeBoardWithoutIdsIsIdentity() {
        boolean[] byId = {true, false, true};
        assertArrayEquals(byId, CornerAlignment.perDetection(byId, null, 3));
    }

    @Test
    public void outOfRangeIdsAreUnused() {
        assertArrayEquals(
                new boolean[] {false, true},
                CornerAlignment.perDetection(new boolean[] {true, true}, new int[] {5, 1}, 2));
    }

    @Test
    public void loadedFlagsAreNormalized() {
        // Already per detection: untouched
        boolean[] per = {true, false};
        assertArrayEquals(per, CornerAlignment.normalizeLoaded(per, new int[] {7, 9}, 2));
        // Saved before the fix: board-indexed, remapped by id
        boolean[] byId = new boolean[10];
        byId[9] = true;
        assertArrayEquals(
                new boolean[] {false, true}, CornerAlignment.normalizeLoaded(byId, new int[] {7, 9}, 2));
        // Missing: all used
        assertArrayEquals(
                new boolean[] {true, true, true}, CornerAlignment.normalizeLoaded(null, null, 3));
    }

    private static BoardObservation obs(boolean[] used, int[] ids, List<Point> residuals) {
        var img = List.of(new Point(10, 10), new Point(20, 10), new Point(30, 10));
        var obj = List.of(new Point3(0, 0, 0), new Point3(1, 0, 0), new Point3(2, 0, 0));
        return new BoardObservation(obj, img, residuals, new Pose3d(), ids, used, "img0.png", null);
    }

    @Test
    public void meanErrorUsesTheMatchingResiduals() {
        // One residual per corner; corner 1 is an outlier with a big residual
        var o =
                obs(
                        new boolean[] {true, false, true},
                        new int[] {4, 5, 6},
                        List.of(new Point(0.1, 0), new Point(3, 4), new Point(0, 0.3)));
        assertTrue(o.residualsAligned());
        assertEquals(0.2, o.meanReprojectionError(), 1e-9);
    }

    @Test
    public void legacyBoardIndexedObservationIsRemapped() {
        // Saved before the fix: flags indexed by board id (ids 4, 5, 6 of 8), residuals only for
        // inliers (so not aligned)
        boolean[] byId = {true, true, true, true, true, false, true, true};
        var o = obs(byId, new int[] {4, 5, 6}, List.of(new Point(0.1, 0), new Point(0, 0.3)));
        assertArrayEquals(new boolean[] {true, false, true}, o.cornersUsed);
        assertFalse(o.residualsAligned());
        assertEquals(0.2, o.meanReprojectionError(), 1e-9);
    }
}
