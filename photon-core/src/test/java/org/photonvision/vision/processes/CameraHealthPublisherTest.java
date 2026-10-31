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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.photonvision.common.LoadJNI;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;

/**
 * SpectrumJetson (photonvision-81): a dead camera's health keeps reaching the robot. At TXMAN1 its
 * values repeated, NetworkTables dropped the repeats, and the robot's log showed nothing for
 * minutes.
 */
public class CameraHealthPublisherTest {
    @BeforeAll
    public static void init() {
        LoadJNI.loadLibraries();
    }

    @Test
    public void aDeadCameraKeepsReporting() throws InterruptedException {
        var inst = NetworkTableInstance.create();
        var health = inst.getTable("photonvision").getSubTable("health-test-cam").getSubTable("health");
        PubSubOption[] all = {PubSubOption.KEEP_DUPLICATES, PubSubOption.pollStorage(100)};
        var heartbeat = health.getIntegerTopic("heartbeat").subscribe(-1, all);
        var state = health.getStringTopic("state").subscribe("", all);
        var fps = health.getDoubleTopic("fps").subscribe(-1, all);
        var age = health.getIntegerTopic("lastFrameAgeMs").subscribe(-2, all);

        // No results at all (the vision thread produces none): only the 1 s timer publishes.
        new CameraHealthPublisher(
                () -> "health-test-cam", () -> 0, () -> 2, () -> "", () -> "gave-up", () -> 61_000, inst);
        Thread.sleep(3500);

        var beats = heartbeat.readQueue();
        assertTrue(beats.length >= 2, "heartbeat every second: " + beats.length);
        assertTrue(beats[beats.length - 1].value > beats[0].value, "and it counts up");
        var states = state.readQueue();
        assertTrue(states.length >= 2, "a repeated state still goes out each second: " + states.length);
        assertEquals("gave-up", states[states.length - 1].value);
        assertEquals(61_000, age.get());
        assertEquals(-1, fps.get(), "no window has started yet, so no fps");
    }

    @Test
    public void resultsStoppingMeansZeroFps() throws InterruptedException {
        var inst = NetworkTableInstance.create();
        var health =
                inst.getTable("photonvision").getSubTable("health-test-cam2").getSubTable("health");
        var fps =
                health
                        .getDoubleTopic("fps")
                        .subscribe(-1, PubSubOption.KEEP_DUPLICATES, PubSubOption.pollStorage(100));

        var p =
                new CameraHealthPublisher(
                        () -> "health-test-cam2", () -> 0, () -> 0, () -> "", () -> "stalled", () -> 0, inst);
        // One blank result starts a window; then the vision thread produces nothing.
        p.accept(
                new org.photonvision.vision.pipeline.result.CVPipelineResult(
                        0L, 0, 0, null, new org.photonvision.vision.frame.Frame()));
        Thread.sleep(3500);
        var values = fps.readQueue();
        assertTrue(values.length >= 1, "fps 0 from the timer");
        assertEquals(0.0, values[values.length - 1].value);
    }
}
