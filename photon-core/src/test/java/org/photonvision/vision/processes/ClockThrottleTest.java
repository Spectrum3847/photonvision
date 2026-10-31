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

package org.photonvision.vision.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.photonvision.vision.processes.ClockThrottle.Action;
import org.photonvision.vision.processes.ClockThrottle.Policy;

/** photonvision-90: the clock throttle's decisions, on a fake clock. */
public class ClockThrottleTest {
    private static final int AFTER = 20;

    /** Steps every 250 ms (the thread's period) from {@code from} to {@code to}, applying actions. */
    private static int run(Policy p, long from, long to, boolean active, int[] throttles) {
        int fulls = 0;
        for (long t = from; t < to; t += 250) {
            var a = p.step(t, active, AFTER, false);
            if (a == Action.THROTTLE) throttles[0]++;
            if (a == Action.FULL) fulls++;
            p.applied(a, true);
        }
        return fulls;
    }

    @Test
    public void autoGapTeleopNeverThrottles() {
        var p = new Policy(false);
        int[] throttles = {0};
        run(p, 0, 15_000, true, throttles); // auto
        run(p, 15_000, 18_000, false, throttles); // ~3 s disabled
        run(p, 18_000, 153_000, true, throttles); // teleop
        assertEquals(0, throttles[0]);
        assertTrue(!p.throttled());
    }

    @Test
    public void repeatedShortGapsNeverAddUp() {
        var p = new Policy(false);
        int[] throttles = {0};
        long t = 0;
        for (int i = 0; i < 20; i++) {
            run(p, t, t + 15_000, false, throttles);
            run(p, t + 15_000, t + 16_000, true, throttles);
            t += 16_000;
        }
        assertEquals(0, throttles[0]);
    }

    @Test
    public void longDisabledThrottlesOnceAndEnableRestoresAtOnce() {
        var p = new Policy(false);
        int[] throttles = {0};
        run(p, 0, 19_900, false, throttles);
        assertEquals(0, throttles[0]);
        run(p, 19_900, 25_000, false, throttles);
        assertEquals(1, throttles[0]);
        assertTrue(p.throttled());
        // The enable: the very first step (woken by the listener) says full.
        assertEquals(Action.FULL, p.step(25_001, true, AFTER, false));
        p.applied(Action.FULL, true);
        assertTrue(!p.throttled());
        assertEquals(Action.NONE, p.step(25_002, true, AFTER, false));
        // Disabled again: the count starts over.
        assertEquals(Action.NONE, p.step(30_000, false, AFTER, false));
        assertEquals(Action.NONE, p.step(49_000, false, AFTER, false));
        assertEquals(Action.THROTTLE, p.step(50_000, false, AFTER, false));
    }

    @Test
    public void zeroOrModeFullNeverThrottlesAndRestores() {
        var p = new Policy(true); // left throttled by an earlier process
        assertEquals(Action.FULL, p.step(0, false, 0, false));
        p.applied(Action.FULL, true);
        assertEquals(Action.NONE, p.step(100_000, false, 0, false));
        var q = new Policy(false);
        assertEquals(Action.NONE, q.step(0, false, AFTER, true));
        assertEquals(Action.NONE, q.step(100_000, false, AFTER, true));
        // Back to auto after a long time inactive: throttles at once.
        assertEquals(Action.THROTTLE, q.step(100_250, false, AFTER, false));
    }

    @Test
    public void failedThrottleIsRetried() {
        var p = new Policy(false);
        assertEquals(Action.NONE, p.step(0, false, AFTER, false));
        assertEquals(Action.THROTTLE, p.step(20_000, false, AFTER, false));
        p.applied(Action.THROTTLE, false);
        assertTrue(!p.throttled());
        assertEquals(Action.THROTTLE, p.step(20_250, false, AFTER, false));
    }
}
