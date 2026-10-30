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

package org.photonvision.fieldmap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.photonvision.common.LoadJNI;
import org.wpilib.fields.Field;
import org.wpilib.fields.Fields;
import org.wpilib.networktables.NetworkTableInstance;

/** SpectrumJetson (photonvision-74): loading, refusing and keeping the robot's field map. */
public class FieldMapSyncTest {
    @TempDir Path dir;

    static String andymarkJson;
    static String weldedJson;

    /** Stands in for PhotonVision's configuration. */
    final AtomicReference<Field> config = new AtomicReference<>();

    final AtomicInteger applies = new AtomicInteger();
    double clock = 1_760_000_000;

    @BeforeAll
    public static void loadFields() throws Exception {
        LoadJNI.loadLibraries();
        andymarkJson = FieldMapHashTest.toJson(Field.loadField(Fields.FRC_2026_REBUILT_ANDY_MARK));
        weldedJson = FieldMapHashTest.toJson(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
    }

    @BeforeEach
    public void freshConfig() {
        // PhotonVision's own setting before the robot says anything: the welded field.
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
        applies.set(0);
    }

    FieldMapSync sync() {
        return new FieldMapSync(
                dir.resolve("spectrum").resolve("field-map.json"),
                config::get,
                l -> {
                    applies.incrementAndGet();
                    config.set(l);
                },
                () -> clock);
    }

    Path file() {
        return dir.resolve("spectrum").resolve("field-map.json");
    }

    @Test
    public void reportsPhotonVisionsOwnMapBeforeTheRobotSendsOne() {
        var s = sync();
        assertEquals(FieldMapHashTest.WELDED_2026, s.currentHash());
        assertEquals(FieldMapSync.LOCAL_NAME, s.name());
        assertEquals("", s.error());
        assertFalse(s.loadSaved());
        assertFalse(s.offer("", "", ""), "an empty JSON means the robot isn't publishing");
        assertEquals("", s.error());
    }

    @Test
    public void loadsAndSavesTheRobotsMap() {
        var s = sync();
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "k2026RebuiltAndymark"));
        assertEquals(1, applies.get());
        assertEquals(FieldMapHashTest.ANDYMARK_2026, FieldMapHash.hash(config.get()));
        assertEquals(FieldMapHashTest.ANDYMARK_2026, s.currentHash());
        assertEquals("k2026RebuiltAndymark", s.name());
        assertEquals(clock, s.loadedAt());
        assertEquals("", s.error());
        assertTrue(Files.exists(file()));
        assertFalse(
                Files.exists(file().resolveSibling("field-map.json.tmp")), "temporary file left behind");
    }

    @Test
    public void hashIsCaseAndSpaceTolerant() {
        var s = sync();
        assertTrue(
                s.offer(andymarkJson, " " + FieldMapHashTest.ANDYMARK_2026.toUpperCase() + "\n", "a"));
    }

    @Test
    public void refusesAHashMismatch() {
        var s = sync();
        var before = config.get();
        assertFalse(s.offer(andymarkJson, FieldMapHashTest.WELDED_2026, "k2026RebuiltAndymark"));
        assertSame(before, config.get());
        assertEquals(0, applies.get());
        assertTrue(s.error().contains("hashes to " + FieldMapHashTest.ANDYMARK_2026), s.error());
        assertEquals(FieldMapHashTest.WELDED_2026, s.currentHash());
        assertFalse(Files.exists(file()));
        // A missing hash is a mismatch too.
        assertFalse(s.offer(andymarkJson, "", "x"));
        assertSame(before, config.get());
    }

    @Test
    public void refusesInvalidMaps() {
        var s = sync();
        var before = config.get();
        String tag =
                "{\"ID\": %d, \"pose\": {\"translation\": {\"x\": %s, \"y\": 1.0, \"z\": 1.0},"
                        + " \"rotation\": {\"quaternion\": {\"W\": 1.0, \"X\": 0.0, \"Y\": 0.0, \"Z\": 0.0}}}}";
        String field = "\"field-dimensions\": {\"length\": %s, \"width\": %s}";
        String[] bad = {
            "not json",
            "{}",
            "{\"field-tags\": [], " + String.format(field, 16.5, 8.0) + "}",
            "{\"field-tags\": ["
                    + String.format(tag, 1, "1.0")
                    + "], "
                    + String.format(field, 0.0, 0.0)
                    + "}",
            "{\"field-tags\": ["
                    + String.format(tag, 1, "1.0")
                    + "], "
                    + String.format(field, 165.0, 8.0)
                    + "}",
            "{\"field-tags\": ["
                    + String.format(tag, 1, "1.0")
                    + ", "
                    + String.format(tag, 1, "2.0")
                    + "], "
                    + String.format(field, 16.5, 8.0)
                    + "}",
            "{\"field-tags\": ["
                    + String.format(tag, 1, "40.0")
                    + "], "
                    + String.format(field, 16.5, 8.0)
                    + "}",
            "{\"field-tags\": ["
                    + String.format(tag, -3, "1.0")
                    + "], "
                    + String.format(field, 16.5, 8.0)
                    + "}",
            andymarkJson.substring(0, andymarkJson.length() / 2),
        };
        for (String json : bad) {
            assertFalse(s.offer(json, "0000000000000000", "bad"), json);
            assertTrue(s.error().contains("invalid"), json + " -> " + s.error());
            assertSame(before, config.get());
        }
        assertEquals(0, applies.get());
        assertFalse(Files.exists(file()));
        // A good map afterwards clears the error.
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "good"));
        assertEquals("", s.error());
    }

    @Test
    public void keepsTheRobotsMapWhenARefusedOneFollows() {
        var s = sync();
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        var loaded = config.get();
        assertFalse(s.offer(weldedJson, "deadbeefdeadbeef", "welded"));
        assertSame(loaded, config.get());
        assertEquals(FieldMapHashTest.ANDYMARK_2026, s.currentHash());
        assertEquals("am", s.name());
        // and at the next boot, too
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
        var next = sync();
        assertTrue(next.loadSaved());
        assertEquals(FieldMapHashTest.ANDYMARK_2026, next.currentHash());
    }

    @Test
    public void theSameMapIsNotReloaded() throws Exception {
        var s = sync();
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        var loaded = config.get();
        long modified = Files.getLastModifiedTime(file()).toMillis();
        clock += 100;
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        assertEquals(1, applies.get());
        assertSame(loaded, config.get());
        assertEquals(clock - 100, s.loadedAt());
        assertEquals(modified, Files.getLastModifiedTime(file()).toMillis());
        // A map PhotonVision already has (here its own setting) is adopted without a reload.
        assertTrue(s.offer(weldedJson, FieldMapHashTest.WELDED_2026, "welded"));
        assertEquals(2, applies.get()); // andymark -> welded is a change
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
        var t = sync();
        assertTrue(t.offer(weldedJson, FieldMapHashTest.WELDED_2026, "welded"));
        assertEquals(2, applies.get());
        assertEquals("welded", t.name());
    }

    @Test
    public void usesTheSavedMapAtTheNextBoot() {
        assertTrue(sync().offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "k2026RebuiltAndymark"));
        // Restart: PhotonVision's database still has the welded field.
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
        applies.set(0);
        clock += 3600;
        var s = sync();
        assertTrue(s.loadSaved());
        assertEquals(1, applies.get());
        assertEquals(FieldMapHashTest.ANDYMARK_2026, s.currentHash());
        assertEquals("k2026RebuiltAndymark", s.name());
        assertEquals(clock, s.loadedAt());
        assertEquals("", s.error());
    }

    @Test
    public void aDamagedSavedMapIsNotUsed() throws Exception {
        assertTrue(sync().offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        String saved = Files.readString(file());

        // edited so the hash no longer matches
        Files.writeString(file(), saved.replace("11.863959", "11.963959"));
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
        var before = config.get();
        var s = sync();
        assertFalse(s.loadSaved());
        assertSame(before, config.get());
        assertTrue(s.error().contains("unusable"), s.error());
        assertEquals(FieldMapHashTest.WELDED_2026, s.currentHash());

        // truncated
        Files.writeString(file(), saved.substring(0, saved.length() / 2));
        assertFalse(sync().loadSaved());
        assertSame(before, config.get());
    }

    @Test
    public void forgetSavedFallsBackToPhotonVisionsMap() {
        var s = sync();
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        FieldMapSync.forgetSaved(file());
        assertFalse(Files.exists(file()));
        // The robot sending the same map again saves it again.
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        assertTrue(Files.exists(file()));
        FieldMapSync.forgetSaved(file());
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED));
        assertFalse(sync().loadSaved());
        assertEquals(FieldMapHashTest.WELDED_2026, FieldMapHash.hash(config.get()));
    }

    @Test
    public void reportsAMapSetSomeOtherWay() {
        var s = sync();
        assertTrue(s.offer(andymarkJson, FieldMapHashTest.ANDYMARK_2026, "am"));
        clock += 5;
        config.set(Field.loadField(Fields.FRC_2026_REBUILT_WELDED)); // e.g. the settings page
        assertEquals(FieldMapHashTest.WELDED_2026, s.currentHash());
        assertEquals(FieldMapSync.LOCAL_NAME, s.name());
        assertEquals(clock, s.loadedAt());
    }

    /** The NetworkTables side, on a local instance: the robot's topics in, the Jetson's out. */
    @Test
    public void networkTables() {
        var nt = NetworkTableInstance.create(); // left open, like NTDriverStationTest's
        var robot = nt.getTable(FieldMapNT.ROBOT_TABLE);
        var json = robot.getStringTopic("Json").publish();
        var hash = robot.getStringTopic("Hash").publish();
        var name = robot.getStringTopic("Name").publish();
        var jetson = nt.getTable(FieldMapNT.JETSON_TABLE);
        var outHash = jetson.getStringTopic("Hash").subscribe("?");
        var outName = jetson.getStringTopic("Name").subscribe("?");
        var outLoadedAt = jetson.getDoubleTopic("LoadedAt").subscribe(-1);
        var outError = jetson.getStringTopic("Error").subscribe("?");

        var adapter = new FieldMapNT(nt, sync());
        assertEquals(FieldMapHashTest.WELDED_2026, outHash.get());
        assertEquals(FieldMapSync.LOCAL_NAME, outName.get());

        // The JSON arrives a poll before its hash: no mismatch is reported in between.
        json.set(andymarkJson);
        adapter.poll();
        hash.set(FieldMapHashTest.ANDYMARK_2026);
        name.set("k2026RebuiltAndymark");
        adapter.poll();
        assertEquals("", outError.get());
        assertEquals(FieldMapHashTest.WELDED_2026, outHash.get());
        adapter.poll();
        assertEquals(FieldMapHashTest.ANDYMARK_2026, outHash.get());
        assertEquals("k2026RebuiltAndymark", outName.get());
        assertEquals(clock, outLoadedAt.get());
        assertEquals("", outError.get());

        // A bad hash: refused, the map in use stays, and the error says so.
        json.set(weldedJson);
        hash.set("0123456789abcdef");
        adapter.poll();
        adapter.poll();
        assertEquals(FieldMapHashTest.ANDYMARK_2026, outHash.get());
        assertTrue(outError.get().contains("0123456789abcdef"), outError.get());
    }
}
