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

package org.photonvision.vision.pipeline;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.photonvision.common.configuration.NeuralNetworkModelManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.frame.FrameStaticProperties;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.objects.Model;
import org.photonvision.vision.objects.NullModel;
import org.photonvision.vision.objects.ObjectDetector;
import org.photonvision.vision.objects.TensorRtObjectDetector;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.opencv.DualOffsetValues;
import org.photonvision.vision.pipe.impl.Collect2dTargetsPipe;
import org.photonvision.vision.pipe.impl.FilterObjectDetectionsPipe;
import org.photonvision.vision.pipe.impl.NeuralNetworkPipeResult;
import org.photonvision.vision.pipe.impl.SortContoursPipe;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.target.PotentialTarget;
import org.photonvision.vision.target.TargetOrientation;
import org.photonvision.vision.target.TrackedTarget;

/**
 * SpectrumJetson (photonvision-100): "AprilTag + Object" on one camera.
 *
 * <p>Every frame goes to the GPU AprilTag detector exactly as in AprilTagCudaPipeline, and its
 * result is published at once on the camera's own table: no added tag latency. At most objectFpsMax
 * of those frames a second are also copied (the grey frame, ~1 MB) and queued on the model's shared
 * TensorRT service (TensorRtService: batched with the other cameras' frames, on a low-priority CUDA
 * stream). When the detections come back they're published, from the service's thread, on a sibling
 * table "<camera>-objects" with the object frame's OWN capture timestamp and sequence ID, so
 * PhotonLib's {@code new PhotonCamera("<camera>-objects")} reads them unchanged.
 *
 * <p>Why a sibling table rather than both kinds of target in one PhotonPipelineResult: a result has
 * one capture timestamp, and the objects for a frame arrive one inference (~5-15 ms) after its tags
 * were published. Holding the tags back would add that to every tag frame; stamping objects onto a
 * later tag result would date them wrong for pose fusion. The sibling table keeps both exact, needs
 * no PhotonLib or serde change, and robot code can ignore it.
 *
 * <p>At most one frame per camera is in flight: if the model is behind, this camera's next object
 * frame waits rather than queueing. Mono cameras send grey frames; the model sees grey copied to R,
 * G and B (train with grayscale augmentation: docs/OBJECT-DETECTION.md).
 */
public class AprilTagObjectPipeline extends AprilTagCudaPipeline {
    private static final Logger logger =
            new Logger(AprilTagObjectPipeline.class, LogGroup.VisionModule);

    private final Object postLock = new Object();
    private final FilterObjectDetectionsPipe filterPipe = new FilterObjectDetectionsPipe();
    private final SortContoursPipe sortPipe = new SortContoursPipe();
    private final Collect2dTargetsPipe collectPipe = new Collect2dTargetsPipe();

    private ObjectDetector detector = NullModel.getInstance();
    private Model wantedModel = NullModel.getInstance();
    private Set<String> keepClasses = Set.of();
    private ExecutorService fallbackExecutor; // non-TensorRT models: detect off the vision thread

    private final AtomicInteger inFlight = new AtomicInteger();
    private long nextObjectDueNanos = 0;
    private volatile Consumer<CVPipelineResult> objectSink;

    // Objects' own frame rate (completions a second), smoothed.
    private long lastObjectNanos = 0;
    private double objectFps = 0;

    public AprilTagObjectPipeline(AprilTagObjectPipelineSettings settings) {
        super(settings);
    }

    /** Where object results go (VisionModule: the "<camera>-objects" table). */
    public void setObjectSink(Consumer<CVPipelineResult> sink) {
        objectSink = sink;
    }

    private AprilTagObjectPipelineSettings objSettings() {
        return (AprilTagObjectPipelineSettings) settings;
    }

    @Override
    protected void setPipeParamsImpl() {
        super.setPipeParamsImpl();
        var s = objSettings();
        Optional<Model> m =
                s.model != null
                        ? NeuralNetworkModelManager.getInstance().getModel(s.model.modelPath())
                        : Optional.empty();
        if (m.isEmpty()) m = NeuralNetworkModelManager.getInstance().getDefaultModel();
        wantedModel = m.orElse(NullModel.getInstance());
        keepClasses =
                s.objectClasses == null || s.objectClasses.isBlank()
                        ? Set.of()
                        : Arrays.stream(s.objectClasses.split(","))
                                .map(String::trim)
                                .filter(c -> !c.isEmpty())
                                .collect(Collectors.toSet());
        synchronized (postLock) {
            sortPipe.setParams(
                    new SortContoursPipe.SortContoursParams(
                            s.contourSortMode, Math.max(1, s.objectMaxTargets), frameStaticProperties));
            filterPipe.setParams(
                    new FilterObjectDetectionsPipe.FilterContoursParams(
                            s.contourArea,
                            s.contourRatio,
                            frameStaticProperties,
                            s.contourTargetOrientation == TargetOrientation.Landscape));
            collectPipe.setParams(
                    new Collect2dTargetsPipe.Collect2dTargetsParams(
                            s.offsetRobotOffsetMode,
                            s.offsetSinglePoint,
                            new DualOffsetValues(
                                    s.offsetDualPointA,
                                    s.offsetDualPointAArea,
                                    s.offsetDualPointB,
                                    s.offsetDualPointBArea),
                            s.contourTargetOffsetPointEdge,
                            s.contourTargetOrientation,
                            frameStaticProperties));
        }
    }

    @Override
    protected CVPipelineResult process(Frame frame, AprilTagCudaPipelineSettings settings) {
        var result = super.process(frame, settings);
        try {
            maybeSubmitObjects(frame);
        } catch (RuntimeException e) {
            logger.error("Object detection submit failed", e);
        }
        result.objectPipeline = this;
        return result;
    }

    private void maybeSubmitObjects(Frame frame) {
        if (frame.type != FrameThresholdType.GREYSCALE || frame.processedImage.getMat().empty()) {
            return;
        }
        var s = objSettings();
        long now = System.nanoTime();
        if (s.objectFpsMax > 0) {
            long period = 1_000_000_000L / s.objectFpsMax;
            if (nextObjectDueNanos == 0 || now - nextObjectDueNanos > period) nextObjectDueNanos = now;
            if (now < nextObjectDueNanos) return;
            if (inFlight.get() > 0) return; // still waiting on the last one; try the next frame
            nextObjectDueNanos += period;
        } else if (inFlight.get() > 0) {
            return;
        }
        if (detector.getModel() != wantedModel) {
            detector.release();
            detector = wantedModel.load();
        }
        if (detector instanceof NullModel) return;

        final long seq = frame.sequenceID;
        final long captured = frame.timestampNanos;
        final FrameStaticProperties fsp = frame.frameStaticProperties;
        final List<String> names = detector.getClasses();
        CompletableFuture<List<NeuralNetworkPipeResult>> f;
        if (detector instanceof TensorRtObjectDetector trt) {
            f = trt.detectAsync(frame.processedImage.getMat(), s.nms, s.confidence);
        } else {
            if (fallbackExecutor == null) {
                fallbackExecutor =
                        Executors.newSingleThreadExecutor(
                                r -> {
                                    var t =
                                            new Thread(
                                                    () -> {
                                                        // photonvision-102
                                                        org.photonvision.vision.processes.CameraThreads.shared();
                                                        r.run();
                                                    },
                                                    "AprilTag+Object detect");
                                    t.setDaemon(true);
                                    return t;
                                });
            }
            var copy = frame.processedImage.getMat().clone();
            var d = detector;
            double nms = s.nms, conf = s.confidence;
            f =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return d.detect(copy, nms, conf);
                                } finally {
                                    copy.release();
                                }
                            },
                            fallbackExecutor);
        }
        inFlight.incrementAndGet();
        f.whenComplete(
                (dets, err) -> {
                    try {
                        if (err == null) publishObjects(seq, captured, fsp, names, dets, now);
                    } catch (RuntimeException e) {
                        logger.error("Publishing objects failed", e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
    }

    private void publishObjects(
            long seq,
            long captured,
            FrameStaticProperties fsp,
            List<String> names,
            List<NeuralNetworkPipeResult> dets,
            long submittedNanos) {
        var sink = objectSink;
        if (sink == null) return;
        var keep = keepClasses;
        if (!keep.isEmpty()) {
            dets =
                    dets.stream()
                            .filter(d -> d.classIdx() < names.size() && keep.contains(names.get(d.classIdx())))
                            .toList();
        }
        List<TrackedTarget> targets;
        synchronized (postLock) {
            var filtered = filterPipe.run(dets).output;
            var sorted = sortPipe.run(filtered.stream().map(PotentialTarget::new).toList()).output;
            targets = collectPipe.run(sorted).output;
        }
        long now = System.nanoTime();
        if (lastObjectNanos != 0) {
            double inst = 1e9 / Math.max(1, now - lastObjectNanos);
            objectFps = objectFps == 0 ? inst : objectFps * 0.9 + inst * 0.1;
        }
        lastObjectNanos = now;
        // An image-less frame: the capture time, sequence ID and calibration for the publisher.
        var meta = new Frame(seq, new CVMat(), new CVMat(), FrameThresholdType.NONE, captured, fsp);
        sink.accept(new CVPipelineResult(seq, now - submittedNanos, objectFps, targets, meta, names));
    }

    @Override
    public void release() {
        detector.release();
        detector = NullModel.getInstance();
        if (fallbackExecutor != null) fallbackExecutor.shutdownNow();
        filterPipe.release();
        sortPipe.release();
        collectPipe.release();
        super.release();
    }
}
