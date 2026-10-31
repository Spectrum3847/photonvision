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

import java.util.EnumSet;
import org.wpilib.networktables.BooleanSubscriber;
import org.wpilib.networktables.NetworkTableEvent;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.NetworkTableListenerPoller;

/**
 * SpectrumJetson (photonvision-77): whether robot code wants a recording, through NetworkTables
 * drops.
 *
 * <p>When the connection drops, ntcore forgets the robot's values. After the reconnect, {@code
 * record} reads as its default (false) until the robot's value arrives again, a moment later. Read
 * as "the robot stopped", that closed the match's recording and opened a new one a tick later: at
 * TXMAN1 (2026-10-17) a flaky robot switch split Q30, Q32 and a playoff match that way. So a robot
 * recording keeps its grace through the reconnect, until a {@code record} value arrives that the
 * robot sent after it. Only an explicit false, or the grace running out, ends it.
 *
 * <p>Connects, disconnects and record values are read from one listener poller each tick, in the
 * order ntcore saw them, so a value is never counted against the wrong connection, and a drop
 * shorter than a tick still counts. (Listener callbacks on NetworkTables' own thread lost events in
 * the tests; the poller didn't.)
 *
 * <p>Times are NetworkTables' clock, which WPILib 2027 keeps in nanoseconds.
 */
final class RobotRecordRequest {
    /** What the robot wants this tick. */
    record State(boolean wants, boolean grace, boolean waitingAfterReconnect) {}

    private final BooleanSubscriber sub;
    private final NetworkTableListenerPoller events;
    // False at each connect or disconnect, true when a record value arrives from the robot.
    private boolean heardSinceConnect = false;
    private long lastTrueNs = Long.MIN_VALUE;

    RobotRecordRequest(NetworkTableInstance nt, BooleanSubscriber sub) {
        this.sub = sub;
        events = new NetworkTableListenerPoller(nt);
        events.addConnectionListener(false);
        events.addListener(sub, EnumSet.of(NetworkTableEvent.Kind.VALUE_REMOTE));
    }

    /**
     * Once a tick. robotSessionOpen: a recording the robot asked for is open, the only kind the grace
     * keeps going. graceNs: how long it keeps going after the robot's last true.
     */
    State update(long nowNs, boolean connected, boolean robotSessionOpen, long graceNs) {
        for (var e : events.readQueue()) {
            heardSinceConnect = !e.is(NetworkTableEvent.Kind.CONNECTION);
        }
        boolean wants = connected && sub.get();
        if (wants) lastTrueNs = nowNs;
        // Disconnected, or reconnected with no value from the robot yet: what record reads is only
        // the default.
        boolean unknown = !connected || !heardSinceConnect;
        boolean grace =
                !wants
                        && unknown
                        && robotSessionOpen
                        && lastTrueNs != Long.MIN_VALUE
                        && nowNs - lastTrueNs < graceNs;
        return new State(wants, grace, grace && connected);
    }
}
