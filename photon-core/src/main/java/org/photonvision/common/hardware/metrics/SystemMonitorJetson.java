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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SpectrumJetson: Jetson metrics (photonvision-20). Fills the "CPU Throttling" row on the Settings
 * page and {@code cpu_thr} in PhotonVision's NetworkTables metrics, like the Raspberry Pi's
 * under-voltage and high-temperature flags. See {@link JetsonThrottle}.
 */
public class SystemMonitorJetson extends SystemMonitor {
    @Override
    public String getCpuThrottleReason() {
        return JetsonThrottle.available() ? JetsonThrottle.reason() : super.getCpuThrottleReason();
    }

    /**
     * Same check as Platform's private isJetsonSBC() (and TeamCameraDefaults.isJetson()); kept here
     * so photon-targeting stays identical to upstream.
     */
    static boolean isJetson() {
        try {
            return Files.readString(Path.of("/proc/device-tree/model")).contains("NVIDIA Jetson");
        } catch (Exception e) {
            return false;
        }
    }
}
