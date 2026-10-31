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

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.photonvision.common.dataflow.CVPipelineResultConsumer;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.IntegerPublisher;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;
import org.wpilib.networktables.StringPublisher;

/**
 * SpectrumJetson: one camera's health on NetworkTables, once a second, under
 * /photonvision/<camera>/health/: {@code fps}, {@code pipelineMs} (average) and {@code
 * pipelineMsMax} over the last second, {@code latencyMs} (average, capture to result), {@code
 * frames} (total since start) and {@code decodeFailures} (total frames whose JPEG couldn't be
 * decoded; a rising count means a corrupt camera stream) and {@code recoveries} (reconnects and USB
 * resets of a stuck camera since start, photonvision-29).
 *
 * <p>photonvision-81, after TXMAN1: a dead camera's health looked frozen on the robot. Its values
 * repeated (fps 0, frames unchanged) and NetworkTables drops a repeated value, so nothing reached
 * the robot's log until a reconnect. Now, from a 1 s timer that doesn't depend on results: {@code
 * heartbeat} (counts up), {@code state} (streaming, stalled, recovering, disconnected, gave-up,
 * paused, starting; "" for a non-USB source) and {@code lastFrameAgeMs} (-1 before the first
 * frame); fps and state keep duplicates; and fps goes to 0 if no result has closed a window for 2
 * s.
 */
public class CameraHealthPublisher implements CVPipelineResultConsumer {
    private final Supplier<String> nickname;
    private final LongSupplier decodeFailures;
    private final LongSupplier recoveries;
    private final Supplier<String> problem;
    private final Supplier<String> state;
    private final LongSupplier lastFrameAgeMs;
    private final NetworkTableInstance nt; // null: PhotonVision's own
    private StringPublisher problemPub, statePub;

    private String publishedName = null;
    private DoublePublisher fps, pipelineMs, pipelineMsMax, latencyMs;
    private IntegerPublisher frames, failures, recoveryCount, heartbeat, lastFrameAge;
    private long beats = 0;

    private long windowStartNs = 0, total = 0;
    private int count = 0;
    private double sumMs = 0, maxMs = 0, sumLatencyMs = 0;

    public CameraHealthPublisher(Supplier<String> nickname, LongSupplier decodeFailures) {
        this(nickname, decodeFailures, () -> 0);
    }

    public CameraHealthPublisher(
            Supplier<String> nickname, LongSupplier decodeFailures, LongSupplier recoveries) {
        this(nickname, decodeFailures, recoveries, () -> "");
    }

    /**
     * SpectrumJetson (photonvision-31): also {@code problem}, why the camera gets no frames ("" if
     * fine).
     */
    public CameraHealthPublisher(
            Supplier<String> nickname,
            LongSupplier decodeFailures,
            LongSupplier recoveries,
            Supplier<String> problem) {
        this(nickname, decodeFailures, recoveries, problem, () -> "", () -> -1);
    }

    /** photonvision-81: also {@code state} and {@code lastFrameAgeMs}. */
    public CameraHealthPublisher(
            Supplier<String> nickname,
            LongSupplier decodeFailures,
            LongSupplier recoveries,
            Supplier<String> problem,
            Supplier<String> state,
            LongSupplier lastFrameAgeMs) {
        this(nickname, decodeFailures, recoveries, problem, state, lastFrameAgeMs, null);
    }

    /** For tests: publishes on {@code nt}, not PhotonVision's own instance. */
    CameraHealthPublisher(
            Supplier<String> nickname,
            LongSupplier decodeFailures,
            LongSupplier recoveries,
            Supplier<String> problem,
            Supplier<String> state,
            LongSupplier lastFrameAgeMs,
            NetworkTableInstance nt) {
        this.nt = nt;
        this.problem = problem;
        this.nickname = nickname;
        this.decodeFailures = decodeFailures;
        this.recoveries = recoveries;
        this.state = state;
        this.lastFrameAgeMs = lastFrameAgeMs;
        // A camera with a problem often has no frames, so no results to publish on: its own timer.
        org.photonvision.common.util.TimedTaskManager.getInstance()
                .addTask(
                        "CameraHealthProblem-" + System.identityHashCode(this), this::publishEverySecond, 1000);
    }

    private synchronized void publishEverySecond() {
        try {
            ensurePublishers();
            problemPub.set(problem.get());
            recoveryCount.set(recoveries.getAsLong());
            statePub.set(state.get());
            lastFrameAge.set(lastFrameAgeMs.getAsLong());
            heartbeat.set(++beats);
            // No result has closed a window for 2 s (the vision thread isn't producing any): 0 fps.
            if (windowStartNs != 0 && System.nanoTime() - windowStartNs > 2_000_000_000L) fps.set(0);
        } catch (Exception e) {
            // NetworkTables not up yet: next second
        }
    }

    @Override
    public synchronized void accept(CVPipelineResult result) {
        long now = System.nanoTime();
        if (windowStartNs == 0) windowStartNs = now;
        // photonvision-63: the blank results a disabled, unplugged or stuck camera sends (capture
        // time 0) aren't frames. Counted, they made a disabled camera read ~50 fps and averaged the
        // Jetson's uptime into latencyMs. The window still closes on them, so fps falls to 0.
        if (result.getImageCaptureTimestampNanos() != 0) {
            double ms = result.getProcessingMillis();
            count++;
            total++;
            sumMs += ms;
            maxMs = Math.max(maxMs, ms);
            sumLatencyMs += result.getLatencyMillis();
        }

        double window = (now - windowStartNs) / 1e9;
        if (window < 1.0) return;
        ensurePublishers();
        fps.set(count / window);
        pipelineMs.set(count > 0 ? sumMs / count : 0);
        pipelineMsMax.set(maxMs);
        latencyMs.set(count > 0 ? sumLatencyMs / count : 0);
        frames.set(total);
        failures.set(decodeFailures.getAsLong());
        recoveryCount.set(recoveries.getAsLong());
        windowStartNs = now;
        count = 0;
        sumMs = maxMs = sumLatencyMs = 0;
    }

    /** (Re)creates the topics when the camera is first seen or renamed. */
    private synchronized void ensurePublishers() {
        String name = nickname.get();
        if (name.equals(publishedName)) return;
        if (publishedName != null) {
            fps.close();
            pipelineMs.close();
            pipelineMsMax.close();
            latencyMs.close();
            frames.close();
            failures.close();
            recoveryCount.close();
            problemPub.close();
            statePub.close();
            heartbeat.close();
            lastFrameAge.close();
        }
        NetworkTable t =
                (nt != null ? nt : NetworkTablesManager.getInstance().getNTInst())
                        .getTable("photonvision")
                        .getSubTable(name)
                        .getSubTable("health");
        // photonvision-81: a repeated fps 0 or state still reaches the robot's log each second.
        var keep = PubSubOption.KEEP_DUPLICATES;
        fps = t.getDoubleTopic("fps").publish(keep);
        statePub = t.getStringTopic("state").publish(keep);
        heartbeat = t.getIntegerTopic("heartbeat").publish();
        lastFrameAge = t.getIntegerTopic("lastFrameAgeMs").publish();
        pipelineMs = t.getDoubleTopic("pipelineMs").publish();
        pipelineMsMax = t.getDoubleTopic("pipelineMsMax").publish();
        latencyMs = t.getDoubleTopic("latencyMs").publish();
        frames = t.getIntegerTopic("frames").publish();
        failures = t.getIntegerTopic("decodeFailures").publish();
        recoveryCount = t.getIntegerTopic("recoveries").publish();
        problemPub = t.getStringTopic("problem").publish();
        publishedName = name;
    }
}
