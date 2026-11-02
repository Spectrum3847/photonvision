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
 * The enable wakes this class's thread straight from the NetworkTables listener, so full clocks are
 * back within milliseconds (the time is logged). "No robot" (no NetworkTables connection: the
 * bench) counts as disabled, so a bench Jetson throttles too; {@code sudo spectrum-clocks mode
 * full} (/etc/spectrum-clocks-mode) or 0 here keeps full clocks. A robot whose control word we
 * can't read counts as enabled (photonvision-62).
 *
 * <p>The clocks are changed by /usr/local/bin/spectrum-clocks (SpectrumJetson 09-robot-tuning.sh),
 * which spectrum-clocks-guard respects while throttled. The service's drop-in sets full clocks
 * before PhotonVision starts and lowers them once it stops or crashes (a Jetson without
 * PhotonVision running has nothing to do), so nothing is needed here on exit. Without the helper
 * (the SystemCore, a laptop) nothing happens.
 *
 * <p>photonvision-99, deep idle: with no robot connected at all, no web UI client and no stream
 * viewer for SPECTRUM_DEEP_IDLE_SECONDS (default 300; 0 = off), every camera drops to
 * SPECTRUM_DEEP_IDLE_FPS (default 5) and {@code spectrum-clocks deep} lowers the clocks further
 * (CPU/GPU caps, EMC unlocked, CPU idle states on; all cores stay online). Any robot connection, UI
 * client or viewer ends it: the frame rate within 5 ms, the clocks back to the 20 s throttle (or
 * full, if enabled) in milliseconds. {@code spectrum-clocks mode shallow} keeps only the 20 s
 * throttle; mode full keeps everything at full.
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
        /** photonvision-99: the deep idle tier (spectrum-clocks deep). */
        DEEP,
        FULL
    }

    /**
     * The decision alone, on a clock and an "active" signal it's given (unit-tested): active
     * (enabled, or a control word we can't read) or not allowed -> full at once; otherwise, after
     * {@code afterSeconds} continuously inactive -> throttle.
     *
     * <p>photonvision-99, the deep tier: after {@code deepAfterSeconds} continuously "deep OK" (no
     * robot connected at all, no web UI client, no stream viewer) -> deep. The moment deep isn't OK
     * any more -> back to throttle (or full, if active). A connected robot is never deep, so nothing
     * between auto and teleop (or a disabled robot in the queue) can be.
     */
    public static final class Policy {
        private long inactiveSinceMs = -1;
        private long deepSinceMs = -1;
        private boolean throttled;
        private boolean deep;

        public Policy(boolean throttled) {
            this(throttled, false);
        }

        public Policy(boolean throttled, boolean deep) {
            this.throttled = throttled || deep;
            this.deep = deep;
        }

        public boolean throttled() {
            return throttled;
        }

        public boolean deep() {
            return deep;
        }

        /** Milliseconds inactive so far (0 while active). */
        public long inactiveMs(long nowMs) {
            return inactiveSinceMs < 0 ? 0 : nowMs - inactiveSinceMs;
        }

        /** Without the deep tier. */
        public Action step(long nowMs, boolean active, int afterSeconds, boolean forceFull) {
            return step(nowMs, active, afterSeconds, forceFull, false, 0);
        }

        /**
         * @param active the robot is enabled (or can't be read)
         * @param afterSeconds throttle after this long inactive; 0 = never (and never deep)
         * @param forceFull full clocks regardless (mode full)
         * @param deepOk no robot connected, no UI client or stream viewer (and deep allowed)
         * @param deepAfterSeconds deep after this long deepOk; 0 = never
         */
        public Action step(
                long nowMs,
                boolean active,
                int afterSeconds,
                boolean forceFull,
                boolean deepOk,
                int deepAfterSeconds) {
            if (active) inactiveSinceMs = -1;
            else if (inactiveSinceMs < 0) inactiveSinceMs = nowMs;
            boolean deepCan = deepOk && !active && deepAfterSeconds > 0;
            if (!deepCan) deepSinceMs = -1;
            else if (deepSinceMs < 0) deepSinceMs = nowMs;
            if (active || afterSeconds <= 0 || forceFull) return throttled ? Action.FULL : Action.NONE;
            if (deepCan && nowMs - deepSinceMs >= deepAfterSeconds * 1000L) {
                return deep ? Action.NONE : Action.DEEP;
            }
            if (deep) return Action.THROTTLE; // deep no longer OK: back up one tier
            if (!throttled && nowMs - inactiveSinceMs >= afterSeconds * 1000L) return Action.THROTTLE;
            return Action.NONE;
        }

        /** After the helper ran: the state it left. */
        public void applied(Action action, boolean ok) {
            switch (action) {
                case THROTTLE -> {
                    if (ok || deep) throttled = true; // from deep: never retry-loop deep
                    deep = false;
                }
                case DEEP -> {
                    if (ok) {
                        throttled = true;
                        deep = true;
                    }
                }
                case FULL -> {
                    throttled = false; // even if it failed: never retry-loop low
                    deep = false;
                }
                default -> {}
            }
        }
    }

    private static final Path DEEP_FLAG = Path.of("/run/spectrum-clocks-deep");
    private static final int DEEP_AFTER_SECONDS = envInt("SPECTRUM_DEEP_IDLE_SECONDS", 300);
    private static final double DEEP_FPS = envInt("SPECTRUM_DEEP_IDLE_FPS", 5);
    private static volatile int uiClients = 0;
    private static volatile boolean deepFps = false;

    private static int envInt(String name, int dflt) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? dflt : Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** photonvision-99: web UI websocket clients now (DataSocketHandler). */
    public static void setUiClients(int n) {
        uiClients = n;
        if (n > 0) wake();
    }

    /**
     * photonvision-99: the frame period while deep idle (0 when not): IdleMode caps every camera to
     * DEEP_FPS. Cleared before the clocks come back, so the full rate is back within 5 ms of a robot
     * connecting or a client opening the UI (plus up to 250 ms to notice a stream viewer).
     */
    public static long deepPeriodNanos() {
        return deepFps && DEEP_FPS > 0 ? (long) (1e9 / DEEP_FPS) : 0;
    }

    private static final Policy policy = new Policy(Files.exists(FLAG), Files.exists(DEEP_FLAG));
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

    private static String mode() {
        try {
            return Files.readString(MODE_FILE).trim();
        } catch (IOException e) {
            return "auto";
        }
    }

    private static boolean forceFull() {
        return "full".equals(mode());
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
        // A robot connecting ends deep idle at once (photonvision-99).
        ntm.getNTInst().addConnectionListener(false, e -> wake());
        var th = new Thread(ClockThrottle::loop, "SpectrumClockThrottle");
        th.setDaemon(true);
        th.setPriority(Thread.MAX_PRIORITY);
        th.start();
        logger.info(
                "Clock throttle ready: lower clocks after "
                        + IdleMode.getSettings().throttleClocksAfterDisabledSeconds
                        + " s disabled (or no robot), full on enable; deep idle "
                        + (DEEP_AFTER_SECONDS > 0
                                ? "("
                                        + DEEP_FPS
                                        + " fps, lower clocks) after "
                                        + DEEP_AFTER_SECONDS
                                        + " s with no robot, UI or stream viewer"
                                : "off (SPECTRUM_DEEP_IDLE_SECONDS=0)")
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
        String mode = mode();
        boolean force = "full".equals(mode);
        boolean robot = NetworkTablesManager.getInstance().getNTInst().isConnected();
        // "shallow" (spectrum-clocks mode shallow): the 20 s throttle only, never deep.
        boolean deepOk =
                !robot
                        && !"shallow".equals(mode)
                        && uiClients == 0
                        && !org.photonvision.vision.frame.consumer.MJPGFrameConsumer.anyViewers();
        boolean wasDeep = policy.deep();
        var action = policy.step(now, active, after, force, deepOk, DEEP_AFTER_SECONDS);
        // Leaving deep: the full frame rate first (IdleMode re-reads it within 5 ms).
        if (wasDeep && action != Action.NONE && action != Action.DEEP) deepFps = false;
        if (action == Action.THROTTLE || action == Action.DEEP) {
            if (now < retryAfterMs && !wasDeep) return;
            String arg = action == Action.DEEP ? "deep" : "throttle";
            long t0 = System.nanoTime();
            String out = run(arg);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            policy.applied(action, out == null);
            if (out != null) {
                lastError = out;
                retryAfterMs = now + 30_000;
                logger.error("Couldn't run spectrum-clocks " + arg + " (trying again in 30 s): " + out);
                return;
            }
            lastError = "";
            if (action == Action.DEEP) {
                deepFps = true;
                why = "deep idle: no robot, UI or stream for " + DEEP_AFTER_SECONDS + " s";
                logger.info("Deep idle: " + DEEP_FPS + " fps per camera, lowest clocks (" + why + ")");
            } else if (wasDeep) {
                why = robot ? "robot connected, disabled" : "UI or stream viewer";
                logger.info(
                        "Deep idle ended ("
                                + why
                                + "): full frame rate, clocks throttled (took "
                                + ms
                                + " ms)");
            } else {
                why = robot ? "robot disabled " + after + " s" : "no robot for " + after + " s";
                logger.info("Clocks throttled: " + why);
            }
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
        m.put("clocksDeep", policy.deep()); // photonvision-99
        m.put("clocksDeepAfterSeconds", DEEP_AFTER_SECONDS);
        m.put("clocksMode", forceFull() ? "full" : "shallow".equals(mode()) ? "shallow" : "auto");
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
