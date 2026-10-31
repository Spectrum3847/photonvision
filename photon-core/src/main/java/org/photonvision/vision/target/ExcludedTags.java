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

package org.photonvision.vision.target;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.photonvision.common.configuration.PathManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TimedTaskManager;
import org.photonvision.vision.processes.FarSearch;
import org.wpilib.networktables.IntegerArrayPublisher;
import org.wpilib.networktables.IntegerArraySubscriber;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableInstance;

/**
 * SpectrumJetson (photonvision-22): AprilTags left out of the multi-tag solve, on every camera. You
 * list the bad tags (e.g. one mounted wrong at an event), not the good ones. Two sources, combined:
 *
 * <ul>
 *   <li>the Settings page, saved on the Jetson in photonvision_config/spectrum/excluded-tags.txt;
 *   <li>robot code: /photonvision/excludedTags (integer array), e.g. set per event from a
 *       dashboard.
 * </ul>
 *
 * Excluded tags are still reported as targets, with their own single-tag poses. They're only kept
 * out of the multi-tag solve. The combined list is published as /photonvision/excludedTagsActive,
 * for the robot log, and handed to the far-tag search (photonvision-69, FarSearch.setExcludedTags),
 * where they don't count toward a good view.
 */
public final class ExcludedTags {
    private static final Logger logger = new Logger(ExcludedTags.class, LogGroup.General);
    private static ExcludedTags instance;

    public static synchronized ExcludedTags getInstance() {
        if (instance == null) instance = new ExcludedTags();
        return instance;
    }

    private final Path file;
    private volatile Set<Integer> saved = Set.of();
    private volatile Set<Integer> robot = Set.of();
    private volatile Set<Integer> active = Set.of();
    private final IntegerArraySubscriber robotSub;
    private final IntegerArrayPublisher activePub;
    private boolean farSearchTold = false;

    private ExcludedTags() {
        // photonvision_config/spectrum/excluded-tags.txt, as in 2026 (the settings partition).
        file =
                PathManager.getInstance().getConfigDir().resolve("spectrum").resolve("excluded-tags.txt");
        saved = load(file);
        // The default instance, which NetworkTablesManager uses too. Not via NetworkTablesManager so
        // multi-tag also runs where its time sync can't start (laptop tests, photonvision-61).
        NetworkTable t = NetworkTableInstance.getDefault().getTable("photonvision");
        robotSub = t.getIntegerArrayTopic("excludedTags").subscribe(new long[0]);
        activePub = t.getIntegerArrayTopic("excludedTagsActive").publish();
        refresh();
        TimedTaskManager.getInstance().addTask("ExcludedTags", this::refresh, 200);
        logger.info("Tags left out of multi-tag: " + (active.isEmpty() ? "none" : active));
    }

    /** The tags to leave out of multi-tag right now: the saved list plus the robot's. */
    public Set<Integer> active() {
        return active;
    }

    public Set<Integer> saved() {
        return saved;
    }

    public Set<Integer> robot() {
        return robot;
    }

    /** Replaces the saved list (the Settings page's), and writes it to disk. */
    public synchronized void setSaved(Collection<Integer> tags) throws IOException {
        var next = new TreeSet<Integer>();
        for (Integer id : tags) {
            if (id != null && id >= 0 && id <= 1000) next.add(id);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join(", ", next.stream().map(String::valueOf).toList()) + "\n");
        saved = Set.copyOf(next);
        logger.info("Saved tags to leave out of multi-tag: " + (next.isEmpty() ? "none" : next));
        refresh();
    }

    private synchronized void refresh() {
        var fromRobot = new TreeSet<Integer>();
        for (long id : robotSub.get()) {
            if (id >= 0 && id <= 1000) fromRobot.add((int) id);
        }
        var combined = new TreeSet<Integer>(saved);
        combined.addAll(fromRobot);
        if (!combined.equals(active)) {
            logger.info(
                    "Tags left out of multi-tag: "
                            + (combined.isEmpty() ? "none" : combined)
                            + (fromRobot.isEmpty() ? "" : " (robot code: " + fromRobot + ")"));
        }
        robot = Set.copyOf(fromRobot);
        boolean changed = !combined.equals(active) || !farSearchTold;
        active = Set.copyOf(combined);
        activePub.set(combined.stream().mapToLong(Integer::longValue).toArray());
        // photonvision-69: the far search (Jetson, CUDA) leaves them out of its good view too.
        if (changed) {
            FarSearch.setExcludedTags(active);
            farSearchTold = true;
        }
    }

    private static Set<Integer> load(Path p) {
        var tags = new TreeSet<Integer>();
        try {
            if (!Files.exists(p)) return Set.of();
            for (String part : Files.readString(p).split("[,\\s]+")) {
                if (part.isBlank()) continue;
                try {
                    tags.add(Integer.parseInt(part.strip()));
                } catch (NumberFormatException e) {
                    logger.warn("Ignoring '" + part + "' in " + p);
                }
            }
        } catch (IOException e) {
            logger.error("Could not read " + p, e);
        }
        return Set.copyOf(tags);
    }
}
