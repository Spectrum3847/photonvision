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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spectrum (photonvision-103): per-frame stage timestamps for the latency breakdown
 * (SpectrumJetson tests/latency-breakdown). Off unless SPECTRUM_LATENCY_TRACE names a CSV file;
 * off costs one static boolean check per mark. All times are System.nanoTime (CLOCK_MONOTONIC);
 * the frame's own timestamps are in NetworkTables' epoch, so each row carries nt_minus_mono to
 * convert them.
 */
public final class LatencyTrace {
    private static final String PATH = System.getenv("SPECTRUM_LATENCY_TRACE");
    public static final boolean ON = PATH != null && !PATH.isEmpty();

    public static final int DET0 = 0, DET1 = 1, POSE1 = 2, NT1 = 3, N = 4;

    // decoder thread -> camera thread, keyed by the frame's published (mid-exposure) timestamp
    private static final ConcurrentHashMap<Long, long[]> decodes = new ConcurrentHashMap<>();
    private static final ThreadLocal<long[]> cur = ThreadLocal.withInitial(() -> new long[N]);
    private static BufferedWriter out;

    private LatencyTrace() {}

    /** Decoder: raw driver timestamp, grab returned (= decode start), decode end. */
    public static void decoded(long publishedTs, long rawTs, long grabRet, long decEnd) {
        if (!ON || publishedTs == 0) return;
        if (decodes.size() > 256) decodes.clear();
        decodes.put(publishedTs, new long[] {rawTs, grabRet, decEnd});
    }

    /** Camera thread: start of a frame's loop iteration. */
    public static void begin() {
        if (ON) java.util.Arrays.fill(cur.get(), 0);
    }

    public static void mark(int stage) {
        if (ON) cur.get()[stage] = System.nanoTime();
    }

    /** Camera thread: the frame was taken at take, pipeline ended at pipeEnd, consumers at end. */
    public static void end(String camera, long publishedTs, long take, long pipeEnd, long end) {
        if (!ON || publishedTs == 0) return;
        long[] c = cur.get();
        long[] d = decodes.remove(publishedTs);
        long ntMinusMono = org.wpilib.networktables.NetworkTablesJNI.now() - System.nanoTime();
        StringBuilder s = new StringBuilder(200);
        s.append(camera.replace(',', '_')).append(',').append(publishedTs).append(',');
        s.append(d == null ? 0 : d[0]).append(',').append(d == null ? 0 : d[1]).append(',');
        s.append(d == null ? 0 : d[2]).append(',').append(take).append(',');
        s.append(c[DET0]).append(',').append(c[DET1]).append(',').append(c[POSE1]).append(',');
        s.append(pipeEnd).append(',').append(c[NT1]).append(',').append(end).append(',');
        s.append(ntMinusMono).append('\n');
        synchronized (LatencyTrace.class) {
            try {
                if (out == null) {
                    out = new BufferedWriter(new FileWriter(PATH, true));
                    out.write(
                            "camera,published_nt,raw_nt,grab_ret,decode_end,take,det0,det1,pose1,pipe_end,"
                                    + "nt_flushed,consumers_end,nt_minus_mono\n");
                }
                out.write(s.toString());
                out.flush();
            } catch (IOException e) {
                // tracing only; ignore
            }
        }
    }
}
