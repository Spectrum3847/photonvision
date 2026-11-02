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

package org.photonvision.vision.objects;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.opencv.core.Mat;
import org.opencv.core.Rect2d;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.jni.TensorRtJNI;
import org.photonvision.vision.pipe.impl.NeuralNetworkPipeResult;

/**
 * SpectrumJetson (photonvision-100): one TensorRT engine per model, shared by every camera that
 * uses it, with batching across cameras.
 *
 * <p>Cameras submit frames from their own threads; one worker thread takes the first waiting frame,
 * waits up to {@code SPECTRUM_OD_BATCH_MS} (default 2 ms) for frames from the other cameras using
 * the model (it stops waiting as soon as every registered camera has one queued, or the batch is
 * full), and runs them as one batch. A batch of 2-4 costs much less than 2-4 single runs on the
 * Orin's GPU (docs/TECHNICAL.md, "Object detection"). The engine runs on a lowest-priority CUDA
 * stream, so the AprilTag detector's work goes first.
 *
 * <p>The engine is built from the ONNX on the worker thread the first time a model is used (a few
 * minutes; cached afterwards in trt-cache beside the models folder). Until it's ready, submissions
 * return no detections at once: the AprilTag side of a combined pipeline never waits for it.
 *
 * <p>Environment: SPECTRUM_OD_MAX_BATCH (default 4; 1 = no batching), SPECTRUM_OD_BATCH_MS (default
 * 2), SPECTRUM_OD_FP32=1 (default FP16).
 */
public final class TensorRtService {
    private static final Logger logger = new Logger(TensorRtService.class, LogGroup.General);

    public static final int MAX_BATCH = Math.max(1, envInt("SPECTRUM_OD_MAX_BATCH", 4));
    private static final long WINDOW_NANOS =
            (long) (Math.max(0, envDouble("SPECTRUM_OD_BATCH_MS", 2.0)) * 1e6);
    private static final boolean FP16 = !"1".equals(System.getenv("SPECTRUM_OD_FP32"));

    /** Frames queued beyond this are dropped (the GPU can't keep up): newest frames matter most. */
    private static final int MAX_QUEUED = 2 * MAX_BATCH;

    private static final Map<String, TensorRtService> services = new HashMap<>();

    public enum State {
        BUILDING,
        READY,
        FAILED
    }

    private record Request(
            Mat frame,
            double conf,
            double nms,
            CompletableFuture<List<NeuralNetworkPipeResult>> future,
            long submittedNanos) {}

    private final String key;
    private final TensorRtModel model;
    private final LinkedBlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile State state = State.BUILDING;
    private volatile String error = "";
    private volatile int clients = 0;
    private long ptr = 0;
    private int maxBatch = 1;

    // Stats, logged every 10 s.
    private long statBatches, statFrames, statDropped, statLoggedNanos = System.nanoTime();
    private double statDetectMs, statMaxDetectMs, statWaitMs;
    private final int[] statBatchSizes = new int[MAX_BATCH + 1];

    /** The shared service for a model (created, and its engine build started, on first use). */
    public static synchronized TensorRtService acquire(TensorRtModel model) {
        var p = model.getProperties();
        String key = p.modelPath() + "|" + p.resolutionWidth() + "x" + p.resolutionHeight();
        var s = services.get(key);
        if (s == null) {
            s = new TensorRtService(key, model);
            services.put(key, s);
        }
        s.clients++;
        return s;
    }

    /** Releases one user; the last frees the engine. */
    public void release() {
        synchronized (TensorRtService.class) {
            if (--clients > 0) return;
            services.remove(key);
        }
        worker.interrupt();
    }

    private TensorRtService(String key, TensorRtModel model) {
        this.key = key;
        this.model = model;
        worker = new Thread(this::run, "TensorRT " + model.getNickname());
        worker.setDaemon(true);
        worker.start();
    }

    public State getState() {
        return state;
    }

    public String getError() {
        return error;
    }

    /**
     * Queues a frame (grey or BGR, any size; ownership passes to the service, which releases it).
     * Completes with the detections in the frame's pixels, or at once with none while the engine is
     * building, after a failure, or when the queue is full.
     */
    public CompletableFuture<List<NeuralNetworkPipeResult>> submit(
            Mat frame, double conf, double nms) {
        if (state != State.READY || queue.size() >= MAX_QUEUED) {
            if (state == State.READY) {
                synchronized (this) {
                    statDropped++;
                }
            }
            frame.release();
            return CompletableFuture.completedFuture(List.of());
        }
        var f = new CompletableFuture<List<NeuralNetworkPipeResult>>();
        queue.add(new Request(frame, conf, nms, f, System.nanoTime()));
        return f;
    }

    private void run() {
        org.photonvision.vision.processes.CameraThreads.shared(); // photonvision-102
        // Beside the models folder, not in it: model exports zip that folder, and engines are big.
        File cache =
                new File(ConfigProvider.getInstance().getModelsDirectory().getParentFile(), "trt-cache");
        var p = model.getProperties();
        try {
            if (!TensorRtJNI.load()) throw new RuntimeException("libspectrumtrt.so didn't load");
            logger.info(
                    "Loading "
                            + p.nickname()
                            + " ("
                            + p.resolutionWidth()
                            + "x"
                            + p.resolutionHeight()
                            + ", batch <= "
                            + MAX_BATCH
                            + (FP16 ? ", FP16" : ", FP32")
                            + "); the first time, building the engine takes a few minutes");
            long t0 = System.nanoTime();
            ptr =
                    TensorRtJNI.createModel(
                            model.modelFile.getPath(),
                            cache.getPath(),
                            p.resolutionWidth(),
                            p.resolutionHeight(),
                            MAX_BATCH,
                            FP16);
            if (ptr == 0) throw new RuntimeException(TensorRtJNI.lastError());
            int[] info = TensorRtJNI.modelInfo(ptr);
            maxBatch = Math.max(1, Math.min(MAX_BATCH, info[2]));
            if (info[3] > 0 && info[3] != p.labels().size()) {
                logger.warn(
                        p.nickname() + " has " + info[3] + " classes but " + p.labels().size() + " labels");
            }
            logger.info(
                    String.format(
                            "%s ready in %.1f s: %dx%d, batch <= %d",
                            p.nickname(), (System.nanoTime() - t0) / 1e9, info[0], info[1], maxBatch));
            state = State.READY;
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            state = State.FAILED;
            logger.error("TensorRT model " + p.nickname() + " failed: " + error);
            drain();
            return;
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                runBatch();
            }
        } catch (InterruptedException e) {
            // released
        } finally {
            state = State.FAILED;
            error = "released";
            drain();
            TensorRtJNI.destroyModel(ptr);
            ptr = 0;
        }
    }

    private void runBatch() throws InterruptedException {
        var batch = new ArrayList<Request>(maxBatch);
        batch.add(queue.take());
        long deadline = System.nanoTime() + WINDOW_NANOS;
        while (batch.size() < maxBatch && batch.size() < clients) {
            long wait = deadline - System.nanoTime();
            Request r = wait > 0 ? queue.poll(wait, TimeUnit.NANOSECONDS) : queue.poll();
            if (r == null) break;
            batch.add(r);
        }
        int n = batch.size();
        long[] ptrs = new long[n];
        double conf = 1;
        for (int i = 0; i < n; i++) {
            ptrs[i] = batch.get(i).frame.getNativeObjAddr();
            conf = Math.min(conf, batch.get(i).conf);
        }
        long t0 = System.nanoTime();
        float[] out;
        try {
            out = TensorRtJNI.detectBatch(ptr, ptrs, n, conf, batch.get(0).nms, 100);
        } catch (Throwable t) {
            out = null;
            logger.error("TensorRT detect failed", t);
        }
        long t1 = System.nanoTime();
        int at = 0;
        for (int i = 0; i < n; i++) {
            var r = batch.get(i);
            var dets = new ArrayList<NeuralNetworkPipeResult>();
            if (out != null && at < out.length) {
                int count = (int) out[at++];
                for (int k = 0; k < count && at + 5 < out.length; k++, at += 6) {
                    if (out[at + 5] < r.conf) continue;
                    dets.add(
                            new NeuralNetworkPipeResult(
                                    new Rect2d(out[at], out[at + 1], out[at + 2], out[at + 3]),
                                    (int) out[at + 4],
                                    out[at + 5]));
                }
            }
            r.frame.release();
            r.future.complete(dets);
        }
        synchronized (this) {
            statBatches++;
            statFrames += n;
            statBatchSizes[Math.min(n, MAX_BATCH)]++;
            double ms = (t1 - t0) / 1e6;
            statDetectMs += ms;
            statMaxDetectMs = Math.max(statMaxDetectMs, ms);
            statWaitMs += (t0 - batch.get(0).submittedNanos) / 1e6;
            if (t1 - statLoggedNanos > 10_000_000_000L) {
                var sizes = new StringBuilder();
                for (int b = 1; b <= MAX_BATCH; b++) {
                    if (statBatchSizes[b] > 0)
                        sizes.append(" b").append(b).append(":").append(statBatchSizes[b]);
                }
                logger.info(
                        String.format(
                                "TensorRT %s: %d frames in %d batches (%s), detect %.2f ms mean %.2f max"
                                        + " per batch, queue wait %.2f ms, dropped %d",
                                model.getNickname(),
                                statFrames,
                                statBatches,
                                sizes.toString().trim(),
                                statDetectMs / statBatches,
                                statMaxDetectMs,
                                statWaitMs / statBatches,
                                statDropped));
                statBatches = statFrames = statDropped = 0;
                statDetectMs = statMaxDetectMs = statWaitMs = 0;
                java.util.Arrays.fill(statBatchSizes, 0);
                statLoggedNanos = t1;
            }
        }
    }

    private void drain() {
        Request r;
        while ((r = queue.poll()) != null) {
            r.frame.release();
            r.future.complete(List.of());
        }
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double envDouble(String name, double fallback) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
