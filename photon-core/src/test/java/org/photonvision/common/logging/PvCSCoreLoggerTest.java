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

package org.photonvision.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SpectrumJetson (photonvision-89): a camera's cscore thread ending is read from cscore's own
 * error, as WPILib 2027 alpha-8 logs it (UsbCameraImpl.cpp, "select(): " after the camera's name).
 */
public class PvCSCoreLoggerTest {
    private static final String FILE =
            "/__w/allwpilib/allwpilib/cscore/src/main/native/linux/UsbCameraImpl.cpp";

    @Test
    public void selectErrorNamesTheCamera() {
        assertEquals(
                "orin-left",
                PvCSCoreLogger.endedCameraThread(40, FILE, "orin-left: select(): Interrupted system call"));
        assertEquals(
                "Arducam OV9281 USB Camera",
                PvCSCoreLogger.endedCameraThread(
                        40, FILE, "Arducam OV9281 USB Camera: select(): Bad file descriptor"));
    }

    @Test
    public void otherMessagesDont() {
        // not an error
        assertNull(
                PvCSCoreLogger.endedCameraThread(30, FILE, "orin-left: select(): Interrupted system call"));
        // another file
        assertNull(
                PvCSCoreLogger.endedCameraThread(
                        40, "/x/HttpCameraImpl.cpp", "cam: select(): Interrupted system call"));
        // a different error from the camera thread
        assertNull(
                PvCSCoreLogger.endedCameraThread(
                        40, FILE, "orin-left: ioctl VIDIOC_STREAMON failed: Protocol error"));
        assertNull(PvCSCoreLogger.endedCameraThread(40, FILE, "select(): no name"));
        assertNull(PvCSCoreLogger.endedCameraThread(40, null, null));
    }

    @Test
    public void endedUntilANewCamera() {
        String name = "PvCSCoreLoggerTest-camera";
        PvCSCoreLogger.clearCameraThreadEnded(name);
        PvCSCoreLogger.noteCameraThread(30, FILE, name + ": set format 1 res 1280x800");
        assertFalse(PvCSCoreLogger.cameraThreadEnded(name));
        PvCSCoreLogger.noteCameraThread(40, FILE, name + ": select(): Interrupted system call");
        assertTrue(PvCSCoreLogger.cameraThreadEnded(name));
        assertFalse(PvCSCoreLogger.cameraThreadEnded("another camera"));
        PvCSCoreLogger.clearCameraThreadEnded(name); // a new UsbCamera with this name
        assertFalse(PvCSCoreLogger.cameraThreadEnded(name));
    }
}
