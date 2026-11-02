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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.pipeline.AprilTagPipelineSettings;
import org.photonvision.vision.pipeline.CVPipeline;
import org.photonvision.vision.pipeline.PipelineType;

/**
 * SpectrumJetson (photonvision-102): pin camera threads to cores. Jetson layout (6 cores): core 0
 * USB IRQs and the system (spectrum-rt pins the IRQs there when it's on), cores 2-5 one tag
 * camera's VisionRunner each (the thread that runs the detector, NAPA included), in camera-name
 * order; a fifth tag camera wraps around (logged). The shared threads (SpectrumDecoder / NVJPG,
 * TensorRT, the AprilTag+Object fallback detect) and cameras whose pipeline isn't a tag pipeline
 * are left to the scheduler by default (pinSharedCore -1): on core 1 together, two cameras'
 * decoders woke at the same moment and the second waited, +1.2 ms tag latency p99 (bench,
 * 2026-11-02, tests/pin-cameras); pinSharedCore 1 puts them there. The CPU AprilTag pipeline
 * without NAPA stays unpinned: its detector's worker pool is created from the camera thread and
 * would inherit a single core.
 *
 * <p>No native code: each thread registers its own Linux TID (from /proc/thread-self) and one
 * daemon thread applies the affinity with taskset (PhotonVision runs as root; any thread of the
 * process may be changed). Every 2 s it compares /proc/self/task/TID/status Cpus_allowed_list and
 * re-applies when it differs (core hotplug drops a core from a mask). Any other thread found
 * restricted to fewer cores than the process started with goes to the process's cores minus the
 * cores tag cameras use right now: threads a pinned thread created (NVJPG/decoder helpers, OpenCV's
 * pool) inherit its single core otherwise. pinOtherThreads / SPECTRUM_PIN_OTHERS=1 moves every
 * other thread there (GC, JIT, web, NT, decoder helpers), keeping those cores for the cameras.
 * Optional SCHED_FIFO for tag camera threads (chrt; off by default, capped at 49, under the
 * kernel's IRQ threads at 50).
 *
 * <p>On by default on a Jetson only; elsewhere only when configured (SystemCore, 4 cores, suggested
 * layout if wanted: SPECTRUM_PIN_CAMERAS=1 SPECTRUM_PIN_CAMERA_CORES=2-3
 * SPECTRUM_PIN_SHARED_CORE=-1). Settings (HardwareSettings, overridden by the environment):
 * pinCameraThreads (null = auto) / SPECTRUM_PIN_CAMERAS=0|1, pinCameraCores "2-5" /
 * SPECTRUM_PIN_CAMERA_CORES, pinSharedCore -1 (unpinned; 1 = core 1) / SPECTRUM_PIN_SHARED_CORE,
 * pinCameraFifo 0 (off) / SPECTRUM_PIN_FIFO, pinOtherThreads false / SPECTRUM_PIN_OTHERS. Read when
 * PhotonVision starts.
 */
public final class CameraThreads {
    private static final Logger logger = new Logger(CameraThreads.class, LogGroup.General);

    private enum Role {
        TAG,
        SHARED
    }

    private static final class Entry {
        final int tid;
        final String camera; // null: a shared thread
        volatile Role role;
        volatile Thread owner;
        String applied; // the cpu list last applied
        boolean fifoApplied;

        Entry(int tid, String camera, Role role) {
            this.tid = tid;
            this.camera = camera;
            this.role = role;
            this.owner = Thread.currentThread();
        }
    }

    private static final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
    private static final ThreadLocal<Entry> mine = new ThreadLocal<>();
    private static volatile boolean enabled;
    private static volatile List<Integer> cameraCores = List.of();
    private static volatile int sharedCore = -1;
    private static volatile int fifo = 0;
    private static String processCpus; // the process's own affinity when it started
    private static int processCpuCount;
    private static boolean started;
    private static String wrapLogged = "";
    private static volatile boolean others;
    private static volatile String othersCpus;

    private CameraThreads() {}

    /** From the camera's VisionRunner, every loop (cheap: a ThreadLocal and two volatile reads). */
    public static void visionRunner(String camera, CVPipeline pipeline) {
        Role role;
        if (pipeline == null) return;
        var s = pipeline.getSettings();
        var type = s.pipelineType;
        if (type == PipelineType.AprilTagCuda || type == PipelineType.AprilTagObject) {
            role = Role.TAG;
        } else if (type == PipelineType.AprilTag) {
            if (!(s instanceof AprilTagPipelineSettings at && at.napa)) {
                unregister();
                return;
            }
            role = Role.TAG;
        } else if (type == PipelineType.Calib3d || type == PipelineType.FocusCamera) {
            unregister(); // rare, short-lived modes: whatever the scheduler picks
            return;
        } else {
            role = Role.SHARED;
        }
        register(camera, role);
    }

    /** From a shared thread itself, once at its start (decode, TensorRT, ...). */
    public static void shared() {
        register(null, Role.SHARED);
    }

    private static void register(String camera, Role role) {
        if (!ensureStarted()) return;
        if (role == Role.SHARED && sharedCore < 0) {
            unregister(); // shared core -1: shared threads left to the scheduler
            return;
        }
        var e = mine.get();
        if (e != null) {
            if (e.role != role) e.role = role;
            return;
        }
        int tid = selfTid();
        if (tid <= 0) return;
        e = new Entry(tid, camera, role);
        mine.set(e);
        entries.put(tid, e);
    }

    private static void unregister() {
        var e = mine.get();
        if (e == null) return;
        mine.remove();
        entries.remove(e.tid);
        // the next sweep widens it back to the process's cores
    }

    private static synchronized boolean ensureStarted() {
        if (started) return enabled;
        started = true;
        processCpus = cpusAllowed("self");
        processCpuCount = processCpus == null ? 0 : parse(processCpus).cardinality();
        enabled = decideEnabled();
        if (!enabled) return false;
        int n = parse(processCpus).length(); // highest allowed core + 1
        var cores = new ArrayList<Integer>();
        var list = parse(setting("SPECTRUM_PIN_CAMERA_CORES", hw().pinCameraCores, "2-5"));
        for (int c = list.nextSetBit(0); c >= 0; c = list.nextSetBit(c + 1)) if (c < n) cores.add(c);
        sharedCore = intSetting("SPECTRUM_PIN_SHARED_CORE", hw().pinSharedCore, -1);
        fifo = Math.max(0, Math.min(49, intSetting("SPECTRUM_PIN_FIFO", hw().pinCameraFifo, 0)));
        if (cores.isEmpty() || sharedCore < -1 || sharedCore >= n || processCpus == null) {
            logger.warn("Pin camera threads: no usable cores (" + cores + ", shared " + sharedCore + ")");
            enabled = false;
            return false;
        }
        cameraCores = List.copyOf(cores);
        othersCpus = processCpus;
        String o = System.getenv("SPECTRUM_PIN_OTHERS");
        others = o != null ? o.trim().equals("1") : hw().pinOtherThreads;
        logger.info(
                "Pin camera threads: tag cameras on cores "
                        + cores
                        + (sharedCore < 0
                                ? ", shared threads unpinned"
                                : ", shared threads on core " + sharedCore)
                        + (fifo > 0 ? ", tag cameras SCHED_FIFO " + fifo : "")
                        + (others ? ", every other thread off the tag cameras' cores" : "")
                        + " (process cpus "
                        + processCpus
                        + "; SPECTRUM_PIN_CAMERAS=0 turns it off)");
        var t = new Thread(CameraThreads::loop, "SpectrumCameraThreads");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static boolean decideEnabled() {
        String env = System.getenv("SPECTRUM_PIN_CAMERAS");
        if (env != null && !env.isBlank()) return env.trim().equals("1") || env.trim().equals("true");
        Boolean set = hw().pinCameraThreads;
        if (set != null) return set;
        return TeamCameraDefaults.isJetson();
    }

    private static org.photonvision.common.configuration.HardwareSettings hw() {
        try {
            return ConfigProvider.getInstance().getConfig().getHardwareSettings();
        } catch (Exception e) {
            return new org.photonvision.common.configuration.HardwareSettings();
        }
    }

    private static String setting(String env, String value, String dflt) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank()) return v.trim();
        return value != null && !value.isBlank() ? value : dflt;
    }

    private static int intSetting(String env, int value, int dflt) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return dflt;
            }
        }
        return value;
    }

    private static void loop() {
        while (true) {
            try {
                apply();
            } catch (RuntimeException e) {
                logger.error("Pin camera threads", e);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One pass: every registered thread to its cores, every other one back to the process's. */
    static void apply() {
        // Tag cameras in name order, one core each, wrapping past the last.
        var tagCams = new TreeSet<String>();
        for (var e : entries.values()) {
            if (!e.owner.isAlive()) {
                entries.remove(e.tid);
                continue;
            }
            if (e.role == Role.TAG && e.camera != null) tagCams.add(e.camera);
        }
        var cores = cameraCores;
        var order = new ArrayList<>(tagCams);
        if (order.size() > cores.size() && !wrapLogged.equals(order.toString())) {
            wrapLogged = order.toString();
            logger.warn(
                    order.size()
                            + " tag cameras for "
                            + cores.size()
                            + " camera cores: wrapping around, some share a core "
                            + order);
        }
        var ours = new java.util.HashSet<Integer>();
        for (var e : entries.values()) {
            ours.add(e.tid);
            String want =
                    e.role == Role.TAG && e.camera != null
                            ? String.valueOf(cores.get(order.indexOf(e.camera) % cores.size()))
                            : String.valueOf(sharedCore);
            String now = cpusAllowed("self/task/" + e.tid);
            if (now == null) continue; // gone
            if (!want.equals(now)) {
                if (run("taskset", "-p", "-c", want, String.valueOf(e.tid))) {
                    if (!want.equals(e.applied)) {
                        logger.info(
                                "Pinned "
                                        + (e.camera != null ? "\"" + e.owner.getName() + "\"" : e.owner.getName())
                                        + " (tid "
                                        + e.tid
                                        + ") to core "
                                        + want
                                        + (e.applied != null ? " (was " + e.applied + ")" : ""));
                    }
                    e.applied = want;
                }
            }
            boolean wantFifo = fifo > 0 && e.role == Role.TAG;
            // Checked every pass, not only on a change: spectrum-rt's timer resets FIFO threads
            // when it's off.
            if (wantFifo && policy(e.tid) != 1) {
                run("chrt", "-f", "-p", String.valueOf(fifo), String.valueOf(e.tid));
                e.fifoApplied = true;
            } else if (!wantFifo && e.fifoApplied) {
                run("chrt", "-o", "-p", "0", String.valueOf(e.tid));
                e.fifoApplied = false;
            }
        }
        // Every other thread: with "others" on, all of them to the process's cores minus the camera
        // cores; otherwise only those found restricted to fewer cores than the process (a pinned
        // thread's native helpers, OpenCV's pool, which inherit its single core), to the same set.
        var restSet = parse(processCpus);
        for (int i = 0; i < Math.min(order.size(), cores.size()); i++) restSet.clear(cores.get(i));
        String rest = restSet.isEmpty() ? processCpus : toList(restSet);
        if (!rest.equals(othersCpus)) {
            othersCpus = rest;
            if (others) logger.info("Other threads on cores " + rest);
        }
        var tasks = new java.io.File("/proc/self/task").list();
        if (tasks == null) return;
        for (var t : tasks) {
            int tid;
            try {
                tid = Integer.parseInt(t);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (ours.contains(tid)) continue;
            String now = cpusAllowed("self/task/" + tid);
            if (now == null || now.equals(rest)) continue;
            if (!others && parse(now).cardinality() >= processCpuCount) continue;
            run("taskset", "-p", "-c", rest, String.valueOf(tid));
        }
    }

    static String toList(BitSet b) {
        var sb = new StringBuilder();
        for (int c = b.nextSetBit(0); c >= 0; c = b.nextSetBit(c + 1)) {
            int e = c;
            while (b.get(e + 1)) e++;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
            if (e > c) sb.append('-').append(e);
            c = e;
        }
        return sb.toString();
    }

    /** The thread's scheduling policy (1 = SCHED_FIFO), -1 if unknown. */
    private static int policy(int tid) {
        try {
            String st = Files.readString(Path.of("/proc/self/task/" + tid + "/stat"));
            // fields after the name's closing parenthesis start at field 3; policy is field 41
            String[] f = st.substring(st.lastIndexOf(')') + 2).split(" ");
            return Integer.parseInt(f[38]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int selfTid() {
        try {
            // "<pid>/task/<tid>"
            String p = Files.readSymbolicLink(Path.of("/proc/thread-self")).toString();
            return Integer.parseInt(p.substring(p.lastIndexOf('/') + 1));
        } catch (Exception e) {
            return -1;
        }
    }

    static String cpusAllowed(String procPath) {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/" + procPath + "/status"))) {
                if (l.startsWith("Cpus_allowed_list:")) return l.substring(18).trim();
            }
        } catch (Exception e) {
            // the thread exited
        }
        return null;
    }

    /** "2-5", "1,3-4" -> bits; empty on anything malformed. */
    static BitSet parse(String list) {
        var b = new BitSet();
        if (list == null) return b;
        try {
            for (String part : list.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int dash = part.indexOf('-');
                if (dash < 0) b.set(Integer.parseInt(part));
                else
                    b.set(
                            Integer.parseInt(part.substring(0, dash)),
                            Integer.parseInt(part.substring(dash + 1)) + 1);
            }
        } catch (RuntimeException e) {
            return new BitSet();
        }
        return b;
    }

    private static boolean run(String... cmd) {
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
