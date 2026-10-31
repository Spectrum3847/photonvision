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
 * Spectrum (photonvision-91): how well a calibration's corners cover the image. A 10x8 grid of
 * cells counts the corners and the snapshots that put a corner in each; the four 2x2 blocks at the
 * image's corners and the distance from the nearest corner to each image edge are tracked too.
 *
 * <p>Targets (SpectrumJetson tests/calibration-analysis RESULTS.md, "Recalibrate"): every cell at
 * least {@value #CELL_CORNERS} corners from at least {@value #CELL_SNAPSHOTS} snapshots, each
 * corner block at least {@value #BLOCK_CORNERS} corners, and corners within {@value #BORDER_PX} px
 * of every edge. The old calibrations never got near the edges, and the lens model folded out
 * there.
 */
public final class CalibrationCoverage {
    public static final int COLS = 10;
    public static final int ROWS = 8;
    public static final int CELL_CORNERS = 50;
    public static final int CELL_SNAPSHOTS = 3;
    public static final int BLOCK_CORNERS = 150;
    public static final double BORDER_PX = 10;

    private final double width, height;
    private final int[] counts = new int[COLS * ROWS];
    private final int[] snapshots = new int[COLS * ROWS];
    // Nearest corner to the left, top, right and bottom edges, pixels
    private final double[] border = {
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY
    };
    private int snapshotCount = 0;

    public CalibrationCoverage(double width, double height) {
        this.width = width;
        this.height = height;
    }

    /** The cell (row-major) a pixel falls in, or -1 if it's outside the image. */
    public int cellOf(double x, double y) {
        if (!(x >= 0 && y >= 0 && x < width && y < height)) return -1;
        int c = Math.min(COLS - 1, (int) (x * COLS / width));
        int r = Math.min(ROWS - 1, (int) (y * ROWS / height));
        return r * COLS + c;
    }

    public static boolean isRing(int cell) {
        int c = cell % COLS, r = cell / COLS;
        return c == 0 || r == 0 || c == COLS - 1 || r == ROWS - 1;
    }

    /**
     * Which image-corner 2x2 block (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right) or -1.
     */
    public static int blockOf(int cell) {
        int c = cell % COLS, r = cell / COLS;
        boolean left = c < 2, right = c >= COLS - 2, top = r < 2, bottom = r >= ROWS - 2;
        if (top && left) return 0;
        if (top && right) return 1;
        if (bottom && left) return 2;
        if (bottom && right) return 3;
        return -1;
    }

    public boolean cellMet(int cell) {
        return counts[cell] >= CELL_CORNERS && snapshots[cell] >= CELL_SNAPSHOTS;
    }

    /** Adds one snapshot's corners (x[i], y[i]). */
    public void add(double[] x, double[] y) {
        boolean[] seen = new boolean[COLS * ROWS];
        for (int i = 0; i < x.length; i++) {
            int cell = cellOf(x[i], y[i]);
            if (cell < 0) continue;
            counts[cell]++;
            seen[cell] = true;
            border[0] = Math.min(border[0], x[i]);
            border[1] = Math.min(border[1], y[i]);
            border[2] = Math.min(border[2], width - 1 - x[i]);
            border[3] = Math.min(border[3], height - 1 - y[i]);
        }
        for (int c = 0; c < seen.length; c++) if (seen[c]) snapshots[c]++;
        snapshotCount++;
    }

    /**
     * What a snapshot would add: corners landing in cells still short of their target, plus a bonus
     * for each edge it would bring closer than the border target.
     */
    public int gain(double[] x, double[] y) {
        int g = 0;
        boolean[] seen = new boolean[COLS * ROWS];
        double[] b = border.clone();
        for (int i = 0; i < x.length; i++) {
            int cell = cellOf(x[i], y[i]);
            if (cell < 0) continue;
            if (counts[cell] < CELL_CORNERS) g++;
            else if (snapshots[cell] < CELL_SNAPSHOTS && !seen[cell]) g += 5;
            int block = blockOf(cell);
            if (block >= 0 && blockCount(block) < BLOCK_CORNERS) g++;
            seen[cell] = true;
            b[0] = Math.min(b[0], x[i]);
            b[1] = Math.min(b[1], y[i]);
            b[2] = Math.min(b[2], width - 1 - x[i]);
            b[3] = Math.min(b[3], height - 1 - y[i]);
        }
        for (int s = 0; s < 4; s++) if (border[s] > BORDER_PX && b[s] < border[s]) g += 10;
        return g;
    }

    public int blockCount(int block) {
        int n = 0;
        for (int cell = 0; cell < counts.length; cell++) if (blockOf(cell) == block) n += counts[cell];
        return n;
    }

    /**
     * The cell to fill next: the one furthest from its target (corners and snapshots), ring and
     * corner-block cells first when tied, since those are what calibrations miss. When every cell is
     * done, the weakest corner block's outermost cell, then a ring cell on an edge that's still too
     * far from the corners. -1 when everything is met.
     */
    public int emptiestCell() {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int cell = 0; cell < counts.length; cell++) {
            if (cellMet(cell)) continue;
            double score =
                    Math.min((double) counts[cell] / CELL_CORNERS, (double) snapshots[cell] / CELL_SNAPSHOTS);
            if (blockOf(cell) >= 0) score -= 0.02;
            if (isRing(cell)) score -= 0.01;
            if (score < bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        if (best >= 0) return best;
        int weakBlock = -1;
        int weakCount = BLOCK_CORNERS;
        for (int b = 0; b < 4; b++) {
            int n = blockCount(b);
            if (n < weakCount) {
                weakCount = n;
                weakBlock = b;
            }
        }
        if (weakBlock >= 0) {
            int c = (weakBlock % 2 == 0) ? 0 : COLS - 1;
            int r = (weakBlock < 2) ? 0 : ROWS - 1;
            return r * COLS + c;
        }
        for (int s = 0; s < 4; s++) {
            if (border[s] <= BORDER_PX) continue;
            return switch (s) {
                case 0 -> (ROWS / 2) * COLS;
                case 1 -> COLS / 2;
                case 2 -> (ROWS / 2) * COLS + COLS - 1;
                default -> (ROWS - 1) * COLS + COLS / 2;
            };
        }
        return -1;
    }

    /**
     * @param counts corners per cell, row-major
     * @param snapshots snapshots with a corner in each cell
     * @param blocks corners in the top-left, top-right, bottom-left and bottom-right 2x2 blocks
     * @param borderPx nearest corner to the left, top, right and bottom edges (-1: none yet)
     * @param fraction share of cells meeting their target
     */
    @Json
    public record Summary(
            int cols,
            int rows,
            int[] counts,
            int[] snapshots,
            int[] blocks,
            double[] borderPx,
            int cellsMet,
            int emptyCells,
            double fraction,
            boolean blocksMet,
            boolean borderMet,
            boolean complete,
            int nextCell,
            int snapshotCount,
            int cellCorners,
            int cellSnapshots,
            int blockCorners,
            double borderTargetPx) {}

    public Summary summary() {
        int met = 0, empty = 0;
        for (int cell = 0; cell < counts.length; cell++) {
            if (cellMet(cell)) met++;
            if (counts[cell] == 0) empty++;
        }
        int[] blocks = new int[4];
        boolean blocksMet = true;
        for (int b = 0; b < 4; b++) {
            blocks[b] = blockCount(b);
            blocksMet &= blocks[b] >= BLOCK_CORNERS;
        }
        double[] bp = new double[4];
        boolean borderMet = true;
        for (int s = 0; s < 4; s++) {
            bp[s] = Double.isInfinite(border[s]) ? -1 : border[s];
            borderMet &= !Double.isInfinite(border[s]) && border[s] <= BORDER_PX;
        }
        int total = counts.length;
        return new Summary(
                COLS,
                ROWS,
                counts.clone(),
                snapshots.clone(),
                blocks,
                bp,
                met,
                empty,
                (double) met / total,
                blocksMet,
                borderMet,
                met == total && blocksMet && borderMet,
                emptiestCell(),
                snapshotCount,
                CELL_CORNERS,
                CELL_SNAPSHOTS,
                BLOCK_CORNERS,
                BORDER_PX);
    }
}
