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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SpectrumJetson Rewind: a recording as a download, built while it streams.
 *
 * <p>The zip holds, per camera, {@code <Camera>.avi} (the recorded JPEGs in an MJPEG AVI, the same
 * bytes, nothing re-encoded) and {@code <Camera>.frames.csv} (each frame's Jetson and robot time),
 * plus {@code session.json}. It matches what {@code scripts/host/rewind-export.py} makes on a
 * laptop. The AVI layout is computed from the index first, so each file is written in one pass
 * straight to the network: nothing is built in memory or on disk. Zip entries are stored without
 * compression (JPEGs don't compress), so the CPU cost is a copy plus a CRC.
 */
public final class RewindExport {
    private RewindExport() {}

    /** Stay under AVI 1.0's 2 GB RIFF limit; longer recordings are split into parts. */
    private static final long AVI_PART_BYTES = 1_900_000_000L;

    private record Frame(
            Path data, long offset, int size, int width, int height, long jetsonUs, String robotUs) {}

    /** Every complete frame of one camera, in order. */
    private static List<Frame> readFrames(Path cameraDir) throws IOException {
        List<Frame> frames = new ArrayList<>();
        List<Path> indexes;
        try (Stream<Path> st = Files.list(cameraDir)) {
            indexes = st.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
        }
        for (Path idx : indexes) {
            String base = idx.getFileName().toString().replaceAll("\\.csv$", "");
            Path data = idx.resolveSibling(base + ".mjpeg");
            if (!Files.exists(data)) continue;
            long length = Files.size(data);
            try (BufferedReader r = Files.newBufferedReader(idx)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    String[] f = line.split(",", -1);
                    if (f.length < 7) continue;
                    long off = Long.parseLong(f[1]);
                    int size = Integer.parseInt(f[2]);
                    if (off + size > length) break; // the recorder stopped mid-write
                    frames.add(
                            new Frame(
                                    data,
                                    off,
                                    size,
                                    Integer.parseInt(f[3]),
                                    Integer.parseInt(f[4]),
                                    Long.parseLong(f[5]),
                                    f[6]));
                }
            }
        }
        return frames;
    }

    /** Writes the recording {@code sessionDir} as a zip to {@code out}. */
    public static void writeZip(Path sessionDir, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(Deflater.NO_COMPRESSION);
        Path meta = RewindManager.sessionJson(sessionDir);
        if (Files.exists(meta)) {
            zip.putNextEntry(new ZipEntry("session.json"));
            Files.copy(meta, zip);
            zip.closeEntry();
        }
        List<Path> cameras;
        try (Stream<Path> st = Files.list(sessionDir)) {
            cameras = st.filter(Files::isDirectory).sorted().toList();
        }
        for (Path cam : cameras) {
            List<Frame> frames = readFrames(cam);
            if (frames.isEmpty()) continue;
            String name = cam.getFileName().toString();
            writeFramesCsv(zip, name, frames);

            // Split into parts under the AVI size limit.
            List<List<Frame>> parts = new ArrayList<>();
            int start = 0;
            long bytes = 0;
            for (int i = 0; i < frames.size(); i++) {
                long cost = 24 + frames.get(i).size() + 1; // chunk header + index entry + pad
                if (bytes + cost > AVI_PART_BYTES && i > start) {
                    parts.add(frames.subList(start, i));
                    start = i;
                    bytes = 0;
                }
                bytes += cost;
            }
            parts.add(frames.subList(start, frames.size()));
            for (int p = 0; p < parts.size(); p++) {
                String file = p == 0 ? name + ".avi" : name + ".part" + (p + 1) + ".avi";
                zip.putNextEntry(new ZipEntry(file));
                writeAvi(zip, parts.get(p));
                zip.closeEntry();
            }
        }
        zip.finish();
        zip.flush();
    }

    private static void writeFramesCsv(ZipOutputStream zip, String name, List<Frame> frames)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name + ".frames.csv"));
        StringBuilder sb = new StringBuilder("frame,video_s,jetson_us,robot_us,robot_s\n");
        long t0 = frames.get(0).jetsonUs();
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            sb.append(i)
                    .append(',')
                    .append(String.format("%.6f", (f.jetsonUs() - t0) / 1e6))
                    .append(',')
                    .append(f.jetsonUs())
                    .append(',')
                    .append(f.robotUs())
                    .append(',');
            if (!f.robotUs().isEmpty()) {
                sb.append(String.format("%.6f", Long.parseLong(f.robotUs()) / 1e6));
            }
            sb.append('\n');
            if (sb.length() > 1 << 16) {
                zip.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
                sb.setLength(0);
            }
        }
        zip.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        zip.closeEntry();
    }

    /**
     * An AVI 1.0 file: RIFF 'AVI ' { LIST hdrl { avih, LIST strl { strh, strf } }, LIST movi { '00dc'
     * chunks }, idx1 }. Sizes are known up front from the index, so it streams in one pass.
     */
    private static void writeAvi(OutputStream out, List<Frame> frames) throws IOException {
        int n = frames.size();
        int width = frames.get(0).width();
        int height = frames.get(0).height();
        long span = frames.get(n - 1).jetsonUs() - frames.get(0).jetsonUs();
        double fps = n > 1 && span > 0 ? (n - 1) / (span / 1e6) : 30.0;
        long moviData = 4; // 'movi'
        int maxFrame = 0;
        for (Frame f : frames) {
            moviData += 8 + f.size() + (f.size() & 1);
            maxFrame = Math.max(maxFrame, f.size());
        }
        long idx1 = 8 + 16L * n;
        long riffSize = 4 + 200 + (8 + moviData) + idx1;

        ByteBuffer h = ByteBuffer.allocate(212 + 12).order(ByteOrder.LITTLE_ENDIAN);
        fourcc(h, "RIFF").putInt((int) riffSize);
        fourcc(h, "AVI ");
        fourcc(h, "LIST").putInt(192);
        fourcc(h, "hdrl");
        fourcc(h, "avih").putInt(56);
        h.putInt((int) Math.round(1e6 / fps)); // microseconds per frame
        h.putInt((int) (maxFrame * fps)); // max bytes per second
        h.putInt(0); // padding granularity
        h.putInt(0x10); // AVIF_HASINDEX
        h.putInt(n); // total frames
        h.putInt(0); // initial frames
        h.putInt(1); // streams
        h.putInt(maxFrame); // suggested buffer size
        h.putInt(width).putInt(height);
        h.putInt(0).putInt(0).putInt(0).putInt(0); // reserved
        fourcc(h, "LIST").putInt(116);
        fourcc(h, "strl");
        fourcc(h, "strh").putInt(56);
        fourcc(h, "vids");
        fourcc(h, "MJPG");
        h.putInt(0); // flags
        h.putShort((short) 0).putShort((short) 0); // priority, language
        h.putInt(0); // initial frames
        h.putInt(1000); // scale
        h.putInt((int) Math.round(fps * 1000)); // rate (rate/scale = fps)
        h.putInt(0); // start
        h.putInt(n); // length
        h.putInt(maxFrame); // suggested buffer size
        h.putInt(-1); // quality
        h.putInt(0); // sample size
        h.putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
        fourcc(h, "strf").putInt(40);
        h.putInt(40).putInt(width).putInt(height);
        h.putShort((short) 1).putShort((short) 24);
        fourcc(h, "MJPG");
        h.putInt(width * height * 3).putInt(0).putInt(0).putInt(0).putInt(0);
        fourcc(h, "LIST").putInt((int) moviData);
        fourcc(h, "movi");
        out.write(h.array(), 0, h.position());

        // Frames, reading each segment file in order.
        byte[] buf = new byte[1 << 20];
        ByteBuffer chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        Path open = null;
        RandomAccessFile raf = null;
        try {
            for (Frame f : frames) {
                if (!f.data().equals(open)) {
                    if (raf != null) raf.close();
                    raf = new RandomAccessFile(f.data().toFile(), "r");
                    open = f.data();
                }
                chunk.clear();
                fourcc(chunk, "00dc").putInt(f.size());
                out.write(chunk.array(), 0, 8);
                if (buf.length < f.size()) buf = new byte[f.size()];
                raf.seek(f.offset());
                raf.readFully(buf, 0, f.size());
                out.write(buf, 0, f.size());
                if ((f.size() & 1) == 1) out.write(0);
            }
        } finally {
            if (raf != null) raf.close();
        }

        // idx1: offsets from the 'movi' fourcc.
        ByteBuffer idx = ByteBuffer.allocate(8 + 16 * 4096).order(ByteOrder.LITTLE_ENDIAN);
        fourcc(idx, "idx1").putInt((int) (16L * n));
        long pos = 4;
        for (Frame f : frames) {
            if (idx.remaining() < 16) {
                out.write(idx.array(), 0, idx.position());
                idx.clear();
            }
            fourcc(idx, "00dc").putInt(0x10).putInt((int) pos).putInt(f.size()); // AVIIF_KEYFRAME
            pos += 8 + f.size() + (f.size() & 1);
        }
        out.write(idx.array(), 0, idx.position());
    }

    private static ByteBuffer fourcc(ByteBuffer b, String s) {
        return b.put(s.getBytes(StandardCharsets.US_ASCII));
    }
}
