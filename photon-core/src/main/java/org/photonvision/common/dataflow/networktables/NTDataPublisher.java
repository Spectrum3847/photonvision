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

package org.photonvision.common.dataflow.networktables;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.dataflow.CVPipelineResultConsumer;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.networktables.NTTopicSet;
import org.photonvision.targeting.PhotonPipelineResult;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.pipeline.result.CalibrationPipelineResult;
import org.photonvision.vision.target.TagQuality;
import org.photonvision.vision.target.TrackedTarget;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.networktables.DoubleArrayPublisher;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableEvent;
import org.wpilib.networktables.NetworkTablesJNI;
import org.wpilib.networktables.PubSubOption;

public class NTDataPublisher implements CVPipelineResultConsumer {
    private final Logger logger = new Logger(NTDataPublisher.class, LogGroup.General);

    private final NetworkTable rootTable = NetworkTablesManager.getInstance().kRootTable;

    private final NTTopicSet ts = new NTTopicSet();

    // SpectrumJetson (photonvision-61): per-tag quality, every frame with tags (see TagQuality). In
    // 2026 it was in NTTopicSet; here it stays out of photon-targeting, which is upstream's.
    private DoubleArrayPublisher tagQualityPublisher;

    NTDataChangeListener pipelineIndexListener;
    private final Supplier<Integer> pipelineIndexSupplier;
    private final Consumer<Integer> pipelineIndexConsumer;

    NTDataChangeListener driverModeListener;
    private final BooleanSupplier driverModeSupplier;
    private final Consumer<Boolean> driverModeConsumer;

    NTDataChangeListener fpsLimitListener;
    private final Consumer<Integer> fpsLimitConsumer;
    private final Supplier<Integer> fpsLimitSupplier;

    NTDataChangeListener isEnabledListener;
    private final Consumer<Boolean> isEnabledConsumer;
    private final BooleanSupplier enabledSupplier;

    public NTDataPublisher(
            String cameraNickname,
            Supplier<Integer> pipelineIndexSupplier,
            Consumer<Integer> pipelineIndexConsumer,
            BooleanSupplier driverModeSupplier,
            Consumer<Boolean> driverModeConsumer,
            Supplier<Integer> fpsLimitSupplier,
            Consumer<Integer> fpsLimitConsumer,
            BooleanSupplier enabledSupplier,
            Consumer<Boolean> isEnabledConsumer) {
        this.pipelineIndexSupplier = pipelineIndexSupplier;
        this.pipelineIndexConsumer = pipelineIndexConsumer;
        this.driverModeSupplier = driverModeSupplier;
        this.driverModeConsumer = driverModeConsumer;
        this.fpsLimitSupplier = fpsLimitSupplier;
        this.fpsLimitConsumer = fpsLimitConsumer;
        this.enabledSupplier = enabledSupplier;
        this.isEnabledConsumer = isEnabledConsumer;

        updateCameraNickname(cameraNickname);
        updateEntries();
    }

    private void onPipelineIndexChange(NetworkTableEvent entryNotification) {
        var newIndex = (int) entryNotification.valueData.value.getInteger();
        var originalIndex = pipelineIndexSupplier.get();

        // ignore indexes below 0
        if (newIndex < 0) {
            ts.pipelineIndexPublisher.set(originalIndex);
            return;
        }

        if (newIndex == originalIndex) {
            logger.debug("Pipeline index is already " + newIndex);
            return;
        }

        pipelineIndexConsumer.accept(newIndex);
        var setIndex = pipelineIndexSupplier.get();
        if (newIndex != setIndex) { // set failed
            ts.pipelineIndexPublisher.set(setIndex);
            // TODO: Log
        }
        logger.debug("Set pipeline index to " + newIndex);
    }

    private void onDriverModeChange(NetworkTableEvent entryNotification) {
        var newDriverMode = entryNotification.valueData.value.getBoolean();
        var originalDriverMode = driverModeSupplier.getAsBoolean();

        if (newDriverMode == originalDriverMode) {
            logger.debug("Driver mode is already " + newDriverMode);
            return;
        }

        driverModeConsumer.accept(newDriverMode);
        logger.debug("Set driver mode to " + newDriverMode);
    }

    private void onFPSLimitChange(NetworkTableEvent entryNotification) {
        var newFPSLimit = (int) entryNotification.valueData.value.getInteger();
        var originalFPSLimit = fpsLimitSupplier.get();

        if (newFPSLimit == originalFPSLimit) {
            logger.debug("FPS limit is already " + newFPSLimit);
            return;
        }

        fpsLimitConsumer.accept(newFPSLimit);
        logger.debug("Set FPS limit to " + newFPSLimit);
    }

    private void onEnabledChange(NetworkTableEvent entryNotification) {
        var newEnabled = entryNotification.valueData.value.getBoolean();
        var originalEnabled = enabledSupplier.getAsBoolean();

        if (newEnabled == originalEnabled) {
            logger.debug("Enabled value is already " + newEnabled);
            return;
        }

        isEnabledConsumer.accept(newEnabled);
        logger.debug("Set is enabled to " + newEnabled);
    }

    private void removeEntries() {
        if (pipelineIndexListener != null) pipelineIndexListener.remove();
        if (driverModeListener != null) driverModeListener.remove();
        // SpectrumJetson (photonvision-11): upstream never removed these two, so a camera rename
        // left the old listeners registered.
        if (fpsLimitListener != null) fpsLimitListener.remove();
        if (isEnabledListener != null) isEnabledListener.remove();
        ts.removeEntries();
        if (tagQualityPublisher != null) tagQualityPublisher.close();
        tagQualityPublisher = null;
    }

    private void updateEntries() {
        if (pipelineIndexListener != null) pipelineIndexListener.remove();
        if (driverModeListener != null) driverModeListener.remove();
        if (fpsLimitListener != null) fpsLimitListener.remove();
        if (isEnabledListener != null) isEnabledListener.remove(); // SpectrumJetson (photonvision-11)

        ts.updateEntries();

        // photonvision-61: every value, like rawBytes, so the robot can match each frame's by
        // sequence ID.
        if (tagQualityPublisher != null) tagQualityPublisher.close();
        tagQualityPublisher =
                ts.subTable
                        .getDoubleArrayTopic("tagQuality")
                        .publish(
                                PubSubOption.periodic(0.01),
                                PubSubOption.SEND_ALL,
                                PubSubOption.KEEP_DUPLICATES);

        pipelineIndexListener =
                new NTDataChangeListener(
                        ts.subTable.getInstance(), ts.pipelineIndexRequestSub, this::onPipelineIndexChange);

        driverModeListener =
                new NTDataChangeListener(
                        ts.subTable.getInstance(), ts.driverModeSubscriber, this::onDriverModeChange);

        fpsLimitListener =
                new NTDataChangeListener(
                        ts.subTable.getInstance(), ts.fpsLimitSubscriber, this::onFPSLimitChange);

        isEnabledListener =
                new NTDataChangeListener(
                        ts.subTable.getInstance(), ts.enabledSubscriber, this::onEnabledChange);
    }

    public void updateCameraNickname(String newCameraNickname) {
        rewindCamera = org.photonvision.rewind.RewindManager.safeName(newCameraNickname);
        removeEntries();
        ts.subTable = rootTable.getSubTable(newCameraNickname);
        updateEntries();
    }

    private String rewindCamera; // photonvision-79: the camera's Rewind folder name

    @Override
    public void accept(CVPipelineResult result) {
        CVPipelineResult acceptedResult;
        if (result
                instanceof
                CalibrationPipelineResult) // If the data is from a calibration pipeline, override the list
            // of targets to be null to prevent the data from being sent and
            // continue to post blank/zero data to the network tables
            acceptedResult =
                    new CVPipelineResult(
                            result.sequenceID,
                            result.processingNanos,
                            result.fps,
                            List.of(),
                            result.inputAndOutputFrame);
        else acceptedResult = result;

        // SpectrumJetson (photonvision-81): a blank result (capture time 0: the camera is disabled,
        // unplugged or sends nothing usable) isn't a frame. Published, it went out ~50 times a second,
        // stamped with just the time-sync offset (TXMAN1: results dated ~26 s into the robot's boot,
        // read for minutes after a camera died), and it blanked the calibration and best-target topics.
        // The camera's settings still go out; its result and heartbeat stand still, so PhotonLib reads
        // no results and isConnected() goes false.
        if (result.getImageCaptureTimestampNanos() == 0) {
            ts.pipelineIndexPublisher.set(pipelineIndexSupplier.get());
            ts.driverModePublisher.set(driverModeSupplier.getAsBoolean());
            ts.fpsLimitPublisher.set(fpsLimitSupplier.get());
            ts.enabledPublisher.set(enabledSupplier.getAsBoolean());
            return;
        }

        var offsetNanos = NetworkTablesManager.getInstance().getOffset();
        var nowNanos = NetworkTablesJNI.now();

        // Transform the metadata timestamps from the local wpi::nt::Now timebase to the Time Sync
        // Server's timebase. Timestamps are published in nanoseconds.
        var simplified =
                new PhotonPipelineResult(
                        acceptedResult.sequenceID,
                        result.getImageCaptureTimestampNanos() + offsetNanos,
                        nowNanos + offsetNanos,
                        NetworkTablesManager.getInstance().getTimeSinceLastPong(),
                        TrackedTarget.simpleFromTrackedTargets(acceptedResult.targets),
                        acceptedResult.multiTagResult);

        // SpectrumJetson (photonvision-61): each tag's quality, sent just before the result it
        // belongs to (same sequence ID), and only for frames with tags.
        var quality = TagQuality.toArray(acceptedResult.sequenceID, acceptedResult.targets);
        if (quality != null && tagQualityPublisher != null) tagQualityPublisher.set(quality);

        // random guess at size of the array
        ts.resultPublisher.set(simplified, 1024);
        // photonvision-79: the same result into the open Rewind recording's vision log.
        org.photonvision.rewind.VisionLog.result(rewindCamera, simplified, nowNanos);
        if (ConfigProvider.getInstance().getConfig().getNetworkConfig().shouldPublishProto) {
            ts.protoResultPublisher.set(simplified);
        }

        ts.pipelineIndexPublisher.set(pipelineIndexSupplier.get());
        ts.driverModePublisher.set(driverModeSupplier.getAsBoolean());
        ts.fpsLimitPublisher.set(fpsLimitSupplier.get());
        ts.enabledPublisher.set(enabledSupplier.getAsBoolean());
        ts.latencyMillisEntry.set(acceptedResult.getLatencyMillis());
        ts.fpsEntry.set(acceptedResult.fps);
        ts.hasTargetEntry.set(acceptedResult.hasTargets());

        if (acceptedResult.hasTargets()) {
            var bestTarget = acceptedResult.targets.get(0);

            ts.targetPitchEntry.set(bestTarget.getPitch());
            ts.targetYawEntry.set(bestTarget.getYaw());
            ts.targetAreaEntry.set(bestTarget.getArea());
            ts.targetSkewEntry.set(bestTarget.getSkew());

            var pose = bestTarget.getBestCameraToTarget3d();
            ts.targetPoseEntry.set(pose);

            var targetOffsetPoint = bestTarget.getTargetOffsetPoint();
            ts.bestTargetPosX.set(targetOffsetPoint.x);
            ts.bestTargetPosY.set(targetOffsetPoint.y);
        } else {
            ts.targetPitchEntry.set(0);
            ts.targetYawEntry.set(0);
            ts.targetAreaEntry.set(0);
            ts.targetSkewEntry.set(0);
            ts.targetPoseEntry.set(new Transform3d());
            ts.bestTargetPosX.set(0);
            ts.bestTargetPosY.set(0);
        }

        // Something in the result can sometimes be null -- so check probably too many things
        if (acceptedResult.inputAndOutputFrame != null
                && acceptedResult.inputAndOutputFrame.frameStaticProperties != null
                && acceptedResult.inputAndOutputFrame.frameStaticProperties.cameraCalibration != null) {
            var fsp = acceptedResult.inputAndOutputFrame.frameStaticProperties;
            ts.cameraIntrinsicsPublisher.accept(fsp.cameraCalibration.getIntrinsicsArr());
            ts.cameraDistortionPublisher.accept(fsp.cameraCalibration.getDistCoeffsArr());
        } else {
            ts.cameraIntrinsicsPublisher.accept(new double[0]);
            ts.cameraDistortionPublisher.accept(new double[0]);
        }

        ts.heartbeatPublisher.set(acceptedResult.sequenceID);

        // TODO...nt4... is this needed?
        rootTable.getInstance().flush();
    }
}
