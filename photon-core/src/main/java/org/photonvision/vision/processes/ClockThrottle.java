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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/**
 * SpectrumJetson (photonvision-90): lower the CPU and GPU clocks while the robot has been disabled
 * for a long time (throttleClocksAfterDisabledSeconds, Settings > Robot state, default 20; 0 =
 * never), and lock them at full again the moment it's enabled. Idle mode already drops the cameras
 * to 30 fps then; on a fanless Orin Nano the lower clocks save ~3 W more (11.8 -> 8.9 W) and the
 * heat with it.
 *
 * <p>Never in a match: the gap between auto and teleop is ~3 s, and any enable restarts the count.
 * The enable wakes this class's thread straight from the NetworkTables listener, so full clocks
 * are back within milliseconds (the time is logged). "No robot" (no NetworkTables connection: the
 * bench) counts as disabled, so a bench Jetson throttles too; {@code sudo spectrum-clocks mode
 * full} (/etc/spectrum-clocks-mode) or 0 here keeps full clocks. A robot whose control word we
 * can't read counts as enabled (photonvision-62).
 *
 * <p>The clocks are changed by /usr/local/bin/spectrum-clocks (SpectrumJetson 09-robot-tuning.sh),
 * which spectrum-clocks-guard respects while throttled. The service's drop-in sets full clocks
 * before PhotonVision starts and lowers them once it stops or crashes (a Jetson without PhotonVision
 * running has nothing to do), so nothing is needed here on exit. Without the helper (the
 * SystemCore, a laptop) nothing happens.
 */
public final class ClockThrottle {
    private static final Logger logger = new Logger(ClockThrottle.class, LogGroup.General);
    private static final Path HELPER = Path.of("/usr/local/bin/spectrum-clocks");
    private static final Path FLAG = Path.of("/run/spectrum-clocks-throttled");
    private static final Path MODE_FILE = Path.of("/etc/spectrum-clocks-mode");

    /** What to do now. */
    public enum Action {
        NONE,
        THROTTLE,
        FULL
    }

    /**
     * The decision alone, on a clock and an "active" signal it's given (unit-tested):
     * active (enabled, or a control word we can't read) or not allowed -> full at once; otherwise,
     * after {@code afterSeconds} continuously inactive -> throttle.
     */
    public static final class Policy {
        private long inactiveSinceMs = -1;
        private boolean throttled;

        public Policy(boolean throttled) {
            this.throttled = throttled;
        }

        public boolean throttled() {
            return throttled;
        }

        /** Milliseconds inactive so far (0 while active). */
        public long inactiveMs(long nowMs) {
            return inactiveSinceMs < 0 ? 0 : nowMs - inactiveSinceMs;
        }

        /**
         * @param active the robot is enabled (or can't be read)
         * @param afterSeconds throttle after this long inactive; 0 = never
         * @param forceFull full clocks regardless (mode full)
         */
        public Action step(long nowMs, boolean active, int afterSeconds, boolean forceFull) {
            if (active) inactiveSinceMs = -1;
            else if (inactiveSinceMs < 0) inactiveSinceMs = nowMs;
            if (active || afterSeconds <= 0 || forceFull) return throttled ? Action.FULL : Action.NONE;
            if (!throttled && nowMs - inactiveSinceMs >= afterSeconds * 1000L) return Action.THROTTLE;
            return Action.NONE;
        }

        /** After the helper ran: the state it left. */
        public void applied(Action action, boolean ok) {
            if (action == Action.THROTTLE && ok) throttled = true;
            if (action == Action.FULL) throttled = false; // even if it failed: never retry-loop low
        }
    }

    private static final Policy policy = new Policy(Files.exists(FLAG));
    private static final Object wakeLock = new Object();
    private static volatile boolean started = false;
    private static volatile String why = "";
    private static volatile long lastFullMs = -1;
    private static volatile String lastError = "";
    private static volatile long retryAfterMs = 0;

    private ClockThrottle() {}

    /** Wakes the thread now (from the NetworkTables listener on an enable). */
    public static void wake() {
        synchronized (wakeLock) {
            wakePending = true;
            wakeLock.notifyAll();
        }
    }

    private static boolean wakePending = false; // guarded by wakeLock: a wake while the helper runs

    private static boolean forceFull() {
        try {
            return "full".equals(Files.readString(MODE_FILE).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** Enabled, or a robot we can't read (photonvision-62); no robot = not active. */
    private static boolean active() {
        var ntm = NetworkTablesManager.getInstance();
        var ds = ntm.getDriverStation();
        if (!ntm.getNTInst().isConnected() || ds == null) return false;
        return !ds.hasControlWord() || ds.current().m_enabled();
    }

    /** Called from IdleMode.start once the driver-station listener exists. */
    public static synchronized void start() {
        if (started) return;
        var ntm = NetworkTablesManager.getInstance();
        var ds = ntm.getDriverStation();
        if (ds == null) return;
        if (!Files.isExecutable(HELPER)) {
            logger.info("Clock throttle off: " + HELPER + " isn't installed (09-robot-tuning.sh)");
            return;
        }
        started = true;
        ds.addTransitionListener(
                (old, now) -> {
                    if (now.m_enabled() != old.m_enabled()) wake();
                });
        ntm.getNTInst().addConnectionListener(false, e -> wake());
        var th = new Thread(ClockThrottle::loop, "SpectrumClockThrottle");
        th.setDaemon(true);
        th.setPriority(Thread.MAX_PRIORITY);
        th.start();
        logger.info(
                "Clock throttle ready: lower clocks after "
                        + IdleMode.getSettings().throttleClocksAfterDisabledSeconds
                        + " s disabled (or no robot), full on enable"
                        + (forceFull() ? "; mode full (/etc/spectrum-clocks-mode): never throttling" : ""));
    }

    private static void loop() {
        while (true) {
            try {
                step();
                synchronized (wakeLock) {
                    if (!wakePending) wakeLock.wait(250);
                    wakePending = false;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                logger.error("Clock throttle", e);
            }
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000; // monotonic: the date jumps (RobotClockSync)
    }

    private static void step() {
        long now = nowMs();
        boolean active = active();
        int after = IdleMode.getSettings().throttleClocksAfterDisabledSeconds;
        boolean force = forceFull();
        var action = policy.step(now, active, after, force);
        if (action == Action.THROTTLE) {
            if (now < retryAfterMs) return;
            boolean robot = NetworkTablesManager.getInstance().getNTInst().isConnected();
            String out = run("throttle");
            policy.applied(action, out == null);
            if (out != null) {
                lastError = out;
                retryAfterMs = now + 30_000;
                logger.error("Couldn't lower the clocks (trying again in 30 s): " + out);
                return;
            }
            lastError = "";
            why = robot ? "robot disabled " + after + " s" : "no robot for " + after + " s";
            logger.info("Clocks throttled: " + why);
        } else if (action == Action.FULL) {
            long t0 = System.nanoTime();
            String out = run("full");
            policy.applied(action, out == null);
            lastFullMs = (System.nanoTime() - t0) / 1_000_000;
            String reason =
                    active
                            ? "robot enabled"
                            : force ? "mode full (/etc/spectrum-clocks-mode)" : "throttling switched off";
            why = "";
            if (out != null) {
                lastError = out;
                logger.error("Full clocks (" + reason + "): the helper failed: " + out);
            } else {
                lastError = "";
                logger.info("Full clocks: " + reason + " (took " + lastFullMs + " ms)");
            }
        }
    }

    public static Map<String, Object> status() {
        var m = new LinkedHashMap<String, Object>();
        m.put("clocksHelper", Files.isExecutable(HELPER));
        m.put("clocksThrottled", policy.throttled());
        m.put("clocksReason", why);
        m.put("clocksMode", forceFull() ? "full" : "auto");
        m.put("clocksLastFullMs", lastFullMs);
        m.put("clocksError", lastError);
        return m;
    }

    /** Runs the helper; null when it worked, else what went wrong. */
    private static String run(String arg) {
        try {
            var p = new ProcessBuilder(HELPER.toString(), arg).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "spectrum-clocks " + arg + " took over 10 s";
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
