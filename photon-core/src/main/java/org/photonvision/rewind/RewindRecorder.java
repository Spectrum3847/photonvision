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

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.wpilib.util.PixelFormat;
import org.wpilib.util.RawFrame;
import org.wpilib.vision.camera.UsbCamera;
import org.wpilib.vision.camera.raw.RawSink;

/**
 * SpectrumJetson Rewind: records one camera's own MJPEG frames to the SSD while {@link
 * RewindManager} has a session open.
 *
 * <p>A second cscore sink on the camera, left at "unknown" pixel format, receives the JPEG bytes
 * exactly as the camera sent them: nothing is decoded or re-encoded, so the cost is one copy and
 * one disk write per kept frame. cscore hands each sink the newest frame, so if this thread or the
 * disk falls behind it simply skips frames; it can never hold up the vision pipeline's own sink.
 * The thread runs at nice 10 so the vision threads win any contention for a core.
 *
 * <p>Output, per camera, in 5 min segments: {@code NNNN.mjpeg} (JPEG frames back to back) and
 * {@code NNNN.csv} (one line per frame: offset, size and timestamps). See {@code docs/REWIND.md}.
 *
 * <p>It is independent of USBFrameProvider's capture path (the decoder thread, direct decode, the
 * stuck-camera watchdog, idle and thermal frame limiting): nothing here runs on the camera loop,
 * and between recordings the sink is disabled, so it costs nothing.
 *
 * <p>WPILib 2027's frame times (cscore, NetworkTables) are nanoseconds. The files keep 2026's
 * microseconds ({@code jetson_us}, {@code robot_us}): SpectrumJetson's replay tools read them.
 */
public class RewindRecorder {
    // photonvision-76: longer than a match plus its tail, so a match is one file per camera.
    private static final long SEGMENT_NS = 300_000_000_000L;
    private static final long SYNC_NS = 2_000_000_000L;
    // 2 ms of slack so a 30 fps target from a ~90 fps camera keeps every 3rd frame.
    private static final long KEEP_SLACK_NS = 2_000_000L;

    /** A 1280x800 MJPEG frame is ~50-60 KB; 4 MB covers any mode these cameras have. */
    private static final int FRAME_BUFFER_BYTES = 4 << 20;

    private final Logger logger;
    private final UsbCamera camera;
    private final Supplier<String> nickname;
    private final RawSink sink;
    private final Thread thread;
    private volatile boolean running = true;

    // Stats, read by RewindManager for status.
    volatile long framesWritten = 0;
    volatile long framesSkippedNotMjpeg = 0;
    volatile long bytesWritten = 0;
    volatile long writeErrors = 0;
    volatile long badFrames = 0;
    volatile String lastProblem = "";
    volatile double keptFps = 0;

    // Current output (recording thread only). Times: NetworkTables' clock, nanoseconds.
    private RewindManager.Session session = null;
    private Path cameraDir = null;
    private int segmentIndex = 0;
    private long segmentStartNs = 0;
    private FileChannel data = null;
    private BufferedWriter index = null;
    private long dataOffset = 0;
    private long lastKeptNs = 0;
    private long lastSyncNs = 0;
    private FileOutputStream indexFile = null;
    private long fpsWindowStartNs = 0;
    private int fpsWindowFrames = 0;

    public RewindRecorder(UsbCamera camera, Supplier<String> nickname) {
        this.camera = camera;
        this.nickname = nickname;
        this.logger = new Logger(RewindRecorder.class, nickname.get(), LogGroup.Camera);
        this.sink = new RawSink("rewind_" + camera.getName());
        this.sink.setSource(camera);
        this.sink.setEnabled(false);
        this.thread = new Thread(this::run, "Rewind-" + camera.getName());
        this.thread.setDaemon(true);
        this.thread.start();
        RewindManager.getInstance().register(this);
    }

    public String getNickname() {
        return nickname.get();
    }

    public void stop() {
        running = false;
        RewindManager.getInstance().unregister(this);
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sink.close();
    }

    private void run() {
        RewindManager.lowerThreadPriority(logger);
        try (RawFrame frame = new RawFrame()) {
            // Our own buffer, far bigger than any frame, so cscore never reallocates it. (With
            // cscore's own buffer, RawFrame.getSize() goes stale: it keeps the size of the frame
            // the buffer was first allocated for, so later, larger JPEGs were cut off.) The frame's
            // real length is found from the JPEG itself (jpegLength).
            ByteBuffer buffer = ByteBuffer.allocateDirect(FRAME_BUFFER_BYTES);
            frame.setData(buffer, 0, 0, 0, PixelFormat.UNKNOWN);
            while (running) {
                RewindManager.Session s = RewindManager.getInstance().currentSession();
                if (s == null) {
                    if (session != null) closeOutput();
                    setSinkEnabled(false);
                    keptFps = 0;
                    sleep(100);
                    continue;
                }
                setSinkEnabled(true);

                // Unknown pixel format: cscore returns the camera's own image (the JPEG) untouched.
                frame.setInfo(0, 0, 0, PixelFormat.UNKNOWN);
                long t = sink.grabFrame(frame, 0.5);
                if (t == 0) continue; // timeout or camera gone; try again
                if (frame.getPixelFormat() != PixelFormat.MJPEG) {
                    framesSkippedNotMjpeg++;
                    lastProblem = "camera is not in an MJPEG video mode; not recording it";
                    sleep(200);
                    continue;
                }
                long minIntervalNs = RewindManager.getInstance().minFrameIntervalNs();
                if (s == session && t - lastKeptNs < minIntervalNs - KEEP_SLACK_NS) continue;

                ByteBuffer data = frame.getData();
                // A frame too big for our buffer makes cscore allocate a new one, sized exactly.
                int size = data == buffer ? jpegLength(data) : data.limit();
                if (size <= 0) {
                    badFrames++;
                    lastProblem = "camera sent an incomplete JPEG";
                    continue;
                }

                try {
                    if (s != session) openSession(s, t);
                    if (t - segmentStartNs >= SEGMENT_NS) openSegment(t);
                    writeFrame(data, size, frame.getWidth(), frame.getHeight(), t);
                    lastKeptNs = t;
                    lastProblem = "";
                } catch (IOException e) {
                    writeErrors++;
                    lastProblem = "write failed: " + e.getMessage();
                    logger.error("Rewind write failed", e);
                    closeOutput();
                    sleep(1000);
                }
            }
        } finally {
            closeOutput();
        }
    }

    private void openSession(RewindManager.Session s, long t) throws IOException {
        closeOutput();
        session = s;
        Path dir = s.dir().resolve(RewindManager.safeName(nickname.get()));
        boolean reopening = dir.equals(cameraDir);
        cameraDir = dir;
        Files.createDirectories(cameraDir);
        // photonvision-63: reopened after a write error (closeOutput), the same session goes on
        // in a new segment. Starting again at 0000 truncated the segments already written.
        segmentIndex = nextSegmentIndex(cameraDir);
        if (!reopening) {
            framesWritten = 0;
            bytesWritten = 0;
        }
        openSegment(t);
        logger.info("Rewind: recording to " + cameraDir);
    }

    /** One past the highest ...NNNN.mjpeg already in {@code dir} (0 for a new camera folder). */
    private static int nextSegmentIndex(Path dir) throws IOException {
        int next = 0;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (n.matches("(.*_)?[0-9]{4}\\.mjpeg")) {
                    int end = n.length() - ".mjpeg".length();
                    next = Math.max(next, Integer.parseInt(n.substring(end - 4, end)) + 1);
                }
            }
        }
        return next;
    }

    private void openSegment(long t) throws IOException {
        closeFiles();
        // photonvision-75: named for the session and camera, so a file means the same thing
        // outside its folder (robotlog stores files by name).
        String base =
                String.format("%s_%s_%04d", session.name(), cameraDir.getFileName(), segmentIndex++);
        data =
                FileChannel.open(
                        cameraDir.resolve(base + ".mjpeg"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
        indexFile = new FileOutputStream(cameraDir.resolve(base + ".csv").toFile());
        index = new BufferedWriter(new OutputStreamWriter(indexFile, StandardCharsets.US_ASCII));
        index.write("# frame,offset,size,width,height,jetson_us,robot_us\n");
        dataOffset = 0;
        segmentStartNs = t;
    }

    /** t: the frame's time from cscore, nanoseconds on NetworkTables' clock. */
    private void writeFrame(ByteBuffer buf, int size, int width, int height, long t)
            throws IOException {
        ByteBuffer jpeg = buf.duplicate();
        jpeg.position(0).limit(size);
        while (jpeg.hasRemaining()) data.write(jpeg);

        // robot_us: the robot's clock (the timebase of PhotonLib's result timestamps and of the
        // AdvantageKit log), from PhotonVision's time sync. Blank when not synced to a robot.
        // Both in microseconds in the file, as in 2026.
        String robot = RewindManager.getInstance().robotTimeUs(t);
        index.write(
                framesWritten
                        + ","
                        + dataOffset
                        + ","
                        + size
                        + ","
                        + width
                        + ","
                        + height
                        + ","
                        + t / 1000
                        + ","
                        + robot
                        + "\n");
        dataOffset += size;
        framesWritten++;
        bytesWritten += size;
        // Power cuts: the robot is switched off, not shut down. Force the files to the SSD every
        // 2 s, so a cut loses at most the last ~2 s of video (the Linux default is up to 30 s).
        if (t - lastSyncNs > SYNC_NS) {
            syncFiles();
            lastSyncNs = t;
        }
        fpsWindowFrames++;
        if (t - fpsWindowStartNs >= 2_000_000_000L) {
            keptFps = fpsWindowFrames * 1e9 / (t - fpsWindowStartNs);
            fpsWindowStartNs = t;
            fpsWindowFrames = 0;
        }
    }

    /** Index and video to the SSD, video first: an index line never points past the video. */
    private void syncFiles() throws IOException {
        if (data != null) data.force(true);
        if (index != null) {
            index.flush();
            indexFile.getFD().sync();
        }
    }

    private void closeFiles() {
        try {
            syncFiles();
        } catch (IOException e) {
            logger.error("Rewind: syncing files", e);
        }
        try {
            if (index != null) index.close();
        } catch (IOException e) {
            logger.error("Rewind: closing index", e);
        }
        try {
            if (data != null) data.close();
        } catch (IOException e) {
            logger.error("Rewind: closing data", e);
        }
        index = null;
        indexFile = null;
        data = null;
    }

    private void closeOutput() {
        closeFiles();
        if (session != null) logger.info("Rewind: stopped, " + framesWritten + " frames");
        session = null;
    }

    /**
     * Length of the JPEG at the start of {@code b} (up to and including its EOI marker), or -1 if it
     * isn't a complete JPEG. Walks the marker segments to the scan data, then finds the first EOI in
     * the scan data (an 0xFF there is always followed by 0x00 or a restart marker, so EOI can't
     * appear by accident). Bytes after it are left over from earlier, larger frames.
     */
    static int jpegLength(ByteBuffer b) {
        int n = b.capacity();
        if (n < 4 || (b.get(0) & 0xFF) != 0xFF || (b.get(1) & 0xFF) != 0xD8) return -1;
        int i = 2;
        while (i + 4 <= n) {
            if ((b.get(i) & 0xFF) != 0xFF) return -1;
            int m = b.get(i + 1) & 0xFF;
            if (m == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (m == 0xD9) return i + 2;
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                i += 2;
                continue;
            }
            int len = ((b.get(i + 2) & 0xFF) << 8) | (b.get(i + 3) & 0xFF);
            if (len < 2) return -1;
            i += 2 + len;
            if (m == 0xDA) { // start of scan: entropy-coded data until the next real marker
                while (i + 1 < n) {
                    if ((b.get(i) & 0xFF) == 0xFF) {
                        int next = b.get(i + 1) & 0xFF;
                        if (next == 0xD9) return i + 2;
                        if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7) && next != 0xFF) break;
                    }
                    i++;
                }
            }
        }
        return -1;
    }

    private boolean sinkEnabled = false;

    /** The sink only copies frames while enabled, so it costs nothing between recordings. */
    private void setSinkEnabled(boolean on) {
        if (on != sinkEnabled) {
            sink.setEnabled(on);
            sinkEnabled = on;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
