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
package org.photonvision.common.configuration;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.photonvision.PhotonVersion;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.ShellExec;

/**
 * SpectrumJetson (photonvision-53): named settings snapshots, kept on the Jetson in
 * /opt/photonvision/snapshots/ID/. A snapshot is every setting that isn't a log or an image:
 * photon.sqlite (cameras, pipelines, calibration results; copied with SQLite's VACUUM INTO, so a
 * save in progress can't tear it), the config folder's spectrum/ folder (excluded tags, the robot's
 * field map), and our other settings files (Robot state, Rewind, far search). About 2 MB each.
 *
 * <p>Restoring first takes an automatic "Before restoring" snapshot (so a restore can be undone),
 * then replaces only those files and restarts PhotonVision. Logs and calibration images are never
 * touched (the stock settings import deletes the whole folder).
 *
 * <p>Automatic snapshots ("before restore", "field connected": the first time the FMS connects each
 * day) are pruned to the newest 20; named ones are kept until deleted.
 *
 * <p>2027: meta.json is read and written as a plain map (avaje), with the same fields as 2026's, so
 * a 2026 Jetson's snapshots list here (restoring one gives 2027's database migration a 2026
 * database, as at the switch). Off a Jetson (no /opt/photonvision) snapshots go beside the config
 * folder.
 */
public final class SettingsSnapshots {
    private static final Logger logger = new Logger(SettingsSnapshots.class, LogGroup.Config);
    private static final Path JETSON_ROOT = Path.of("/opt/photonvision");
    private static final int KEEP_AUTOMATIC = 20;
    private static final JsonType<Map<String, Object>> mapJson =
            Jsonb.instance().type(Types.mapOf(Object.class));

    /** Where snapshots are kept. */
    public static Path root() {
        if (Files.isDirectory(JETSON_ROOT)) return JETSON_ROOT.resolve("snapshots");
        Path config = PathManager.getInstance().getConfigDir().toAbsolutePath();
        return (config.getParent() != null ? config.getParent() : config).resolve("spectrum-snapshots");
    }

    /**
     * Settings files outside the config folder's spectrum/ folder, saved under extra/ by their file
     * name: the Robot state settings (IdleMode), Rewind's, and the far search's (FarSearch).
     */
    private static List<Path> extraFiles() {
        Path config = PathManager.getInstance().getConfigDir();
        Path robotState = JETSON_ROOT.resolve("spectrum-robot-state.json");
        if (!Files.isDirectory(JETSON_ROOT)) robotState = config.resolve("spectrum-robot-state.json");
        return List.of(
                robotState,
                org.photonvision.rewind.RewindManager.ROOT.resolve("settings.json"),
                config.resolve("spectrum-far-search.properties"));
    }

    /** A snapshot's meta.json: id, name, reason, createdAt, version, bytes, cameras. */
    public static class Meta {
        public String id;
        public String name;

        /** "manual", "before restore" or "field connected". */
        public String reason;

        public long createdAt;
        public String version;
        public long bytes;

        /** [{nickname, pipelines: [names]}] */
        public List<Map<String, Object>> cameras = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("reason", reason);
            m.put("createdAt", createdAt);
            m.put("version", version);
            m.put("bytes", bytes);
            m.put("cameras", cameras);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Meta fromMap(Map<String, Object> m) {
            var meta = new Meta();
            meta.id = str(m.get("id"));
            meta.name = str(m.get("name"));
            meta.reason = str(m.get("reason"));
            meta.createdAt = num(m.get("createdAt"));
            meta.version = str(m.get("version"));
            meta.bytes = num(m.get("bytes"));
            if (m.get("cameras") instanceof List<?> l) {
                for (Object c : l) {
                    if (c instanceof Map<?, ?> cm) meta.cameras.add((Map<String, Object>) cm);
                }
            }
            return meta;
        }

        private static String str(Object o) {
            return o == null ? null : o.toString();
        }

        private static long num(Object o) {
            return o instanceof Number n ? n.longValue() : 0;
        }
    }

    private SettingsSnapshots() {}

    private static Meta readMeta(Path dir) throws IOException {
        try {
            var m = mapJson.fromJson(Files.readString(dir.resolve("meta.json")));
            if (m == null) throw new IOException("empty meta.json");
            return Meta.fromMap(m);
        } catch (RuntimeException e) {
            throw new IOException("unreadable meta.json: " + e.getMessage(), e);
        }
    }

    public static synchronized Meta create(String name, String reason) throws IOException {
        // photonvision-63: between a restore and the restart, a snapshot would save the old
        // in-memory settings over the restored database (create() calls saveToDisk()).
        if (restoring) throw new IOException("A restore is in progress; PhotonVision is restarting");
        Path rootDir = root();
        Files.createDirectories(rootDir);
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = rootDir.resolve(id);
        for (int i = 2; Files.exists(dir); i++) dir = rootDir.resolve(id + "-" + i);
        Files.createDirectories(dir);
        try {
            var cm = ConfigProvider.getInstance();
            cm.saveToDisk(); // anything still waiting for the 1 s write task
            Path root = cm.configDirectoryFile.toPath();
            Path db = root.resolve("photon.sqlite");
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                    var st = conn.createStatement()) {
                st.execute(
                        "VACUUM INTO '" + dir.resolve("photon.sqlite").toString().replace("'", "''") + "'");
            } catch (java.sql.SQLException e) {
                throw new IOException("Couldn't copy the settings database: " + e.getMessage(), e);
            }
            Path spectrum = root.resolve("spectrum");
            if (Files.isDirectory(spectrum)) copyTree(spectrum, dir.resolve("spectrum"));
            Files.createDirectories(dir.resolve("extra"));
            for (Path p : extraFiles()) {
                if (Files.exists(p)) Files.copy(p, dir.resolve("extra").resolve(p.getFileName()));
            }
            var meta = new Meta();
            meta.id = dir.getFileName().toString();
            meta.name = name == null || name.isBlank() ? "Snapshot " + meta.id : name.trim();
            meta.reason = reason;
            meta.createdAt = System.currentTimeMillis();
            meta.version = PhotonVersion.versionString;
            for (var c : cm.getConfig().getCameraConfigurations().values()) {
                Map<String, Object> cam = new LinkedHashMap<>();
                cam.put("nickname", c.nickname);
                List<String> pipelines = new ArrayList<>();
                for (var p : c.pipelineSettings) pipelines.add(p.pipelineNickname);
                cam.put("pipelines", pipelines);
                meta.cameras.add(cam);
            }
            meta.bytes = size(dir);
            Files.writeString(
                    dir.resolve("meta.json"), mapJson.toJson(meta.toMap()), StandardCharsets.UTF_8);
            logger.info("Settings snapshot '" + meta.name + "' (" + reason + ") saved as " + dir);
            prune();
            return meta;
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    public static synchronized List<Meta> list() {
        var out = new ArrayList<Meta>();
        Path rootDir = root();
        if (!Files.isDirectory(rootDir)) return out;
        try (Stream<Path> dirs = Files.list(rootDir)) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                if (!Files.exists(d.resolve("meta.json"))) continue;
                try {
                    out.add(readMeta(d));
                } catch (IOException e) {
                    logger.warn("Unreadable snapshot " + d + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Couldn't list snapshots", e);
        }
        out.sort(Comparator.comparingLong((Meta m) -> m.createdAt).reversed());
        return out;
    }

    /** The list as plain maps, for the dashboard. */
    public static List<Map<String, Object>> listAsMaps() {
        return list().stream().map(Meta::toMap).toList();
    }

    /** True if a "field connected" snapshot was already taken today. */
    public static boolean fieldSnapshotToday() {
        long start =
                LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return list().stream()
                .anyMatch(m -> "field connected".equals(m.reason) && m.createdAt >= start);
    }

    public static synchronized void delete(String id) throws IOException {
        deleteTree(dirOf(id));
        logger.info("Settings snapshot " + id + " deleted");
    }

    /**
     * Writes snapshot {@code id} as a zip to {@code os}. photonvision-65: streamed. It used to be
     * built in /tmp, which on the Jetson is the system partition, and never deleted.
     */
    public static void writeZip(String id, OutputStream os) throws IOException {
        Path dir = dirOf(id);
        var zos = new ZipOutputStream(os);
        try (Stream<Path> st = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) st.filter(Files::isRegularFile).sorted()::iterator) {
                zos.putNextEntry(new ZipEntry(dir.relativize(f).toString()));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
        zos.finish();
    }

    private static volatile boolean restoring = false;

    /**
     * Restores snapshot {@code id}: an automatic snapshot of the current settings first, then the
     * snapshot's files in place of the current ones, then PhotonVision restarts.
     *
     * @return the automatic "before restoring" snapshot
     */
    public static synchronized Meta restore(String id) throws IOException {
        Path dir = dirOf(id);
        Meta meta = readMeta(dir);
        var cm = ConfigProvider.getInstance();
        Path root = cm.configDirectoryFile.toPath();
        // photonvision-63: the snapshot is staged beside the live settings first. The "Before
        // restoring" snapshot below prunes old automatic snapshots, which can delete the one being
        // restored; and staging on the same partition makes the database swap a single rename.
        Path stage = root.resolve(".restore-staging");
        deleteTree(stage);
        copyTree(dir, stage);
        Meta before;
        try {
            before = create("Before restoring '" + meta.name + "'", "before restore");
        } catch (IOException | RuntimeException e) {
            deleteTree(stage);
            throw e;
        }
        cm.setWriteTaskEnabled(false);
        cm.disableFlushOnShutdown();
        restoring = true;
        try {
            for (String suffix : List.of("-journal", "-wal", "-shm")) {
                Files.deleteIfExists(root.resolve("photon.sqlite" + suffix));
            }
            // The database: synced, then renamed over the old one. A power cut leaves the old
            // database or the new one, never a truncated one.
            Path db = root.resolve("photon.sqlite");
            Path staged = stage.resolve("photon.sqlite");
            try (var ch =
                    java.nio.channels.FileChannel.open(staged, java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(staged, db, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (Files.isDirectory(stage.resolve("spectrum"))) {
                Path old = root.resolve("spectrum.before-restore");
                deleteTree(old);
                if (Files.exists(root.resolve("spectrum"))) Files.move(root.resolve("spectrum"), old);
                Files.move(stage.resolve("spectrum"), root.resolve("spectrum"));
                deleteTree(old);
            }
        } catch (IOException | RuntimeException e) {
            // Nothing restarts, so PhotonVision goes on with the settings it has in memory: saves
            // back on, so those are what's kept (they overwrite a half-restored database).
            restoring = false;
            cm.setWriteTaskEnabled(true);
            cm.enableFlushOnShutdown();
            cm.requestSave();
            deleteTree(stage);
            throw e;
        }
        // The extra files (Rewind's, idle mode's and the far search's settings) are best effort:
        // Rewind's is on the scratch partition, read-only in quiet mode, and must not fail a restore
        // already done.
        for (Path p : extraFiles()) {
            Path saved = stage.resolve("extra").resolve(p.getFileName());
            // photonvision-59: write into the existing file, don't replace it. With the data
            // partitions (10-data-partition.sh) spectrum-robot-state.json is a bind mount, and
            // replacing a mounted file fails with "Device or resource busy".
            try {
                if (Files.exists(saved)) Files.write(p, Files.readAllBytes(saved));
            } catch (IOException e) {
                logger.warn("Restore: couldn't write " + p + " (" + e.getMessage() + "); kept as it was");
            }
        }
        try {
            deleteTree(stage);
        } catch (IOException e) {
            logger.warn("Restore: couldn't remove " + stage + ": " + e.getMessage());
        }
        logger.info("Restored settings snapshot '" + meta.name + "' (" + id + "); restarting");
        new Thread(
                        () -> {
                            try {
                                Thread.sleep(500); // let the web request answer first
                                new ShellExec().executeBashCommand("systemctl restart photonvision.service");
                            } catch (Exception e) {
                                logger.error("Couldn't restart after restoring; restart PhotonVision by hand", e);
                            }
                        },
                        "SnapshotRestoreRestart")
                .start();
        return before;
    }

    private static Path dirOf(String id) throws IOException {
        if (id == null || !id.matches("[0-9]{8}-[0-9]{6}(-[0-9]+)?")) {
            throw new IOException("Unknown snapshot " + id);
        }
        Path dir = root().resolve(id);
        if (!Files.exists(dir.resolve("meta.json"))) throw new IOException("Unknown snapshot " + id);
        return dir;
    }

    private static void prune() throws IOException {
        var automatic = list().stream().filter(m -> !"manual".equals(m.reason)).toList();
        for (int i = KEEP_AUTOMATIC; i < automatic.size(); i++) {
            deleteTree(root().resolve(automatic.get(i).id));
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> paths = Files.walk(p)) {
            for (Path q : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(q);
            }
        }
    }

    private static long size(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
