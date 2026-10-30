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

package org.photonvision.vision.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SpectrumJetson (photonvision-86): a camera with its own serial is known by it. */
public class PVCameraInfoSerialTest {
    private static final String PORT =
            "/dev/v4l/by-path/platform-3610000.usb-usb-0:2.3:1.0-video-index0";

    private static PVCameraInfo camera(String... otherPaths) {
        return new PVCameraInfo.PVUsbCameraInfo(2, "/dev/video2", "Thrifty: ", otherPaths, 7119, 10437);
    }

    @Test
    public void ownSerialWins() {
        var own = "/dev/v4l/by-id/usb-DCX-260513-K_Thrifty_SJ-TC02-video-index0";
        assertEquals(own, camera(own, PORT).uniquePath());
        assertEquals(own, camera(PORT, own).uniquePath());
    }

    @Test
    public void factorySerialKeepsThePort() {
        // Every Thriftiest Cam's factory serial is 01.00.00: the port tells them apart.
        assertEquals(
                PORT,
                camera("/dev/v4l/by-id/usb-DCX-260513-K_Thrifty_01.00.00-video-index0", PORT).uniquePath());
        assertEquals(PORT, camera(PORT).uniquePath());
    }

    @Test
    public void onlyCaptureNodesAndOurSerials() {
        assertTrue(
                PVCameraInfo.hasOwnSerial("/dev/v4l/by-id/usb-DCX-260513-K_Thrifty_SJ-TC01-video-index0"));
        assertFalse(
                PVCameraInfo.hasOwnSerial("/dev/v4l/by-id/usb-DCX-260513-K_Thrifty_SJ-TC01-video-index1"));
        assertFalse(
                PVCameraInfo.hasOwnSerial(
                        "/dev/v4l/by-id/usb-Arducam_Global_Shutter_SN12345-video-index0"));
        assertFalse(PVCameraInfo.hasOwnSerial(PORT));
    }
}
