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

package org.photonvision.rewind;

import io.avaje.json.node.JsonDouble;
import io.avaje.json.node.JsonNodeMapper;
import io.avaje.json.node.JsonObject;
import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TimedTaskManager;
import org.photonvision.vision.processes.QuietMode;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.BooleanSubscriber;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.IntegerPublisher;
import org.wpilib.networktables.IntegerSubscriber;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.NetworkTablesJNI;
import org.wpilib.networktables.StringPublisher;
import org.wpilib.networktables.StringSubscriber;

/**
 * SpectrumJetson Rewind: decides when the cameras record, and keeps the recordings within their
 * disk quota. See {@code docs/REWIND.md} in SpectrumJetson.
 *
 * <p>Robot code decides when to record, over NetworkTables under {@code /photonvision/rewind/}:
 *
 * <ul>
 *   <li>{@code record} (boolean, robot sets): record while true.
 *   <li>{@code label} (string, robot sets, optional): names the recording, e.g. "shooter-test-3".
 *       Empty: the FMS match (e.g. "Q12"), else the robot's mode ("teleop", "disabled", ...).
 *   <li>{@code recording}, {@code session}, {@code freeGB}, {@code framesDropped} (Jetson sets).
 * </ul>
 *
 * <p>The web UI can also record ("Record now", for bench tests). If the robot disconnects while it
 * asked for recording (brownout, reboot), recording continues for {@link #DISCONNECT_GRACE_NS} so
 * the footage of what happened is kept.
 *
 * <p>Each recording is a session folder under {@link #ROOT}{@code /sessions}, holding {@code
 * <session>_session.json} and one folder per camera. When the folder passes its quota or the disk
 * gets low, the oldest sessions are deleted.
 *
 * <p>WPILib 2027: NetworkTables' clock and the time-sync offset are nanoseconds, so times here are
 * too. The files keep 2026's microseconds ({@code startJetsonUs}, {@code robotOffsetUs}, {@code
 * endJetsonUs} in session.json; {@code jetson_us}, {@code robot_us} in the frame tables), which
 * SpectrumJetson's tools read. The 2026 clock marks that rode on this tick are {@code ClockMarks}'
 * own 1 Hz task now.
 */
public class RewindManager {
    private static final Logger logger = new Logger(RewindManager.class, LogGroup.General);

    /** Outside photonvision_config, so a settings export never includes the video. */
    public static final Path ROOT = Path.of("/opt/photonvision/rewind");

    public static final Path SESSIONS = ROOT.resolve("sessions");

    /** photonvision-75: {@code <session>_session.json}; older recordings: {@code session.json}. */
    public static final String SESSION_JSON = "_session.json";

    /** A session folder's metadata file, under either name. */
    public static Path sessionJson(Path sessionDir) {
        Path p = sessionDir.resolve(sessionDir.getFileName() + SESSION_JSON);
        return Files.exists(p) ? p : sessionDir.resolve("session.json");
    }

    private static final Path SETTINGS_FILE = ROOT.resolve("settings.json");
    private static final Path SEQ_FILE = ROOT.resolve("next-session-number");
    private static final long DISCONNECT_GRACE_NS = 60_000_000_000L;
    // photonvision-78: an FMS match, or any robot recording with recordThroughDisconnects on, is
    // kept through a drop until the robot is back and says stop, or this long. Longer than a match.
    private static final long MATCH_DISCONNECT_GRACE_NS = 300_000_000_000L;
    private static final long DISK_CHECK_NS = 10_000_000_000L;
    private static final JsonNodeMapper json = JsonNodeMapper.builder().build();
    private static final JsonType<Map<String, Object>> mapJson =
            Jsonb.instance().type(Types.mapOf(Object.class));

    /** Persisted settings (settings.json). */
    public static class Settings {
        /** Frames kept per second per camera (the cameras run ~90-100). */
        public double fps = 30;

        /** Most the sessions folder may hold; the oldest sessions are deleted past this. */
        public double quotaGB = 100;

        /** Keep at least this much of the SSD free; stop recording below half of it. */
        public double minFreeGB = 15;

        /**
         * photonvision-78: keep any robot recording through a disconnect, as FMS matches always are
         * (practice matches at an event). On at events, off on the bench.
         */
        public boolean recordThroughDisconnects = false;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fps", fps);
            m.put("quotaGB", quotaGB);
            m.put("minFreeGB", minFreeGB);
            m.put("recordThroughDisconnects", recordThroughDisconnects);
            return m;
        }
    }

    // The 2026 session also carried the camera and Jetson settings at its start (photonvision-24's
    // CameraSettingsPublisher and JetsonSettingsPublisher, feature 13); they aren't ported yet, so
    // session.json has no "settings"/"settingsAtEnd" until they are.
    /** startNs: NetworkTables' clock (nanoseconds) when the session opened. */
    public record Session(String name, Path dir, long startNs, String reason) {}

    private static RewindManager INSTANCE;

    public static synchronized RewindManager getInstance() {
        if (INSTANCE == null) INSTANCE = new RewindManager();
        return INSTANCE;
    }

    private final List<RewindRecorder> recorders = new CopyOnWriteArrayList<>();
    private volatile Settings settings = new Settings();
    private volatile Session current = null;
    private volatile boolean manual = false;
    private volatile String manualLabel = "";
    private volatile String stopReason = "";
    private volatile boolean diskLow = false;
    private RobotRecordRequest robotRequest; // photonvision-77
    private volatile boolean robotGraceNow = false;
    private boolean fmsMatch = false; // photonvision-78: the open recording saw the FMS
    private boolean waitingAfterReconnect = false;
    private volatile long sessionsBytes = 0;
    private volatile int sessionCount = 0;
    private volatile long lastDiskCheckNs = 0;
    private boolean started = false;

    private BooleanSubscriber recordSub;
    private StringSubscriber labelSub;
    // The match info, from both tables a robot may publish it in: WPILib 2027 publishes it under
    // /DriverStation, 2026 robot code under /FMSInfo (as NTDriverStation reads the control word).
    private final List<StringSubscriber> eventSubs = new ArrayList<>();
    private final List<IntegerSubscriber> matchTypeSubs = new ArrayList<>();
    private final List<IntegerSubscriber> matchNumberSubs = new ArrayList<>();
    private BooleanPublisher recordingPub;
    private StringPublisher sessionPub;
    private DoublePublisher freeGBPub;
    private IntegerPublisher droppedPub;

    private RewindManager() {}

    /** Call once NetworkTables is up. */
    public synchronized void start() {
        if (started) return;
        started = true;
        try {
            Files.createDirectories(SESSIONS);
        } catch (IOException e) {
            logger.error("Rewind: cannot create " + SESSIONS, e);
        }
        loadSettings();

        NetworkTableInstance nt = NetworkTablesManager.getInstance().getNTInst();
        NetworkTable t = nt.getTable("photonvision").getSubTable("rewind");
        recordSub = t.getBooleanTopic("record").subscribe(false);
        robotRequest = new RobotRecordRequest(nt, recordSub);
        labelSub = t.getStringTopic("label").subscribe("");
        recordingPub = t.getBooleanTopic("recording").publish();
        sessionPub = t.getStringTopic("session").publish();
        freeGBPub = t.getDoubleTopic("freeGB").publish();
        droppedPub = t.getIntegerTopic("framesDropped").publish();
        for (String table : new String[] {"DriverStation", "FMSInfo"}) {
            NetworkTable fms = nt.getTable(table);
            eventSubs.add(fms.getStringTopic("EventName").subscribe(""));
            matchTypeSubs.add(fms.getIntegerTopic("MatchType").subscribe(0));
            matchNumberSubs.add(fms.getIntegerTopic("MatchNumber").subscribe(0));
        }

        // photonvision-56: quiet mode never starts while someone wants a recording, and ends if
        // one is asked for (QuietMode's hook for Rewind).
        QuietMode.setRecordingRequested(RewindManager::recordingRequested);
        TimedTaskManager.getInstance().addTask("RewindManager::tick", this::tick, 200);
        logger.info(
                "Rewind ready: robot code records by setting /photonvision/rewind/record; saving to "
                        + SESSIONS);
    }

    void register(RewindRecorder r) {
        recorders.add(r);
    }

    void unregister(RewindRecorder r) {
        recorders.remove(r);
    }

    /** The open session, or null when not recording. Read by the recorder threads per frame. */
    Session currentSession() {
        return current;
    }

    long minFrameIntervalNs() {
        double fps = settings.fps;
        return fps <= 0 ? 0 : (long) (1e9 / fps);
    }

    /**
     * Robot-clock time, in microseconds (robot_us), for a Jetson frame time in nanoseconds; "" when
     * not synced to a robot.
     */
    String robotTimeUs(long jetsonNs) {
        var ntm = NetworkTablesManager.getInstance();
        if (!ntm.getNTInst().isConnected()) return "";
        return Long.toString(Math.floorDiv(jetsonNs + ntm.getOffset(), 1000L));
    }

    // ---- control

    public synchronized void setManual(boolean on) {
        setManual(on, "");
    }

    /**
     * SpectrumJetson (photonvision-30): a web-UI recording with its own label (field calibration
     * records as "fieldcal"). The label is read when the recording opens.
     */
    public synchronized void setManual(boolean on, String label) {
        manual = on;
        if (on) manualLabel = label == null ? "" : label;
        logger.info("Rewind: bench recording " + (on ? "on" : "off") + " (from the web UI)");
        tick();
    }

    /** The open recording's name, or "" when not recording. */
    public String currentSessionName() {
        var s = current;
        return s == null ? "" : s.name();
    }

    public synchronized void updateSettings(
            Double fps, Double quotaGB, Double minFreeGB, Boolean recordThroughDisconnects) {
        var s = new Settings();
        s.fps = fps != null ? Math.max(1, Math.min(120, fps)) : settings.fps;
        s.quotaGB = quotaGB != null ? Math.max(1, quotaGB) : settings.quotaGB;
        s.minFreeGB = minFreeGB != null ? Math.max(1, minFreeGB) : settings.minFreeGB;
        s.recordThroughDisconnects =
                recordThroughDisconnects != null
                        ? recordThroughDisconnects
                        : settings.recordThroughDisconnects;
        settings = s;
        saveSettings(s);
        lastDiskCheckNs = 0; // re-check the quota now
    }

    /** Deletes a recording: null when it's gone, else why not. */
    public synchronized String deleteSession(String name) {
        if (current != null && current.name().equals(name)) return "it's still recording";
        Path p = SESSIONS.resolve(safeName(name));
        if (!p.getParent().equals(SESSIONS) || !Files.isDirectory(p)) return "no such recording";
        // photonvision-65: deleting in quiet mode (the partition read-only after a match) used to
        // fail file by file in silence and report success.
        if (QuietMode.quiet()) {
            return "the SSD is in quiet mode after the match (read-only); it's writable again at the next"
                                 + " enable";
        }
        lastDiskCheckNs = 0;
        return deleteTree(p) ? null : "some files couldn't be deleted (see the log)";
    }

    /**
     * photonvision-56: whether someone wants a recording right now: the web UI's Record now or field
     * calibration (bench recording), or robot code's /photonvision/rewind/record. Quiet mode never
     * starts while this is true, and leaves if it becomes true: recording is writing on purpose.
     * Doesn't create the manager.
     */
    public static boolean recordingRequested() {
        var m = INSTANCE;
        if (m == null) return false;
        if (m.manual) return true;
        // photonvision-77: a robot recording kept through a NetworkTables drop counts too.
        if (m.robotGraceNow) return true;
        var sub = m.recordSub;
        return sub != null && NetworkTablesManager.getInstance().getNTInst().isConnected() && sub.get();
    }

    // ---- the 5 Hz tick

    private synchronized void tick() {
        try {
            long now = NetworkTablesJNI.now(); // nanoseconds
            boolean ntConnected = NetworkTablesManager.getInstance().getNTInst().isConnected();
            // Robot asked to record, then vanished: keep going for the grace period. photonvision-77:
            // also after it reconnects, until it sends record again (see RobotRecordRequest).
            var ds = NetworkTablesManager.getInstance().getDriverStation();
            if (current == null) fmsMatch = false;
            else if (ds.current().m_fmsAttached()) fmsMatch = true;
            long graceNs =
                    fmsMatch || settings.recordThroughDisconnects
                            ? MATCH_DISCONNECT_GRACE_NS
                            : DISCONNECT_GRACE_NS;
            var robot =
                    robotRequest.update(
                            now, ntConnected, current != null && current.reason().equals("robot"), graceNs);
            boolean robotWants = robot.wants();
            boolean robotGrace = robot.grace();
            robotGraceNow = robotGrace;
            boolean wasWaiting = waitingAfterReconnect;
            if (robot.waitingAfterReconnect() != waitingAfterReconnect) {
                waitingAfterReconnect = robot.waitingAfterReconnect();
                if (waitingAfterReconnect) {
                    logger.info(
                            "Rewind: NetworkTables reconnected; keeping "
                                    + current.name()
                                    + " until the robot sends record again");
                } else if (robotWants) {
                    logger.info("Rewind: the robot still wants recording; same session");
                }
            }

            // photonvision-56: never while quiet mode has the scratch partition read-only (and no
            // tidying either: deleting old recordings is a write too).
            boolean quiet = QuietMode.quiet();
            if (!quiet && now - lastDiskCheckNs > DISK_CHECK_NS) {
                lastDiskCheckNs = now;
                manageDisk();
            }

            boolean want = (robotWants || robotGrace || manual) && !diskLow && !quiet;
            if (want && current == null) {
                openSession(now, robotWants || robotGrace ? "robot" : "bench");
            } else if (!want && current != null) {
                closeSession(
                        quiet
                                ? "quiet mode"
                                : diskLow
                                        ? "disk low"
                                        : current.reason().equals("bench")
                                                ? "bench recording stopped"
                                                : !ntConnected
                                                        ? "robot disconnected"
                                                        : wasWaiting
                                                                ? "robot didn't send record after reconnecting"
                                                                : "robot stopped");
            }

            VisionLog.robot(
                    now,
                    ntConnected,
                    NetworkTablesManager.getInstance().getOffset(),
                    robotMode(),
                    (eventName() + " " + matchName()).strip());
            recordingPub.set(current != null);
            sessionPub.set(current != null ? current.name() : "");
            freeGBPub.set(freeBytes() / 1e9);
            long dropped = 0;
            for (var r : recorders) dropped += r.framesSkippedNotMjpeg + r.writeErrors + r.badFrames;
            droppedPub.set(dropped);
        } catch (Exception e) {
            logger.error("Rewind tick failed", e);
        }
    }

    private void openSession(long now, String reason) {
        String label = reason.equals("robot") ? labelSub.get().strip() : manualLabel;
        if (label.isEmpty()) label = matchName();
        if (label.isEmpty()) label = robotMode();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = String.format("%04d_%s_%s", nextSessionNumber(), safeName(label), stamp);
        Path dir = SESSIONS.resolve(name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.error("Rewind: cannot create " + dir, e);
            return;
        }
        current = new Session(name, dir, now, reason);
        VisionLog.open(dir.resolve(name + "_vision.wpilog")); // photonvision-79
        stopReason = "";
        writeSessionJson(current, null);
        logger.info("Rewind: recording session " + name + " (" + reason + ")");
    }

    private void closeSession(String why) {
        var s = current;
        current = null; // recorder threads close their files on their next frame
        VisionLog.close();
        stopReason = why;
        // Give the recorders a moment to close, then record the end in session.json.
        TimedTaskManager.getInstance().addOneShotTask(() -> writeSessionJson(s, why), 1000);
        logger.info("Rewind: stopped session " + s.name() + (why.isEmpty() ? "" : " (" + why + ")"));
    }

    private void writeSessionJson(Session s, String endReason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("reason", s.reason());
        // Microseconds in the file, as in 2026 (WPILib 2027's clock is nanoseconds).
        m.put("startJetsonUs", s.startNs() / 1000);
        m.put("robotOffsetUs", NetworkTablesManager.getInstance().getOffset() / 1000);
        m.put("robotConnected", NetworkTablesManager.getInstance().getNTInst().isConnected());
        m.put("event", eventName());
        m.put("match", matchName());
        m.put("label", labelSub.get());
        m.put("fps", settings.fps);
        List<Map<String, Object>> cams = new ArrayList<>();
        for (var r : recorders) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("camera", r.getNickname());
            c.put("frames", r.framesWritten);
            c.put("bytes", r.bytesWritten);
            if (!r.lastProblem.isEmpty()) c.put("problem", r.lastProblem);
            cams.add(c);
        }
        m.put("cameras", cams);
        if (endReason != null) {
            m.put("endJetsonUs", NetworkTablesJNI.now() / 1000);
            m.put("endReason", endReason);
        }
        try (var out =
                new java.io.FileOutputStream(s.dir().resolve(s.name() + SESSION_JSON).toFile())) {
            out.write(mapJson.toJsonPretty(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync(); // power cuts: on the SSD before it counts
        } catch (IOException | RuntimeException e) {
            logger.error("Rewind: cannot write session.json", e);
        }
    }

    /** photonvision-75: off the FMS there's no event or match, so name the robot's mode. */
    private static String robotMode() {
        var nt = NetworkTablesManager.getInstance();
        var ds = nt.getDriverStation();
        if (!nt.getNTInst().isConnected() || !ds.hasControlWord()) return "no-robot";
        var w = ds.current();
        if (w.m_emergencyStop()) return "estop";
        if (!w.m_enabled()) return "disabled";
        if (w.m_test()) return "test";
        return w.m_autonomous() ? "auto" : "teleop";
    }

    /** The FMS event name, from whichever table the robot publishes it in. */
    private String eventName() {
        for (var sub : eventSubs) {
            String e = sub.get();
            if (!e.isBlank()) return e;
        }
        return "";
    }

    private String matchName() {
        for (int i = 0; i < matchNumberSubs.size(); i++) {
            String type =
                    switch ((int) matchTypeSubs.get(i).get()) {
                        case 1 -> "P";
                        case 2 -> "Q";
                        case 3 -> "E";
                        default -> "";
                    };
            long n = matchNumberSubs.get(i).get();
            if (!type.isEmpty() && n > 0) return type + n;
        }
        return "";
    }

    private int nextSessionNumber() {
        int n = 1;
        try {
            if (Files.exists(SEQ_FILE)) n = Integer.parseInt(Files.readString(SEQ_FILE).strip());
        } catch (Exception e) {
            n = 1;
        }
        // photonvision-65: never below the newest recording on disk. This file is on the scratch
        // partition, which takes power cuts; lost or rolled back, the numbering started again, and
        // since name order is age order (listSessions), the quota then deleted the newest first.
        for (Path p : listSessions()) {
            String f = p.getFileName().toString();
            int end = f.indexOf('_');
            try {
                n = Math.max(n, Integer.parseInt(end > 0 ? f.substring(0, end) : f) + 1);
            } catch (NumberFormatException e) {
                // not one of ours
            }
        }
        try {
            Files.writeString(SEQ_FILE, Integer.toString(n + 1));
        } catch (IOException e) {
            logger.error("Rewind: cannot write " + SEQ_FILE, e);
        }
        return n;
    }

    // ---- disk

    private long freeBytes() {
        return ROOT.toFile().getUsableSpace();
    }

    /** Delete the oldest sessions past the quota or when the disk is low. */
    private void manageDisk() {
        var s = settings;
        long quota = (long) (s.quotaGB * 1e9);
        long minFree = (long) (s.minFreeGB * 1e9);
        List<Path> sessions = listSessions();
        long total = 0;
        Map<Path, Long> sizes = new LinkedHashMap<>();
        for (Path p : sessions) {
            long b = treeSize(p);
            sizes.put(p, b);
            total += b;
        }
        var cur = current;
        for (Path p : sessions) { // oldest first
            if (total <= quota && freeBytes() >= minFree) break;
            if (cur != null && p.equals(cur.dir())) continue;
            logger.info(
                    "Rewind: deleting oldest session "
                            + p.getFileName()
                            + " (quota "
                            + s.quotaGB
                            + " GB, free "
                            + freeBytes() / 1_000_000_000
                            + " GB)");
            if (deleteTree(p)) total -= sizes.get(p);
        }
        sessionsBytes = total;
        sessionCount = listSessions().size();
        boolean low = freeBytes() < minFree / 2;
        if (low && !diskLow) logger.error("Rewind: disk nearly full, recording stopped");
        diskLow = low;
    }

    private static List<Path> listSessions() {
        try (Stream<Path> st = Files.list(SESSIONS)) {
            // Names start with a zero-padded session number, so name order is age order.
            return st.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long treeSize(Path p) {
        try (Stream<Path> st = Files.walk(p)) {
            return st.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** True when p is gone afterwards. */
    private static boolean deleteTree(Path p) {
        try (Stream<Path> st = Files.walk(p)) {
            st.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            logger.error("Rewind: cannot delete " + p, e);
        }
        if (Files.exists(p)) {
            logger.error("Rewind: couldn't delete all of " + p);
            return false;
        }
        return true;
    }

    // ---- status for the web UI (GET /api/rewind)

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        var cur = current;
        var ntm = NetworkTablesManager.getInstance();
        m.put("recording", cur != null);
        m.put("session", cur != null ? cur.name() : "");
        m.put("reason", cur != null ? cur.reason() : "");
        m.put("seconds", cur != null ? (NetworkTablesJNI.now() - cur.startNs()) / 1e9 : 0);
        m.put("stopReason", stopReason);
        m.put("manual", manual);
        m.put("robotConnected", ntm.getNTInst().isConnected());
        m.put("robotRequest", ntm.getNTInst().isConnected() && recordSub != null && recordSub.get());
        m.put("diskLow", diskLow);
        m.put("freeGB", freeBytes() / 1e9);
        m.put("usedGB", sessionsBytes / 1e9);
        m.put("sessionCount", sessionCount);
        m.put("settings", settings.toMap());
        List<Map<String, Object>> cams = new ArrayList<>();
        for (var r : recorders) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("camera", r.getNickname());
            c.put("fps", Math.round(r.keptFps * 10) / 10.0);
            c.put("frames", r.framesWritten);
            c.put("MB", Math.round(r.bytesWritten / 1e5) / 10.0);
            c.put("problem", r.lastProblem);
            cams.add(c);
        }
        m.put("cameras", cams);
        List<Map<String, Object>> recent = new ArrayList<>();
        var all = listSessions();
        for (int i = all.size() - 1; i >= 0 && recent.size() < 10; i--) {
            Path p = all.get(i);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", p.getFileName().toString());
            e.put("MB", Math.round(treeSize(p) / 1e5) / 10.0);
            recent.add(e);
        }
        m.put("recent", recent);
        return m;
    }

    // ---- helpers

    private void loadSettings() {
        if (!Files.exists(SETTINGS_FILE)) return;
        try {
            JsonObject j = json.fromJsonObject(Files.readString(SETTINGS_FILE));
            var s = new Settings();
            s.fps = j.extract("fps", s.fps);
            s.quotaGB = j.extract("quotaGB", s.quotaGB);
            s.minFreeGB = j.extract("minFreeGB", s.minFreeGB);
            s.recordThroughDisconnects =
                    j.extract("recordThroughDisconnects", s.recordThroughDisconnects);
            settings = s;
        } catch (IOException | RuntimeException e) {
            logger.error("Rewind: bad " + SETTINGS_FILE + ", using defaults", e);
        }
    }

    /** settings.json, the same fields as 2026's (written by Jackson then). */
    private static void saveSettings(Settings s) {
        JsonObject j =
                JsonObject.create()
                        .add("fps", JsonDouble.of(s.fps))
                        .add("quotaGB", JsonDouble.of(s.quotaGB))
                        .add("minFreeGB", JsonDouble.of(s.minFreeGB))
                        .add("recordThroughDisconnects", s.recordThroughDisconnects);
        try {
            Files.writeString(SETTINGS_FILE, json.toJson(j));
        } catch (IOException | RuntimeException e) {
            logger.error("Rewind: cannot save settings", e);
        }
    }

    /** Letters, digits, '-', '_' and '.' only (session and camera folder names). */
    public static String safeName(String s) {
        String r = s.replaceAll("[^A-Za-z0-9._-]", "-");
        return r.isEmpty() || r.startsWith(".") ? "x" + r : r;
    }

    /** nice 10 for the calling thread, so the vision threads win any contention for a core. */
    static void lowerThreadPriority(Logger log) {
        setThreadNice(10, log);
    }

    /** Sets the calling thread's nice value (PhotonVision runs as root, so it can go back to 0). */
    public static void setThreadNice(int nice, Logger log) {
        try {
            String self = Files.readSymbolicLink(Path.of("/proc/thread-self")).toString();
            String tid = self.substring(self.lastIndexOf('/') + 1);
            new ProcessBuilder("renice", "-n", Integer.toString(nice), "-p", tid)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (Exception e) {
            log.warn("Rewind: could not set thread priority: " + e.getMessage());
        }
    }

    /** The folder of a finished or current recording, or null if there's no such recording. */
    public static Path sessionDir(String name) {
        if (name == null || name.isEmpty() || !safeName(name).equals(name)) return null;
        Path p = SESSIONS.resolve(name);
        return p.getParent().equals(SESSIONS) && Files.isDirectory(p) ? p : null;
    }
}
