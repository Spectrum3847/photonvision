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

import java.util.Arrays;

/**
 * Spectrum (photonvision-91): line up per-corner flags with the detections they belong to.
 *
 * <p>PhotonVision's observations are indexed by <b>detection</b> (the corners that were seen, in
 * the order the detector returned them). The photon-mrcal JNI in use (dev-v2027.0.2) returns
 * {@code cornersUsed} per detection too, and that is used as is. A board-indexed array (one
 * slot per board corner id, a different length from the detections: older builds, and the 2026
 * calibrations the SpectrumJetson tests/calibration-analysis looked at) is remapped by id. The
 * residuals are what didn't line up: upstream's list skipped the outliers, so from the first
 * outlier on, residual i belonged to a later corner (fixed in Calibrate3dPipe).
 */
public final class CornerAlignment {
    private CornerAlignment() {}

    /**
     * mrcal's board-indexed flags to per-detection flags.
     *
     * @param usedByBoardId one flag per board corner id
     * @param ids each detection's board corner id, or null when the whole board was seen in id order
     *     (then detection i is corner i)
     * @param detections number of detections
     */
    public static boolean[] perDetection(boolean[] usedByBoardId, int[] ids, int detections) {
        boolean[] out = new boolean[detections];
        for (int i = 0; i < detections; i++) {
            int id = ids != null ? ids[i] : i;
            out[i] = usedByBoardId != null && id >= 0 && id < usedByBoardId.length && usedByBoardId[id];
        }
        return out;
    }

    /**
     * Flags loaded from a saved calibration: those written before this fix by mrcal with a partial
     * board are board-indexed (a different length from the detections) and are remapped; flags that
     * already have one entry per detection are kept.
     */
    public static boolean[] normalizeLoaded(boolean[] used, int[] ids, int detections) {
        if (used == null) {
            boolean[] all = new boolean[detections];
            Arrays.fill(all, true);
            return all;
        }
        if (used.length == detections) return used;
        if (ids != null && ids.length == detections) return perDetection(used, ids, detections);
        // Unknown layout: don't guess, keep as many as line up and mark the rest unused
        return Arrays.copyOf(used, detections);
    }
}
