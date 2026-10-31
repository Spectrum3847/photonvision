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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.events.OutgoingUIEvent;
import org.photonvision.common.dataflow.websocket.UIPhotonConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.frame.FrameProvider;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.pipe.impl.HSVPipe;
import org.photonvision.vision.pipeline.AdvancedPipelineSettings;
import org.photonvision.vision.pipeline.ArucoPipelineSettings;
import org.photonvision.vision.pipeline.CVPipeline;
import org.photonvision.vision.pipeline.result.CVPipelineResult;

/**
 * VisionRunner has a frame supplier, a pipeline supplier, and a result consumer; it must be closed
 * prior to the frame supplier closing
 */
@SuppressWarnings("rawtypes")
public class VisionRunner implements AutoCloseable {
    private final Logger logger;
    private final Thread visionProcessThread;
    private final FrameProvider frameSupplier;
    private final Supplier<CVPipeline> pipelineSupplier;
    private final Consumer<CVPipelineResult> pipelineResultConsumer;
    private final VisionModuleChangeSubscriber changeSubscriber;
    private final List<Runnable> runnableList = new ArrayList<Runnable>();
    private final QuirkyCamera cameraQuirks;
    private final Supplier<Integer> fpsLimitSupplier;
    private final Supplier<Boolean> enabledSupplier;

    private long loopCount;
    private long lastGrabNanos = 0; // SpectrumJetson (photonvision-49)
    private long lastLoopErrorLogMs = 0; // photonvision-63: monotonic (System.nanoTime) ms

    /**
     * VisionRunner contains a thread to run a pipeline, given a frame, and will give the result to
     * the consumer.
     *
     * @param frameSupplier
     * @param pipelineSupplier
     * @param pipelineResultConsumer
     * @param cameraQuirks
     * @param changeSubscriber The subscriber to setting changes for this VisionRunner, so it can
     *     update its settings when they change.
     * @param fpsLimitSupplier
     * @param enabledSupplier
     */
    public VisionRunner(
            FrameProvider frameSupplier,
            Supplier<CVPipeline> pipelineSupplier,
            Consumer<CVPipelineResult> pipelineResultConsumer,
            QuirkyCamera cameraQuirks,
            VisionModuleChangeSubscriber changeSubscriber,
            Supplier<Integer> fpsLimitSupplier,
            Supplier<Boolean> enabledSupplier) {
        this.frameSupplier = frameSupplier;
        this.pipelineSupplier = pipelineSupplier;
        this.pipelineResultConsumer = pipelineResultConsumer;
        this.cameraQuirks = cameraQuirks;
        this.changeSubscriber = changeSubscriber;
        this.fpsLimitSupplier = fpsLimitSupplier;
        this.enabledSupplier = enabledSupplier;

        visionProcessThread = new Thread(this::update);
        visionProcessThread.setName("VisionRunner - " + frameSupplier.getName());
        logger = new Logger(VisionRunner.class, frameSupplier.getName(), LogGroup.VisionModule);
        changeSubscriber.processSettingChanges();
    }

    static void configureFrameProviderForPipeline(FrameProvider frameSupplier, CVPipeline pipeline) {
        var wantedProcessType = pipeline.getThresholdType();

        frameSupplier.requestFrameThresholdType(wantedProcessType);
        var settings = pipeline.getSettings();
        if (settings instanceof AdvancedPipelineSettings advanced) {
            var hsvParams =
                    new HSVPipe.HSVParams(
                            advanced.hsvHue, advanced.hsvSaturation, advanced.hsvValue, advanced.hueInverted);
            frameSupplier.requestHsvSettings(hsvParams);
        }

        // Use the pipeline threshold type to determine whether a color image is required,
        // so we can request the correct frame copies for CSI cameras.
        boolean needsColor = wantedProcessType == FrameThresholdType.NONE;

        if (settings instanceof ArucoPipelineSettings ar) {
            needsColor = ar.debugThreshold;
        }

        frameSupplier.requestFrameRotation(settings.inputImageRotationMode);
        frameSupplier.requestFrameCopies(
                settings.inputShouldShow || needsColor,
                settings.outputShouldShow || wantedProcessType != FrameThresholdType.NONE);
        frameSupplier.requestBlockForFrames(settings.blockForFrames);
    }

    public void startProcess() {
        visionProcessThread.start();
    }

    public void stopProcess() {
        try {
            System.out.println("Interrupting vision process thread");
            visionProcessThread.interrupt();
            visionProcessThread.join();
        } catch (InterruptedException e) {
            logger.error("Exception killing process thread", e);
        }
    }

    public boolean isRunning() {
        return visionProcessThread.isAlive();
    }

    public Future<Void> runSynchronously(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        synchronized (runnableList) {
            runnableList.add(
                    () -> {
                        try {
                            runnable.run();
                            future.complete(null);
                        } catch (Exception ex) {
                            future.completeExceptionally(ex);
                        }
                    });
        }
        return future;
    }

    public <T> Future<T> runSynchronously(Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();

        synchronized (runnableList) {
            runnableList.add(
                    () -> {
                        try {
                            T result = callable.call();
                            future.complete(result);
                        } catch (Exception ex) {
                            future.completeExceptionally(ex);
                        }
                    });
        }

        return future;
    }

    /**
     * Waits until the next time this VisionRunner should run its pipeline, based on current FPS limit
     */
    private void waitUntilNextTick(long startNanos) {
        int fpsLimit = fpsLimitSupplier.get();

        if (fpsLimit > 0) {
            // SpectrumJetson (photonvision-65): a monotonic clock. The date can jump when the Jetson
            // takes the robot's (RobotClockSync), and a jump back here slept this camera for as long.
            long sleepTime = 1000 / fpsLimit - (System.nanoTime() - startNanos) / 1_000_000;

            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                }
            }
            return;
        } else {
            // Fall through to no limit
            return;
        }
    }

    // Spectrum: where each loop's time goes, logged every 10 s (frame = grab incl. decode,
    // pipeline = pipeline.run, consumers = pipelineResultConsumer: NT, UI, health, ...).
    private long tSettings, tFrame, tPipeline, tConsumers, tLoops, tLoggedNs = System.nanoTime();
    // Spectrum: capture-to-published latency of each frame in the window (mid-exposure to after the
    // consumers sent it), for its median, p95 and max: averages hide the jitter we tune for.
    private long[] latNs = new long[4096];
    private int latCount;

    private void logLoopTiming() {
        long now = System.nanoTime();
        if (now - tLoggedNs < 10_000_000_000L || tLoops == 0) return;
        logger.info(
                String.format(
                        "Loop timing over %d frames: settings %.2f ms, frame %.2f ms, pipeline %.2f ms,"
                                + " consumers %.2f ms; latency %s",
                        tLoops,
                        tSettings / 1e6 / tLoops,
                        tFrame / 1e6 / tLoops,
                        tPipeline / 1e6 / tLoops,
                        tConsumers / 1e6 / tLoops,
                        latencySummary()));
        tSettings = tFrame = tPipeline = tConsumers = tLoops = 0;
        latCount = 0;
        tLoggedNs = now;
    }

    private String latencySummary() {
        if (latCount == 0) return "n/a";
        long[] s = java.util.Arrays.copyOf(latNs, latCount);
        java.util.Arrays.sort(s);
        return String.format(
                "median %.2f p95 %.2f max %.2f ms",
                s[s.length / 2] / 1e6, s[(int) (s.length * 0.95)] / 1e6, s[s.length - 1] / 1e6);
    }

    private void update() {
        // wait for the camera to connect
        while (!frameSupplier.isConnected() && !Thread.interrupted()) {
            // yield
            pipelineResultConsumer.accept(new CVPipelineResult(0l, 0, 0, null, new Frame()));
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }

        // SpectrumJetson (photonvision-38): a failure building the UI's state must not end this
        // camera's thread.
        try {
            DataChangeService.getInstance()
                    .publishEvent(
                            new OutgoingUIEvent<>(
                                    "fullsettings",
                                    UIPhotonConfiguration.programStateToUi(
                                            ConfigProvider.getInstance().getConfig())));
        } catch (Exception e) {
            logger.error("Couldn't send the settings to the UI", e);
        }

        while (!Thread.interrupted()) {
            // SpectrumJetson (photonvision-63, ported for photonvision-89): nothing may end this loop
            // but an interrupt. An exception outside pipeline.run (a setting change, the frame grab,
            // the stuck-camera watchdog resetting or reopening the camera in cscore, a native library
            // that failed to load) used to end the thread: that camera went dark until PhotonVision
            // restarted, and nothing reported it. Errors that leave the JVM unusable
            // (VirtualMachineError: out of memory, stack overflow) still propagate.
            try {
                // SpectrumJetson (photonvision-49, 57): while the robot is disabled (idle mode) or the
                // chip is too hot (the thermal limit), at most that many frames a second; the ones in
                // between are never grabbed (decoded). Before the settings and the grab, so both are
                // fresh for the frame that's processed.
                if (IdleMode.active() && !IdleMode.waitForNextFrame(lastGrabNanos)) return;
                lastGrabNanos = System.nanoTime();

                long start = System.nanoTime(); // photonvision-65: monotonic
                long n0 = System.nanoTime();
                changeSubscriber.processSettingChanges();
                synchronized (runnableList) {
                    for (var runnable : runnableList) {
                        try {
                            runnable.run();
                        } catch (Exception ex) {
                            logger.error("Exception running runnable", ex);
                        }
                    }
                    runnableList.clear();
                }

                var pipeline = pipelineSupplier.get();

                // Tell our camera implementation here what kind of pre-processing we need it to
                // be doing
                // (pipeline-dependent). I kinda hate how much leak this has...
                // TODO would a callback object be a better fit?
                configureFrameProviderForPipeline(frameSupplier, pipeline);

                // Grab the new camera frame
                long n1 = System.nanoTime();
                var frame = frameSupplier.get();
                long n2 = System.nanoTime();

                // Frame empty -- no point in trying to do anything more?
                if (frame.processedImage.getMat().empty() && frame.colorImage.getMat().empty()) {
                    // give up without increasing loop count
                    // Still feed with blank frames just dont run any pipelines

                    frame.release();
                    pipelineResultConsumer.accept(new CVPipelineResult(0l, 0, 0, null, new Frame()));
                } else if (pipeline == pipelineSupplier.get()) {
                    if (!enabledSupplier.get()) {
                        // If we are skipping processing due to the camera being disabled, we still want
                        // to send a result with the new frame and settings, just with a null pipeline
                        // result
                        pipelineResultConsumer.accept(new CVPipelineResult(0l, 0, 0, null, new Frame()));
                        frame.release();
                        continue;
                    }

                    // If the pipeline has changed while we are getting our frame we should scrap
                    // that frame it may result in incorrect frame settings like hsv values

                    // There's no guarantee the processing type change will occur this tick, so
                    // pipelines should check themselves

                    // If we have an FPS limit, check if it's 0, in which case we skip processing and
                    // just send a blank frame, otherwise we sleep until the next tick
                    waitUntilNextTick(start);
                    try {
                        var pipelineResult = pipeline.run(frame, cameraQuirks);
                        long n3 = System.nanoTime();
                        try {
                            pipelineResultConsumer.accept(pipelineResult);
                            long n4 = System.nanoTime();
                            long captured = pipelineResult.getImageCaptureTimestampNanos();
                            if (captured > 0 && latCount < latNs.length) {
                                latNs[latCount++] =
                                        org.wpilib.networktables.NetworkTablesJNI.now() - captured;
                            }
                            tSettings += n1 - n0;
                            tFrame += n2 - n1;
                            tPipeline += n3 - n2;
                            tConsumers += n4 - n3;
                            tLoops++;
                            logLoopTiming();
                        } catch (Exception ex) {
                            logger.error("Exception on loop " + loopCount, ex);
                            pipelineResult.release();
                        }
                    } catch (Exception ex) {
                        logger.error("Pipeline exception on loop " + loopCount, ex);
                        frame.release();
                    }
                    loopCount++;
                }
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable t) {
                long now = System.nanoTime() / 1_000_000;
                if (now - lastLoopErrorLogMs > 5000) {
                    lastLoopErrorLogMs = now;
                    logger.error("Vision loop error (the camera keeps going)", t);
                }
                try {
                    pipelineResultConsumer.accept(new CVPipelineResult(0l, 0, 0, null, new Frame()));
                } catch (Throwable ignored) {
                    // the result consumers failing too: nothing more to tell anyone
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        if (visionProcessThread.isAlive()) {
            stopProcess();
        }
    }
}
