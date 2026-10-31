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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.JetsonLogMirror;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.BooleanSubscriber;
import org.wpilib.networktables.StringPublisher;

/**
 * SpectrumJetson (photonvision-56): quiet mode. Power cuts only hurt an SSD while it's being
 * written, and a robot is switched off minutes after every match. So once a match has ended (field
 * connected, teleop enabled, then disabled), or the robot has been disabled for
 * quietAfterDisabledSeconds, the Jetson stops writing to its scratch partition: Rewind stops,
 * PhotonVision's log file closes (the log keeps going to the console and the UI), and
 * /usr/local/bin/spectrum-quiet moves the system log to RAM and remounts the scratch partition
 * read-only. The cameras keep running and tags are still found. Any enable leaves quiet mode at
 * once.
 *
 * <p>Robot code can decide instead: {@code /photonvision/jetson/quiet} (boolean, robot sets) forces
 * it on or off while published. The Jetson publishes {@code quietNow} and {@code quietReason}.
 *
 * <p>A recording someone asked for always wins: while field calibration, the web UI's Record now or
 * robot code's {@code /photonvision/rewind/record} wants one, quiet mode doesn't start, and it ends
 * if it was on.
 *
 * <p>The settings partition isn't touched: it's only written when a setting changes, and then
 * straight to the SSD. Off the robot (no NetworkTables connection) nothing happens, and without the
 * helper (the SystemCore, a Jetson set up without 10-data-partition.sh) quiet mode never starts:
 * only {@code quietNow} (false) is published.
 *
 * <p>Rewind (feature 7) isn't ported yet. When it is, it plugs in at two hook points: it calls
 * {@link #setRecordingRequested} with its RewindManager.recordingRequested, and its 5 Hz tick
 * neither records nor tidies old recordings while {@link #quiet()} (2026's photonvision-56 and 65
 * changes to RewindManager, which wait for it).
 */
public final class QuietMode {
    private static final Logger logger = new Logger(QuietMode.class, LogGroup.General);
    private static final Path HELPER = Path.of("/usr/local/bin/spectrum-quiet");
    // The helper's own record of quiet mode (it survives a PhotonVision restart), and the partition.
    private static final Path STATE = Path.of("/run/spectrum-quiet");
    private static final Path SCRATCH = Path.of("/data/scratch");

    private static volatile boolean quiet = false;
    private static volatile String reason = "";
    private static volatile String lastError = "";
    private static volatile boolean matchEnded = false;
    // photonvision-83: the first 20 s after a match still reach the SSD (JetsonLogMirror's notes).
    private static final long AFTER_MATCH_MS = 20_000;
    private static volatile long disabledSinceMs = 0;
    private static volatile long retryAfterMs = 0;
    private static volatile boolean started = false;

    private static BooleanSubscriber robotSays;
    private static BooleanPublisher quietPub;
    private static StringPublisher reasonPub;

    private QuietMode() {}

    /** True while the scratch partition must not be written (Rewind checks this). */
    public static boolean quiet() {
        return quiet;
    }

    // Hook for Rewind (feature 7): whether someone wants a recording right now (the web UI's Record
    // now, field calibration, or robot code's /photonvision/rewind/record). Nothing until then.
    private static volatile BooleanSupplier recordingRequested = () -> false;

    /**
     * For Rewind (feature 7): quiet mode never starts while this says true, and ends if it was on.
     */
    public static void setRecordingRequested(BooleanSupplier requested) {
        recordingRequested = requested;
    }

    public static Map<String, Object> status() {
        var m = new HashMap<String, Object>();
        m.put("quietNow", quiet);
        m.put("quietReason", reason);
        m.put("quietError", lastError);
        m.put("quietHelper", Files.isExecutable(HELPER));
        m.put("quietLastLeaveMs", lastLeaveMs);
        return m;
    }

    /** Called from IdleMode once NetworkTables and the driver-station listener exist. */
    public static synchronized void start() {
        if (started) return;
        var ntm = NetworkTablesManager.getInstance();
        var ds = ntm.getDriverStation();
        if (ds == null) return;
        // photonvision-83: the log mirror sends INFO lines too while quiet (Main started it with
        // "never quiet"; calling start again only replaces the check).
        JetsonLogMirror.start(ntm.getNTInst(), QuietMode::quiet);
        started = true;
        var t = ntm.getNTInst().getTable("/photonvision/jetson");
        robotSays = t.getBooleanTopic("quiet").subscribe(false);
        quietPub = t.getBooleanTopic("quietNow").publish();
        reasonPub = t.getStringTopic("quietReason").publish();
        // photonvision-64: an earlier PhotonVision process may have left the scratch partition
        // read-only (quiet mode, then a restart: a crash, a settings import, Restart in the UI).
        // Starting as "not quiet" left it that way: no enable remounted it, and Rewind couldn't
        // record any later match until a reboot. So take over the real state; the loop then leaves
        // quiet mode at once unless it's wanted now (any enable, a recording asked for, or the
        // robot disabled for less than quietAfterDisabledSeconds).
        if (Files.exists(STATE) || scratchReadOnly()) {
            quiet = true;
            reason = "left on before PhotonVision restarted";
            Logger.pauseFileLogging();
            logger.warn(
                    "Quiet mode: the scratch partition was left read-only before this restart; taking it over");
        }
        quietPub.set(quiet);
        reasonPub.set(reason);
        if (!quiet && !Files.isExecutable(HELPER)) {
            logger.info("Quiet mode off: " + HELPER + " isn't installed (10-data-partition.sh)");
            return;
        }
        ds.addTransitionListener(
                (old, now) -> {
                    if (now.m_enabled()) {
                        matchEnded = false;
                        disabledSinceMs = 0;
                        // Leave quiet mode now, not at the next 200 ms check, so a recording robot
                        // code starts at enable loses as little as possible. Off this thread.
                        if (quiet && !robotSays.exists()) wake();
                    } else if (old.m_enabled()) {
                        disabledSinceMs = nowMs();
                        // The end of a match: teleop on the field, then disabled.
                        if (!old.m_autonomous() && now.m_fmsAttached()) matchEnded = true;
                    }
                });
        var th = new Thread(QuietMode::loop, "SpectrumQuietMode");
        th.setDaemon(true);
        th.start();
        logger.info(
                "Quiet mode ready: the SSD stops being written 20 s after a match, or after "
                        + IdleMode.getSettings().quietAfterDisabledSeconds
                        + " s disabled");
    }

    private static final Object wakeLock = new Object();

    private static void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    private static void loop() {
        while (true) {
            try {
                step();
                synchronized (wakeLock) {
                    wakeLock.wait(200);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                logger.error("Quiet mode", e);
            }
        }
    }

    /** What quiet mode should be now, and why ("" = not quiet). */
    private static String wanted() {
        // A recording someone asked for (field calibration, Record now, robot code) always wins:
        // never quiet while one is wanted, even if robot code also asks for quiet.
        if (recordingRequested.getAsBoolean()) {
            wasRecording = true;
            return "";
        }
        if (wasRecording) {
            // A requested recording just ended: the disabled-for timer starts again from now, so a
            // long field calibration recording doesn't go quiet the moment it stops. (A match that
            // ended still goes quiet: that recording was the robot's, during the match.)
            wasRecording = false;
            disabledSinceMs = nowMs();
        }
        var ntm = NetworkTablesManager.getInstance();
        var ds = ntm.getDriverStation();
        // Off the robot nothing starts; a connection lost while quiet (radio, field) stays quiet.
        if (!ntm.getNTInst().isConnected() || ds == null) return quiet ? reason : "";
        // photonvision-62: no control word received (a format we can't read) counts as enabled,
        // so the Jetson never goes quiet on a robot it can't tell is disabled.
        if (!ds.hasControlWord())
            return robotSays.exists() && robotSays.get() ? "robot code asked" : "";
        var word = ds.current();
        // Robot code decides while it publishes the topic.
        if (robotSays.exists()) return robotSays.get() ? "robot code asked" : "";
        if (word.m_enabled()) return "";
        var s = IdleMode.getSettings();
        // photonvision-83: the first seconds after a match still reach the SSD; whatever happens
        // then (a camera dying, the robot being carried off) is worth keeping, and a robot is
        // rarely switched off sooner.
        if (s.quietAfterMatch && matchEnded && nowMs() - disabledSinceMs >= AFTER_MATCH_MS) {
            return "the match ended";
        }
        if (disabledSinceMs == 0) disabledSinceMs = nowMs();
        if (s.quietAfterDisabledSeconds > 0
                && nowMs() - disabledSinceMs >= s.quietAfterDisabledSeconds * 1000L) {
            return "disabled for " + s.quietAfterDisabledSeconds + " s";
        }
        return "";
    }

    private static volatile boolean wasRecording = false;

    private static void step() throws InterruptedException {
        String want = wanted();
        if (!want.isEmpty() && !quiet && nowMs() >= retryAfterMs) enter(want);
        else if (want.isEmpty() && quiet && nowMs() >= leaveRetryAfterMs) leave();
    }

    private static void enter(String why) throws InterruptedException {
        if (!Files.isExecutable(HELPER)) {
            lastError = HELPER + " isn't installed (10-data-partition.sh)";
            retryAfterMs = nowMs() + 60_000;
            return;
        }
        // Rewind stops at its next loop, and its recorder threads close their files on their next
        // frame; then the log file closes. Only then can the partition go read-only.
        quiet = true;
        reason = why;
        TimeUnit.MILLISECONDS.sleep(1500);
        logger.info(
                "Quiet mode on (" + why + "): closing the log file, scratch partition to read-only");
        Logger.pauseFileLogging();
        String out = run("on");
        if (out != null) {
            Logger.resumeFileLogging();
            quiet = false;
            reason = "";
            lastError = out;
            retryAfterMs = nowMs() + 10_000;
            logger.error(
                    "Quiet mode: couldn't make the scratch partition read-only, trying again in 10 s: "
                            + out);
            return;
        }
        lastError = "";
        quietPub.set(true);
        reasonPub.set(why);
    }

    private static volatile long lastLeaveMs = -1;

    private static volatile long leaveRetryAfterMs = 0;

    private static void leave() {
        long t0 = System.nanoTime();
        String out = run("off");
        if (out != null) {
            lastError = out;
            // photonvision-65: still read-only? Then stay quiet (Rewind and the log file stay
            // paused, and quietNow says so) and try again in 5 s. Leaving anyway left the partition
            // read-only with quiet mode off: nothing tried again, and every Rewind write failed.
            if (scratchReadOnly()) {
                leaveRetryAfterMs = nowMs() + 5_000;
                logger.error(
                        "Quiet mode: couldn't make the scratch partition writable again, trying again in 5 s: "
                                + out);
                return;
            }
            logger.error("Quiet mode: leaving it, but the helper failed: " + out);
        } else {
            lastError = "";
        }
        Logger.resumeFileLogging();
        quiet = false;
        reason = "";
        quietPub.set(false);
        reasonPub.set("");
        lastLeaveMs = (System.nanoTime() - t0) / 1_000_000;
        logger.info("Quiet mode off in " + lastLeaveMs + " ms: the scratch partition is writable");
    }

    /**
     * photonvision-65: milliseconds on a monotonic clock, for every interval here. The date jumps
     * when the Jetson takes the robot's (RobotClockSync). Usually forward (at an event the Jetson
     * boots on the time it last saved), and quiet mode started about a second after the robot
     * connected instead of after quietAfterDisabledSeconds. Back, and it didn't start while disabled
     * for as long as the jump, and a failed attempt waited that much longer to retry.
     */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    private static boolean scratchReadOnly() {
        try {
            return Files.isDirectory(SCRATCH) && Files.getFileStore(SCRATCH).isReadOnly();
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs the helper; null when it worked, else what went wrong. */
    private static String run(String arg) {
        try {
            var p = new ProcessBuilder(HELPER.toString(), arg).redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "spectrum-quiet " + arg + " took over 20 s";
            }
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 ? null : text.isEmpty() ? "exit " + p.exitValue() : text;
        } catch (IOException e) {
            return e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
