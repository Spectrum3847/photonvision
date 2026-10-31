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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** SpectrumJetson (photonvision-55): the far search's settings, without the detector library. */
public class FarSearchTest {
    @Test
    public void sweepsAreClamped() {
        assertEquals(1, FarSearch.clampSweeps(0));
        assertEquals(30, FarSearch.clampSweeps(30));
        assertEquals(120, FarSearch.clampSweeps(500));
    }

    @Test
    public void noStatusBeforeTheLibraryIsLoaded() {
        // apply() was never called, so the native library is never touched.
        assertNull(FarSearch.status());
        var m = FarSearch.toMap();
        assertTrue(m.containsKey("farSearch"));
        assertTrue(m.containsKey("farSweepsPerSecond"));
        assertFalse(m.containsKey("farSearchSweeps"));
    }
}
