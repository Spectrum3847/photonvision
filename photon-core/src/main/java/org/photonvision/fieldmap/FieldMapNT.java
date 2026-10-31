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

package org.photonvision.fieldmap;

import java.nio.file.Path;
import java.util.Objects;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.configuration.PathManager;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.events.OutgoingUIEvent;
import org.photonvision.common.dataflow.websocket.UIPhotonConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TimedTaskManager;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.StringPublisher;
import org.wpilib.networktables.StringSubscriber;

/**
 * SpectrumJetson (photonvision-74): {@link FieldMapSync} on NetworkTables.
 *
 * <ul>
 *   <li>Reads the robot's map: {@code /SpectrumVision/FieldMap/Json} (WPILib 2027 Field JSON),
 *       {@code Hash} and {@code Name} (strings).
 *   <li>Reports the map in use under {@code /photonvision/jetson/FieldMap/}: {@code Hash} and
 *       {@code Name} (strings), {@code LoadedAt} (double, unix seconds by the Jetson's clock, which
 *       is only right once the robot has set it), and {@code Error} ("" when the robot's map is in
 *       use or none was sent).
 * </ul>
 *
 * Polled every 200 ms on PhotonVision's timer thread, never a camera's. The robot publishes the
 * three topics one after another, so they're checked as a set once they've stopped changing.
 */
public final class FieldMapNT {
    public static final String ROBOT_TABLE = "/SpectrumVision/FieldMap";
    public static final String JETSON_TABLE = "/photonvision/jetson/FieldMap";

    private static final Logger logger = new Logger(FieldMapNT.class, LogGroup.General);
    private static FieldMapNT instance;

    private final FieldMapSync sync;
    private final StringSubscriber jsonSub;
    private final StringSubscriber hashSub;
    private final StringSubscriber nameSub;
    private final StringPublisher hashPub;
    private final StringPublisher namePub;
    private final DoublePublisher loadedAtPub;
    private final StringPublisher errorPub;
    private String lastJson = "";
    private String lastHash = "";
    private String lastName = "";
    private boolean pending = false;

    /** Where the robot's map is kept between boots, with the other Spectrum settings. */
    public static Path savedFile() {
        return PathManager.getInstance().getConfigDir().resolve("spectrum").resolve("field-map.json");
    }

    /**
     * Loads the saved map, then follows the robot's. Call after the configuration is loaded and
     * before the cameras start, so they start on the saved map.
     */
    public static synchronized void start() {
        if (instance != null) return;
        var sync =
                new FieldMapSync(
                        savedFile(),
                        () -> ConfigProvider.getInstance().getConfig().getFieldLayout(),
                        layout -> {
                            // As upstream's /photonvision/field_layout does: PhotonVision's own
                            // setting, saved to its database by the settings thread, so the page,
                            // exports and snapshots show the map in use.
                            ConfigProvider.getInstance().getConfig().setFieldLayout(layout);
                            ConfigProvider.getInstance().requestSave();
                            // Only refreshes the web page's field view: a failure here must not
                            // look like a failed load.
                            try {
                                DataChangeService.getInstance()
                                        .publishEvent(
                                                new OutgoingUIEvent<>(
                                                        "fullsettings",
                                                        UIPhotonConfiguration.programStateToUi(
                                                                ConfigProvider.getInstance().getConfig())));
                            } catch (RuntimeException | LinkageError e) {
                                logger.warn("Field map loaded, but the web page wasn't told: " + e);
                            }
                        },
                        () -> System.currentTimeMillis() / 1000.0);
        sync.loadSaved();
        instance = new FieldMapNT(NetworkTableInstance.getDefault(), sync);
        TimedTaskManager.getInstance().addTask("FieldMap", instance::poll, 200);
    }

    FieldMapNT(NetworkTableInstance nt, FieldMapSync sync) {
        this.sync = sync;
        NetworkTable robot = nt.getTable(ROBOT_TABLE);
        jsonSub = robot.getStringTopic("Json").subscribe("");
        hashSub = robot.getStringTopic("Hash").subscribe("");
        nameSub = robot.getStringTopic("Name").subscribe("");
        NetworkTable jetson = nt.getTable(JETSON_TABLE);
        hashPub = jetson.getStringTopic("Hash").publish();
        namePub = jetson.getStringTopic("Name").publish();
        loadedAtPub = jetson.getDoubleTopic("LoadedAt").publish();
        errorPub = jetson.getStringTopic("Error").publish();
        publish();
    }

    synchronized void poll() {
        String json = jsonSub.get();
        String hash = hashSub.get();
        String name = nameSub.get();
        if (!Objects.equals(json, lastJson)
                || !Objects.equals(hash, lastHash)
                || !Objects.equals(name, lastName)) {
            lastJson = json;
            lastHash = hash;
            lastName = name;
            pending = true;
        } else if (pending) {
            // Unchanged for a whole poll: the robot has finished publishing, so a JSON from one
            // publish and a hash from the next aren't reported as a mismatch.
            pending = false;
            sync.offer(json, hash, name);
        }
        publish();
    }

    private void publish() {
        hashPub.set(sync.currentHash());
        namePub.set(sync.name());
        loadedAtPub.set(sync.loadedAt());
        errorPub.set(sync.error());
    }
}
