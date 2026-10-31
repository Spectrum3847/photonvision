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

import io.javalin.websocket.WsContext;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.opencv.core.Size;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.dataflow.CVPipelineResultConsumer;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.DataChangeService.SubscriberHandle;
import org.photonvision.common.dataflow.events.OutgoingUIEvent;
import org.photonvision.common.dataflow.networktables.NTDataPublisher;
import org.photonvision.common.dataflow.statusLEDs.StatusLEDConsumer;
import org.photonvision.common.dataflow.websocket.UICameraConfiguration;
import org.photonvision.common.dataflow.websocket.UIDataPublisher;
import org.photonvision.common.dataflow.websocket.UIPhotonConfiguration;
import org.photonvision.common.hardware.HardwareManager;
import org.photonvision.common.hardware.metrics.JetsonSettingsPublisher;
import org.photonvision.common.hardware.metrics.JetsonTelemetry;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.SerializationUtils;
import org.photonvision.common.util.file.FileUtils;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.camera.CameraQuirk;
import org.photonvision.vision.camera.CameraType;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.camera.csi.LibcameraGpuSource;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.frame.consumer.FileSaveFrameConsumer;
import org.photonvision.vision.frame.consumer.MJPGFrameConsumer;
import org.photonvision.vision.frame.provider.USBFrameProvider;
import org.photonvision.vision.pipeline.AdvancedPipelineSettings;
import org.photonvision.vision.pipeline.CVPipelineSettings;
import org.photonvision.vision.pipeline.OutputStreamPipeline;
import org.photonvision.vision.pipeline.PipelineType;
import org.photonvision.vision.pipeline.ReflectivePipelineSettings;
import org.photonvision.vision.pipeline.UICalibrationData;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.target.TargetModel;
import org.photonvision.vision.target.TrackedTarget;
import org.wpilib.vision.camera.CameraServerJNI;
import org.wpilib.vision.camera.VideoException;

/**
 * This is the God Class
 *
 * <p>VisionModule has a pipeline manager, vision runner, and data providers. The data providers
 * provide info on settings changes. VisionModuleManager holds a list of all current vision modules.
 */
public class VisionModule implements AutoCloseable {
    private final Logger logger;
    protected final PipelineManager pipelineManager;
    protected final VisionSource visionSource;
    private final VisionRunner visionRunner;
    private final StreamRunnable streamRunnable;
    private final VisionModuleChangeSubscriber changeSubscriber;
    private final SubscriberHandle changeSubscriberHandle;
    private final LinkedList<CVPipelineResultConsumer> resultConsumers = new LinkedList<>();
    // Raw result consumers run before any drawing has been done by the
    // OutputStreamPipeline
    private final LinkedList<BiConsumer<Frame, List<TrackedTarget>>> streamResultConsumers =
            new LinkedList<>();
    private final NTDataPublisher ntConsumer;
    private final UIDataPublisher uiDataConsumer;
    private final StatusLEDConsumer statusLEDsConsumer;
    // SpectrumJetson (photonvision-34): a focus score for turning the lens (Camera page)
    private final FocusMeter focusMeter = new FocusMeter();
    protected final QuirkyCamera cameraQuirks;

    protected TrackedTarget lastPipelineResultBestTarget;

    private int inputStreamPort = -1;
    private int outputStreamPort = -1;

    private int fpsLimit = -1;
    // SpectrumJetson (photonvision-11): volatile, written by the NT listener thread and read by the
    // VisionRunner. Not saved: every camera starts enabled when PhotonVision starts.
    private volatile boolean enabled = true;

    FileSaveFrameConsumer inputFrameSaver;
    FileSaveFrameConsumer outputFrameSaver;

    MJPGFrameConsumer inputVideoStreamer;
    MJPGFrameConsumer outputVideoStreamer;

    boolean mismatch;

    // The most recently computed calibration, kept for inspection from the UI
    private volatile CameraCalibrationCoefficients pendingCalibration = null;
    private volatile Path pendingImageDir = null;

    public VisionModule(
            PipelineManager pipelineManager,
            VisionSource visionSource,
            Collection<CVPipelineResultConsumer> extraConsumers) {
        logger =
                new Logger(
                        VisionModule.class,
                        visionSource.getSettables().getConfiguration().nickname,
                        LogGroup.VisionModule);

        mismatch = false;

        cameraQuirks = visionSource.getCameraConfiguration().cameraQuirks;

        if (visionSource.getCameraConfiguration().cameraQuirks == null)
            visionSource.getCameraConfiguration().cameraQuirks = QuirkyCamera.DefaultCamera;

        // We don't show gain if the config says it's -1. So check here to make sure
        // it's non-negative if it _is_ supported. (The constructor's visionSource: the field
        // hasGainControl() reads isn't set yet.)
        if (cameraQuirks.hasQuirk(CameraQuirk.Gain) && visionSource.getSettables().hasControl("gain")) {
            pipelineManager.userPipelineSettings.forEach(
                    it -> {
                        if (it.cameraGain == -1) it.cameraGain = 75; // Sane default
                    });
        }
        if (cameraQuirks.hasQuirk(CameraQuirk.AwbRedBlueGain)) {
            pipelineManager.userPipelineSettings.forEach(
                    it -> {
                        if (it.cameraRedGain == -1) it.cameraRedGain = 11; // Sane defaults
                        if (it.cameraBlueGain == -1) it.cameraBlueGain = 20;
                    });
        }

        this.pipelineManager = pipelineManager;
        this.visionSource = visionSource;
        changeSubscriber = new VisionModuleChangeSubscriber(this);
        this.visionRunner =
                new VisionRunner(
                        this.visionSource.getFrameProvider(),
                        this.pipelineManager::getCurrentPipeline,
                        this::consumeResult,
                        this.cameraQuirks,
                        getChangeSubscriber(),
                        this::getFPSLimit,
                        this::getEnabled);
        this.streamRunnable = new StreamRunnable(new OutputStreamPipeline());
        changeSubscriberHandle = DataChangeService.getInstance().addSubscriber(changeSubscriber);

        createStreams();

        recreateStreamResultConsumers();

        ntConsumer =
                new NTDataPublisher(
                        visionSource.getSettables().getConfiguration().nickname,
                        pipelineManager::getRequestedIndex,
                        this::setPipeline,
                        pipelineManager::getDriverMode,
                        this::setDriverMode,
                        this::getFPSLimit,
                        this::setFPSLimit,
                        this::getEnabled,
                        this::setEnabled);
        uiDataConsumer = new UIDataPublisher(visionSource.getSettables().getConfiguration().uniqueName);
        statusLEDsConsumer =
                new StatusLEDConsumer(visionSource.getSettables().getConfiguration().uniqueName);
        resultConsumers.add(ntConsumer);
        resultConsumers.add(uiDataConsumer);
        resultConsumers.add(focusMeter); // SpectrumJetson (photonvision-34): idle unless asked
        resultConsumers.add(statusLEDsConsumer);
        resultConsumers.add(
                (result) ->
                        lastPipelineResultBestTarget = result.hasTargets() ? result.targets.get(0) : null);
        // SpectrumJetson (photonvision-16): the Jetson's health on NetworkTables; this camera's is the
        // CameraHealthPublisher below.
        JetsonTelemetry.start();
        resultConsumers.addAll(extraConsumers);
        // SpectrumJetson: this camera's health on NetworkTables (from photonvision-16's camera half):
        // recoveries (photonvision-29), problem (-31), state and lastFrameAgeMs (-81).
        resultConsumers.add(
                new CameraHealthPublisher(
                        () -> visionSource.getSettables().getConfiguration().nickname,
                        () ->
                                visionSource.getFrameProvider() instanceof USBFrameProvider usb
                                        ? usb.getDecodeFailures()
                                        : 0,
                        () ->
                                visionSource.getFrameProvider() instanceof USBFrameProvider usb
                                        ? usb.getRecoveries()
                                        : 0,
                        // SpectrumJetson (photonvision-31): e.g. "not enough USB bandwidth (...)".
                        () ->
                                visionSource.getFrameProvider() instanceof USBFrameProvider usb
                                        ? usb.getProblem()
                                        : "",
                        // SpectrumJetson (photonvision-81): e.g. "streaming", "gave-up".
                        () ->
                                visionSource.getFrameProvider() instanceof USBFrameProvider usb
                                        ? usb.getHealthState()
                                        : "",
                        () ->
                                visionSource.getFrameProvider() instanceof USBFrameProvider usb
                                        ? usb.getLastFrameAgeMs()
                                        : -1));
        // SpectrumJetson: the camera's mount (height, pitch, roll) from the tags it sees
        // (photonvision-17).
        resultConsumers.add(
                new MountEstimatePublisher(() -> visionSource.getSettables().getConfiguration().nickname));
        // SpectrumJetson: this camera's settings, and the Jetson's, as JSON for the robot log
        // (photonvision-24).
        settingsPublisher = new CameraSettingsPublisher(this);
        JetsonSettingsPublisher.start();

        // SpectrumJetson: a brand-new camera gets the team's tuned settings (TeamCameraDefaults)
        // instead of stock PhotonVision's CPU AprilTag at 320x240. The camera usually hasn't
        // reported its video modes yet at this point (they're enumerated when it connects), so
        // the resolution is picked by a short background wait once the modes are known.
        if (pipelineManager.createdDefaultPipeline && !pipelineManager.userPipelineSettings.isEmpty()) {
            var first = pipelineManager.userPipelineSettings.get(0);
            TeamCameraDefaults.apply(first, visionSource.getSettables().getAllVideoModes());
            // Spectrum: a known camera model also gets its profile (CameraProfiles): exposure,
            // resolution, a generic starting calibration, and the FOV computed from it.
            var profile =
                    CameraProfiles.forCamera(visionSource.getSettables().getConfiguration().matchedCameraInfo);
            profile.ifPresent(p -> applyCameraProfile(p, first));
            logger.info(
                    "New camera: applied team defaults ("
                            + first.pipelineType
                            + ", exposure "
                            + first.cameraExposureRaw
                            + ", brightness "
                            + first.cameraBrightness
                            + "); resolution is picked once the camera reports its modes");
            pipelineManager.createdDefaultPipeline = false;
            saveModule();
            var modeThread =
                    new Thread(
                            () -> {
                                for (int i = 0; i < 150; i++) { // up to 30 s
                                    var modes = visionSource.getSettables().getAllVideoModes();
                                    if (!modes.isEmpty()) {
                                        int idx =
                                                profile
                                                        .map(p -> TeamCameraDefaults.pickVideoMode(modes, p.width(), p.height()))
                                                        .orElseGet(() -> TeamCameraDefaults.pickVideoMode(modes));
                                        if (idx >= 0) {
                                            first.cameraVideoModeIndex = idx;
                                            var m = modes.get(idx);
                                            logger.info(
                                                    "New camera: team default resolution "
                                                            + m.width
                                                            + "x"
                                                            + m.height
                                                            + " "
                                                            + m.pixelFormat
                                                            + " @ "
                                                            + m.fps
                                                            + " fps (mode "
                                                            + idx
                                                            + ")");
                                            enable3dWhereCalibrated();
                                            setPipeline(pipelineManager.getRequestedIndex());
                                            saveAndBroadcastAll();
                                        }
                                        return;
                                    }
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                                logger.warn("New camera: no video modes after 30 s; resolution left at default");
                            },
                            "TeamDefaultVideoMode");
            modeThread.setDaemon(true);
            modeThread.start();
        }

        // Sync VisionModule state with the first pipeline index
        setPipeline(visionSource.getSettables().getConfiguration().currentPipelineIndex);

        // Set vendor FOV
        if (isVendorCamera()) {
            var fov = ConfigProvider.getInstance().getConfig().getHardwareConfig().vendorFOV;
            logger.info("Setting FOV of vendor camera to " + fov);
            visionSource.getSettables().setFOV(fov);
        }

        // Configure LED's if supported by the underlying hardware.
        if (this.camShouldControlLEDs()) {
            HardwareManager.getInstance()
                    .visionLED
                    .ifPresent(
                            (visionLED) ->
                                    visionLED.setPipelineModeSupplier(
                                            () -> pipelineManager.getCurrentPipelineSettings().ledMode));
            setVisionLEDs(pipelineManager.getCurrentPipelineSettings().ledMode);
        }

        getCameraConfiguration().deactivated = false;
        saveAndBroadcastAll();
        start();
    }

    private void createStreams() {
        var camStreamIdx = visionSource.getSettables().getConfiguration().streamIndex;
        // If idx = 0, we want (1181, 1182)
        this.inputStreamPort = 1181 + (camStreamIdx * 2);
        this.outputStreamPort = 1181 + (camStreamIdx * 2) + 1;

        inputFrameSaver =
                new FileSaveFrameConsumer(
                        visionSource.getSettables().getConfiguration().nickname,
                        visionSource.getSettables().getConfiguration().uniqueName,
                        "input");
        outputFrameSaver =
                new FileSaveFrameConsumer(
                        visionSource.getSettables().getConfiguration().nickname,
                        visionSource.getSettables().getConfiguration().uniqueName,
                        "output");

        String camHostname = CameraServerJNI.getHostname();
        inputVideoStreamer =
                new MJPGFrameConsumer(
                        camHostname + "_Port_" + inputStreamPort + "_Input_MJPEG_Server", inputStreamPort);
        outputVideoStreamer =
                new MJPGFrameConsumer(
                        camHostname + "_Port_" + outputStreamPort + "_Output_MJPEG_Server", outputStreamPort);
    }

    private void recreateStreamResultConsumers() {
        streamResultConsumers.add(
                (frame, tgts) -> {
                    if (frame != null) inputFrameSaver.accept(frame.colorImage);
                });
        streamResultConsumers.add(
                (frame, tgts) -> {
                    if (frame != null) outputFrameSaver.accept(frame.processedImage);
                });
        streamResultConsumers.add(
                (frame, tgts) -> {
                    if (frame != null) inputVideoStreamer.accept(frame.colorImage);
                });
        streamResultConsumers.add(
                (frame, tgts) -> {
                    if (frame != null) outputVideoStreamer.accept(frame.processedImage);
                });
    }

    private class StreamRunnable extends Thread {
        private final OutputStreamPipeline outputStreamPipeline;

        private final Object frameLock = new Object();
        private Frame latestFrame;
        private AdvancedPipelineSettings settings = new AdvancedPipelineSettings();
        private List<TrackedTarget> targets = new ArrayList<>();

        private boolean shouldRun = false;

        public StreamRunnable(OutputStreamPipeline outputStreamPipeline) {
            this.outputStreamPipeline = outputStreamPipeline;
        }

        public void updateData(
                Frame inputOutputFrame, AdvancedPipelineSettings settings, List<TrackedTarget> targets) {
            synchronized (frameLock) {
                if (shouldRun && this.latestFrame != null) {
                    logger.trace("Fell behind; releasing last unused Mats");
                    this.latestFrame.release();
                    if (this.targets != null) {
                        this.targets.forEach(TrackedTarget::release);
                    }
                }

                this.latestFrame = inputOutputFrame;
                this.settings = settings;
                this.targets = targets;

                shouldRun = inputOutputFrame != null;
            }
        }

        @Override
        public void run() {
            while (!Thread.interrupted()) {
                final Frame m_frame;
                final AdvancedPipelineSettings settings;
                final List<TrackedTarget> targets;
                final boolean shouldRun;
                synchronized (frameLock) {
                    m_frame = this.latestFrame;
                    this.latestFrame = null;

                    settings = this.settings;
                    targets = this.targets;
                    shouldRun = this.shouldRun;

                    this.shouldRun = false;
                }
                if (shouldRun) {
                    try {
                        CVPipelineResult osr = outputStreamPipeline.process(m_frame, settings, targets);
                        consumeResults(m_frame, targets);
                    } catch (Exception e) {
                        // Never die
                        logger.error("Exception while running stream runnable!", e);
                    } finally {
                        if (targets != null) {
                            targets.forEach(TrackedTarget::release);
                        }
                        try {
                            m_frame.release();
                        } catch (Exception e) {
                            logger.error("Exception freeing frames", e);
                        }
                    }
                } else {
                    // busy wait! hurray!
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        logger.warn("StreamRunnable was interrupted - exiting");
                        return;
                    }
                }
            }
        }
    }

    private void start() {
        visionRunner.startProcess();
        streamRunnable.start();
    }

    private void stop() {
        visionRunner.stopProcess();

        try {
            streamRunnable.interrupt();
            streamRunnable.join();
        } catch (InterruptedException e) {
            logger.error("Exception killing process thread", e);
        }
    }

    public void setFov(double fov) {
        var settables = visionSource.getSettables();
        logger.trace(() -> "Setting " + settables.getConfiguration().nickname + ") FOV (" + fov + ")");

        // Only set FOV if we have no vendor JSON, and we aren't using a PiCAM
        if (isVendorCamera()) {
            logger.info("Cannot set FOV on a vendor device! Ignoring...");
        } else {
            settables.setFOV(fov);
        }
    }

    private boolean isVendorCamera() {
        return visionSource.isVendorCamera();
    }

    public VisionModuleChangeSubscriber getChangeSubscriber() {
        return changeSubscriber;
    }

    void changePipelineType(int newType) {
        pipelineManager.changePipelineType(newType);
        setPipeline(pipelineManager.getRequestedIndex());
        saveAndBroadcastAll();
    }

    void setDriverMode(boolean isDriverMode) {
        pipelineManager.setDriverMode(isDriverMode);
        setVisionLEDs(!isDriverMode);
        setPipeline(
                isDriverMode ? PipelineManager.DRIVERMODE_INDEX : pipelineManager.getRequestedIndex());
        saveAndBroadcastAll();
    }

    public void startCalibration(UICalibrationData data) {
        if (pendingCalibration != null) pendingCalibration.release();
        pendingCalibration = null;
        deletePendingImageDir();

        var settings = pipelineManager.calibration3dPipeline.getSettings();
        settings.autoCalibrate = data.autoCalibrate;

        var videoMode = visionSource.getSettables().getAllVideoModes().get(data.videoModeIndex);
        var resolution = new Size(videoMode.width, videoMode.height);

        settings.cameraVideoModeIndex = data.videoModeIndex;
        visionSource.getSettables().setVideoModeIndex(data.videoModeIndex);
        logger.info(
                "Starting calibration at resolution index "
                        + data.videoModeIndex
                        + " and settings "
                        + data);
        settings.gridSize = data.squareSizeMeters;
        settings.markerSize = data.markerSizeMeters;
        settings.boardHeight = data.patternHeight;
        settings.boardWidth = data.patternWidth;
        settings.boardType = data.boardType;
        settings.resolution = resolution;
        settings.useOldPattern = data.useOldPattern;
        settings.tagFamily = data.tagFamily;

        // Disable gain if not applicable
        if (!hasGainControl()) {
            settings.cameraGain = -1;
        }
        if (!cameraQuirks.hasQuirk(CameraQuirk.AwbRedBlueGain)) {
            settings.cameraRedGain = -1;
            settings.cameraBlueGain = -1;
        }

        settings.cameraAutoExposure = true;

        setPipeline(PipelineManager.CAL_3D_INDEX);
        pipelineManager.calibration3dPipeline.broadcastState();
    }

    public void saveInputSnapshot() {
        inputFrameSaver.overrideTakeSnapshot();
    }

    public void saveOutputSnapshot() {
        outputFrameSaver.overrideTakeSnapshot();
    }

    public void takeCalibrationSnapshot() {
        pipelineManager.calibration3dPipeline.takeSnapshot();
    }

    /**
     * Computes a calibration from the currently collected snapshots without committing it.
     *
     * @return The computed calibration result, or null if the computation failed
     */
    public CameraCalibrationCoefficients computeCalibration() {
        if (pipelineManager.getCurrentPipelineIndex() != PipelineManager.CAL_3D_INDEX) {
            throw new IllegalStateException("Camera is not in calibration mode");
        }

        final Path imageSavePath;
        try {
            imageSavePath =
                    ConfigProvider.getInstance()
                            .createPreliminaryCalibrationImageDir(
                                    visionSource.getCameraConfiguration().uniqueName);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not create a temporary directory for preliminary calibration images", e);
        }

        var ret = pipelineManager.calibration3dPipeline.tryCalibration(imageSavePath);
        if (ret != null) {
            // Only replace the previous pending result once the new solve has succeeded
            deletePendingImageDir();
            pendingImageDir = imageSavePath;
            if (pendingCalibration != null) pendingCalibration.release();
            pendingCalibration = ret;
        } else {
            FileUtils.deleteDirectory(imageSavePath);
        }
        return ret;
    }

    /**
     * Calls {@link #computeCalibration()} and commits the calibration.
     *
     * @return The committed calibration, or null if the computation failed
     */
    public CameraCalibrationCoefficients commitCalibration() {
        var ret = computeCalibration();

        pendingCalibration = null;
        var imageDir = pendingImageDir;
        pendingImageDir = null;

        if (ret != null) {
            logger.debug("Saving calibration...");
            movePendingSnapshotImages(ret, imageDir);
            visionSource.getSettables().addCalibration(ret);
        } else {
            logger.error("Calibration failed...");
            // We only want to release the pending calibration when compute fails
            // since the endingCalibration and the ret object are the same
            if (pendingCalibration != null) pendingCalibration.release();
        }
        if (imageDir != null) {
            FileUtils.deleteDirectory(imageDir);
        }

        pipelineManager.setCalibrationMode(false);
        setPipeline(pipelineManager.getRequestedIndex());
        saveAndBroadcastAll();
        return ret;
    }

    /**
     * Moves a committed calibration's snapshot images from their temporary directory to the
     * persistent calibration image directory, updating the observations to point at the new
     * locations.
     */
    private void movePendingSnapshotImages(CameraCalibrationCoefficients cal, Path tempImageDir) {
        if (tempImageDir == null) return;

        var persistentDir =
                ConfigProvider.getInstance()
                        .getCalibrationImageSavePathWithRes(
                                cal.resolution, visionSource.getCameraConfiguration().uniqueName);

        for (var observation : cal.observations) {
            if (observation.snapshotDataLocation == null) continue;

            var target = persistentDir.resolve(observation.snapshotName);
            try {
                Files.move(observation.snapshotDataLocation, target, StandardCopyOption.REPLACE_EXISTING);
                observation.snapshotDataLocation = target;
            } catch (IOException e) {
                logger.error("Failed to move calibration snapshot image to " + target, e);
            }
        }
    }

    /** Aborts the calibration session and discards pending information. */
    public void cancelCalibration() {
        logger.info("Calibration canceled -- not computing or saving a result");
        if (pendingCalibration != null) pendingCalibration.release();
        pendingCalibration = null;
        deletePendingImageDir();

        pipelineManager.setCalibrationMode(false);
        setPipeline(pipelineManager.getRequestedIndex());
        saveAndBroadcastAll();
    }

    private void deletePendingImageDir() {
        var dir = pendingImageDir;
        pendingImageDir = null;
        if (dir != null) {
            FileUtils.deleteDirectory(dir);
        }
    }

    public CameraCalibrationCoefficients getPendingCalibration() {
        return pendingCalibration;
    }

    /** SpectrumJetson (photonvision-48): whether each stream has a viewer, for tests/ui. */
    public Map<String, Boolean> streamViewers() {
        return Map.of(
                "input", inputVideoStreamer != null && inputVideoStreamer.hasViewers(),
                "output", outputVideoStreamer != null && outputVideoStreamer.hasViewers());
    }

    // SpectrumJetson (photonvision-25): the settings "Copy settings from..." can copy, by group.
    // Orientation, names and each camera's exposure limits are never copied.
    public static final Map<String, List<String>> COPY_GROUPS =
            Map.of(
                    "camera",
                    List.of(
                            "cameraAutoExposure",
                            "cameraExposureRaw",
                            "cameraBrightness",
                            "cameraGain",
                            "cameraAutoWhiteBalance",
                            "cameraWhiteBalanceTemp",
                            "streamingFrameDivisor",
                            "blockForFrames",
                            "cameraContrast",
                            "cameraGamma",
                            "cameraSharpness",
                            "cameraBacklightCompensation"),
                    "resolution",
                    List.of("cameraVideoModeIndex"),
                    "apriltag",
                    List.of(
                            "tagFamily",
                            "decimate",
                            "blur",
                            "threads",
                            "refineEdges",
                            "numIterations",
                            "hammingDist",
                            "decisionMargin"),
                    "output",
                    List.of(
                            "solvePNPEnabled",
                            "doMultiTarget",
                            "doSingleTargetAlways",
                            "outputShouldDraw",
                            "outputMaximumTargets"),
                    "objectDetection",
                    List.of("confidence", "nms", "model"));

    /** SpectrumJetson (photonvision-25): pipeline {@code index}'s settings, or null. */
    public CVPipelineSettings getPipelineSettingsAt(int index) {
        return pipelineManager.getPipelineSettings(index);
    }

    /** The video modes this camera offers, for comparing cameras before copying a resolution. */
    public List<String> videoModeList() {
        var modes = visionSource.getSettables().getAllVideoModes();
        var out = new ArrayList<String>();
        for (var m : modes) {
            out.add(m == null ? "" : m.width + "x" + m.height + " " + m.pixelFormat + " " + m.fps);
        }
        return out;
    }

    /**
     * SpectrumJetson (photonvision-25): copies groups of settings (see COPY_GROUPS) from another
     * pipeline, of this camera or another one, into this camera's pipeline {@code targetIndex}. If
     * that pipeline is running, the camera settings are re-applied. Fields the other pipeline type
     * doesn't have are skipped.
     *
     * @return the fields copied
     */
    public List<String> copySettingsFrom(
            CVPipelineSettings source, int targetIndex, Collection<String> groups, boolean sameVideoModes) {
        var target = pipelineManager.getPipelineSettings(targetIndex);
        if (target == null) throw new IllegalArgumentException("This camera has no pipeline " + targetIndex);
        var copied = new ArrayList<String>();
        // photonvision-63: every group checked before anything is copied. Checked inside the copy
        // loop, a refused resolution left the groups before it copied, unsaved and not broadcast.
        for (String group : groups) {
            if (group == null || !COPY_GROUPS.containsKey(group)) {
                throw new IllegalArgumentException("Unknown settings group " + group);
            }
            if (group.equals("resolution") && !sameVideoModes) {
                throw new IllegalArgumentException(
                        "The two cameras offer different video modes, so the resolution can't be copied");
            }
        }
        for (String group : groups) {
            var fields = COPY_GROUPS.get(group);
            for (String name : fields) {
                try {
                    var from = source.getClass().getField(name);
                    var to = target.getClass().getField(name);
                    if (!to.getType().equals(from.getType())) continue;
                    to.set(target, from.get(source));
                    copied.add(name);
                } catch (NoSuchFieldException e) {
                    // The other pipeline type doesn't have this setting.
                } catch (IllegalAccessException e) {
                    logger.error("Could not copy " + name, e);
                }
            }
        }
        logger.info(
                "Copied "
                        + copied
                        + " from pipeline '"
                        + source.pipelineNickname
                        + "' into pipeline '"
                        + target.pipelineNickname
                        + "'");
        if (targetIndex == pipelineManager.getRequestedIndex()) setPipeline(targetIndex);
        saveAndBroadcastAll();
        return copied;
    }

    /**
     * Fields that belong to the camera, not the pipeline: when a new pipeline starts from another
     * camera's, it keeps this camera's own. SpectrumJetson (photonvision-46; the mask is
     * photonvision-40's DetectionMask).
     */
    private static final Set<String> CAMERA_OWN_FIELDS =
            Set.of(
                    "inputImageRotationMode",
                    "cameraMinExposureRaw",
                    "cameraMaxExposureRaw",
                    "detectionMask");

    private static final io.avaje.jsonb.JsonType<CVPipelineSettings> pipelineJson =
            io.avaje.jsonb.Jsonb.instance().type(CVPipelineSettings.class);

    /** A deep copy through PhotonVision's own JSON: clone() would share the mask, HSV ranges, ... */
    private static CVPipelineSettings deepCopy(CVPipelineSettings settings) {
        return pipelineJson.fromJson(pipelineJson.toJson(settings));
    }

    /**
     * SpectrumJetson (photonvision-46): adds a pipeline called {@code nickname}, blank ({@code
     * source} null, of {@code type}) or starting from a copy of {@code source}. From this camera's
     * own pipeline it's an exact copy (as Duplicate). From another camera's, this camera keeps its
     * orientation, exposure limits and mask, and its resolution unless the video modes match.
     *
     * @return the new pipeline's index
     */
    public int createPipeline(
            String nickname,
            PipelineType type,
            CVPipelineSettings source,
            boolean sourceIsThisCamera,
            boolean sameVideoModes,
            boolean switchToIt) {
        if (pipelineManager.getPipelineNicknames().contains(nickname)) {
            throw new IllegalArgumentException("already has a pipeline called " + nickname);
        }
        // photonvision-63: this camera's own values come from the pipeline it runs now. Skipping
        // them (as before) left the new pipeline's defaults: unrotated, unmasked, mode 0.
        CVPipelineSettings own = null;
        if (source != null && !sourceIsThisCamera) {
            try {
                own = deepCopy(pipelineManager.getCurrentPipelineSettings());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("couldn't read this camera's pipeline: " + e.getMessage());
            }
        }
        CVPipelineSettings copy = null;
        if (source != null) {
            try {
                copy = deepCopy(source);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("couldn't copy the pipeline: " + e.getMessage());
            }
        }
        var added = pipelineManager.addPipeline(source == null ? type : source.pipelineType, nickname);
        if (added == null) throw new IllegalArgumentException("can't make a " + type + " pipeline");
        if (copy != null) {
            for (var field : copy.getClass().getFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isFinal(mods)) continue;
                String name = field.getName();
                if (name.equals("pipelineIndex") || name.equals("pipelineNickname")) continue;
                boolean keepOwn =
                        !sourceIsThisCamera
                                && (CAMERA_OWN_FIELDS.contains(name)
                                        || (!sameVideoModes && name.equals("cameraVideoModeIndex")));
                try {
                    if (!keepOwn) {
                        field.set(added, field.get(copy));
                    } else if (own != null) {
                        // From this camera's current pipeline, if it has the field (a mask, say,
                        // only exists on some pipeline types); otherwise the new pipeline's default.
                        try {
                            field.set(added, own.getClass().getField(name).get(own));
                        } catch (NoSuchFieldException e) {
                            // keep the default
                        }
                    }
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    logger.error("Could not copy " + name, e);
                }
            }
        }
        int index = pipelineManager.getPipelineNicknames().indexOf(nickname);
        logger.info(
                "Created pipeline "
                        + index
                        + " '"
                        + nickname
                        + "'"
                        + (source == null ? " (blank " + type + ")" : " from '" + source.pipelineNickname + "'"));
        if (switchToIt) setPipeline(index);
        saveAndBroadcastAll();
        return index;
    }

    boolean setPipeline(int index) {
        logger.info("Setting pipeline to " + index);
        logger.info("Pipeline name: " + pipelineManager.getPipelineNickname(index));
        pipelineManager.setIndex(index);

        VisionSourceSettables settables = visionSource.getSettables();

        var pipelineSettings = pipelineManager.getPipelineSettings(index);

        if (pipelineSettings == null) {
            logger.error("Config for index " + index + " was null! Not changing pipelines");
            return false;
        }

        visionRunner.runSynchronously(
                () -> {
                    settables.setVideoModeInternal(pipelineSettings.cameraVideoModeIndex);
                    settables.setBrightness(pipelineSettings.cameraBrightness);
                    // SpectrumJetson (photonvision-28): -1 restores the camera's default.
                    settables.setContrast(pipelineSettings.cameraContrast);
                    settables.setGamma(pipelineSettings.cameraGamma);
                    settables.setSharpness(pipelineSettings.cameraSharpness);
                    settables.setBacklightCompensation(pipelineSettings.cameraBacklightCompensation);

                    // If manual exposure, force exposure slider to be valid
                    if (!pipelineSettings.cameraAutoExposure) {
                        if (pipelineSettings.cameraExposureRaw < 0)
                            pipelineSettings.cameraExposureRaw = 10; // reasonable default
                    }

                    settables.setExposureRaw(pipelineSettings.cameraExposureRaw);
                    try {
                        settables.setAutoExposure(pipelineSettings.cameraAutoExposure);
                    } catch (VideoException e) {
                        logger.error("Unable to set camera auto exposure!");
                        logger.error(e.toString());
                    }
                    if (hasGainControl()) {
                        // If the gain is disabled for some reason, re-enable it
                        if (pipelineSettings.cameraGain == -1) pipelineSettings.cameraGain = 75;
                        settables.setGain(Math.max(0, pipelineSettings.cameraGain));
                    } else {
                        pipelineSettings.cameraGain = -1;
                    }

                    if (cameraQuirks.hasQuirk(CameraQuirk.AwbRedBlueGain)) {
                        // If the AWB gains are disabled for some reason, re-enable it
                        if (pipelineSettings.cameraRedGain == -1) pipelineSettings.cameraRedGain = 11;
                        if (pipelineSettings.cameraBlueGain == -1) pipelineSettings.cameraBlueGain = 20;
                        settables.setRedGain(Math.max(0, pipelineSettings.cameraRedGain));
                        settables.setBlueGain(Math.max(0, pipelineSettings.cameraBlueGain));
                    } else {
                        pipelineSettings.cameraRedGain = -1;
                        pipelineSettings.cameraBlueGain = -1;

                        // All other cameras (than picams) should support AWB temp
                        settables.setWhiteBalanceTemp(pipelineSettings.cameraWhiteBalanceTemp);
                        settables.setAutoWhiteBalance(pipelineSettings.cameraAutoWhiteBalance);
                    }

                    setVisionLEDs(pipelineSettings.ledMode);

                    settables.getConfiguration().currentPipelineIndex = pipelineManager.getRequestedIndex();
                });

        return true;
    }

    private boolean camShouldControlLEDs() {
        // Heuristic - if the camera has a known FOV or is a piCam, assume it's in use
        // for
        // vision processing, and should command stuff to the LED's.
        // TODO: Make LED control a property of the camera itself and controllable in
        // the UI.
        return isVendorCamera();
    }

    private void setVisionLEDs(boolean on) {
        if (camShouldControlLEDs()) {
            HardwareManager.getInstance().visionLED.ifPresent((visionLED) -> visionLED.setState(on));
        }
    }

    public void saveModule() {
        ConfigProvider.getInstance()
                .saveModule(
                        getStateAsCameraConfig(), visionSource.getSettables().getConfiguration().uniqueName);
    }

    public void saveAndBroadcastAll() {
        saveModule();
        DataChangeService.getInstance()
                .publishEvent(
                        new OutgoingUIEvent<>(
                                "fullsettings",
                                UIPhotonConfiguration.programStateToUi(ConfigProvider.getInstance().getConfig())));
    }

    void saveAndBroadcastSelective(WsContext originContext, String propertyName, Object value) {
        logger.trace("Broadcasting PSC mutation - " + propertyName + ": " + value);
        saveModule();

        Map<String, Object> map = new HashMap<>();
        Map<String, Object> subMap = new HashMap<>();
        subMap.put(propertyName, value);
        map.put("mutatePipelineSettings", subMap);
        // SpectrumJetson (photonvision-41): the UI applies a mutation only with the camera's name
        // (App.vue), so without it no other open dashboard ever saw a setting change: a second
        // tab kept stale values and could overwrite the first's (seen with detection masks).
        map.put("cameraUniqueName", uniqueName());

        DataChangeService.getInstance()
                .publishEvent(new OutgoingUIEvent<>("mutatePipeline", map, originContext));
    }

    public void setCameraNickname(String newName) {
        visionSource.getSettables().getConfiguration().nickname = newName;
        ntConsumer.updateCameraNickname(newName);
        inputFrameSaver.updateCameraNickname(newName);
        outputFrameSaver.updateCameraNickname(newName);

        // Push new data to the UI
        saveAndBroadcastAll();
    }

    public UICameraConfiguration toUICameraConfig() {
        var ret = new UICameraConfiguration();

        var config = visionSource.getCameraConfiguration();
        ret.matchedCameraInfo = config.matchedCameraInfo;
        ret.cameraPath = config.getDevicePath();
        ret.fov = visionSource.getSettables().getFOV();
        ret.isCSICamera = config.matchedCameraInfo.type() == CameraType.ZeroCopyPicam;
        ret.nickname = visionSource.getSettables().getConfiguration().nickname;
        ret.uniqueName = visionSource.getSettables().getConfiguration().uniqueName;
        ret.currentPipelineSettings =
                SerializationUtils.objectToHashMap(pipelineManager.getCurrentPipelineSettings());
        ret.currentPipelineIndex = pipelineManager.getRequestedIndex();
        ret.pipelineNicknames = pipelineManager.getPipelineNicknames();
        ret.cameraQuirks = visionSource.getSettables().getConfiguration().cameraQuirks;
        ret.minExposureRaw = visionSource.getSettables().getMinExposureRaw();
        ret.maxExposureRaw = visionSource.getSettables().getMaxExposureRaw();
        ret.minWhiteBalanceTemp = visionSource.getSettables().getMinWhiteBalanceTemp();
        ret.maxWhiteBalanceTemp = visionSource.getSettables().getMaxWhiteBalanceTemp();
        ret.extraControls = visionSource.getSettables().getExtraControls(); // SpectrumJetson

        ret.deactivated = config.deactivated;
        ret.isEnabled = getEnabled();

        ret.mismatch = this.mismatch;

        ret.fpsLimit = this.fpsLimit;

        // TODO refactor into helper method
        var temp = new ArrayList<Map<String, Object>>();
        var videoModes = visionSource.getSettables().getAllVideoModes();

        for (var videoMode : videoModes) {
            var internalMap = new HashMap<String, Object>();

            internalMap.put("width", videoMode.width);
            internalMap.put("height", videoMode.height);
            internalMap.put("fps", videoMode.fps);
            internalMap.put(
                    "pixelFormat",
                    ((videoMode instanceof LibcameraGpuSource.FPSRatedVideoMode)
                            ? "Picam"
                            : videoMode.pixelFormat.toString()));

            temp.add(internalMap);
        }

        if (videoModes.size() == 0) {
            logger.error("no video modes, guhhhhh");
        }

        ret.videoFormatList = temp;
        ret.outputStreamPort = this.outputStreamPort;
        ret.inputStreamPort = this.inputStreamPort;

        ret.calibrations =
                visionSource.getSettables().getConfiguration().calibrations.stream()
                        .map(
                                c -> {
                                    var ui = c.cloneWithoutObservations();
                                    ui.isGeneric = CameraProfiles.isGeneric(c); // Spectrum
                                    return ui;
                                })
                        .toList();
        // Spectrum: resolutions still on a generic calibration, for the UI and health checks.
        ret.genericCalibrations =
                ret.calibrations.stream()
                        .filter(c -> c.isGeneric)
                        .map(c -> (int) c.resolution.width + "x" + (int) c.resolution.height)
                        .toList();

        ret.isFovConfigurable =
                !(ConfigProvider.getInstance().getConfig().getHardwareConfig().hasPresetFOV());

        ret.isConnected = visionSource.getFrameProvider().isConnected();
        ret.hasConnected = visionSource.getFrameProvider().hasConnected();

        return ret;
    }

    /**
     * Set FPS limit for this vision module. This will cause our processing thread to sleep in order
     * to increase our processing time to match the provided fps. If our processing time is longer
     * than the frame period, the FPS limit will not be reached.
     *
     * @param fps
     */
    public void setFPSLimit(int fps) {
        this.fpsLimit = fps;
        saveAndBroadcastAll();
    }

    /**
     * Get the current FPS limit for this vision module. This limit cannot be exceeded, but may be
     * lower depending on processing time.
     *
     * @return the FPS limit
     */
    public int getFPSLimit() {
        return fpsLimit;
    }

    /**
     * Sets whether the camera is enabled/disabled, disabling the camera allows you to reduce util
     * while keeping the vision runner up for fast toggling.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        saveAndBroadcastAll();
    }

    /**
     * Gets whether the camera is enabled or disabled, if disabled the vision runner will still be
     * running but the camera will not capture frames, allowing for fast toggling.
     *
     * @return the enabled state of the camera
     */
    public boolean getEnabled() {
        return enabled;
    }

    public CameraConfiguration getStateAsCameraConfig() {
        var config = visionSource.getSettables().getConfiguration();
        config.setPipelineSettings(pipelineManager.userPipelineSettings);
        config.driveModeSettings = pipelineManager.driverModePipeline.getSettings();
        config.currentPipelineIndex = Math.max(pipelineManager.getRequestedIndex(), -1);

        return config;
    }

    private void consumeResult(CVPipelineResult result) {
        if (result.tagContrast != null) {
            org.photonvision.vision.target.TagContrast.record(
                    visionSource.getSettables().getConfiguration().nickname, result.tagContrast);
        }
        consumePipelineResult(result);

        // Pipelines like DriverMode and Calibrate3dPipeline have null output frames
        if (result.inputAndOutputFrame != null
                && (pipelineManager.getCurrentPipelineSettings()
                        instanceof AdvancedPipelineSettings settings)) {
            if (streamFrameWanted()) {
                result.inputAndOutputFrame.unshareProcessed(); // the stream draws on both (photonvision-37)
                streamRunnable.updateData(result.inputAndOutputFrame, settings, result.targets);
                // The streamRunnable manages releasing in this case
            } else {
                // SpectrumJetson (photonvision-15): no viewer and no snapshot, or not yet time for
                // the next stream frame
                result.release();
            }
        } else {
            consumeResults(result.inputAndOutputFrame, result.targets);

            result.release();
            // In this case we don't bother with a separate streaming thread and we release
        }
    }

    // SpectrumJetson (photonvision-15): the stream thread resizes, colour-converts and draws every
    // frame it gets, and it used to get every frame (~0.3 ms of CPU each, plus photonvision-37's
    // 1 MB unshareProcessed copy) even with no dashboard open. Now it gets a frame only while a
    // stream has a viewer or a snapshot is pending, and at most SPECTRUM_STREAM_FPS a second while
    // streaming (default 30; 0 = every frame). Snapshots are never delayed by the cap.
    private static final double kStreamFps = streamFpsSetting();
    private long nextStreamFrameNs = 0;

    private static double streamFpsSetting() {
        try {
            String v = System.getenv("SPECTRUM_STREAM_FPS");
            return v == null ? 30 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private boolean streamFrameWanted() {
        if (inputFrameSaver.wantsFrame() || outputFrameSaver.wantsFrame()) return true;
        if (!inputVideoStreamer.hasViewers() && !outputVideoStreamer.hasViewers()) return false;
        if (kStreamFps <= 0) return true;
        long now = System.nanoTime();
        if (now < nextStreamFrameNs) return false;
        long period = (long) (1e9 / kStreamFps);
        // Keep the average rate exact, but don't bank frames after a pause.
        nextStreamFrameNs =
                nextStreamFrameNs + period < now ? now + period : nextStreamFrameNs + period;
        return true;
    }

    private void consumePipelineResult(CVPipelineResult result) {
        for (var dataConsumer : resultConsumers) {
            dataConsumer.accept(result);
        }
    }

    /** Consume stream/target results, no rate limiting applied */
    private void consumeResults(Frame frame, List<TrackedTarget> targets) {
        for (var c : streamResultConsumers) {
            c.accept(frame, targets);
        }
    }

    public void setTargetModel(TargetModel targetModel) {
        // photonvision-66: not getCurrentPipeline(), which switches pipelines off the vision thread.
        var pipelineSettings = pipelineManager.getCurrentPipelineSettings();
        if (pipelineSettings instanceof ReflectivePipelineSettings settings) {
            settings.targetModel = targetModel;
            saveAndBroadcastAll();
        } else {
            logger.error("Cannot set target model of non-reflective pipe! Ignoring...");
        }
    }

    public void addCalibrationToConfig(CameraCalibrationCoefficients newCalibration) {
        if (newCalibration != null) {
            logger.info("Got new calibration for " + newCalibration.resolution);
            visionSource.getSettables().addCalibration(newCalibration);
            enable3dWhereCalibrated();
        } else {
            logger.error("Got null calibration?");
        }

        saveAndBroadcastAll();
    }

    /**
     * Spectrum: a brand-new camera of a known model (CameraProfiles) gets the model's exposure,
     * generic starting calibrations (only at resolutions with no calibration) and the diagonal FOV
     * computed from the calibration at its preferred resolution. Models without a calibration keep
     * PhotonVision's FOV.
     */
    private void applyCameraProfile(CameraProfiles.Profile profile, CVPipelineSettings first) {
        logger.info("New camera: known model " + profile.name() + "; applying its camera profile");
        first.cameraAutoExposure = false;
        first.cameraExposureRaw = profile.exposureRaw();
        List<CameraCalibrationCoefficients> generic;
        try {
            generic = CameraProfiles.genericCalibrations(profile);
        } catch (RuntimeException e) {
            logger.error("Couldn't load the generic calibration for " + profile.name(), e);
            return;
        }
        var settables = visionSource.getSettables();
        for (var cal : generic) {
            boolean has =
                    settables.getConfiguration().calibrations.stream()
                            .anyMatch(c -> c.resolution != null && c.resolution.equals(cal.resolution));
            if (has) continue;
            settables.addCalibration(cal);
            logger.warn(
                    "New camera: added a GENERIC "
                            + profile.name()
                            + " calibration at "
                            + (int) cal.resolution.width
                            + "x"
                            + (int) cal.resolution.height
                            + "; calibrate this camera");
        }
        if (!isVendorCamera()) {
            generic.stream()
                    .filter(
                            c ->
                                    (int) c.resolution.width == profile.width()
                                            && (int) c.resolution.height == profile.height())
                    .findFirst()
                    .ifPresent(
                            c -> {
                                double fov = CameraProfiles.diagonalFovDegrees(c);
                                logger.info(
                                        "New camera: diagonal FOV " + fov + " deg from the calibration");
                                settables.setFOV(fov);
                            });
        }
    }

    /**
     * SpectrumJetson: turn 3D and multi-tag on for every AprilTag pipeline whose resolution now
     * has a calibration (TeamCameraDefaults). Called when a calibration is saved and when a new
     * camera's resolution is picked. Never turns anything off.
     */
    private void enable3dWhereCalibrated() {
        var modes = visionSource.getSettables().getAllVideoModes();
        var calibrations = visionSource.getSettables().getConfiguration().calibrations;
        for (var settings : pipelineManager.userPipelineSettings) {
            if (TeamCameraDefaults.enable3dIfCalibrated(settings, modes, calibrations)) {
                logger.info(
                        "Calibrated at this resolution: turned on 3D and multi-tag for pipeline \""
                                + settings.pipelineNickname
                                + "\"");
            }
        }
    }

    public void removeCalibrationFromConfig(Size resolution) {
        if (resolution != null) {
            visionSource.getSettables().removeCalibration(resolution);
        } else {
            logger.error("Got null size?");
        }

        saveAndBroadcastAll();
    }

    /**
     * Add/remove quirks from the camera we're controlling
     *
     * @param quirksToChange map of true/false for quirks we should change
     */
    public void changeCameraQuirks(Map<CameraQuirk, Boolean> quirksToChange) {
        visionSource.getCameraConfiguration().cameraQuirks.updateQuirks(quirksToChange);
        visionSource.remakeSettables();
        saveAndBroadcastAll();
    }

    /** SpectrumJetson (photonvision-32): this camera's USB frame provider, or null (CSI, file). */
    public USBFrameProvider usbFrameProvider() {
        return visionSource.getFrameProvider() instanceof USBFrameProvider usb ? usb : null;
    }

    public String uniqueName() {
        return this.visionSource.cameraConfiguration.uniqueName;
    }

    /** SpectrumJetson (photonvision-72): for GpuStatus on the Device card. */
    public CVPipelineSettings getCurrentPipelineSettings() {
        return pipelineManager.getCurrentPipelineSettings();
    }

    public CameraConfiguration getCameraConfiguration() {
        return this.visionSource.cameraConfiguration;
    }

    // SpectrumJetson (photonvision-24, -63): kept so close() can end its timed task.
    private CameraSettingsPublisher settingsPublisher;

    @Override
    public void close() {
        if (settingsPublisher != null) settingsPublisher.stop();
        if (visionRunner.isRunning()) {
            stop();
        }

        // Ensure config is saved and synced before closing
        saveAndBroadcastAll();

        inputVideoStreamer.close();
        outputVideoStreamer.close();
        inputFrameSaver.close();
        outputFrameSaver.close();

        deletePendingImageDir();
        if (pendingCalibration != null) pendingCalibration.release();

        changeSubscriberHandle.stop();
        setVisionLEDs(false);

        visionRunner.close();
        pipelineManager.close();
        visionSource.close();
        if (lastPipelineResultBestTarget != null) lastPipelineResultBestTarget.close();
    }

    /**
     * The camera's quirks say it has gain, and (once connected) it really has the control
     * (photonvision-54: the Thriftiest Cam is listed with gain but has none).
     */
    public boolean hasGainControl() {
        return cameraQuirks.hasQuirk(CameraQuirk.Gain) && visionSource.getSettables().hasControl("gain");
    }

    /** SpectrumJetson (photonvision-34): the focus score for this camera's lens. */
    public FocusMeter focusMeter() {
        return focusMeter;
    }
}
