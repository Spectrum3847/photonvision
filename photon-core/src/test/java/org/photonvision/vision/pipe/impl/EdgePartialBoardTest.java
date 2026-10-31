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

package org.photonvision.vision.pipe.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Spectrum (photonvision-91): small partial boards at the image edge are kept. */
public class EdgePartialBoardTest {
    // 12x9 squares: 11 inner corners per row
    private static final int PER_ROW = 11;

    @Test
    public void cornerOfTheImageTwoByThree() {
        // Six corners, 2 rows x 3 columns, in the image's top-left corner
        float[] xy = {5, 5, 25, 5, 45, 5, 5, 25, 25, 25, 45, 25};
        int[] ids = {0, 1, 2, 11, 12, 13};
        assertTrue(FindBoardCornersPipe.edgePartialOk(xy, ids, PER_ROW, 1280, 800));
    }

    @Test
    public void aLineIsRejected() {
        float[] xy = {5, 5, 25, 5, 45, 5, 65, 5, 85, 5, 105, 5};
        int[] ids = {0, 1, 2, 3, 4, 5};
        assertFalse(FindBoardCornersPipe.edgePartialOk(xy, ids, PER_ROW, 1280, 800));
    }

    @Test
    public void smallPartialInTheMiddleIsRejected() {
        float[] xy = {600, 400, 620, 400, 640, 400, 600, 420, 620, 420, 640, 420};
        int[] ids = {0, 1, 2, 11, 12, 13};
        assertFalse(FindBoardCornersPipe.edgePartialOk(xy, ids, PER_ROW, 1280, 800));
    }
}
