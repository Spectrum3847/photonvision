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

package org.photonvision.common.hardware.metrics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** SpectrumJetson (photonvision-82). */
public class InputVoltageTest {
    @Test
    public void nothingBeforeTheFirstSample() {
        var v = new InputVoltage().takeSecond();
        assertTrue(Double.isNaN(v[0]) && Double.isNaN(v[1]) && Double.isNaN(v[2]));
    }

    @Test
    public void aShortDipShowsInTheSecondsMinimumAndTheLowest() {
        var iv = new InputVoltage();
        // One second at 20 ms: 19 V, with one 40 ms dip to 7.2 V in the middle.
        for (int i = 0; i < 50; i++) iv.sample(i == 24 || i == 25 ? 7200 : 19040);
        var v = iv.takeSecond();
        assertEquals(19.04, v[0], 1e-9, "latest");
        assertEquals(7.2, v[1], 1e-9, "lowest this second");
        assertEquals(7.2, v[2], 1e-9, "lowest since start");

        // The next second is steady: its minimum resets, the lowest since start doesn't.
        for (int i = 0; i < 50; i++) iv.sample(19000);
        v = iv.takeSecond();
        assertEquals(19.0, v[1], 1e-9);
        assertEquals(7.2, v[2], 1e-9);

        // No samples this second (the sampler held up): no minimum to publish.
        assertTrue(Double.isNaN(iv.takeSecond()[1]));
    }
}
