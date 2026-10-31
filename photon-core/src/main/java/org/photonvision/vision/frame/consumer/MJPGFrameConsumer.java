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

package org.photonvision.vision.frame.consumer;

import org.photonvision.vision.frame.StaticFrames;
import org.photonvision.vision.opencv.CVMat;
import org.wpilib.networktables.NetworkTablesJNI;
import org.wpilib.util.PixelFormat;
import org.wpilib.vision.camera.*;
import org.wpilib.vision.stream.CameraServer;

public class MJPGFrameConsumer implements AutoCloseable {
    private static final double MAX_FRAMERATE = -1;
    private static final long MAX_FRAME_PERIOD_NS = Math.round(1e9 / MAX_FRAMERATE);

    // SpectrumJetson (photonvision-99): every open stream, for anyViewers() (ClockThrottle's deep
    // idle never starts while someone is watching).
    private static final java.util.Set<MJPGFrameConsumer> OPEN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private long lastFrameTimeNs;
    private CvSource cvSource;
    private MjpegServer mjpegServer;

    public MJPGFrameConsumer(String sourceName, int width, int height, int port) {
        this.cvSource = new CvSource(sourceName, PixelFormat.MJPEG, width, height, 30);

        this.mjpegServer = new MjpegServer("serve_" + cvSource.getName(), port);
        mjpegServer.setSource(cvSource);
        mjpegServer.setCompression(75);
        CameraServer.addServer(mjpegServer);
        OPEN.add(this);
    }

    /** photonvision-99: true while any camera stream has a viewer. */
    public static boolean anyViewers() {
        for (var c : OPEN) {
            try {
                if (c.hasViewers()) return true;
            } catch (RuntimeException e) {
                // closing concurrently
            }
        }
        return false;
    }

    public MJPGFrameConsumer(String name, int port) {
        this(name, 320, 240, port);
    }

    /**
     * SpectrumJetson (photonvision-15): true while a client (the dashboard) is streaming. cscore
     * enables a source only while an MjpegServer connection streams from it
     * (CS_CONNECTION_AUTO_MANAGE).
     */
    public boolean hasViewers() {
        return cvSource != null && cvSource.isEnabled();
    }

    public void accept(CVMat image) {
        long now = NetworkTablesJNI.now();

        if (image == null || image.getMat() == null || image.getMat().empty()) {
            image.copyFrom(StaticFrames.LOST_MAT);
        }

        if (now - lastFrameTimeNs > MAX_FRAME_PERIOD_NS) {
            lastFrameTimeNs = now;
            cvSource.putFrame(image.getMat());
        }
    }

    @Override
    public void close() {
        OPEN.remove(this);
        CameraServer.removeServer(mjpegServer.getName());
        mjpegServer.close();
        cvSource.close();
        mjpegServer = null;
        cvSource = null;
    }
}
