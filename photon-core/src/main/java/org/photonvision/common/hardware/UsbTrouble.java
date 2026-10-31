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

package org.photonvision.common.hardware;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * SpectrumJetson (photonvision-31): USB trouble the kernel reports, by USB port (e.g. "1-2.4"), for
 * the cameras to explain why they get no frames. Fed every kernel log line by KernelLogLogger.
 *
 * <ul>
 *   <li><b>Not enough bandwidth:</b> the camera asked to reserve more USB 2.0 bandwidth than its
 *       root port has left, so it can't stream. A reset won't fix it; capping it (payload_cap) or
 *       moving it to another root port will. scripts/jetson/usb-bandwidth.py says which.
 *   <li><b>Couldn't be connected:</b> something plugged into a port couldn't be read or addressed
 *       (a bad cable or adapter, a plug not fully in, not enough power), or the port hit
 *       over-current.
 * </ul>
 */
public final class UsbTrouble {
    private static final Pattern BANDWIDTH =
            Pattern.compile("usb (\\d+-[\\d.]+): Not enough bandwidth");
    private static final Pattern CONNECT =
            Pattern.compile(
                    "usb (\\d+-[\\d.]+)(?:-port(\\d+))?: (device descriptor read|device not accepting"
                            + " address|Device not responding to setup address|unable to enumerate USB"
                            + " device|over-current)");
    private static final Map<String, Long> bandwidthNs = new ConcurrentHashMap<>();
    private static final Map<String, Long> connectNs = new ConcurrentHashMap<>();

    private UsbTrouble() {}

    /** One kernel log line. */
    public static void noteKernelLine(String line) {
        if (line == null || !line.contains("usb ")) return;
        var m = BANDWIDTH.matcher(line);
        if (m.find()) {
            bandwidthNs.put(m.group(1), System.nanoTime());
            return;
        }
        m = CONNECT.matcher(line);
        if (m.find()) {
            String port = m.group(2) != null ? m.group(1) + "." + m.group(2) : m.group(1);
            connectNs.put(port, System.nanoTime());
        }
    }

    /** Whether the kernel refused this USB port bandwidth within the last {@code withinNs}. */
    public static boolean bandwidthFailed(String port, long withinNs) {
        Long t = port == null ? null : bandwidthNs.get(port);
        return t != null && System.nanoTime() - t < withinNs;
    }

    /** Whether something on this USB port failed to connect within the last {@code withinNs}. */
    public static boolean connectFailed(String port, long withinNs) {
        Long t = port == null ? null : connectNs.get(port);
        return t != null && System.nanoTime() - t < withinNs;
    }
}
