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

/** SpectrumJetson (photonvision-102). */
public class CameraThreadsTest {
    @Test
    public void parsesCpuLists() {
        assertEquals("{2, 3, 4, 5}", CameraThreads.parse("2-5").toString());
        assertEquals("{1, 3, 4}", CameraThreads.parse("1,3-4").toString());
        assertEquals("{0}", CameraThreads.parse(" 0 ").toString());
        assertTrue(CameraThreads.parse("x-2").isEmpty());
        assertTrue(CameraThreads.parse(null).isEmpty());
        assertEquals("0-1", CameraThreads.toList(CameraThreads.parse("0,1")));
        assertEquals("0,2-4,6", CameraThreads.toList(CameraThreads.parse("0,2-4,6")));
    }

    @Test
    public void readsOwnAffinity() {
        // Linux only: the test JVM's own mask
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) return;
        var cpus = CameraThreads.cpusAllowed("self");
        assertNotNull(cpus);
        assertFalse(CameraThreads.parse(cpus).isEmpty());
    }
}
