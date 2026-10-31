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

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.photonvision.common.LoadJNI;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.BooleanSubscriber;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.NetworkTablesJNI;

/**
 * SpectrumJetson (photonvision-77): a robot recording through a NetworkTables drop, with real
 * ntcore. A "robot" NetworkTables server and the "Jetson" client on localhost; the drop is the
 * robot server going away and coming back. Each test ticks {@link RobotRecordRequest} the way
 * RewindManager's tick does and counts the sessions that would open and close. Times are
 * NetworkTables' clock, nanoseconds in WPILib 2027.
 */
public class RobotRecordRequestTest {
    static final int PORT4 = 35810; // not the real 5810 (WPILib 2027 has no NT3 port)
    static final long GRACE_NS = 60_000_000_000L;

    NetworkTableInstance jetson, robot;
    BooleanSubscriber sub;
    RobotRecordRequest req;
    RobotRecordRequest.State state;
    boolean open;
    int opened, closed;

    @BeforeAll
    public static void init() {
        LoadJNI.loadLibraries();
    }

    @BeforeEach
    public void connect() throws InterruptedException {
        jetson = NetworkTableInstance.create();
        sub =
                jetson
                        .getTable("photonvision")
                        .getSubTable("rewind")
                        .getBooleanTopic("record")
                        .subscribe(false);
        req = new RobotRecordRequest(jetson, sub);
        jetson.startClient("jetson");
        jetson.setServer("127.0.0.1", PORT4);
    }

    @AfterEach
    public void close() {
        if (robot != null) robot.close();
        jetson.close();
    }

    /** A robot program starting: its NetworkTables server, and record unpublished until set. */
    BooleanPublisher startRobot() {
        robot = NetworkTableInstance.create();
        robot.startServer("", "127.0.0.1", "", PORT4);
        return robot.getTable("photonvision").getSubTable("rewind").getBooleanTopic("record").publish();
    }

    void dropRobot() {
        robot.close();
        robot = null;
    }

    /** One RewindManager tick at nowNs. */
    void tick(long nowNs) {
        state = req.update(nowNs, jetson.isConnected(), open, GRACE_NS);
        boolean want = state.wants() || state.grace();
        if (want && !open) {
            open = true;
            opened++;
        } else if (!want && open) {
            open = false;
            closed++;
        }
    }

    /** Ticks every 20 ms (the real tick is 200 ms: this looks harder for a gap) until ok. */
    void tickUntil(String what, BooleanSupplier ok) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (!ok.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < until, "timed out waiting for: " + what);
            tick(NetworkTablesJNI.now());
            Thread.sleep(20);
        }
        tick(NetworkTablesJNI.now());
    }

    void tickFor(long ms) throws InterruptedException {
        for (long until = System.currentTimeMillis() + ms; System.currentTimeMillis() < until; ) {
            tick(NetworkTablesJNI.now());
            Thread.sleep(20);
        }
    }

    /** Recording, then the robot drops off and comes back with record not yet sent. */
    void recordingThenDropAndReconnect() throws InterruptedException {
        var record = startRobot();
        record.set(true);
        tickUntil("a session opens", () -> open);
        assertEquals(1, opened);

        dropRobot();
        tickUntil("disconnected", () -> !jetson.isConnected());
        tickFor(500);
        assertTrue(open, "kept through the drop (the disconnect grace)");
        // ntcore has forgotten the robot's true: this is what tick() used to read after a reconnect.
        assertFalse(sub.get());

        startRobot();
        tickUntil("reconnected", jetson::isConnected);
        tickFor(1000);
        assertTrue(jetson.isConnected());
        assertFalse(sub.get(), "record reads its default: nothing from the robot yet");
        assertTrue(open, "still the same session, not \"robot stopped\"");
        assertTrue(state.waitingAfterReconnect());
    }

    @Test
    public void lateTrueAfterReconnectKeepsOneSession() throws InterruptedException {
        recordingThenDropAndReconnect();
        var record =
                robot.getTable("photonvision").getSubTable("rewind").getBooleanTopic("record").publish();
        record.set(true);
        tickUntil("the robot's true arrives", sub::get);
        tickFor(500);
        assertTrue(open);
        assertFalse(state.grace());
        assertEquals(1, opened, "one session");
        assertEquals(0, closed);

        // And then the match ends normally.
        record.set(false);
        tickUntil("closes", () -> !open);
        assertEquals(1, opened);
        assertEquals(1, closed);
    }

    @Test
    public void explicitFalseAfterReconnectEndsIt() throws InterruptedException {
        recordingThenDropAndReconnect();
        var record =
                robot.getTable("photonvision").getSubTable("rewind").getBooleanTopic("record").publish();
        record.set(false);
        tickUntil("closes on the robot's false", () -> !open);
        tickFor(300);
        assertEquals(1, opened);
        assertEquals(1, closed);
    }

    @Test
    public void graceRunsOutWithNoValueAfterReconnect() throws InterruptedException {
        recordingThenDropAndReconnect();
        tick(NetworkTablesJNI.now() + GRACE_NS);
        assertFalse(open, "the 60 s grace ends it, connected or not");
        assertEquals(1, closed);
    }

    @Test
    public void noRobotSessionNoGrace() throws InterruptedException {
        // A bench recording (or none) isn't kept by the robot's grace.
        var record = startRobot();
        record.set(true);
        tickUntil("connected", sub::get);
        assertTrue(req.update(NetworkTablesJNI.now(), true, false, GRACE_NS).wants());
        dropRobot();
        tickUntil("disconnected", () -> !jetson.isConnected());
        assertFalse(req.update(NetworkTablesJNI.now(), false, false, GRACE_NS).grace());
    }
}
