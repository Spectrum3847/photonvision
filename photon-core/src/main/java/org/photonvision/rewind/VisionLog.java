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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.photonvision.common.dataflow.structures.Packet;
import org.photonvision.common.dataflow.structures.PacketSerde;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.targeting.PhotonPipelineResult;
import org.wpilib.datalog.BooleanLogEntry;
import org.wpilib.datalog.DataLog;
import org.wpilib.datalog.DataLogBackgroundWriter;
import org.wpilib.datalog.IntegerLogEntry;
import org.wpilib.datalog.RawLogEntry;
import org.wpilib.datalog.StringLogEntry;
import org.wpilib.datalog.StructLogEntry;
import org.wpilib.math.geometry.Pose3d;

/**
 * SpectrumJetson (photonvision-79): {@code <session>_vision.wpilog} beside a Rewind recording's
 * video, with every result PhotonVision publishes, as the robot would receive it, so a match can be
 * replayed even when the robot lost the connection.
 *
 * <p>Timestamps are the Jetson's clock ({@code jetson_us}, as in the frame tables). Each result
 * also carries its robot-clock times; {@code /Jetson/robotOffsetUs} converts anything else. Per
 * camera: {@code /<camera>/result} (PhotonVision's photonstruct, the bytes sent to the robot) and
 * {@code /<camera>/multiTagCameraPose}. {@code /Jetson/*}: connection, offset, robot mode, match.
 *
 * <p>WPILib 2027 keeps NetworkTables' clock and the time-sync offset in nanoseconds; callers pass
 * those, and this class writes microseconds, as in 2026 (the wpilog format's timestamps, the frame
 * tables' {@code jetson_us}, and {@code robotOffsetUs}). The result bytes are WPILib 2027
 * PhotonVision's own (their timestamps are nanoseconds, as the robot receives them).
 */
public final class VisionLog {
    private static final Logger logger = new Logger(VisionLog.class, LogGroup.General);
    private static final String RESULT_TYPE = PhotonPipelineResult.photonStruct.getTypeString();

    private static DataLog log;
    private static final Map<String, RawLogEntry> results = new HashMap<>();
    private static final Map<String, StructLogEntry<Pose3d>> poses = new HashMap<>();
    private static IntegerLogEntry offsetEntry;
    private static BooleanLogEntry connectedEntry;
    private static StringLogEntry modeEntry;
    private static StringLogEntry matchEntry;
    private static Long lastOffset;
    private static Boolean lastConnected;
    private static String lastMode;
    private static String lastMatch;

    private VisionLog() {}

    static synchronized void open(Path file) {
        close();
        try {
            log =
                    new DataLogBackgroundWriter(
                            file.getParent().toString(), file.getFileName().toString(), 0.25);
            addSchema(PhotonPipelineResult.photonStruct);
            offsetEntry = new IntegerLogEntry(log, "/Jetson/robotOffsetUs");
            connectedEntry = new BooleanLogEntry(log, "/Jetson/ntConnected");
            modeEntry = new StringLogEntry(log, "/Jetson/robotMode");
            matchEntry = new StringLogEntry(log, "/Jetson/match");
        } catch (RuntimeException e) {
            logger.error("Rewind: cannot open " + file, e);
            log = null;
        }
    }

    static synchronized void close() {
        if (log == null) return;
        log.close();
        log = null;
        results.clear();
        poses.clear();
        lastOffset = null;
        lastConnected = null;
        lastMode = null;
        lastMatch = null;
    }

    /** From each camera's vision thread, with the result it just published (nowNs: NT clock). */
    public static synchronized void result(String camera, PhotonPipelineResult r, long nowNs) {
        if (log == null) return;
        long nowUs = nowNs / 1000;
        var packet = new Packet(1024);
        PhotonPipelineResult.photonStruct.pack(packet, r);
        results
                .computeIfAbsent(camera, c -> new RawLogEntry(log, "/" + c + "/result", "", RESULT_TYPE))
                .append(packet.getWrittenDataCopy(), nowUs);
        var multi = r.getMultiTagResult();
        if (multi.isPresent()) {
            var t = multi.get().estimatedPose.best;
            poses
                    .computeIfAbsent(camera, VisionLog::poseEntry)
                    .append(new Pose3d(t.getTranslation(), t.getRotation()), nowUs);
        }
    }

    /** From Rewind's tick: the link to the robot, logged when it changes (nanosecond arguments). */
    static synchronized void robot(
            long nowNs, boolean connected, long offsetNs, String mode, String match) {
        if (log == null) return;
        long nowUs = nowNs / 1000;
        long offsetUs = offsetNs / 1000;
        if (!Objects.equals(lastConnected, connected)) connectedEntry.append(connected, nowUs);
        if (!Objects.equals(lastOffset, offsetUs)) offsetEntry.append(offsetUs, nowUs);
        if (!Objects.equals(lastMode, mode)) modeEntry.append(mode, nowUs);
        if (!Objects.equals(lastMatch, match)) matchEntry.append(match, nowUs);
        lastConnected = connected;
        lastOffset = offsetUs;
        lastMode = mode;
        lastMatch = match;
    }

    private static StructLogEntry<Pose3d> poseEntry(String camera) {
        return StructLogEntry.create(log, "/" + camera + "/multiTagCameraPose", Pose3d.struct);
    }

    private static void addSchema(PacketSerde<?> serde) {
        if (log.hasSchema(serde.getTypeString())) return;
        log.addSchema(serde.getTypeString(), "photonstructschema", serde.getSchema());
        for (var inner : serde.getNestedPhotonMessages()) addSchema(inner);
        for (var inner : serde.getNestedWpilibMessages()) log.addSchema(inner);
    }
}
