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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.avaje.json.node.JsonArray;
import io.avaje.json.node.JsonNode;
import io.avaje.json.node.JsonObject;
import io.avaje.jsonb.Jsonb;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.photonvision.common.LoadJNI;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.pipeline.AprilTagCudaPipelineSettings;

/**
 * SpectrumJetson: a robot's 2026 settings database (the patched 2026 fork's) through this branch's
 * migration, loaded into this branch's classes and saved back. Prints, per pipeline, the settings
 * that don't survive the save: the ones a feature still to port must bring back before a Jetson
 * boots this branch with its settings, or they reset to defaults.
 *
 * <p>Needs a database: {@code SPECTRUM_MIGRATION_DB=/path/to/photon.sqlite ./gradlew
 * photon-core:test --tests '*SpectrumDbMigrationTest'}; skipped without one.
 */
public class SpectrumDbMigrationTest {
    @TempDir private Path tmpDir;

    @BeforeAll
    public static void init() {
        LoadJNI.loadLibraries();
        CVMat.enablePrint(false);
    }

    @Test
    public void migrate2026Database() throws Exception {
        var source = System.getenv("SPECTRUM_MIGRATION_DB");
        assumeTrue(
                source != null && Files.isRegularFile(Path.of(source)), "SPECTRUM_MIGRATION_DB not set");

        var db = tmpDir.resolve("photon.sqlite");
        Files.copy(Path.of(source), db);
        Map<String, List<JsonObject>> before = legacyPipelines(db);
        Map<String, Integer> calibrationsBefore = legacyCalibrationCounts(db);
        assertFalse(before.isEmpty(), "no cameras in " + source);

        var provider = new ConfigProvider(tmpDir);
        provider.load();
        assertEquals(
                provider.getExpectedVersion(),
                provider.getDbVersion(),
                "not migrated to the latest version");

        var cameras = provider.getConfig().getCameraConfigurations();
        assertEquals(before.keySet(), cameras.keySet(), "cameras lost or renamed");
        for (var entry : cameras.entrySet()) {
            var cam = entry.getValue();
            System.out.printf(
                    "camera %s (%s): %d pipelines, %d calibrations, path %s%n",
                    cam.nickname,
                    entry.getKey(),
                    cam.pipelineSettings.size(),
                    cam.calibrations.size(),
                    cam.matchedCameraInfo.path());
            assertEquals(
                    before.get(entry.getKey()).size(), cam.pipelineSettings.size(), "pipelines lost");
            assertEquals(
                    calibrationsBefore.get(entry.getKey()), cam.calibrations.size(), "calibrations lost");
            for (int i = 0; i < cam.pipelineSettings.size(); i++) {
                var legacyType = before.get(entry.getKey()).get(i).extract("type");
                assertEquals(
                        legacyType,
                        cam.pipelineSettings.get(i).getClass().getSimpleName(),
                        "pipeline type changed");
            }
            for (var cal : cam.calibrations) {
                System.out.printf(
                        "  calibration %s, %d distortion coefficients%n",
                        cal.resolution, cal.distCoeffs.data.length);
            }
        }
        assertTrue(
                cameras.values().stream()
                        .flatMap(c -> c.pipelineSettings.stream())
                        .anyMatch(p -> p instanceof AprilTagCudaPipelineSettings),
                "no CUDA pipelines after migration");

        // What a save keeps: the next settings change on the Jetson writes exactly this.
        provider.saveToDisk();
        for (var entry : savedPipelines(db).entrySet()) {
            var legacy = before.get(entry.getKey());
            var saved = entry.getValue();
            for (int i = 0; i < saved.size(); i++) {
                var dropped = new TreeSet<>(legacy.get(i).elements().keySet());
                dropped.removeAll(saved.get(i).elements().keySet());
                var added = new TreeSet<>(saved.get(i).elements().keySet());
                added.removeAll(legacy.get(i).elements().keySet());
                System.out.printf(
                        "  %s pipeline %d \"%s\": %d settings dropped by a save %s; new %s%n",
                        entry.getKey(),
                        i,
                        saved.get(i).extract("pipelineNickname"),
                        dropped.size(),
                        dropped,
                        added);
            }
        }
    }

    /** Each camera's pipelines as the 2026 database stores them (type name, then settings). */
    private static Map<String, List<JsonObject>> legacyPipelines(Path db) throws Exception {
        var out = new LinkedHashMap<String, List<JsonObject>>();
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                var rs =
                        conn.createStatement()
                                .executeQuery("SELECT unique_name, pipeline_jsons FROM cameras")) {
            while (rs.next()) {
                var pipelines = new java.util.ArrayList<JsonObject>();
                for (JsonNode s :
                        Jsonb.instance().type(JsonArray.class).fromJson(rs.getString(2)).elements()) {
                    var pair = Jsonb.instance().type(JsonArray.class).fromJson(s.text());
                    var settings = ((JsonObject) pair.elements().get(1)).copy();
                    settings.add("type", pair.elements().get(0).text());
                    pipelines.add(settings);
                }
                out.put(rs.getString(1), pipelines);
            }
        }
        return out;
    }

    private static Map<String, Integer> legacyCalibrationCounts(Path db) throws Exception {
        var out = new LinkedHashMap<String, Integer>();
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                var rs =
                        conn.createStatement().executeQuery("SELECT unique_name, config_json FROM cameras")) {
            while (rs.next()) {
                var cfg = Jsonb.instance().type(JsonObject.class).fromJson(rs.getString(2));
                out.put(rs.getString(1), ((JsonArray) cfg.get("calibrations")).size());
            }
        }
        return out;
    }

    private static Map<String, List<JsonObject>> savedPipelines(Path db) throws Exception {
        var out = new LinkedHashMap<String, List<JsonObject>>();
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                var rs = conn.createStatement().executeQuery("SELECT unique_name, contents FROM cameras")) {
            while (rs.next()) {
                var cfg = Jsonb.instance().type(JsonObject.class).fromJson(rs.getString(2));
                var pipelines = new java.util.ArrayList<JsonObject>();
                for (JsonNode p : ((JsonArray) cfg.get("pipelineSettings")).elements()) {
                    pipelines.add((JsonObject) p);
                }
                out.put(rs.getString(1), pipelines);
            }
        }
        return out;
    }
}
