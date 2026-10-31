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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.jni.GpuDetectorJNI;

/**
 * SpectrumJetson (photonvision-55, 69): the far-tag search's settings, handed to the CUDA detector
 * library (detector/far_search.h). Tags are normally searched for on a half-size image; while no
 * camera has a good view, one camera at a time also searches its full-size image (~1.7x the range).
 *
 * <p>In the 2026 tree these lived in IdleMode (Settings > Robot state); that isn't ported yet, so
 * they have their own small file and card until it is.
 */
public final class FarSearch {
    private FarSearch() {}

    private static final Logger logger = new Logger(FarSearch.class, LogGroup.VisionModule);

    /** The settings: a copy, changed through {@link #update}. */
    public static final class Settings {
        /** photonvision-55: full-size searches for far tags while no camera has a good view. */
        // Spectrum (2026-10-30): off by default. On JetPack 7 each full-size search took 5.9 ms
        // (2.7 on JetPack 6), ~26 a second inside a camera's detect call: ~1 frame in 10 came back
        // ~6 ms late. Switch it on from its Settings card once it's optimized.
        public boolean farSearch = false;

        public double farSweepsPerSecond = 30;
    }

    // In PhotonVision's config folder: on the Jetson that's the settings partition, so it survives
    // the read-only system partition (ro-root), unlike /opt/photonvision itself.
    private static Path settingsFile() {
        return org.photonvision.common.configuration.PathManager.getInstance()
                .getConfigDir()
                .resolve("spectrum-far-search.properties");
    }

    private static volatile Settings settings = load();

    // Set once setFarSearch reached the library: it's loaded (by the CUDA pipe) and has the far search.
    private static volatile boolean applied = false;
    private static volatile long nextTryMs = 0;

    // photonvision-69: the excluded tags, for the far search's good view; null until there are any.
    private static volatile Set<Integer> excluded = null;
    private static volatile Set<Integer> excludedSent = null;
    private static volatile boolean excludedUnsupported = false;

    public static Settings getSettings() {
        return settings;
    }

    /** Clamps the sweeps a second to 1..120, as the 2026 IdleMode did. */
    static double clampSweeps(double v) {
        return Math.clamp(v, 1, 120);
    }

    /** Changes and saves the settings; null leaves a field as it is. */
    public static synchronized Settings update(Boolean farSearch, Double farSweepsPerSecond) {
        var s = new Settings();
        s.farSearch = farSearch != null ? farSearch : settings.farSearch;
        s.farSweepsPerSecond =
                farSweepsPerSecond != null ? clampSweeps(farSweepsPerSecond) : settings.farSweepsPerSecond;
        settings = s;
        save(s);
        if (applied) {
            applied = false;
            nextTryMs = 0;
            apply();
        }
        return s;
    }

    /**
     * Hands the settings to the detector library. Called by the CUDA pipe on each frame until it
     * works: the library is loaded by then. At most every 5 s, since a missing native method throws.
     */
    public static void apply() {
        if (applied) {
            applyExcluded();
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        if (now < nextTryMs) return;
        nextTryMs = now + 5000;
        var s = settings;
        try {
            GpuDetectorJNI.setFarSearch(s.farSearch, s.farSweepsPerSecond);
            applied = true;
            excludedSent = null; // send them again
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // An older lib971apriltag.so without the far search: try again later.
        }
    }

    /**
     * photonvision-69: the excluded tags (left out of the multi-tag solve), which then don't count
     * toward the far search's good view. Sent on the next frame.
     */
    public static void setExcludedTags(Set<Integer> ids) {
        excluded = Set.copyOf(ids);
    }

    private static void applyExcluded() {
        var ex = excluded;
        if (excludedUnsupported || ex == null || ex.equals(excludedSent)) return;
        try {
            GpuDetectorJNI.setFarSearchExcluded(ex.stream().mapToInt(Integer::intValue).toArray());
            excludedSent = ex;
        } catch (UnsatisfiedLinkError e) {
            excludedUnsupported = true; // an older lib971apriltag.so without it
        }
    }

    /** The far search's counters, or null before the detector library is loaded, or without it. */
    public static double[] status() {
        if (!applied) return null;
        try {
            return GpuDetectorJNI.farSearchStatus();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            return null;
        }
    }

    /** The settings and counters, for Settings > Far-tag search. */
    public static Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        var s = settings;
        m.put("farSearch", s.farSearch);
        m.put("farSweepsPerSecond", s.farSweepsPerSecond);
        var far = status();
        if (far != null && far.length >= 8) {
            m.put("farSearchStarved", far[1] > 0);
            m.put("farSearchSweeps", (long) far[2]);
            m.put("farSearchCrops", (long) far[3]);
            m.put("farSearchTags", (long) far[4]);
            m.put("farSearchStarvedSeconds", far[5]);
            m.put("farSearchSweepMs", far[2] > 0 ? far[6] / far[2] : 0.0);
        }
        return m;
    }

    private static Settings load() {
        var s = new Settings();
        if (!Files.exists(settingsFile())) return s;
        try (Reader r = Files.newBufferedReader(settingsFile())) {
            var p = new Properties();
            p.load(r);
            s.farSearch = Boolean.parseBoolean(p.getProperty("farSearch", "false"));
            s.farSweepsPerSecond =
                    clampSweeps(Double.parseDouble(p.getProperty("farSweepsPerSecond", "30")));
        } catch (IOException | RuntimeException e) {
            logger.warn("Far search: couldn't read " + settingsFile() + ": " + e.getMessage());
            return new Settings();
        }
        return s;
    }

    private static void save(Settings s) {
        var p = new Properties();
        p.setProperty("farSearch", Boolean.toString(s.farSearch));
        p.setProperty("farSweepsPerSecond", Double.toString(s.farSweepsPerSecond));
        try (Writer w = Files.newBufferedWriter(settingsFile())) {
            p.store(w, "SpectrumJetson far-tag search (photonvision-55)");
        } catch (IOException e) {
            logger.warn("Far search: couldn't save " + settingsFile() + ": " + e.getMessage());
        }
    }
}
