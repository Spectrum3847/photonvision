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

package org.photonvision.common.util;

import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.wpilib.networktables.NetworkTablesJNI;

/**
 * SpectrumJetson (photonvision-80): a log line pairing the Jetson's clocks, so tools can place any
 * journal line (stamped on the kernel's monotonic clock) on jetson_us and the robot's clock. Every
 * 60 s, and when the robot connects, disconnects or the time-sync offset moves by more than 1 ms.
 *
 * <p>In the 2026 tree this ran from Rewind's 1 Hz tick; here it has its own 1 Hz task, so it
 * doesn't depend on Rewind.
 *
 * <p>WPILib 2027's NetworkTables clock and time-sync offset are in nanoseconds; the line keeps
 * 2026's microsecond fields ({@code jetson_us}, {@code robot_offset_us}), which SpectrumJetson's
 * tools parse.
 */
public final class ClockMarks {
    private static final Logger logger = new Logger(ClockMarks.class, LogGroup.General);

    static final long EVERY_NS = 60_000_000_000L;
    static final long OFFSET_MOVE_NS = 1_000_000L;

    private static long lastMarkNs = Long.MIN_VALUE;
    private static long lastMarkOffsetNs;
    private static boolean lastMarkConnected;

    private ClockMarks() {}

    public static void start() {
        TimedTaskManager.getInstance().addTask("ClockMarks", ClockMarks::tick, 1000);
    }

    private static void tick() {
        try {
            var ntm = NetworkTablesManager.getInstance();
            mark(NetworkTablesJNI.now(), ntm.getNTInst().isConnected(), ntm.getOffset());
        } catch (RuntimeException e) {
            logger.error("Clock mark failed", e);
        }
    }

    private static synchronized void mark(long nowNs, boolean connected, long offsetNs) {
        if (lastMarkNs != Long.MIN_VALUE
                && nowNs - lastMarkNs < EVERY_NS
                && connected == lastMarkConnected
                && Math.abs(offsetNs - lastMarkOffsetNs) <= OFFSET_MOVE_NS) return;
        lastMarkNs = nowNs;
        lastMarkOffsetNs = offsetNs;
        lastMarkConnected = connected;
        logger.info(
                String.format(
                        "Clock mark: jetson_us=%d mono_us=%d unix_ms=%d robot_offset_us=%d nt=%d",
                        nowNs / 1000,
                        System.nanoTime() / 1000, // CLOCK_MONOTONIC, the journal's monotonic stamp
                        System.currentTimeMillis(),
                        offsetNs / 1000,
                        connected ? 1 : 0));
    }
}
