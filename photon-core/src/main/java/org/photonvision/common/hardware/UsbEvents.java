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

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * SpectrumJetson (photonvision-82): USB trouble in the kernel log, counted for
 * /photonvision/jetson/usbEvents and lastUsbEvent. KernelLogLogger feeds it every kernel line.
 *
 * <p>In the 2026 tree this lived in UsbTrouble (photonvision-31, camera reliability); it's its own
 * class here so the diagnostics don't depend on that feature.
 */
public final class UsbEvents {
    /**
     * Any USB trouble worth a line in the robot's log. TXMAN1 Q13's camera death was "disabled by hub
     * (EMI?)", "USB disconnect", "device descriptor read ... error -110", "unable to enumerate USB
     * device".
     */
    private static final Pattern EVENT =
            Pattern.compile(
                    "usb \\d+-[\\d.]+(?:-port\\d+)?: (?:USB disconnect|disabled by hub|device descriptor read"
                            + "|device not accepting address|Device not responding|unable to enumerate"
                            + "|over-current|attempt power cycle|Not enough bandwidth|reset (?:high|full|super)"
                            + "-speed)|uvcvideo.*(?:[Ee]rror|[Ff]ailed|Non-zero status)|xhci.*(?:died|not"
                            + " responding|[Ee]rror|Babble)|HC died");

    /** Syslog's "Oct 15 15:27:11 host kernel: [ 224.500901] " in front of the message. */
    private static final Pattern PREFIX = Pattern.compile("^.*?kernel: (?:\\[\\s*[\\d.]+\\] )?");

    private static final AtomicLong events = new AtomicLong();
    private static volatile String lastEvent = null;

    private UsbEvents() {}

    /** Whether a kernel line is USB trouble (see {@link #EVENT}). */
    public static boolean isEvent(String line) {
        return line != null && EVENT.matcher(line).find();
    }

    /** One kernel log line. */
    public static void noteKernelLine(String line) {
        if (isEvent(line)) {
            events.incrementAndGet();
            lastEvent = PREFIX.matcher(line).replaceFirst("");
        }
    }

    /** USB trouble lines since PhotonVision started. */
    public static long events() {
        return events.get();
    }

    /** The latest USB trouble line, without its syslog prefix; null if none. */
    public static String lastEvent() {
        return lastEvent;
    }
}
