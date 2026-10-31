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

package org.photonvision.server;

import io.avaje.json.JsonException;
import io.avaje.jsonb.Json;
import io.avaje.jsonb.Types;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.commons.io.FileUtils;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Point3;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.configuration.NetworkConfig;
import org.photonvision.common.configuration.NeuralNetworkModelManager;
import org.photonvision.common.configuration.NeuralNetworkModelsSettings.ModelProperties;
import org.photonvision.common.dataflow.DataChangeDestination;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.events.IncomingWebSocketEvent;
import org.photonvision.common.dataflow.events.OutgoingUIEvent;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.dataflow.websocket.UIPhotonConfiguration;
import org.photonvision.common.hardware.HardwareManager;
import org.photonvision.common.hardware.Platform;
import org.photonvision.common.hardware.SwyftFirmware;
import org.photonvision.common.hardware.ThriftiestFirmware;
import org.photonvision.common.hardware.UsbBandwidth;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.networking.NetworkManager;
import org.photonvision.common.util.ShellExec;
import org.photonvision.common.util.TimedTaskManager;
import org.photonvision.common.util.file.ProgramDirectoryUtilities;
import org.photonvision.rewind.RewindManager;
import org.photonvision.tflite.TFLiteJNI.TFLiteSource;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.camera.CameraQuirk;
import org.photonvision.vision.camera.PVCameraInfo;
import org.photonvision.vision.objects.ObjectDetector;
import org.photonvision.vision.objects.RknnModel;
import org.photonvision.vision.objects.TFLiteModel;
import org.photonvision.vision.processes.VisionSourceManager;
import org.zeroturnaround.zip.ZipUtil;

public class RequestHandler {
    // Treat all 2XX calls as "INFO"
    // Treat all 4XX calls as "ERROR"
    // Treat all 5XX calls as "ERROR"

    private static final Logger logger = new Logger(RequestHandler.class, LogGroup.WebServer);

    private static boolean testMode = false;

    public static void onStatusRequest(Context ctx) {
        ctx.status(200);
        ctx.result("not dead yet");
    }

    public static void setTestMode(boolean isTestMode) {
        testMode = isTestMode;
    }

    @Json
    record CommonCameraUniqueName(String cameraUniqueName) {}

    public static void onSettingsImportRequest(Context ctx) {
        var file = ctx.uploadedFile("data");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the settings zip is sent at the key 'data'");
            logger.error(
                    "No File was sent with the request. Make sure that the settings zip is sent at the key 'data'");
            return;
        }

        if (!file.extension().contains("zip")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'zip'. The uploaded file should be a .zip file.");
            logger.error(
                    "The uploaded file was not of type 'zip'. The uploaded file should be a .zip file.");
            return;
        }

        // Create a temp file
        var tempFilePath = handleTempFileCreation(file);

        if (tempFilePath.isEmpty()) {
            ctx.status(500);
            ctx.result("There was an error while creating a temporary copy of the file");
            logger.error("There was an error while creating a temporary copy of the file");
            return;
        }

        ConfigProvider.getInstance().setWriteTaskEnabled(false);
        ConfigProvider.getInstance().disableFlushOnShutdown();
        // We want to delete the -whole- zip file, so we need to teardown loggers for
        // now
        logger.info("Writing new settings zip (logs may be truncated)...");
        Logger.closeAllLoggers();
        if (ConfigProvider.saveUploadedSettingsZip(tempFilePath.get())) {
            ctx.status(200);
            ctx.result("Successfully saved the uploaded settings zip, rebooting...");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while saving the uploaded zip file");
        }
    }

    public static void onSettingsExportRequest(Context ctx) {
        logger.info("Exporting Settings to ZIP Archive");

        try {
            var zip = ConfigProvider.getInstance().getSettingsFolderAsZip();
            var stream = new FileInputStream(zip);
            logger.info("Uploading settings with size " + stream.available());

            ctx.contentType("application/zip");
            ctx.header(
                    "Content-Disposition", "attachment; filename=\"photonvision-settings-export.zip\"");

            ctx.result(stream);
            ctx.status(200);
        } catch (IOException e) {
            logger.error("Unable to export settings archive, bad recode from zip to byte");
            ctx.status(500);
            ctx.result("There was an error while exporting the settings archive");
        }
    }

    public static void onHardwareConfigRequest(Context ctx) {
        var file = ctx.uploadedFile("data");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the hardware config json is sent at the key 'data'");
            logger.error(
                    "No File was sent with the request. Make sure that the hardware config json is sent at the key 'data'");
            return;
        }

        if (!file.extension().contains("json")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            logger.error(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            return;
        }

        // Create a temp file
        var tempFilePath = handleTempFileCreation(file);

        if (tempFilePath.isEmpty()) {
            ctx.status(500);
            ctx.result("There was an error while creating a temporary copy of the file");
            logger.error("There was an error while creating a temporary copy of the file");
            return;
        }

        if (ConfigProvider.getInstance().saveUploadedHardwareConfig(tempFilePath.get().toPath())) {
            ctx.status(200);
            ctx.result("Successfully saved the uploaded hardware config, rebooting...");
            logger.info("Successfully saved the uploaded hardware config, rebooting...");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while saving the uploaded hardware config");
            logger.error("There was an error while saving the uploaded hardware config");
        }
    }

    public static void onHardwareSettingsRequest(Context ctx) {
        var file = ctx.uploadedFile("data");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the hardware settings json is sent at the key 'data'");
            logger.error(
                    "No File was sent with the request. Make sure that the hardware settings json is sent at the key 'data'");
            return;
        }

        if (!file.extension().contains("json")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            logger.error(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            return;
        }

        // Create a temp file
        var tempFilePath = handleTempFileCreation(file);

        if (tempFilePath.isEmpty()) {
            ctx.status(500);
            ctx.result("There was an error while creating a temporary copy of the file");
            logger.error("There was an error while creating a temporary copy of the file");
            return;
        }

        if (ConfigProvider.getInstance().saveUploadedHardwareSettings(tempFilePath.get().toPath())) {
            ctx.status(200);
            ctx.result("Successfully saved the uploaded hardware settings, rebooting...");
            logger.info("Successfully saved the uploaded hardware settings, rebooting...");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while saving the uploaded hardware settings");
            logger.error("There was an error while saving the uploaded hardware settings");
        }
    }

    public static void onNetworkConfigRequest(Context ctx) {
        var file = ctx.uploadedFile("data");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the network config json is sent at the key 'data'");
            logger.error(
                    "No File was sent with the request. Make sure that the network config json is sent at the key 'data'");
            return;
        }

        if (!file.extension().contains("json")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            logger.error(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            return;
        }

        // Create a temp file
        var tempFilePath = handleTempFileCreation(file);

        if (tempFilePath.isEmpty()) {
            ctx.status(500);
            ctx.result("There was an error while creating a temporary copy of the file");
            logger.error("There was an error while creating a temporary copy of the file");
            return;
        }

        if (ConfigProvider.getInstance().saveUploadedNetworkConfig(tempFilePath.get().toPath())) {
            ctx.status(200);
            ctx.result("Successfully saved the uploaded network config, rebooting...");
            logger.info("Successfully saved the uploaded network config, rebooting...");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while saving the uploaded network config");
            logger.error("There was an error while saving the uploaded network config");
        }
    }

    public static void onFieldLayoutRequest(Context ctx) {
        var file = ctx.uploadedFile("data");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the field layout json is sent at the key 'data'");
            logger.error(
                    "No File was sent with the request. Make sure that the field layout json is sent at the key 'data'");
            return;
        }

        if (!file.extension().contains("json")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            logger.error(
                    "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
            return;
        }

        // Create a temp file
        var tempFilePath = handleTempFileCreation(file);

        if (tempFilePath.isEmpty()) {
            ctx.status(500);
            ctx.result("There was an error while creating a temporary copy of the file");
            logger.error("There was an error while creating a temporary copy of the file");
            return;
        }

        if (ConfigProvider.getInstance().saveUploadedFieldLayout(tempFilePath.get().toPath())) {
            // SpectrumJetson (photonvision-74): this upload, not the robot's last map, after the
            // restart (until the robot sends its map again).
            org.photonvision.fieldmap.FieldMapSync.forgetSaved(
                    org.photonvision.fieldmap.FieldMapNT.savedFile());
            ctx.status(200);
            ctx.result("Successfully saved the uploaded FieldLayout, rebooting...");
            logger.info("Successfully saved the uploaded FieldLayout, rebooting...");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while saving the uploaded FieldLayout");
            logger.error("There was an error while saving the uploaded FieldLayout");
        }
    }

    public static void onOfflineUpdateRequest(Context ctx) {
        var file = ctx.uploadedFile("jarData");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the new jar is sent at the key 'jarData'");
            logger.error(
                    "No File was sent with the request. Make sure that the new jar is sent at the key 'jarData'");
            return;
        }

        if (!file.extension().contains("jar")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'jar'. The uploaded file should be a .jar file.");
            logger.error(
                    "The uploaded file was not of type 'jar'. The uploaded file should be a .jar file.");
            return;
        }

        Path targetPath =
                Paths.get(ProgramDirectoryUtilities.getProgramDirectory(), "photonvision.jar");
        try (InputStream fileSteam = file.content()) {
            Files.copy(fileSteam, targetPath, StandardCopyOption.REPLACE_EXISTING);

            ctx.status(200);
            ctx.result(
                    "Offline update successfully complete. PhotonVision will restart in the background.");
            logger.info(
                    "Offline update successfully complete. PhotonVision will restart in the background.");
            restartProgram();
        } catch (FileNotFoundException e) {
            ctx.result("The current program jar file couldn't be found.");
            ctx.status(500);
            logger.error("The current program jar file couldn't be found.", e);
        } catch (IOException e) {
            ctx.result("Unable to overwrite the existing program with the new program.");
            ctx.status(500);
            logger.error("Unable to overwrite the existing program with the new program.", e);
        }
    }

    public static void onGeneralSettingsRequest(Context ctx) {
        NetworkConfig config;
        try {
            config = ctx.bodyAsClass(NetworkConfig.class);

            ctx.status(200);
            ctx.result("Successfully saved general settings");
            logger.info("Successfully saved general settings");
        } catch (IllegalStateException | JsonException e) {
            // If the settings can't be parsed, use the default network settings
            config = new NetworkConfig();

            ctx.status(400);
            ctx.result("The provided general settings were malformed");
            logger.error("The provided general settings were malformed", e);
        }

        ConfigProvider.getInstance().setNetworkSettings(config);
        ConfigProvider.getInstance().requestSave();

        NetworkManager.getInstance().reinitialize();

        NetworkTablesManager.getInstance().setConfig(config);
    }

    @Json
    record CameraSettingsRequest(
            double fov, Map<CameraQuirk, Boolean> quirksToChange, String cameraUniqueName) {}

    public static void onCameraSettingsRequest(Context ctx) {
        try {
            CameraSettingsRequest request = ctx.bodyAsClass(CameraSettingsRequest.class);
            // Extract the settings from the request
            double fov = request.fov;
            Map<CameraQuirk, Boolean> quirksToChange = request.quirksToChange;
            String cameraUniqueName = request.cameraUniqueName;

            if (cameraUniqueName == null || cameraUniqueName.isEmpty()) {
                ctx.status(400).result("cameraUniqueName is required");
                logger.error("cameraUniqueName is missing in the request");
                return;
            }

            logger.info("Changing camera FOV to: " + fov);

            logger.info("Changing quirks to: " + quirksToChange.toString());

            var module = VisionSourceManager.getInstance().vmm.getModule(cameraUniqueName);

            module.setFov(fov);
            module.changeCameraQuirks(quirksToChange);
            module.saveModule();

            ctx.status(200).result("Camera settings updated successfully");
        } catch (Exception e) {
            logger.error("Failed to process camera settings request", e);
            ctx.status(500).result("Failed to process camera settings request");
        }
    }

    public static void onLogExportRequest(Context ctx) {
        if (!Platform.isLinux()) {
            ctx.status(405);
            ctx.result("Logs can only be exported on a Linux platform");
            // INFO only log because this isn't ERROR worthy
            logger.info("Logs can only be exported on a Linux platform");
            return;
        }

        try {
            ShellExec shell = new ShellExec();
            var tempPath = Files.createTempFile("photonvision-journalctl", ".txt");
            var tempPath2 = Files.createTempFile("photonvision-kernelogs", ".txt");
            // In the command below:
            // dmesg = output all kernel logs since current boot
            // cat /var/log/kern.log = output all kernel logs since first boot
            shell.executeBashCommand(
                    "journalctl -a --output cat -u photonvision.service | sed -E 's/\\x1B\\[(0|30|31|32|33|34|35|36|37)m//g' >"
                            + tempPath.toAbsolutePath()
                            + " && dmesg > "
                            + tempPath2.toAbsolutePath());

            while (!shell.isOutputCompleted()) {
                // TODO: add timeout
            }

            if (shell.getExitCode() == 0) {
                // Wrote to the temp file! Zip and yeet it to the client

                var out = Files.createTempFile("photonvision-logs", "zip").toFile();

                try {
                    ZipUtil.packEntries(new File[] {tempPath.toFile(), tempPath2.toFile()}, out);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                var stream = new FileInputStream(out);
                ctx.contentType("application/zip");
                ctx.header("Content-Disposition", "attachment; filename=\"photonvision-logs.zip\"");
                ctx.result(stream);
                ctx.status(200);
                logger.info("Outputting log ZIP with size " + stream.available());
            } else {
                ctx.status(500);
                ctx.result("The journalctl service was unable to export logs");
                logger.error("The journalctl service was unable to export logs");
            }
        } catch (IOException e) {
            ctx.status(500);
            ctx.result("There was an error while exporting journalctl logs");
            logger.error("There was an error while exporting journalctl logs", e);
        }
    }

    public static void onCalibrationCommitRequest(Context ctx) {
        logger.info("Calibrating camera! This will take a long time...");

        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);

            var module = VisionSourceManager.getInstance().vmm.getModule(request.cameraUniqueName);
            if (module == null) {
                ctx.status(404);
                ctx.result(
                        "No vision module was found with cameraUniqueName (" + request.cameraUniqueName + ")");
                return;
            }

            var calData = module.commitCalibration();
            if (calData == null) {
                ctx.result("The calibration process failed");
                ctx.status(500);
                logger.error(
                        "The calibration process failed. Calibration data for module at cameraUniqueName ("
                                + request.cameraUniqueName
                                + ") was null");
                return;
            }

            ctx.result("Camera calibration successfully completed!");
            ctx.status(200);
            logger.info("Camera calibration successfully completed!");
        } catch (IllegalStateException | JsonException e) {
            ctx.status(400);
            ctx.result(
                    "The 'cameraUniqueName' field was not found in the request, or the camera is not in calibration mode. Please make sure the cameraUniqueName of the vision module is specified with the 'cameraUniqueName' key.");
            logger.error(
                    "The 'cameraUniqueName' field was not found in the request, or the camera is not in calibration mode.",
                    e);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("There was an error while committing the calibration");
            logger.error("There was an error while committing the calibration", e);
        }
    }

    public static void onCalibrationCancelRequest(Context ctx) {
        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);

            var module = VisionSourceManager.getInstance().vmm.getModule(request.cameraUniqueName);
            if (module == null) {
                ctx.status(404);
                ctx.result(
                        "No vision module was found with cameraUniqueName (" + request.cameraUniqueName + ")");
                return;
            }

            module.cancelCalibration();

            ctx.result("The calibration process was canceled");
            ctx.status(200);
            logger.info(
                    "Calibration canceled for module at cameraUniqueName (" + request.cameraUniqueName + ")");
        } catch (JsonException e) {
            ctx.status(400);
            ctx.result("The provided calibration data was malformed.");
            logger.error("The provided calibration data was malformed.", e);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("There was an error while canceling the calibration");
            logger.error("There was an error while canceling the calibration", e);
        }
    }

    public static void onCalibrationComputeRequest(Context ctx) {
        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);

            var module = VisionSourceManager.getInstance().vmm.getModule(request.cameraUniqueName);
            if (module == null) {
                ctx.status(404);
                ctx.result(
                        "No vision module was found with cameraUniqueName (" + request.cameraUniqueName + ")");
                return;
            }

            var calData = module.computeCalibration();
            if (calData == null) {
                ctx.status(500);
                ctx.result("The calibration computation failed");
                logger.error(
                        "The calibration computation failed for module at cameraUniqueName ("
                                + request.cameraUniqueName
                                + ")");
                return;
            }

            ctx.contentType("application/json");
            // Send the UI variant without observations -- the client fetches the full pending
            // calibration on demand (see onCalibrationJsonRequest) to draw them
            ctx.json(calData.cloneWithoutObservations());
            ctx.status(200);
            logger.info("Computed calibration for camera " + request.cameraUniqueName);
        } catch (IllegalStateException | JsonException e) {
            ctx.status(400);
            ctx.result("The provided calibration data was malformed.");
            logger.error("The provided calibration data was malformed.", e);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("There was an error while computing the calibration");
            logger.error("There was an error while computing the calibration", e);
        }
    }

    @Json
    record DataCalibrationImportRequest(
            String cameraUniqueName, CameraCalibrationCoefficients calibration) {}

    public static void onDataCalibrationImportRequest(Context ctx) {
        try {
            DataCalibrationImportRequest request = ctx.bodyAsClass(DataCalibrationImportRequest.class);

            // A negative focal length is physically impossible, so reject the calibration
            var intrinsics =
                    request.calibration != null && request.calibration.cameraIntrinsics != null
                            ? request.calibration.cameraIntrinsics.data
                            : null;
            if (intrinsics == null || intrinsics.length < 5 || intrinsics[0] < 0 || intrinsics[4] < 0) {
                ctx.status(400);
                ctx.result("The provided calibration data was malformed: invalid camera intrinsics");
                logger.error(
                        "Rejected calibration import with invalid intrinsics (fx="
                                + (intrinsics != null && intrinsics.length > 0 ? intrinsics[0] : "n/a")
                                + ", fy="
                                + (intrinsics != null && intrinsics.length > 4 ? intrinsics[4] : "n/a")
                                + ")");
                return;
            }

            var uploadCalibrationEvent =
                    new IncomingWebSocketEvent<>(
                            DataChangeDestination.DCD_ACTIVEMODULE,
                            "calibrationUploaded",
                            request.calibration,
                            request.cameraUniqueName,
                            null);
            DataChangeService.getInstance().publishEvent(uploadCalibrationEvent);

            ctx.status(200);
            ctx.result("Calibration imported successfully from imported data!");
            logger.info("Calibration imported successfully from imported data!");
        } catch (IllegalStateException | JsonException e) {
            ctx.status(400);
            ctx.result("The provided calibration data was malformed");
            logger.error("The provided calibration data was malformed", e);

        } catch (Exception e) {
            ctx.status(500);
            ctx.result("An error occurred while uploading calibration data");
            logger.error("An error occurred while uploading calibration data", e);
        }
    }

    public static void onProgramRestartRequest(Context ctx) {
        // TODO, check if this was successful or not
        ctx.status(204);
        restartProgram();
    }

    public static void onImportObjectDetectionModelRequest(Context ctx) {
        try {
            // Retrieve the uploaded files
            var modelFile = ctx.uploadedFile("modelFile");

            // Strip any whitespaces on either side of the commas
            LinkedList<String> labels = new LinkedList<>();
            String rawLabels = ctx.formParam("labels");
            if (rawLabels != null) {
                for (String label : rawLabels.split(",")) {
                    labels.add(label.trim());
                }
            }
            int width = Integer.parseInt(ctx.formParam("width"));
            int height = Integer.parseInt(ctx.formParam("height"));
            NeuralNetworkModelManager.Version version =
                    switch (ctx.formParam("version").toString()) {
                        case "YOLOv5" -> NeuralNetworkModelManager.Version.YOLOV5;
                        case "YOLOv8" -> NeuralNetworkModelManager.Version.YOLOV8;
                        case "YOLO11" -> NeuralNetworkModelManager.Version.YOLOV11;
                        // Add more versions as necessary for new models
                        default -> {
                            ctx.status(400);
                            ctx.result("The provided version was not valid");
                            logger.error("The provided version was not valid");
                            yield null;
                        }
                    };

            if (modelFile == null) {
                ctx.status(400);
                ctx.result(
                        "No File was sent with the request. Make sure that the model file is sent at the key 'modelFile'");
                logger.error(
                        "No File was sent with the request. Make sure that the model file is sent at the key 'modelFile'");
                return;
            }

            if (labels == null || labels.isEmpty()) {
                ctx.status(400);
                ctx.result("The provided labels were malformed");
                logger.error("The provided labels were malformed");
                return;
            }

            if (width < 0 || height < 0 || width != Math.floor(width) || height != Math.floor(height)) {
                ctx.status(400);
                ctx.result(
                        "The provided width and height were malformed. They must be integers greater than one.");
                logger.error(
                        "The provided width and height were malformed.  They must be integers greater than one.");
                return;
            }

            NeuralNetworkModelManager.Family family;

            switch (Platform.getCurrentPlatform()) {
                case LINUX_QCS6490:
                    family = NeuralNetworkModelManager.Family.RUBIK;
                    break;
                case LINUX_RK3588_64:
                    family = NeuralNetworkModelManager.Family.RKNN;
                    break;
                default:
                    ctx.status(400);
                    ctx.result("The current platform does not support object detection models");
                    logger.error("The current platform does not support object detection models");
                    return;
            }

            // If adding additional platforms, check platform matches
            if (!modelFile.extension().contains(family.extension())) {
                ctx.status(400);
                ctx.result(
                        "The uploaded file was not of type '"
                                + family.extension()
                                + "'. The uploaded file should be a ."
                                + family.extension()
                                + " file.");
                logger.error(
                        "The uploaded file was not of type '"
                                + family.extension()
                                + "'. The uploaded file should be a ."
                                + family.extension()
                                + " file.");
                return;
            }

            Path modelPath =
                    Paths.get(
                            ConfigProvider.getInstance().getModelsDirectory().toString(), modelFile.filename());

            if (modelPath.toFile().exists()) {
                ctx.status(400);
                ctx.result(
                        "The model file already exists. Please delete the existing model file before uploading a new one.");
                logger.error(
                        "The model file already exists. Please delete the existing model file before uploading a new one.");
                return;
            }

            try (InputStream modelFileStream = modelFile.content()) {
                Files.copy(modelFileStream, modelPath, StandardCopyOption.REPLACE_EXISTING);
            }

            int idx = modelFile.filename().lastIndexOf('.');
            String nickname = modelFile.filename().substring(0, idx);

            ModelProperties modelProperties =
                    new ModelProperties(modelPath, nickname, labels, width, height, family, version);

            if (!testMode) {
                ObjectDetector objDetector = null;

                try {
                    objDetector =
                            switch (family) {
                                case RUBIK -> new TFLiteModel(modelProperties, TFLiteSource.RUBIK).load();
                                case RKNN -> new RknnModel(modelProperties).load();
                            };
                } catch (RuntimeException e) {
                    ctx.status(400);
                    ctx.result("Failed to load object detection model: " + e.getMessage());

                    try {
                        Files.deleteIfExists(modelPath);
                    } catch (IOException ex) {
                        e.addSuppressed(ex);
                    }

                    logger.error("Failed to load object detection model", e);
                    return;
                } finally {
                    // this finally block will run regardless of what happens in try/catch
                    // please see https://docs.oracle.com/javase/tutorial/essential/exceptions/finally.html
                    // for a summary on how finally works
                    if (objDetector != null) {
                        objDetector.release();
                    }
                }
            }
            ConfigProvider.getInstance()
                    .getConfig()
                    .getNeuralNetworkProperties()
                    .addModelProperties(modelProperties);

            logger.debug(
                    ConfigProvider.getInstance().getConfig().getNeuralNetworkProperties().toString());

            NeuralNetworkModelManager.getInstance().discoverModels();

            ctx.status(200).result("Successfully uploaded object detection model");
        } catch (Exception e) {
            ctx.status(500).result("Error processing files: " + e.getMessage());
            logger.error("Error processing new object detection model", e);
        }

        DataChangeService.getInstance()
                .publishEvent(
                        new OutgoingUIEvent<>(
                                "fullsettings",
                                UIPhotonConfiguration.programStateToUi(ConfigProvider.getInstance().getConfig())));
    }

    public static void onExportObjectDetectionModelsRequest(Context ctx) {
        logger.info("Exporting Object Detection Models to ZIP Archive");

        try {
            var zip = ConfigProvider.getInstance().getObjectDetectionExportAsZip();
            var stream = new FileInputStream(zip);
            logger.info("Uploading object detection models with size " + stream.available());

            ctx.contentType("application/zip");
            ctx.header(
                    "Content-Disposition",
                    "attachment; filename=\"photonvision-object-detection-models-export.zip\"");

            ctx.result(stream);
            ctx.status(200);
        } catch (IOException e) {
            logger.error("Unable to export object detection models archive, bad recode from zip to byte");
            ctx.status(500);
            ctx.result("There was an error while exporting the object detection models archive");
        }
    }

    public static void onExportIndividualObjectDetectionModelRequest(Context ctx) {
        logger.info("Exporting Individual Object Detection Model");

        try {
            String modelPath = ctx.queryParam("modelPath");

            if (modelPath == null || modelPath.isEmpty()) {
                ctx.status(400);
                ctx.result("The provided model path was malformed");
                logger.error("The provided model path was malformed");
                return;
            }

            File modelFile = NeuralNetworkModelManager.getInstance().exportSingleModel(modelPath);

            var stream = new FileInputStream(modelFile);
            logger.info("Uploading object detection model with size " + stream.available());

            ctx.contentType("application/octet-stream");
            ctx.header("Content-Disposition", "attachment; filename=" + modelFile.getName());

            ctx.result(stream);
            ctx.status(200);
        } catch (IOException e) {
            logger.error("Unable to export object detection model, " + e);
            ctx.status(500);
            ctx.result("There was an error while exporting the object detection model");
        }
    }

    public static void onBulkImportObjectDetectionModelRequest(Context ctx) {
        var file = ctx.uploadedFile("data");

        if (file == null) {
            ctx.status(400);
            ctx.result(
                    "No File was sent with the request. Make sure that the object detection zip is sent at the key 'data'");
            logger.error(
                    "No File was sent with the request. Make sure that the object detection zip file is sent at the key 'data'");
            return;
        }

        if (!file.extension().contains("zip")) {
            ctx.status(400);
            ctx.result(
                    "The uploaded file was not of type 'zip'. The uploaded file should be a .zip file.");
            logger.error(
                    "The uploaded file was not of type 'zip'. The uploaded file should be a .zip file.");
            return;
        }

        // Create a temp file
        var tempFilePath = handleTempFileCreation(file);

        if (tempFilePath.isEmpty()) {
            ctx.status(500);
            ctx.result("There was an error while creating a temporary copy of the file");
            logger.error("There was an error while creating a temporary copy of the file");
            return;
        }

        Path tempDir = null;
        // Extract .rknn files from zip and move to models directory
        try {
            tempDir = Files.createTempDirectory("photonvision-od-models");
            ZipUtil.unpack(tempFilePath.get(), tempDir.toFile());

            Path targetModelsDir = ConfigProvider.getInstance().getModelsDirectory().toPath();

            // Copy all files from the source models directory to the target models
            // directory
            try (var stream = Files.list(tempDir)) {
                for (Path modelFile : stream.toList()) {
                    if (Files.isRegularFile(modelFile)
                            && !modelFile.getFileName().toString().endsWith(".json")) {
                        logger.debug("Copying model file: " + modelFile.getFileName());
                        Files.copy(
                                modelFile,
                                Path.of(targetModelsDir.toString(), modelFile.getFileName().toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            logger.info("Successfully copied models from " + tempDir + " to " + targetModelsDir);

        } catch (Exception e) {
            ctx.status(500);
            ctx.result("There was an error while extracting and coyping the object detection models");
            logger.error(
                    "There was an error while extracting and copying the object detection models", e);
            return;
        }

        if (ConfigProvider.getInstance()
                .saveUploadedNeuralNetworkProperties(
                        Path.of(tempDir.toString(), "photonvision-object-detection-models.json"))) {
            ctx.status(200);
            ctx.result("Successfully saved the uploaded object detection models, rebooting...");
            logger.info("Successfully saved the uploaded object detection models, rebooting...");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while saving the uploaded object detection models");
            logger.error("There was an error while saving the uploaded object detection models");
        }

        DataChangeService.getInstance()
                .publishEvent(
                        new OutgoingUIEvent<>(
                                "fullsettings",
                                UIPhotonConfiguration.programStateToUi(ConfigProvider.getInstance().getConfig())));
    }

    private static org.photonvision.vision.processes.VisionModule moduleByUniqueName(String name) {
        for (var m : VisionSourceManager.getInstance().vmm.getModules()) {
            if (m.uniqueName().equals(name)) return m;
        }
        return null;
    }

    /**
     * GET /api/focus?camera=UNIQUE_NAME (photonvision-34): the camera's focus score, and keep
     * measuring for 3 s (FocusMeter). 204 until the first measurement. {@code &reset=1} clears the
     * best score first.
     */
    public static void onFocusRequest(Context ctx) {
        var m = moduleByUniqueName(ctx.queryParam("camera"));
        if (m == null) {
            ctx.status(404);
            ctx.result("No camera " + ctx.queryParam("camera"));
            return;
        }
        if (ctx.queryParam("reset") != null) m.focusMeter().resetBest();
        var r = m.focusMeter().read();
        try {
            if (r == null) {
                ctx.status(204);
                return;
            }
            ctx.status(200);
            ctx.json(r);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("Focus reading failed: " + e.getMessage());
        }
    }

    /**
     * GET /api/focus/frame?camera=UNIQUE_NAME (Spectrum, focus view): the camera's latest image at
     * full size as a JPEG, as FocusMeter measured it. 204 until the first one.
     */
    public static void onFocusFrameRequest(Context ctx) {
        var m = moduleByUniqueName(ctx.queryParam("camera"));
        if (m == null) {
            ctx.status(404);
            ctx.result("No camera " + ctx.queryParam("camera"));
            return;
        }
        byte[] jpeg = m.focusMeter().frameJpeg();
        if (jpeg == null) {
            ctx.status(204);
            return;
        }
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("image/jpeg");
        ctx.result(jpeg);
    }

    // ---- Spectrum (photonvision-91): the full-screen calibration view ----

    /**
     * GET /api/calibration/live?camera=UNIQUE_NAME: the board in view (corners, motion, distance,
     * tilt), the session's snapshots, coverage and guidance. 204 until the first frame; keeps the
     * live state (and the frames below) coming for 3 s.
     */
    public static void onCalibrationLiveRequest(Context ctx) {
        var m = moduleByUniqueName(ctx.queryParam("camera"));
        if (m == null) {
            ctx.status(404);
            ctx.result("No camera " + ctx.queryParam("camera"));
            return;
        }
        var live = m.calibrationPipeline().live();
        if (live == null) {
            ctx.status(204);
            return;
        }
        ctx.header("Cache-Control", "no-store");
        ctx.json(live);
    }

    /** GET /api/calibration/frame?camera=UNIQUE_NAME: the camera's image at full size, JPEG. */
    public static void onCalibrationFrameRequest(Context ctx) {
        var m = moduleByUniqueName(ctx.queryParam("camera"));
        if (m == null) {
            ctx.status(404);
            ctx.result("No camera " + ctx.queryParam("camera"));
            return;
        }
        byte[] jpeg = m.calibrationPipeline().viewJpeg();
        if (jpeg == null) {
            ctx.status(204);
            return;
        }
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("image/jpeg");
        ctx.result(jpeg);
    }

    /**
     * POST /api/calibration/solve {"cameraUniqueName": ...}: solve from the snapshots so far (kept as
     * the pending calibration, like /api/calibration/compute) and grade it: rms, coverage, fx and its
     * spread, outliers, the fold check, and the save gate.
     */
    public static void onCalibrationSolveRequest(Context ctx) {
        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);
            var m = moduleByUniqueName(request.cameraUniqueName);
            if (m == null) {
                ctx.status(404);
                ctx.result("No camera " + request.cameraUniqueName);
                return;
            }
            var cal = m.computeCalibration();
            if (cal == null) {
                ctx.status(500);
                ctx.result("The calibration solve failed (see the log)");
                return;
            }
            ctx.json(m.calibrationPipeline().evaluate(cal));
        } catch (IllegalStateException | JsonException e) {
            ctx.status(400);
            ctx.result(e.getMessage());
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("The calibration solve failed: " + e.getMessage());
            logger.error("Calibration solve failed", e);
        }
    }

    /** GET /api/calibration/quality?camera=UNIQUE_NAME: the last solve's grade, 204 if none. */
    public static void onCalibrationQualityRequest(Context ctx) {
        var m = moduleByUniqueName(ctx.queryParam("camera"));
        if (m == null) {
            ctx.status(404);
            ctx.result("No camera " + ctx.queryParam("camera"));
            return;
        }
        var q = m.calibrationPipeline().lastQuality();
        if (q == null) {
            ctx.status(204);
            return;
        }
        ctx.json(q);
    }

    @Json
    record CalibrationSnapshotRemoveRequest(String cameraUniqueName, int index) {}

    /** POST /api/calibration/removeSnapshot {"cameraUniqueName": ..., "index": n}. */
    public static void onCalibrationRemoveSnapshotRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(CalibrationSnapshotRemoveRequest.class);
            var m = moduleByUniqueName(req.cameraUniqueName());
            if (m == null) {
                ctx.status(404);
                ctx.result("No camera " + req.cameraUniqueName());
                return;
            }
            if (!m.isCalibrating()) {
                ctx.status(400);
                ctx.result("The camera isn't calibrating");
                return;
            }
            if (!m.calibrationPipeline().removeSnapshot(req.index())) {
                ctx.status(404);
                ctx.result("No snapshot " + req.index());
                return;
            }
            ctx.status(200);
            ctx.result("Removed snapshot " + req.index());
        } catch (JsonException e) {
            ctx.status(400);
            ctx.result(e.getMessage());
        }
    }

    /**
     * GET /api/usb/bandwidth (photonvision-32): every USB camera's bandwidth, reserved and really
     * used, against the budget all USB 2.0 ports share. See UsbBandwidth.
     */
    public static void onUsbBandwidthRequest(Context ctx) {
        try {
            ctx.status(200);
            ctx.json(UsbBandwidth.survey());
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("USB bandwidth survey failed: " + e.getMessage());
        }
    }

    @Json
    record UsbAllocationRequest(String port, Integer bytes) {}

    /**
     * POST /api/usb/bandwidth {"port": "1-2.4", "bytes": 944} (photonvision-32): the most that camera
     * may reserve per microframe: one of its alternate settings, 0 for uncapped, or null for its
     * model's cap. Saved for the next boot; the camera is reset (like a replug) to apply it.
     */
    public static void onUsbAllocationRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(UsbAllocationRequest.class);
            if (req.port() == null) throw new IllegalArgumentException("port is required");
            ctx.status(200);
            ctx.result(UsbBandwidth.setAllocation(req.port(), req.bytes()));
        } catch (IllegalArgumentException | IllegalStateException | JsonException e) {
            ctx.status(400);
            ctx.result(e.getMessage());
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("Couldn't change the allocation: " + e.getMessage());
        }
    }

    /** SpectrumJetson (photonvision-54): each camera's tag contrast over the last 2 s (TagContrast). */
    public static void onTagContrastRequest(Context ctx) {
        var names =
                VisionSourceManager.getInstance().vmm.getModules().stream()
                        .map(m -> m.toUICameraConfig().nickname)
                        .toList();
        try {
            ctx.json(
                    org.photonvision.vision.target.TagContrast.summary(names),
                    Types.mapOf(Object.class));
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("Tag contrast failed: " + e.getMessage());
        }
    }

    // SpectrumJetson (photonvision-50): Settings > Robot state: idle mode (49, 50), the event
    // profile (51), the far-tag search (55), quiet mode (56), the thermal limit (57), the cooling
    // (71), the clock throttle (90), and the robot's state as PhotonVision sees it.
    @Json
    record RobotStateRequest(
            Boolean idleWhileDisabled,
            Double idleFps,
            Boolean eventProfileOnFms,
            Integer eventPipeline,
            Boolean switchNow,
            Boolean farSearch,
            Double farSweepsPerSecond,
            Boolean quietAfterMatch,
            Integer quietAfterDisabledSeconds,
            Integer throttleClocksAfterDisabledSeconds,
            String fanMode) {}

    public static void onRobotStateRequest(Context ctx) {
        ctx.json(org.photonvision.vision.processes.IdleMode.status(), Types.mapOf(Object.class));
    }

    public static void onRobotStateSetRequest(Context ctx) {
        RobotStateRequest req;
        try {
            req = ctx.bodyAsClass(RobotStateRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400);
            ctx.result(
                    "Expected {idleWhileDisabled, idleFps, eventProfileOnFms, eventPipeline, switchNow,"
                            + " farSearch, farSweepsPerSecond, quietAfterMatch, quietAfterDisabledSeconds,"
                            + " throttleClocksAfterDisabledSeconds, fanMode}: "
                            + e.getMessage());
            return;
        }
        org.photonvision.vision.processes.IdleMode.update(
                req.idleWhileDisabled(),
                req.idleFps(),
                req.eventProfileOnFms(),
                req.eventPipeline(),
                req.quietAfterMatch(),
                req.quietAfterDisabledSeconds(),
                req.throttleClocksAfterDisabledSeconds());
        // photonvision-55: the far search keeps its own settings (FarSearch), shown here as in 2026.
        if (req.farSearch() != null || req.farSweepsPerSecond() != null) {
            org.photonvision.vision.processes.FarSearch.update(req.farSearch(), req.farSweepsPerSecond());
        }
        // photonvision-71: the cooling (quiet, full or off), applied and saved by spectrum-fan.
        if (req.fanMode() != null) {
            String why = org.photonvision.vision.processes.FanMode.set(req.fanMode());
            if (why != null) {
                ctx.status(400);
                ctx.result("Couldn't change the cooling: " + why);
                return;
            }
        }
        // photonvision-51: "Switch now" on Settings > Robot state, to test the event profile.
        if (Boolean.TRUE.equals(req.switchNow())) {
            var idle = org.photonvision.vision.processes.IdleMode.getSettings();
            org.photonvision.vision.processes.IdleMode.switchAllTo(
                    idle.eventPipeline, "Switch now (Settings)");
        }
        onRobotStateRequest(ctx);
    }

    // SpectrumJetson Rewind (photonvision-07). POST body, all optional:
    // {"manual": true|false, "fps": 30, "quotaGB": 100, "minFreeGB": 15,
    //  "recordThroughDisconnects": true|false, "delete": "<session>"}
    @Json
    record RewindRequest(
            Boolean manual,
            Double fps,
            Double quotaGB,
            Double minFreeGB,
            Boolean recordThroughDisconnects,
            String delete) {}

    public static void onRewindStatusRequest(Context ctx) {
        ctx.json(RewindManager.getInstance().status(), Types.mapOf(Object.class));
    }

    public static void onRewindSetRequest(Context ctx) {
        RewindRequest req;
        try {
            req = ctx.bodyAsClass(RewindRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400);
            ctx.result(
                    "Expected {\"manual\": true|false, \"fps\": n, \"quotaGB\": n, \"minFreeGB\": n,"
                            + " \"recordThroughDisconnects\": true|false, \"delete\": name}");
            return;
        }
        var rm = RewindManager.getInstance();
        if (req.fps() != null
                || req.quotaGB() != null
                || req.minFreeGB() != null
                || req.recordThroughDisconnects() != null) {
            rm.updateSettings(req.fps(), req.quotaGB(), req.minFreeGB(), req.recordThroughDisconnects());
        }
        if (req.manual() != null) rm.setManual(req.manual());
        // photonvision-65: why a delete failed (still recording, quiet mode, files left over).
        String why = req.delete() != null ? rm.deleteSession(req.delete()) : null;
        if (why != null) {
            ctx.status(400);
            ctx.result("Couldn't delete " + req.delete() + ": " + why);
            return;
        }
        onRewindStatusRequest(ctx);
    }

    /** GET /api/rewind/download?session=NAME: the recording as a zip of AVIs, built as it streams. */
    public static void onRewindDownloadRequest(Context ctx) {
        String name = ctx.queryParam("session");
        var dir = RewindManager.sessionDir(name);
        if (dir == null) {
            ctx.status(404);
            ctx.result("No such recording");
            return;
        }
        logger.info("Rewind: sending recording " + name + " to " + ctx.ip());
        ctx.contentType("application/zip");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + ".zip\"");
        // A match is ~1 GB: run the copy below the vision threads' priority.
        RewindManager.setThreadNice(10, logger);
        try {
            org.photonvision.rewind.RewindExport.writeZip(dir, ctx.outputStream());
        } catch (IOException e) {
            logger.warn("Rewind: download of " + name + " stopped: " + e.getMessage());
        } finally {
            RewindManager.setThreadNice(0, logger);
        }
    }

    // SpectrumJetson (photonvision-05): Wi-Fi / Bluetooth radio switches (Settings > Networking).
    // PhotonVision runs as root, so it drives nmcli / rfkill directly. Both persist across reboots
    // (NetworkManager saves the Wi-Fi radio state, systemd-rfkill the Bluetooth block).
    @Json
    record RadioRequest(Boolean wifi, Boolean bluetooth) {}

    private static String shellOutput(String command) {
        try {
            var shell = new ShellExec(true, false);
            shell.executeBashCommand(command, true, false);
            String out = shell.getOutput();
            return out == null ? "" : out.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static Map<String, Object> radioStatus(String clientIp) {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("wifiSupported", !shellOutput("nmcli -t -f TYPE dev | grep -x wifi").isEmpty());
        status.put("wifi", shellOutput("nmcli radio wifi").equals("enabled"));
        status.put(
                "wifiConnection",
                shellOutput(
                        "nmcli -t -f TYPE,STATE,CONNECTION dev"
                                + " | awk -F: '$1==\"wifi\" && $2==\"connected\" {print $3}'"));
        status.put("bluetoothSupported", !shellOutput("rfkill list bluetooth").isEmpty());
        status.put(
                "bluetooth", !shellOutput("rfkill list bluetooth | grep -i 'soft blocked: no'").isEmpty());
        // Does this browser reach us over Wi-Fi? Then turning Wi-Fi off cuts the UI off.
        boolean overWifi = false;
        if (clientIp != null && clientIp.matches("[0-9.]+")) {
            String dev =
                    shellOutput("ip route get " + clientIp + " | grep -o 'dev [^ ]*' | cut -d' ' -f2");
            overWifi =
                    !dev.isEmpty() && shellOutput("nmcli -t -f DEVICE,TYPE dev").contains(dev + ":wifi");
        }
        status.put("clientOverWifi", overWifi);
        return status;
    }

    /** GET /api/settings/radios. */
    public static void onRadioStatusRequest(Context ctx) {
        ctx.json(radioStatus(ctx.ip()), Types.mapOf(Object.class));
    }

    /** POST /api/settings/radios {"wifi": bool, "bluetooth": bool}, either optional. */
    public static void onRadioSetRequest(Context ctx) {
        RadioRequest req;
        try {
            req = ctx.bodyAsClass(RadioRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400);
            ctx.result("Expected {\"wifi\": true|false} and/or {\"bluetooth\": true|false}");
            return;
        }
        if (req.wifi() != null) {
            logger.info("Turning Wi-Fi " + (req.wifi() ? "on" : "off"));
            shellOutput("nmcli radio wifi " + (req.wifi() ? "on" : "off"));
        }
        if (req.bluetooth() != null) {
            logger.info("Turning Bluetooth " + (req.bluetooth() ? "on" : "off"));
            shellOutput("rfkill " + (req.bluetooth() ? "unblock" : "block") + " bluetooth");
        }
        onRadioStatusRequest(ctx);
    }

    // SpectrumJetson (photonvision-22): tags left out of multi-tag on every camera.
    @Json
    record ExcludedTagsRequest(java.util.List<Integer> saved) {}

    private static Map<String, Object> excludedTagsStatus() {
        var et = org.photonvision.vision.target.ExcludedTags.getInstance();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("saved", new ArrayList<>(new java.util.TreeSet<>(et.saved())));
        m.put("robot", new ArrayList<>(new java.util.TreeSet<>(et.robot())));
        m.put("active", new ArrayList<>(new java.util.TreeSet<>(et.active())));
        return m;
    }

    /** GET /api/excludedTags: {saved, robot, active}. */
    public static void onExcludedTagsRequest(Context ctx) {
        ctx.json(excludedTagsStatus(), Types.mapOf(Object.class));
    }

    /** POST /api/excludedTags {"saved": [7, 12]}: replaces the list saved on the Jetson. */
    public static void onExcludedTagsSetRequest(Context ctx) {
        ExcludedTagsRequest req;
        try {
            req = ctx.bodyAsClass(ExcludedTagsRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400);
            ctx.result("Expected {\"saved\": [tag IDs]}");
            return;
        }
        try {
            org.photonvision.vision.target.ExcludedTags.getInstance()
                    .setSaved(req.saved() == null ? java.util.List.of() : req.saved());
        } catch (IOException e) {
            ctx.status(500);
            ctx.result("Could not save the excluded tags: " + e.getMessage());
            return;
        }
        onExcludedTagsRequest(ctx);
    }

    // ---- Spectrum feature 13, part A (settings): copy settings, new pipeline from any camera's,
    // settings snapshots (photonvision-25, 46, 53, 59, 68). ----

    // SpectrumJetson (photonvision-25): copy settings from another camera's or pipeline's settings.
    @Json
    record CopySettingsRequest(
            String fromCamera,
            Integer fromPipeline,
            String toCamera,
            Integer toPipeline,
            java.util.List<String> groups) {}

    /**
     * POST /api/settings/copySettings {fromCamera, fromPipeline, toCamera, toPipeline, groups}:
     * copies the chosen groups (camera, resolution, apriltag, output, objectDetection) of settings.
     */
    public static void onCopySettingsRequest(Context ctx) {
        CopySettingsRequest req;
        try {
            req = ctx.bodyAsClass(CopySettingsRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400);
            ctx.result("Expected {fromCamera, fromPipeline, toCamera, toPipeline, groups}");
            return;
        }
        var vmm = VisionSourceManager.getInstance().vmm;
        var from = req.fromCamera() == null ? null : vmm.getModule(req.fromCamera());
        var to = req.toCamera() == null ? null : vmm.getModule(req.toCamera());
        if (from == null || to == null || req.fromPipeline() == null || req.toPipeline() == null) {
            ctx.status(400);
            ctx.result("Unknown camera or pipeline");
            return;
        }
        var source = from.getPipelineSettingsAt(req.fromPipeline());
        if (source == null) {
            ctx.status(400);
            ctx.result("The source camera has no pipeline " + req.fromPipeline());
            return;
        }
        if (req.fromCamera().equals(req.toCamera()) && req.fromPipeline().equals(req.toPipeline())) {
            ctx.status(400);
            ctx.result("Pick a different pipeline or camera to copy from");
            return;
        }
        try {
            var copied =
                    to.copySettingsFrom(
                            source,
                            req.toPipeline(),
                            req.groups() == null ? java.util.List.of() : req.groups(),
                            from.videoModeList().equals(to.videoModeList()));
            ctx.json(Map.of("copied", copied), Types.mapOf(Object.class));
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.result(e.getMessage());
        }
    }

    @Json
    record CreatePipelineRequest(
            String name,
            Integer type,
            String fromCamera,
            Integer fromPipeline,
            java.util.List<String> cameras,
            String switchCamera) {}

    /**
     * SpectrumJetson (photonvision-46): the new-pipeline dialog. Creates pipeline {@code name} on
     * each of {@code cameras}: blank of {@code type} (the dashboard's pipeline type number), or
     * starting from {@code fromCamera}'s pipeline {@code fromPipeline}. {@code switchCamera} (the one
     * on the dashboard) switches to its new pipeline; the others stay on theirs. A camera that
     * already has a pipeline with that name is skipped. Answers {created: [{camera, index}], skipped:
     * [{camera, reason}]}.
     */
    public static void onCreatePipelineRequest(Context ctx) {
        CreatePipelineRequest req;
        try {
            req = ctx.bodyAsClass(CreatePipelineRequest.class);
        } catch (RuntimeException e) {
            ctx.status(400);
            ctx.result("Expected {name, type or fromCamera and fromPipeline, cameras, switchCamera}");
            return;
        }
        var vmm = VisionSourceManager.getInstance().vmm;
        if (req.name() == null
                || req.name().isBlank()
                || req.cameras() == null
                || req.cameras().isEmpty()) {
            ctx.status(400);
            ctx.result("A name and at least one camera are needed");
            return;
        }
        org.photonvision.vision.processes.VisionModule from = null;
        org.photonvision.vision.pipeline.CVPipelineSettings source = null;
        org.photonvision.vision.pipeline.PipelineType type = null;
        if (req.fromCamera() != null) {
            from = vmm.getModule(req.fromCamera());
            source =
                    from == null || req.fromPipeline() == null
                            ? null
                            : from.getPipelineSettingsAt(req.fromPipeline());
            if (source == null) {
                ctx.status(400);
                ctx.result("Unknown camera or pipeline to start from");
                return;
            }
        } else {
            // The dashboard numbers types from -3 (FocusCamera); PipelineType starts there too.
            var types = org.photonvision.vision.pipeline.PipelineType.values();
            int t = req.type() == null ? -99 : req.type() + 3;
            if (t < 0 || t >= types.length) {
                ctx.status(400);
                ctx.result("Unknown pipeline type " + req.type());
                return;
            }
            type = types[t];
        }
        var created = new ArrayList<Map<String, Object>>();
        var skipped = new ArrayList<Map<String, Object>>();
        for (String id : req.cameras()) {
            var to = id == null ? null : vmm.getModule(id);
            if (to == null) {
                skipped.add(Map.of("camera", String.valueOf(id), "reason", "no such camera"));
                continue;
            }
            String nickname = to.getCameraConfiguration().nickname;
            try {
                int index =
                        to.createPipeline(
                                req.name(),
                                type,
                                source,
                                to == from,
                                from != null && from.videoModeList().equals(to.videoModeList()),
                                id.equals(req.switchCamera()));
                created.add(Map.of("camera", nickname, "index", index));
            } catch (IllegalArgumentException e) {
                skipped.add(Map.of("camera", nickname, "reason", String.valueOf(e.getMessage())));
            }
        }
        ctx.status(created.isEmpty() ? 400 : 200);
        ctx.json(Map.of("created", created, "skipped", skipped), Types.mapOf(Object.class));
    }

    // SpectrumJetson (photonvision-53): named settings snapshots (Settings > Settings snapshots).
    @Json
    record SnapshotRequest(String name, String id) {}

    /** GET /api/snapshots: every snapshot's meta, newest first. */
    public static void onSnapshotListRequest(Context ctx) {
        ctx.json(
                org.photonvision.common.configuration.SettingsSnapshots.listAsMaps(),
                Types.listOf(Types.mapOf(Object.class)));
    }

    /** POST /api/snapshots {name}: takes a snapshot now. */
    public static void onSnapshotCreateRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(SnapshotRequest.class);
            var meta =
                    org.photonvision.common.configuration.SettingsSnapshots.create(req.name(), "manual");
            ctx.json(meta.toMap(), Types.mapOf(Object.class));
        } catch (IOException | RuntimeException e) {
            ctx.status(500);
            ctx.result("Couldn't save the snapshot: " + e.getMessage());
        }
    }

    /**
     * POST /api/snapshots/restore {id}: restores it and restarts PhotonVision; answers with the
     * automatic "Before restoring" snapshot.
     */
    public static void onSnapshotRestoreRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(SnapshotRequest.class);
            var before = org.photonvision.common.configuration.SettingsSnapshots.restore(req.id());
            ctx.json(before.toMap(), Types.mapOf(Object.class));
        } catch (IOException | RuntimeException e) {
            ctx.status(400);
            ctx.result("Couldn't restore: " + e.getMessage());
        }
    }

    /** POST /api/snapshots/delete {id}: answers with the list left. */
    public static void onSnapshotDeleteRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(SnapshotRequest.class);
            org.photonvision.common.configuration.SettingsSnapshots.delete(req.id());
            onSnapshotListRequest(ctx);
        } catch (IOException | RuntimeException e) {
            ctx.status(400);
            ctx.result("Couldn't delete: " + e.getMessage());
        }
    }

    /** GET /api/snapshots/download?id=...: the snapshot as a zip. */
    public static void onSnapshotDownloadRequest(Context ctx) {
        try {
            String id = ctx.queryParam("id");
            ctx.contentType("application/zip");
            ctx.header(
                    "Content-Disposition", "attachment; filename=\"photonvision-snapshot-" + id + ".zip\"");
            org.photonvision.common.configuration.SettingsSnapshots.writeZip(id, ctx.outputStream());
        } catch (IOException e) {
            ctx.status(400);
            ctx.result("Couldn't download: " + e.getMessage());
        }
    }

    // ---- end of feature 13 part A ----

    // SpectrumJetson (photonvision-87): Settings > Thriftiest Cam firmware.
    @Json
    record ThriftiestFlashRequest(String port, String image) {}

    private static final io.avaje.json.node.JsonNodeMapper kNodeMapper =
            io.avaje.json.node.JsonNodeMapper.builder().build();

    private static void thriftiestJson(Context ctx, io.avaje.json.node.JsonObject body) {
        ctx.contentType("application/json");
        ctx.result(kNodeMapper.toJson(body));
    }

    public static void onThriftiestFirmwareRequest(Context ctx) {
        thriftiestJson(ctx, ThriftiestFirmware.status());
    }

    public static void onThriftiestFirmwareUploadRequest(Context ctx) {
        var file = ctx.uploadedFile("data");
        if (file == null) {
            ctx.status(400);
            ctx.result("No file was sent (form field 'data')");
            return;
        }
        try {
            thriftiestJson(ctx, ThriftiestFirmware.upload(file.filename(), file.content()));
        } catch (IOException e) {
            ctx.status(400);
            ctx.result("Not stored: " + e.getMessage());
        }
    }

    public static void onThriftiestFirmwareDeleteRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(ThriftiestFlashRequest.class);
            ThriftiestFirmware.delete(req.image());
            thriftiestJson(ctx, ThriftiestFirmware.status());
        } catch (IOException | RuntimeException e) {
            ctx.status(400);
            ctx.result("Couldn't delete: " + e.getMessage());
        }
    }

    public static void onThriftiestFirmwareFlashRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(ThriftiestFlashRequest.class);
            ThriftiestFirmware.flash(req.port(), req.image());
            ctx.status(202);
        } catch (IOException | RuntimeException e) {
            ctx.status(400);
            ctx.result("Not flashed: " + e.getMessage());
        }
    }

    // SpectrumJetson: Settings > Swyft camera firmware (like the Thriftiest card above).
    @Json
    record SwyftFlashRequest(String port, String image, String serial) {}

    public static void onSwyftFirmwareRequest(Context ctx) {
        thriftiestJson(ctx, SwyftFirmware.status());
    }

    public static void onSwyftFirmwareUploadRequest(Context ctx) {
        var file = ctx.uploadedFile("data");
        if (file == null) {
            ctx.status(400);
            ctx.result("No file was sent (form field 'data')");
            return;
        }
        try {
            thriftiestJson(ctx, SwyftFirmware.upload(file.filename(), file.content()));
        } catch (IOException e) {
            ctx.status(400);
            ctx.result("Not stored: " + e.getMessage());
        }
    }

    public static void onSwyftFirmwareDeleteRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(SwyftFlashRequest.class);
            SwyftFirmware.delete(req.image());
            thriftiestJson(ctx, SwyftFirmware.status());
        } catch (IOException | RuntimeException e) {
            ctx.status(400);
            ctx.result("Couldn't delete: " + e.getMessage());
        }
    }

    public static void onSwyftFirmwareFlashRequest(Context ctx) {
        try {
            var req = ctx.bodyAsClass(SwyftFlashRequest.class);
            SwyftFirmware.flash(req.port(), req.image(), req.serial());
            ctx.status(202);
        } catch (IOException | RuntimeException e) {
            ctx.status(400);
            ctx.result("Not flashed: " + e.getMessage());
        }
    }

    @Json
    record DeleteObjectDetectionModelRequest(Path modelPath) {}

    public static void onDeleteObjectDetectionModelRequest(Context ctx) {
        logger.info("Deleting object detection model");

        try {
            DeleteObjectDetectionModelRequest request =
                    ctx.bodyAsClass(DeleteObjectDetectionModelRequest.class);

            if (request.modelPath == null) {
                ctx.status(400);
                ctx.result("The provided model path was malformed");
                logger.error("The provided model path was malformed");
                return;
            }

            if (!request.modelPath.toFile().exists()) {
                ctx.status(400);
                ctx.result("The provided model path does not exist");
                logger.error("The provided model path does not exist");
                return;
            }

            if (!request.modelPath.toFile().delete()) {
                ctx.status(500);
                ctx.result("Unable to delete the model file");
                logger.error("Unable to delete the model file");
                return;
            }

            if (!ConfigProvider.getInstance()
                    .getConfig()
                    .getNeuralNetworkProperties()
                    .removeModel(request.modelPath)) {
                ctx.status(400);
                ctx.result("The model's information was not found in the config");
                logger.error("The model's information was not found in the config");
                return;
            }

            NeuralNetworkModelManager.getInstance().discoverModels();

            ctx.status(200).result("Successfully deleted object detection model");

            DataChangeService.getInstance()
                    .publishEvent(
                            new OutgoingUIEvent<>(
                                    "fullsettings",
                                    UIPhotonConfiguration.programStateToUi(
                                            ConfigProvider.getInstance().getConfig())));

        } catch (Exception e) {
            ctx.status(500);
            ctx.result("Error deleting object detection model: " + e.getMessage());
            logger.error("Error deleting object detection model", e);
        }
    }

    @Json
    record RenameObjectDetectionModelRequest(Path modelPath, String newName) {}

    public static void onRenameObjectDetectionModelRequest(Context ctx) {
        try {
            RenameObjectDetectionModelRequest request =
                    ctx.bodyAsClass(RenameObjectDetectionModelRequest.class);

            if (request.modelPath == null) {
                ctx.status(400);
                ctx.result("The provided model path was malformed");
                logger.error("The provided model path was malformed");
                return;
            }

            if (!request.modelPath.toFile().exists()) {
                ctx.status(400);
                ctx.result("The provided model path does not exist");
                logger.error("The model path: " + request.modelPath + " does not exist");
                return;
            }

            if (request.newName == null || request.newName.isEmpty()) {
                ctx.status(400);
                ctx.result("The provided new name was malformed");
                logger.error("The provided new name was malformed");
                return;
            }

            if (!ConfigProvider.getInstance()
                    .getConfig()
                    .getNeuralNetworkProperties()
                    .renameModel(request.modelPath, request.newName)) {
                ctx.status(400);
                ctx.result("The model's information was not found in the config");
                logger.error("The model's information was not found in the config");
                return;
            }

            NeuralNetworkModelManager.getInstance().discoverModels();
            ctx.status(200).result("Successfully renamed object detection model");

            DataChangeService.getInstance()
                    .publishEvent(
                            new OutgoingUIEvent<>(
                                    "fullsettings",
                                    UIPhotonConfiguration.programStateToUi(
                                            ConfigProvider.getInstance().getConfig())));
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("Error renaming object detection model: " + e.getMessage());
            logger.error("Error renaming object detection model", e);
            return;
        }
    }

    public static void onNukeObjectDetectionModelsRequest(Context ctx) {
        logger.info("Attempting to clear object detection models");
        try {
            NeuralNetworkModelManager.getInstance().clearModels();
            NeuralNetworkModelManager.getInstance().extractModels();
            ctx.status(200).result("Successfully cleared and reset object detection models");
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("Error clearing object detection models: " + e.getMessage());
            logger.error("Error clearing object detection models", e);
        }

        DataChangeService.getInstance()
                .publishEvent(
                        new OutgoingUIEvent<>(
                                "fullsettings",
                                UIPhotonConfiguration.programStateToUi(ConfigProvider.getInstance().getConfig())));
    }

    public static void onDeviceRestartRequest(Context ctx) {
        ctx.status(HardwareManager.getInstance().restartDevice() ? 204 : 500);
    }

    @Json
    record CameraNicknameChangeRequest(String name, String cameraUniqueName) {}

    public static void onCameraNicknameChangeRequest(Context ctx) {
        try {
            CameraNicknameChangeRequest request = ctx.bodyAsClass(CameraNicknameChangeRequest.class);

            VisionSourceManager.getInstance()
                    .vmm
                    .getModule(request.cameraUniqueName)
                    .setCameraNickname(request.name);
            ctx.status(200);
            ctx.result("Successfully changed the camera name to: " + request.name);
            logger.info("Successfully changed the camera name to: " + request.name);
        } catch (IllegalStateException | JsonException e) {
            ctx.status(400).result("Invalid JSON format");
            logger.error("Failed to process camera nickname change request", e);
        } catch (Exception e) {
            ctx.status(500).result("Failed to change camera nickname");
            logger.error("Unexpected error while changing camera nickname", e);
        }
    }

    /**
     * The calibration to serve for a resolution: the pending calibration while a calibration session
     * is active (it is cleared when the session ends), otherwise the saved calibration.
     */
    private static CameraCalibrationCoefficients getActiveCalibration(
            String cameraUniqueName, double width, double height) {
        var module = VisionSourceManager.getInstance().vmm.getModule(cameraUniqueName);
        if (module == null) {
            return null;
        }

        var pending = module.getPendingCalibration();
        if (pending != null
                && Math.abs(pending.resolution.width - width) < 1e-4
                && Math.abs(pending.resolution.height - height) < 1e-4) {
            return pending;
        }
        return module.getStateAsCameraConfig().calibrations.stream()
                .filter(
                        it ->
                                Math.abs(it.resolution.width - width) < 1e-4
                                        && Math.abs(it.resolution.height - height) < 1e-4)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the calibration JSON for a specific observation. Excludes camera image data
     *
     * <p>This is excluded from UICalibrationCoefficients by default to save bandwidth on large
     * calibrations
     */
    public static void onCalibrationJsonRequest(Context ctx) {
        String cameraUniqueName = ctx.queryParam("cameraUniqueName");
        var width = Integer.parseInt(ctx.queryParam("width"));
        var height = Integer.parseInt(ctx.queryParam("height"));

        var calList = getActiveCalibration(cameraUniqueName, width, height);
        if (calList == null) {
            ctx.status(404);
            return;
        }

        ctx.contentType("application/json");
        ctx.json(calList);
        ctx.status(200);
    }

    public static void onUncertaintyJsonRequest(Context ctx) {
        String cameraUniqueName = ctx.queryParam("cameraUniqueName");
        var width = Integer.parseInt(ctx.queryParam("width"));
        var height = Integer.parseInt(ctx.queryParam("height"));

        var calList = getActiveCalibration(cameraUniqueName, width, height);
        if (calList == null) {
            ctx.status(404);
            return;
        }

        try {
            ctx.json(calList.estimateUncertainty(), Types.listOf(Point3.class));
            ctx.status(200);
        } catch (Exception e) {
            ctx.status(422)
                    .result("Unable to estimate uncertainty for this calibration: " + e.getMessage());
            logger.error(
                    "Unable to estimate uncertainty for camera "
                            + cameraUniqueName
                            + " at "
                            + width
                            + "x"
                            + height
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    @Json
    record CalibrationRemoveRequest(int width, int height, String cameraUniqueName) {}

    public static void onCalibrationRemoveRequest(Context ctx) {
        try {
            CalibrationRemoveRequest request = ctx.bodyAsClass(CalibrationRemoveRequest.class);

            logger.info(
                    "Attempting to remove calibration for camera: "
                            + request.cameraUniqueName
                            + " with a resolution of "
                            + request.width
                            + "x"
                            + request.height);

            VisionSourceManager.getInstance()
                    .vmm
                    .getModule(request.cameraUniqueName)
                    .removeCalibrationFromConfig(new Size(request.width, request.height));

            ctx.status(200);
            ctx.result(
                    "Successfully removed calibration for resolution: "
                            + request.width
                            + "x"
                            + request.height);
            logger.info(
                    "Successfully removed calibration for resolution: "
                            + request.width
                            + "x"
                            + request.height);
        } catch (IllegalStateException | JsonException e) {
            ctx.status(400).result("Invalid JSON format");
            logger.error("Failed to process calibration removed request", e);
        } catch (Exception e) {
            ctx.status(500).result("Failed to removed calibration");
            logger.error("Unexpected error while attempting to remove calibration", e);
        }
    }

    public static void onCalibrationSnapshotRequest(Context ctx) {
        String cameraUniqueName = ctx.queryParam("cameraUniqueName");
        var width = Integer.parseInt(ctx.queryParam("width"));
        var height = Integer.parseInt(ctx.queryParam("height"));
        Integer observationIdx = Integer.parseInt(ctx.queryParam("snapshotIdx"));

        var calList = getActiveCalibration(cameraUniqueName, width, height);
        if (calList == null || calList.observations.size() < observationIdx) {
            ctx.status(404);
            return;
        }

        // encode as jpeg to save even more space. reduces size of a 1280p image from 300k to 25k
        var mat = calList.observations.get(observationIdx).annotateImage();
        if (mat == null) {
            ctx.status(404);
            return;
        }

        var jpegBytes = new MatOfByte();
        Imgcodecs.imencode(".jpg", mat, jpegBytes, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 60));
        ctx.result(jpegBytes.toArray());

        mat.release();
        jpegBytes.release();

        ctx.status(200);
    }

    public static void onCalibrationExportRequest(Context ctx) {
        String cameraUniqueName = ctx.queryParam("cameraUniqueName");
        var width = Integer.parseInt(ctx.queryParam("width"));
        var height = Integer.parseInt(ctx.queryParam("height"));

        var cc =
                VisionSourceManager.getInstance().vmm.getModule(cameraUniqueName).getStateAsCameraConfig();

        CameraCalibrationCoefficients calList =
                cc.calibrations.stream()
                        .filter(
                                it ->
                                        Math.abs(it.resolution.width - width) < 1e-4
                                                && Math.abs(it.resolution.height - height) < 1e-4)
                        .findFirst()
                        .orElse(null);

        if (calList == null) {
            ctx.status(404);
            return;
        }

        var filename = "photon_calibration_" + cc.uniqueName + "_" + width + "x" + height + ".json";
        ctx.contentType("application/json");
        ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ctx.json(calList);

        ctx.status(200);
    }

    public static void onImageSnapshotsRequest(Context ctx) {
        var snapshots = new ArrayList<Map<String, Object>>();
        var cameraDirs = ConfigProvider.getInstance().getImageSavePath().toFile().listFiles();

        if (cameraDirs != null) {
            try {
                for (File cameraDir : cameraDirs) {
                    var cameraSnapshots = cameraDir.listFiles();
                    if (cameraSnapshots == null) continue;

                    String cameraUniqueName = cameraDir.getName();

                    for (File snapshot : cameraSnapshots) {
                        var snapshotData = new HashMap<String, Object>();

                        var bufferedImage = ImageIO.read(snapshot);
                        var buffer = new ByteArrayOutputStream();
                        ImageIO.write(bufferedImage, "jpg", buffer);
                        byte[] data = buffer.toByteArray();

                        snapshotData.put("snapshotName", snapshot.getName());
                        snapshotData.put("cameraUniqueName", cameraUniqueName);
                        snapshotData.put("snapshotData", data);

                        snapshots.add(snapshotData);
                    }
                }
            } catch (IOException e) {
                ctx.status(500);
                ctx.result("Unable to read saved images");
            }
        }

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(snapshots);
    }

    public static void onCameraCalibImagesRequest(Context ctx) {
        try {
            Map<String, Map<String, ArrayList<Map<String, Object>>>> snapshots = new HashMap<>();

            var cameraDirs = ConfigProvider.getInstance().getCalibDir().toFile().listFiles();
            if (cameraDirs != null) {
                var camData = new HashMap<String, ArrayList<Map<String, Object>>>();
                for (var cameraDir : cameraDirs) {
                    var resolutionDirs = cameraDir.listFiles();
                    if (resolutionDirs == null) continue;
                    for (var resolutionDir : resolutionDirs) {
                        var calibImages = resolutionDir.listFiles();
                        if (calibImages == null) continue;
                        var resolutionImages = new ArrayList<Map<String, Object>>();
                        for (var calibImg : calibImages) {
                            var snapshotData = new HashMap<String, Object>();

                            var bufferedImage = ImageIO.read(calibImg);
                            var buffer = new ByteArrayOutputStream();
                            ImageIO.write(bufferedImage, "png", buffer);
                            byte[] data = buffer.toByteArray();

                            snapshotData.put("snapshotData", data);
                            snapshotData.put("snapshotFilename", calibImg.getName());

                            resolutionImages.add(snapshotData);
                        }
                        camData.put(resolutionDir.getName(), resolutionImages);
                    }

                    var cameraName = cameraDir.getName();
                    snapshots.put(cameraName, camData);
                }
            }

            ctx.contentType("application/json");
            ctx.json(snapshots);
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("An error occurred while getting calib data");
            logger.error("An error occurred while getting calib data", e);
        }
    }

    /**
     * Create a temporary file using the UploadedFile from Javalin.
     *
     * @param file the uploaded file.
     * @return Temporary file. Empty if the temporary file was unable to be created.
     */
    private static Optional<File> handleTempFileCreation(UploadedFile file) {
        var tempFilePath =
                new File(Path.of(System.getProperty("java.io.tmpdir"), file.filename()).toString());
        boolean makeDirsRes = tempFilePath.getParentFile().mkdirs();

        if (!makeDirsRes && !(tempFilePath.getParentFile().exists())) {
            logger.error(
                    "There was an error while creating "
                            + tempFilePath.getAbsolutePath()
                            + "! Exists: "
                            + tempFilePath.getParentFile().exists());
            return Optional.empty();
        }

        try {
            FileUtils.copyInputStreamToFile(file.content(), tempFilePath);
        } catch (IOException e) {
            logger.error(
                    "There was an error while copying "
                            + file.filename()
                            + " to the temp file "
                            + tempFilePath.getAbsolutePath());
            return Optional.empty();
        }

        return Optional.of(tempFilePath);
    }

    /**
     * Restart the running program. Note that this doesn't actually restart the program itself,
     * instead, it relies on systemd or an equivalent.
     */
    private static void restartProgram() {
        TimedTaskManager.getInstance()
                .addOneShotTask(
                        () -> {
                            if (Platform.isLinux()) {
                                try {
                                    new ShellExec().executeBashCommand("systemctl restart photonvision.service");
                                } catch (IOException e) {
                                    logger.error("Could not restart device!", e);
                                    System.exit(0);
                                }
                            } else {
                                System.exit(0);
                            }
                        },
                        0);
    }

    public static void onNukeConfigDirectory(Context ctx) {
        ConfigProvider.getInstance().setWriteTaskEnabled(false);
        ConfigProvider.getInstance().disableFlushOnShutdown();

        Logger.closeAllLoggers();
        if (ConfigProvider.nukeConfigDirectory()) {
            ctx.status(200);
            ctx.result("Successfully nuked config dir");
            restartProgram();
        } else {
            ctx.status(500);
            ctx.result("There was an error while nuking the config directory");
        }
    }

    public static void onNukeOneCamera(Context ctx) {
        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);

            logger.warn("Deleting camera name " + request.cameraUniqueName);

            var cameraDir =
                    ConfigProvider.getInstance()
                            .getCalibrationImageSavePath(request.cameraUniqueName)
                            .toFile();
            if (cameraDir.exists()) {
                FileUtils.deleteDirectory(cameraDir);
            }

            VisionSourceManager.getInstance().deleteVisionSource(request.cameraUniqueName);

            ctx.status(200);
        } catch (IOException | IllegalStateException | JsonException e) {
            logger.error("Failed to delete camera", e);
            ctx.status(500);
            ctx.result("Failed to delete camera");
            return;
        }
    }

    public static void onActivateMatchedCameraRequest(Context ctx) {
        logger.info(ctx.queryString());
        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);

            if (VisionSourceManager.getInstance()
                    .reactivateDisabledCameraConfig(request.cameraUniqueName)) {
                ctx.status(200);
            } else {
                ctx.status(403);
            }
        } catch (IllegalStateException | JsonException e) {
            ctx.status(401);
            logger.error("Failed to process activate matched camera request", e);
            ctx.result("Failed to process activate matched camera request");
            return;
        }
    }

    @Json
    record AssignUnmatchedCamera(PVCameraInfo cameraInfo) {}

    public static void onAssignUnmatchedCameraRequest(Context ctx) {
        logger.info(ctx.queryString());

        try {
            AssignUnmatchedCamera request = ctx.bodyAsClass(AssignUnmatchedCamera.class);

            if (request.cameraInfo == null) {
                ctx.status(400);
                ctx.result("cameraInfo is required");
                logger.error("cameraInfo is missing in the request");
                return;
            }

            if (VisionSourceManager.getInstance().assignUnmatchedCamera(request.cameraInfo)) {
                ctx.status(200);
            } else {
                ctx.status(404);
            }

            ctx.result("Successfully assigned camera: " + request.cameraInfo);
        } catch (IllegalStateException | JsonException e) {
            ctx.status(401);
            logger.error("Failed to process assign unmatched camera request", e);
            ctx.result("Failed to process assign unmatched camera request");
            return;
        }
    }

    public static void onUnassignCameraRequest(Context ctx) {
        logger.info(ctx.queryString());
        try {
            CommonCameraUniqueName request = ctx.bodyAsClass(CommonCameraUniqueName.class);

            if (VisionSourceManager.getInstance().deactivateVisionSource(request.cameraUniqueName)) {
                ctx.status(200);
            } else {
                ctx.status(403);
            }
        } catch (IllegalStateException | JsonException e) {
            ctx.status(401);
            logger.error("Failed to process unassign camera request", e);
            ctx.result("Failed to process unassign camera request");
            return;
        }
    }

    // ---- SpectrumJetson operations UI, part B (photonvision-52, 45, 58, 60) ----

    // SpectrumJetson (photonvision-52): the Match ready page runs scripts/jetson/health-check.sh, the
    // same checks as over SSH, through /opt/photonvision/health-check.sh (a link to the repo's copy,
    // made by 06-install-fork-jar.sh; SPECTRUM_HEALTH_CHECK overrides). One run at a time, 60 s at
    // most; a request within 3 s of the last run gets that run. The script reads the other Spectrum
    // endpoints itself (robotState, tagContrast, usb/bandwidth, spectrum/uiState).
    private static final Object healthLock = new Object();
    private static Map<String, Object> lastHealth = null;
    private static long lastHealthNanos = 0;

    public static void onHealthCheckRequest(Context ctx) {
        Map<String, Object> result;
        synchronized (healthLock) {
            // photonvision-65: monotonic (the date can jump: RobotClockSync).
            if (lastHealth != null && System.nanoTime() - lastHealthNanos < 3_000_000_000L) {
                result = lastHealth;
            } else {
                result = runHealthCheck();
                lastHealth = result;
                lastHealthNanos = System.nanoTime();
            }
        }
        ctx.json(result, Types.mapOf(Object.class));
    }

    private static final java.util.regex.Pattern kCheckLine =
            java.util.regex.Pattern.compile("^\\s*(PASS|WARN|FAIL)\\s+(.*)$");

    private static Map<String, Object> runHealthCheck() {
        String env = System.getenv("SPECTRUM_HEALTH_CHECK");
        String script = env != null && !env.isBlank() ? env : "/opt/photonvision/health-check.sh";
        var sections = new ArrayList<Map<String, Object>>();
        var out = new HashMap<String, Object>();
        String verdict = "";
        long startNanos = System.nanoTime();
        var raw = new StringBuilder();
        try {
            if (!new File(script).exists()) {
                throw new IOException(script + " not found (run 06-install-fork-jar.sh)");
            }
            var proc =
                    new ProcessBuilder("timeout", "60", "bash", script).redirectErrorStream(true).start();
            java.util.List<Map<String, Object>> items = null;
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line).append('\n');
                    var m = kCheckLine.matcher(line);
                    if (line.startsWith("== ")) {
                        items = new ArrayList<>();
                        sections.add(Map.of("name", line.substring(3).trim(), "items", items));
                    } else if (m.matches() && items != null) {
                        items.add(Map.of("level", m.group(1), "text", m.group(2)));
                    } else if (line.startsWith("READY") || line.startsWith("NOT READY")) {
                        verdict = line.trim();
                    } else if (!line.isBlank() && items != null) {
                        items.add(Map.of("level", "INFO", "text", line.trim()));
                    }
                }
            }
            if (!proc.waitFor(65, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
            if (verdict.isEmpty()) verdict = "NOT READY: the health check didn't finish";
        } catch (IOException | InterruptedException e) {
            verdict = "NOT READY: couldn't run the health check: " + e.getMessage();
        }
        out.put("verdict", verdict);
        out.put("sections", sections);
        out.put("seconds", (System.nanoTime() - startNanos) / 1e9);
        out.put("ranAt", System.currentTimeMillis()); // wall clock, for the page's "checked N s ago"
        out.put("raw", raw.toString());
        return out;
    }

    /**
     * The backend's own copy of what each camera's dashboard shows (current pipeline and its
     * settings), so tests/ui can check a control really changed the backend, not only the page.
     * Read-only. SpectrumJetson (photonvision-45), with framesProcessed (58) and unmatchedCameras
     * (60). health-check.sh reads cameras[].nickname and pipelineNicknames.
     */
    public static void onUiStateRequest(Context ctx) {
        var jsonb = io.avaje.jsonb.Jsonb.instance();
        var tree = jsonb.type(Object.class);
        var settingsType = jsonb.type(org.photonvision.vision.pipeline.CVPipelineSettings.class);
        var cameras = new ArrayList<Map<String, Object>>();
        for (var module : VisionSourceManager.getInstance().vmm.getModules()) {
            var ui = module.toUICameraConfig();
            var camera = new HashMap<String, Object>();
            camera.put("uniqueName", ui.uniqueName);
            camera.put("nickname", ui.nickname);
            camera.put("currentPipelineIndex", ui.currentPipelineIndex);
            camera.put("pipelineNicknames", ui.pipelineNicknames);
            // As the dashboard gets it (enums as numbers), so tests compare like with like.
            camera.put("currentPipelineSettings", ui.currentPipelineSettings);
            camera.put("extraControls", ui.extraControls);
            camera.put("streamViewers", module.streamViewers()); // photonvision-48
            camera.put("framesProcessed", module.framesProcessed.get()); // photonvision-58
            // Every pipeline's saved settings, not only the running one's (photonvision-46), as
            // saved: avaje's form, with its "type" property (2026's had Jackson's ["type", {...}]
            // wrapper, which 45 stripped).
            var pipelines = new ArrayList<Object>();
            for (var settings : module.uiStatePipelineSettings()) {
                try {
                    pipelines.add(tree.fromJson(settingsType.toJson(settings)));
                } catch (RuntimeException e) {
                    pipelines.add(Map.of("error", String.valueOf(e.getMessage())));
                }
            }
            camera.put("pipelines", pipelines);
            cameras.add(camera);
        }
        // photonvision-60: connected cameras not in use yet, in the form assignUnmatchedCamera takes
        // (each written as a PVCameraInfo, so its "type" property comes along).
        var unmatched = new ArrayList<Object>();
        var infoType = jsonb.type(PVCameraInfo.class);
        for (var info : VisionSourceManager.getInstance().unmatchedConnectedCameras()) {
            unmatched.add(tree.fromJson(infoType.toJson(info)));
        }
        ctx.json(Map.of("cameras", cameras, "unmatchedCameras", unmatched), Types.mapOf(Object.class));
    }
}
