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

import io.avaje.jsonb.Jsonb;
import org.junit.jupiter.api.Test;
import org.wpilib.fields.Field;
import org.wpilib.fields.Fields;

/**
 * SpectrumJetson (photonvision-74). The expected hashes were computed outside Java (Python,
 * rounding like Java's %.4f), so these check the contract, not just this code against itself.
 * Robot code must get the same two reference hashes.
 */
public class FieldMapHashTest {
    /**
     * A field's own WPILib 2027 Field JSON, as WPILib ships it: what robot code sends, with every
     * -0.0 kept (see {@link #photonVisionsJsonWriterDropsTheSignOfZero}).
     */
    static String toJson(Field field) {
        return field.getJson();
    }

    static Field fromJson(String json) {
        return Jsonb.instance().type(Field.class).fromJson(json);
    }

    static final String ANDYMARK_2026 = "2792cd4e868967b8";
    static final String WELDED_2026 = "ca28a7c1139ed099";

    @Test
    public void referenceFields() {
        assertEquals(ANDYMARK_2026, FieldMapHash.hash(Field.loadField(Fields.FRC_2026_REBUILT_ANDY_MARK)));
        assertEquals(WELDED_2026, FieldMapHash.hash(Field.loadField(Fields.FRC_2026_REBUILT_WELDED)));
    }

    @Test
    public void canonicalText() {
        String text = FieldMapHash.canonical(Field.loadField(Fields.FRC_2026_REBUILT_ANDY_MARK));
        String[] lines = text.split("\n", -1);
        assertEquals(33, lines.length); // 32 tags and the field line
        assertEquals("1,11.8640,7.4115,0.8890,0.0000,0.0000,0.0000,1.0000", lines[0]);
        // 0.55245 m is a tie at 4 decimals: Java rounds it up (Python's f"{:.4f}" would say 0.5524).
        assertEquals("13,16.4993,7.3919,0.5525,0.0000,0.0000,0.0000,1.0000", lines[12]);
        assertEquals("field,16.5180,8.0430", lines[32]);
        assertFalse(text.endsWith("\n"));
    }

    @Test
    public void formatting() {
        assertEquals("0.0000", FieldMapHash.format(0.0));
        assertEquals("-0.0000", FieldMapHash.format(-0.0));
        assertEquals("-0.0000", FieldMapHash.format(-6.123233995736766e-17));
        assertEquals("0.0000", FieldMapHash.format(6.123233995736766e-17));
        assertEquals("0.5525", FieldMapHash.format(0.55245));
        assertEquals("-1.2346", FieldMapHash.format(-1.23456));
    }

    static final String NEGATIVE_ZERO_JSON =
            """
            {"field-tags": [
              {"ID": 2, "pose": {"translation": {"x": -0.0, "y": 1.0, "z": 1.0},
                "rotation": {"quaternion": {"W": 1.0, "X": -0.0, "Y": 0.0, "Z": 0.0}}}},
              {"ID": 1, "pose": {"translation": {"x": 1.0, "y": 2.0, "z": 0.55245},
                "rotation": {"quaternion": {"W": -6.123233995736766e-17, "X": 0.0, "Y": 0.0, "Z": 1.0}}}}
            ],
            "field-dimensions": {"length": 16.518, "width": 8.043}}
            """;

    @Test
    public void negativeZeroKeepsItsSign() throws Exception {
        var layout = fromJson(NEGATIVE_ZERO_JSON);
        assertEquals(
                "1,1.0000,2.0000,0.5525,-0.0000,0.0000,0.0000,1.0000\n"
                        + "2,-0.0000,1.0000,1.0000,1.0000,-0.0000,0.0000,0.0000\n"
                        + "field,16.5180,8.0430",
                FieldMapHash.canonical(layout));
        assertEquals("2ebfc3a8b8c327fa", FieldMapHash.hash(layout));
        // The same map with every -0 written as +0 hashes differently: the sign is part of the text.
        var positive = fromJson(NEGATIVE_ZERO_JSON.replace("-0.0,", "0.0,").replace("-6.1", "6.1"));
        assertEquals("8a1d9a629d0e492e", FieldMapHash.hash(positive));
    }

    @Test
    public void survivesAJsonRoundTrip() throws Exception {
        var layout = Field.loadField(Fields.FRC_2026_REBUILT_WELDED);
        assertEquals(WELDED_2026, FieldMapSync.check(toJson(layout)).hash());
    }

    /**
     * WPILib's Field JSON writer (avaje) writes -0.0 as 0, and four 2026 tags have a -0.0 in their
     * rotation: a map written by PhotonVision (its settings database) can hash differently from the
     * robot's. So FieldMapSync keeps the robot's own JSON text for the next boot.
     */
    @Test
    public void photonVisionsJsonWriterDropsTheSignOfZero() throws Exception {
        var layout = Field.loadField(Fields.FRC_2026_REBUILT_WELDED);
        var rewritten = Jsonb.instance().type(Field.class).toJson(layout);
        assertNotEquals(WELDED_2026, FieldMapHash.hash(fromJson(rewritten)));
    }

    /** What the robot publishes (robot code's FieldMapJson): no field-image, tags sorted by ID. */
    static final String ROBOT_JSON =
            """
            {"name":"test","season":"2026","game":"Rebuilt","program":"frc",
             "field-dimensions":{"length":16.518,"width":8.043},
             "field-tags":[
              {"ID":1,"pose":{"translation":{"x":1.0,"y":2.0,"z":0.55245},
                "rotation":{"quaternion":{"W":-6.123233995736766e-17,"X":0.0,"Y":0.0,"Z":1.0}}}},
              {"ID":2,"pose":{"translation":{"x":-0.0,"y":1.0,"z":1.0},
                "rotation":{"quaternion":{"W":1.0,"X":-0.0,"Y":0.0,"Z":0.0}}}}]}
            """;

    @Test
    public void robotsJsonAndThe2026FormHashTheSame() throws Exception {
        assertEquals("2ebfc3a8b8c327fa", FieldMapSync.check(ROBOT_JSON).hash());
        String legacy = NEGATIVE_ZERO_JSON.replace("\"field-tags\"", "\"tags\"").replace("\"field-dimensions\"", "\"field\"");
        assertTrue(legacy.contains("\"tags\""));
        assertEquals("2ebfc3a8b8c327fa", FieldMapSync.check(legacy).hash());
    }
}
