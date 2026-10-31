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

import java.util.function.BooleanSupplier;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;
import org.wpilib.networktables.StringPublisher;

/**
 * SpectrumJetson (photonvision-83): PhotonVision's log lines on {@code /photonvision/jetson/log},
 * so robot code can keep them in the robot's log. In quiet mode the Jetson's own logs live in RAM
 * and are lost when the robot is switched off; this is the copy that survives.
 *
 * <p>Warnings and errors always; every line at INFO while quiet (the window otherwise lost). At
 * most {@link #MAX_PER_S} lines a second, then a count of the dropped ones.
 */
public final class JetsonLogMirror {
    static final int MAX_PER_S = 20;

    private static volatile StringPublisher pub;
    private static volatile BooleanSupplier quiet = () -> false;
    private static final ThreadLocal<Boolean> inside = ThreadLocal.withInitial(() -> false);
    private static long windowStartMs = 0;
    private static int sentInWindow = 0;
    private static int dropped = 0;

    private JetsonLogMirror() {}

    /**
     * Once NetworkTables is up (the logger runs long before it). {@code quietNow} says whether quiet
     * mode is on (INFO lines go out then too); calling again only replaces it, so quiet mode can hand
     * over its own once it starts.
     */
    public static synchronized void start(NetworkTableInstance nt, BooleanSupplier quietNow) {
        quiet = quietNow;
        if (pub != null) return;
        pub =
                nt.getStringTopic("/photonvision/jetson/log")
                        .publish(PubSubOption.SEND_ALL, PubSubOption.KEEP_DUPLICATES);
    }

    /** Whether a line at this level goes out now (checked before it's formatted). */
    static boolean wants(LogLevel level) {
        if (pub == null || inside.get() || level.code > LogLevel.INFO.code) return false;
        return level != LogLevel.INFO || quiet.getAsBoolean();
    }

    static void accept(String line) {
        inside.set(true); // publishing must not log its way back here
        try {
            String out = admit(line);
            if (out != null) pub.set(out);
        } finally {
            inside.set(false);
        }
    }

    /** The line to publish, with any dropped count before it, or null to drop it. */
    private static synchronized String admit(String line) {
        long now = System.nanoTime() / 1_000_000;
        if (now - windowStartMs >= 1000) {
            windowStartMs = now;
            sentInWindow = 0;
        }
        if (sentInWindow >= MAX_PER_S) {
            dropped++;
            return null;
        }
        sentInWindow++;
        if (dropped == 0) return line;
        String out = "(" + dropped + " lines dropped) " + line;
        dropped = 0;
        return out;
    }
}
