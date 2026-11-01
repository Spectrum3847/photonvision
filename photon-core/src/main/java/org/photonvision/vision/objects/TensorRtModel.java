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

import io.avaje.json.node.JsonArray;
import io.avaje.json.node.JsonNodeMapper;
import io.avaje.json.node.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.photonvision.common.configuration.NeuralNetworkModelManager.Family;
import org.photonvision.common.configuration.NeuralNetworkModelManager.Version;
import org.photonvision.common.configuration.NeuralNetworkModelsSettings.ModelProperties;

/**
 * SpectrumJetson (photonvision-100): a YOLOv8/YOLO11 detect model as ONNX, run by TensorRT on the
 * Jetson's GPU. The engine is built from the ONNX on this Jetson the first time the model is used
 * (minutes; see TensorRtService) at the properties' input size (e.g. 640x640 or 640x384) and
 * cached.
 */
public class TensorRtModel implements Model {
    public final File modelFile;
    public final ModelProperties properties;

    public TensorRtModel(ModelProperties properties) throws IllegalArgumentException {
        modelFile = new File(properties.modelPath().toString());
        if (!modelFile.exists()) {
            throw new IllegalArgumentException("Model file does not exist: " + modelFile);
        }
        if (properties.labels() == null || properties.labels().isEmpty()) {
            throw new IllegalArgumentException("Labels must be provided");
        }
        if (properties.resolutionWidth() <= 0 || properties.resolutionHeight() <= 0) {
            throw new IllegalArgumentException("Resolution must be greater than 0");
        }
        if (properties.resolutionWidth() % 32 != 0 || properties.resolutionHeight() % 32 != 0) {
            throw new IllegalArgumentException("YOLO input sizes are multiples of 32");
        }
        if (properties.family() != Family.TENSORRT) {
            throw new IllegalArgumentException("Model family must be TENSORRT");
        }
        this.properties = properties;
    }

    /**
     * Properties from a sidecar {@code <model>.json} next to an ONNX copied into the models folder
     * (12-install-yolo-model.sh writes it): {"nickname", "labels": [...], "width", "height",
     * "version": "YOLOV8" | "YOLOV11"}. Null if there's none or it's unreadable.
     */
    public static ModelProperties propertiesFromSidecar(Path onnxPath) {
        String p = onnxPath.toString();
        File sidecar =
                new File(p.substring(0, p.length() - Family.TENSORRT.extension().length()) + ".json");
        if (!sidecar.isFile()) return null;
        try {
            JsonObject j =
                    JsonNodeMapper.builder().build().fromJsonObject(Files.readString(sidecar.toPath()));
            List<String> labels = new ArrayList<>();
            if (j.extractNode("labels", JsonArray.empty()) instanceof JsonArray a) {
                a.elements().forEach(l -> labels.add(String.valueOf(l.toPlain())));
            }
            return new ModelProperties(
                    onnxPath,
                    j.extract("nickname", onnxPath.getFileName().toString()),
                    labels,
                    j.extract("width", 640),
                    j.extract("height", 640),
                    Family.TENSORRT,
                    Version.valueOf(j.extract("version", "YOLOV11")));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Path getPath() {
        return properties.modelPath();
    }

    @Override
    public String getNickname() {
        return properties.nickname();
    }

    @Override
    public Family getFamily() {
        return properties.family();
    }

    @Override
    public ModelProperties getProperties() {
        return properties;
    }

    @Override
    public ObjectDetector load() {
        return new TensorRtObjectDetector(this);
    }

    @Override
    public String toString() {
        return "TensorRtModel{modelFile=" + modelFile + ", properties=" + properties + '}';
    }
}
