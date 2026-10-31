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

import java.util.ArrayDeque;
import java.util.function.Supplier;
import org.photonvision.common.dataflow.CVPipelineResultConsumer;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.IntegerPublisher;
import org.wpilib.networktables.NetworkTable;

/**
 * SpectrumJetson: this camera's mount, estimated from the tags it sees, for setting and checking
 * robotToCamera in robot code.
 *
 * <p>The multi-tag result is the camera's pose on the field. With the robot level on the floor, the
 * camera's height, pitch and roll on the field are its height, pitch and roll on the robot,
 * wherever the robot is. Yaw and the forward/sideways offsets also need the robot's pose, so the
 * camera's field x, y and yaw are published for robot code to combine with its own pose.
 *
 * <p>Every 0.5 s, over the multi-tag frames of the last 2 s, under /photonvision/<camera>/mount/:
 * {@code heightM}, {@code pitchDeg}, {@code rollDeg} (means) and {@code heightStdM}, {@code
 * pitchStdDeg}, {@code rollStdDeg}; {@code fieldXM}, {@code fieldYM}, {@code fieldYawDeg}; {@code
 * reprojErrorPx} (mean); {@code samples} (0: no multi-tag frames, nothing else is updated). Angles
 * follow WPILib's Rotation3d, like robotToCamera: a camera tilted up has negative pitch.
 */
public class MountEstimatePublisher implements CVPipelineResultConsumer {
    private static final long WINDOW_NS = 2_000_000_000L;
    private static final long PUBLISH_NS = 500_000_000L;

    private final Supplier<String> nickname;
    private final ArrayDeque<double[]> samples =
            new ArrayDeque<>(); // {t, z, pitch, roll, x, y, yaw, err}

    private String publishedName = null;
    private DoublePublisher height,
            pitch,
            roll,
            heightStd,
            pitchStd,
            rollStd,
            fieldX,
            fieldY,
            fieldYaw,
            reproj;
    private IntegerPublisher count;
    private long lastPublishNs = 0;

    public MountEstimatePublisher(Supplier<String> nickname) {
        this.nickname = nickname;
    }

    @Override
    public void accept(CVPipelineResult result) {
        long now = System.nanoTime();
        if (result.multiTagResult != null && result.multiTagResult.isPresent()) {
            var est = result.multiTagResult.get().estimatedPose;
            var cam = new Pose3d().plus(est.best); // field to camera
            var r = cam.getRotation();
            samples.addLast(
                    new double[] {
                        now, cam.getZ(), r.getY(), r.getX(), cam.getX(), cam.getY(), r.getZ(), est.bestReprojErr
                    });
        }
        while (!samples.isEmpty() && now - (long) samples.peekFirst()[0] > WINDOW_NS)
            samples.removeFirst();
        if (now - lastPublishNs < PUBLISH_NS) return;
        lastPublishNs = now;
        publish();
    }

    private void publish() {
        ensurePublishers();
        int n = samples.size();
        count.set(n);
        if (n == 0) return;
        double[] sum = new double[8], sq = new double[8];
        double yawSin = 0, yawCos = 0;
        for (double[] s : samples) {
            for (int i = 1; i < 8; i++) {
                sum[i] += s[i];
                sq[i] += s[i] * s[i];
            }
            yawSin += Math.sin(s[6]);
            yawCos += Math.cos(s[6]);
        }
        height.set(sum[1] / n);
        pitch.set(Math.toDegrees(sum[2] / n));
        roll.set(Math.toDegrees(sum[3] / n));
        heightStd.set(std(sum[1], sq[1], n));
        pitchStd.set(Math.toDegrees(std(sum[2], sq[2], n)));
        rollStd.set(Math.toDegrees(std(sum[3], sq[3], n)));
        fieldX.set(sum[4] / n);
        fieldY.set(sum[5] / n);
        fieldYaw.set(Math.toDegrees(Math.atan2(yawSin, yawCos)));
        reproj.set(sum[7] / n);
    }

    private static double std(double sum, double sq, int n) {
        if (n < 2) return 0;
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sq / n - mean * mean));
    }

    /** (Re)creates the topics when the camera is first seen or renamed. */
    private void ensurePublishers() {
        String name = nickname.get();
        if (name.equals(publishedName)) return;
        if (publishedName != null) {
            for (var p :
                    new DoublePublisher[] {
                        height, pitch, roll, heightStd, pitchStd, rollStd, fieldX, fieldY, fieldYaw, reproj
                    }) {
                p.close();
            }
            count.close();
        }
        NetworkTable t =
                NetworkTablesManager.getInstance()
                        .getNTInst()
                        .getTable("photonvision")
                        .getSubTable(name)
                        .getSubTable("mount");
        height = t.getDoubleTopic("heightM").publish();
        pitch = t.getDoubleTopic("pitchDeg").publish();
        roll = t.getDoubleTopic("rollDeg").publish();
        heightStd = t.getDoubleTopic("heightStdM").publish();
        pitchStd = t.getDoubleTopic("pitchStdDeg").publish();
        rollStd = t.getDoubleTopic("rollStdDeg").publish();
        fieldX = t.getDoubleTopic("fieldXM").publish();
        fieldY = t.getDoubleTopic("fieldYM").publish();
        fieldYaw = t.getDoubleTopic("fieldYawDeg").publish();
        reproj = t.getDoubleTopic("reprojErrorPx").publish();
        count = t.getIntegerTopic("samples").publish();
        publishedName = name;
    }
}
