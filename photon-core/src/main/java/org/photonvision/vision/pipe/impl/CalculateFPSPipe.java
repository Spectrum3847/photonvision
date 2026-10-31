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

import org.photonvision.vision.pipe.CVPipe;
import org.wpilib.system.Timer;

public class CalculateFPSPipe
        extends CVPipe<Void, Integer, CalculateFPSPipe.CalculateFPSPipeParams> {
    // Spectrum: frames counted over time, not a moving average of 1/dt. Averaging 1/dt over-reads
    // whenever frames arrive unevenly (4 ms then 12 ms apart average to 167 fps, though 2 frames in
    // 16 ms is 125): the dashboard showed 130-160 fps from a camera that sends 122. This is the
    // last 30 intervals' frames over their duration, so it can't exceed the real rate.
    private static final int WINDOW = 30;
    private final double[] times = new double[WINDOW + 1];
    private int count = 0, next = 0;

    @Override
    protected Integer process(Void in) {
        double now = Timer.getMonotonicTimestamp();
        times[next] = now;
        next = (next + 1) % times.length;
        if (count < times.length) count++;
        if (count < 2) return 0;
        double oldest = times[(next - count + times.length) % times.length];
        double span = now - oldest;
        return span < 1e-6 ? 0 : (int) Math.round((count - 1) / span);
    }

    public static class CalculateFPSPipeParams {}

    @Override
    public void release() {}
}
