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

package org.photonvision.common.dataflow.networktables;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.photonvision.common.LoadJNI;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.wpilib.networktables.NetworkTablesJNI;
import org.wpilib.networktables.PubSubOption;

/**
 * SpectrumJetson (photonvision-81): a blank result (capture time 0) publishes no result and no
 * heartbeat. At TXMAN1 a dead camera's blank results reached the robot ~50 times a second.
 */
public class NTDataPublisherBlankResultTest {
    @BeforeAll
    public static void init() {
        LoadJNI.loadLibraries();
    }

    @Test
    public void blankResultsPublishNoResultOrHeartbeat() {
        var inst = NetworkTablesManager.getInstance().getNTInst();
        var table = inst.getTable("photonvision").getSubTable("blank-test-cam");
        var all = PubSubOption.KEEP_DUPLICATES;
        var raw =
                table
                        .getRawTopic("rawBytes")
                        .subscribe("", new byte[0], all, PubSubOption.pollStorage(100));
        var heartbeat =
                table.getIntegerTopic("heartbeat").subscribe(-1, all, PubSubOption.pollStorage(100));
        var enabled =
                table.getBooleanTopic("enabled").subscribe(false, all, PubSubOption.pollStorage(100));

        var pub =
                new NTDataPublisher(
                        "blank-test-cam",
                        () -> 0,
                        i -> {},
                        () -> false,
                        b -> {},
                        () -> -1,
                        i -> {},
                        () -> true,
                        b -> {});
        raw.readQueue();
        heartbeat.readQueue();
        enabled.readQueue();

        for (int i = 0; i < 5; i++) pub.accept(new CVPipelineResult(0L, 0, 0, null, new Frame()));
        assertEquals(0, raw.readQueue().length, "no result for a blank frame");
        assertEquals(0, heartbeat.readQueue().length, "no heartbeat for a blank frame");
        assertTrue(enabled.readQueue().length > 0, "the camera's state still goes out");
        assertTrue(enabled.get());

        var real = new CVPipelineResult(7L, 1e6, 100, List.of(), new Frame());
        real.setImageCaptureTimestampNanos(NetworkTablesJNI.now());
        pub.accept(real);
        assertEquals(1, raw.readQueue().length, "a real frame's result goes out");
        assertEquals(7, heartbeat.get());
    }
}
