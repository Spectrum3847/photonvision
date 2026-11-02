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

import static org.junit.jupiter.api.Assertions.*;

import io.avaje.jsonb.Jsonb;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.photonvision.vision.pipeline.*;

/**
 * SpectrumJetson: Spectrum pipeline settings survive save -> load with their class and settings,
 * and a saved pipeline whose class doesn't match its pipelineType is repaired instead of
 * crash-looping (an AprilTag + Object pipeline used to be saved as AprilTagCudaPipelineSettings).
 */
public class SpectrumPipelineSettingsJsonTest {
    private static CVPipelineSettings roundTrip(CVPipelineSettings s) {
        var t = Jsonb.instance().type(CVPipelineSettings.class);
        return t.fromJson(t.toJson(s));
    }

    @Test
    public void aprilTagObjectRoundTrip() {
        var s = new AprilTagObjectPipelineSettings();
        s.pipelineNickname = "obj";
        s.confidence = 0.31;
        s.objectFpsMax = 7;
        s.objectClasses = "note";
        s.tagsOnCpu = true;
        s.napaMinTagPx = 12;
        s.decimate = 3;
        String json = Jsonb.instance().type(CVPipelineSettings.class).toJson(s);
        assertTrue(json.contains("\"type\":\"AprilTagObjectPipelineSettings\""), json);
        var back = roundTrip(s);
        assertEquals(AprilTagObjectPipelineSettings.class, back.getClass());
        var b = (AprilTagObjectPipelineSettings) back;
        assertEquals(PipelineType.AprilTagObject, b.pipelineType);
        assertEquals("obj", b.pipelineNickname);
        assertEquals(0.31, b.confidence);
        assertEquals(7, b.objectFpsMax);
        assertEquals("note", b.objectClasses);
        assertTrue(b.tagsOnCpu);
        assertEquals(12, b.napaMinTagPx);
        assertEquals(3, b.decimate);
    }

    @Test
    public void aprilTagCudaNapaRoundTrip() {
        var s = new AprilTagCudaPipelineSettings();
        s.tagsOnCpu = true;
        s.napaMinTagPx = 20;
        s.napaMaxHamming = 0;
        s.napaFarSearch = true;
        var back = roundTrip(s);
        assertEquals(AprilTagCudaPipelineSettings.class, back.getClass());
        var b = (AprilTagCudaPipelineSettings) back;
        assertEquals(PipelineType.AprilTagCuda, b.pipelineType);
        assertTrue(b.tagsOnCpu);
        assertEquals(20, b.napaMinTagPx);
        assertEquals(0, b.napaMaxHamming);
        assertTrue(b.napaFarSearch);
    }

    @Test
    public void aprilTagNapaRoundTrip() {
        var s = new AprilTagPipelineSettings();
        s.napa = true;
        s.napaMinTagPx = 24;
        s.napaFarMinTagPx = 10;
        var back = roundTrip(s);
        assertEquals(AprilTagPipelineSettings.class, back.getClass());
        var b = (AprilTagPipelineSettings) back;
        assertTrue(b.napa);
        assertEquals(24, b.napaMinTagPx);
        assertEquals(10, b.napaFarMinTagPx);
    }

    @Test
    public void everyTypeKeepsItsClass() {
        List<CVPipelineSettings> all =
                List.of(
                        new ReflectivePipelineSettings(),
                        new ColoredShapePipelineSettings(),
                        new AprilTagPipelineSettings(),
                        new ArucoPipelineSettings(),
                        new ObjectDetectionPipelineSettings(),
                        new AprilTagCudaPipelineSettings(),
                        new AprilTagObjectPipelineSettings(),
                        new DriverModePipelineSettings());
        for (var s : all) {
            var back = roundTrip(s);
            assertEquals(s.getClass(), back.getClass());
            assertEquals(s.pipelineType, back.pipelineType);
        }
    }

    /** The shape the old bug saved: AprilTag + Object pipelineType in AprilTagCuda settings. */
    @Test
    public void mismatchedSavedTypeIsRepaired() {
        String bad =
                "{\"type\":\"AprilTagCudaPipelineSettings\",\"pipelineType\":\"AprilTagObject\","
                        + "\"pipelineIndex\":0,\"pipelineNickname\":\"saved\",\"decimate\":3,"
                        + "\"tagsOnCpu\":true,\"confidence\":0.4}";
        var loaded = Jsonb.instance().type(CVPipelineSettings.class).fromJson(bad);
        assertEquals(AprilTagCudaPipelineSettings.class, loaded.getClass());
        List<CVPipelineSettings> list = new ArrayList<>(List.of(loaded));
        var repaired = assertDoesNotThrow(() -> PipelineManager.repairLoadedPipelines(list));
        assertEquals(1, repaired.size());
        var r = assertInstanceOf(AprilTagObjectPipelineSettings.class, repaired.get(0));
        assertEquals(PipelineType.AprilTagObject, r.pipelineType);
        assertEquals("saved", r.pipelineNickname);
        assertEquals(3, r.decimate);
        assertTrue(r.tagsOnCpu);

        // PipelineManager's constructor runs this repair before recreateUserPipeline casts.
    }

    @Test
    public void unusableTypeIsDropped() {
        var s = new AprilTagCudaPipelineSettings();
        s.pipelineType = null;
        var keep = new AprilTagPipelineSettings();
        var repaired = PipelineManager.repairLoadedPipelines(new ArrayList<>(List.of(s, keep)));
        assertEquals(List.of(keep), repaired);
    }
}
