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

import java.io.File;
import java.time.Instant;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.hardware.Platform;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.wpilib.networktables.IntegerSubscriber;
import org.wpilib.networktables.NetworkTablesJNI;

/**
 * SpectrumJetson (photonvision-08): sets the Jetson's date and time from the robot's.
 *
 * <p>The Jetson has no clock battery, so after every power cut its clock restarts at 1970, and at
 * events (Wi-Fi off) nothing on the network corrects it. The robot's clock is set by the Driver
 * Station, so robot code publishes it: {@code /photonvision/clock/unixMs} (integer, milliseconds
 * since 1970 UTC), only once it's valid, e.g. every loop or once a second.
 *
 * <p>This only fixes human-facing times: Rewind recording names, the system log, file dates. Vision
 * doesn't use the date: frame timestamps, PhotonVision's time sync with the robot and Rewind's
 * frame times all use a monotonic clock that setting the date doesn't touch.
 *
 * <p>Checked once a second. The clock is set when it's more than {@link #MAX_ERROR_MS} off, from a
 * value received within the last few seconds that looks like a real date, and only if the Jetson
 * hasn't synced to internet time this boot (internet time wins; at events there is none). After
 * setting it, systemd-timesyncd's clock file is touched, so the next boot (after a power cut)
 * starts from about this time instead of 1970.
 *
 * <p>Off on the SystemCore (and a roboRIO): there PhotonVision runs on the robot, whose clock this
 * is. {@link #source()} reads "robot" there.
 */
public class RobotClockSync {
    private static final Logger logger = new Logger(RobotClockSync.class, LogGroup.General);

    /** Set the clock when it's this far off. */
    private static final long MAX_ERROR_MS = 1000;

    // WPILib 2027: NetworkTables times (nt::Now, a value's timestamp) are in nanoseconds.

    /** Ignore a value this old (the robot stopped publishing). */
    private static final long MAX_AGE_NS = 5_000_000_000L;

    /** Don't set the clock more often than this (a robot publishing nonsense can't thrash it). */
    private static final long MIN_INTERVAL_NS = 30_000_000_000L;

    /** 2026-01-01 UTC: anything earlier isn't a valid robot clock yet. */
    private static final long EARLIEST_MS = 1_767_225_600_000L;

    /** 2100-01-01 UTC. */
    private static final long LATEST_MS = 4_102_444_800_000L;

    private static final File TIMESYNCD_CLOCK = new File("/var/lib/systemd/timesync/clock");

    /** Created by systemd-timesyncd once it has synced to internet (NTP) time this boot. */
    private static final File NTP_SYNCED = new File("/run/systemd/timesync/synchronized");

    private static boolean loggedNtp = false;

    private static IntegerSubscriber sub;
    private static long lastSetNs = 0;
    private static volatile boolean onTheRobot = false;

    // photonvision-82: for /photonvision/jetson/clockSource and clockLastJumpSeconds.
    private static volatile boolean setFromRobot = false;
    private static volatile double lastJumpSeconds = 0;

    /**
     * Where this boot's clock came from: "internet" (NTP), "robot" (set from robot code's clock at
     * least once, or checked against it and within {@link #MAX_ERROR_MS}), or "unset" (still the date
     * restored at boot: whatever was saved last, possibly long ago). TXMAN1: several boots stayed
     * "unset" all match, so their logs carried a wrong date.
     *
     * <p>photonvision-84: "robot" also when the clock already agrees with the robot's. After a
     * PhotonVision restart the clock was right, so it was never set, and this read "unset" with a
     * correct clock (robot code's bench check, 2026-10-29). {@link #lastJumpSeconds()} stays 0 then.
     */
    public static String source() {
        if (onTheRobot) return "robot";
        if (NTP_SYNCED.exists()) return "internet";
        return setFromRobot ? "robot" : "unset";
    }

    /** Seconds the last set from the robot moved the clock (+ forward), 0 if never. */
    public static double lastJumpSeconds() {
        return lastJumpSeconds;
    }

    /**
     * Whether this is the robot controller itself (SystemCore, roboRIO): its clock is the robot's.
     * The SystemCore is a QCS6490; the other QCS6490 board PhotonVision knows, the Rubik Pi, is a
     * coprocessor and keeps the sync.
     */
    static boolean isRobotController() {
        if (Platform.isAthena()) return true;
        if (!Platform.isQCS6490()) return false;
        try {
            String model =
                    java.nio.file.Files.readString(java.nio.file.Path.of("/proc/device-tree/model"));
            return !model.toUpperCase().contains("RUBIK");
        } catch (Exception e) {
            return true;
        }
    }

    public static synchronized void start() {
        if (sub != null || onTheRobot) return;
        if (!Platform.isLinux() || isRobotController()) {
            onTheRobot = isRobotController();
            if (onTheRobot) logger.info("Robot clock sync off: this is the robot controller");
            return;
        }
        sub =
                NetworkTablesManager.getInstance()
                        .getNTInst()
                        .getIntegerTopic("/photonvision/clock/unixMs")
                        .subscribe(0);
        TimedTaskManager.getInstance().addTask("RobotClockSync", RobotClockSync::tick, 1000);
    }

    private static void tick() {
        try {
            if (!NetworkTablesManager.getInstance().getNTInst().isConnected()) return;
            var v = sub.getAtomic();
            long robotMs = v.value;
            if (robotMs < EARLIEST_MS || robotMs > LATEST_MS) return; // not published, or not valid
            long nowNs = NetworkTablesJNI.now();
            long ageNs = nowNs - v.timestamp; // v.timestamp: when it arrived, in the same clock (ns)
            if (ageNs < 0 || ageNs > MAX_AGE_NS) return;
            long robotNowMs = robotMs + ageNs / 1_000_000;
            long errorMs = robotNowMs - System.currentTimeMillis();
            if (Math.abs(errorMs) <= MAX_ERROR_MS) {
                setFromRobot = true; // photonvision-84: checked against the robot's, and right
                return;
            }
            // Internet time wins: with NTP (shop Wi-Fi), timesyncd would undo our change at once
            // and the two would fight. The robot's clock is for when there's no internet (events).
            if (NTP_SYNCED.exists()) {
                if (!loggedNtp) {
                    loggedNtp = true;
                    logger.info(
                            "Robot clock is "
                                    + errorMs / 1000.0
                                    + " s from ours; not using it, this Jetson has internet time");
                }
                return;
            }
            if (lastSetNs != 0 && nowNs - lastSetNs < MIN_INTERVAL_NS) return;
            lastSetNs = nowNs;
            set(robotNowMs, errorMs);
        } catch (Exception e) {
            logger.error("RobotClockSync failed", e);
        }
    }

    private static void set(long unixMs, long errorMs) throws Exception {
        String was = Instant.ofEpochMilli(System.currentTimeMillis()).toString();
        String seconds = String.format("@%d.%03d", unixMs / 1000, unixMs % 1000);
        var p =
                new ProcessBuilder("date", "-u", "-s", seconds)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (p.waitFor() != 0) {
            logger.error("RobotClockSync: 'date -s' failed (exit " + p.exitValue() + ")");
            return;
        }
        setFromRobot = true;
        lastJumpSeconds = errorMs / 1000.0;
        // Next boot starts from about now, not 1970 (timesyncd moves the clock up to this file's
        // modification time at boot).
        if (TIMESYNCD_CLOCK.exists() && !TIMESYNCD_CLOCK.setLastModified(unixMs)) {
            logger.warn("RobotClockSync: could not update " + TIMESYNCD_CLOCK);
        }
        logger.info(
                "Clock set from the robot: was "
                        + was
                        + ", now "
                        + Instant.ofEpochMilli(unixMs)
                        + " ("
                        + (errorMs > 0 ? "+" : "")
                        + errorMs / 1000.0
                        + " s)");
    }
}
