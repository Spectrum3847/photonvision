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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/**
 * SpectrumJetson (photonvision-71): the Jetson's cooling, on Settings > Robot state. "quiet": the
 * stock fan on NVIDIA's quiet profile; "full": the fan at full speed; "off": no fan (a heatsink
 * plate): the fan stays stopped and every camera is capped at 60 fps from 95 C until under 88 C
 * (spectrum-fan-guard, photonvision-57). Before, the mode was fixed at install time (FAN=), and a
 * team with the stock fan that left it at our "off" ran with its fan stopped.
 *
 * <p>/usr/local/bin/spectrum-fan (scripts/jetson/spectrum-fan.sh, installed by 09-robot-tuning.sh)
 * applies a mode at once and saves it on the settings partition for the next boot.
 */
public final class FanMode {
    private static final Logger logger = new Logger(FanMode.class, LogGroup.General);
    private static final Path HELPER = Path.of("/usr/local/bin/spectrum-fan");
    private static final Set<String> MODES = Set.of("quiet", "full", "off");

    private static volatile String mode = null; // the saved mode, read from the helper once
    private static volatile String lastError = "";

    private FanMode() {}

    public static boolean available() {
        return Files.isExecutable(HELPER);
    }

    /** The saved mode (quiet, full or off), or "" without the helper. */
    public static String mode() {
        if (!available()) return "";
        if (mode == null) {
            String out = run("status");
            mode = out != null && MODES.contains(out.strip()) ? out.strip() : "quiet";
        }
        return mode;
    }

    public static String error() {
        return lastError;
    }

    /** Applies and saves a mode. Null when it worked, else why not. */
    public static synchronized String set(String newMode) {
        if (!MODES.contains(newMode))
            return "unknown cooling mode " + newMode + " (quiet, full or off)";
        if (!available()) return HELPER + " isn't installed (09-robot-tuning.sh)";
        lastError = "";
        String out = run(newMode); // on failure it leaves the helper's own message in lastError
        if (out == null) {
            if (lastError.isEmpty()) lastError = "spectrum-fan " + newMode + " failed (see the log)";
            return lastError;
        }
        mode = newMode;
        lastError = "";
        logger.info("Cooling: " + out.strip());
        return null;
    }

    /** Runs the helper; its output, or null if it failed. */
    private static String run(String arg) {
        try {
            var p = new ProcessBuilder(HELPER.toString(), arg).redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                logger.error("spectrum-fan " + arg + " took over 20 s");
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                logger.error("spectrum-fan " + arg + " failed: " + out.strip());
                lastError = out.strip();
                return null;
            }
            return out;
        } catch (Exception e) {
            logger.error("spectrum-fan " + arg, e);
            return null;
        }
    }
}
