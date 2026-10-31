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

package org.photonvision.vision.frame.provider;

import java.util.function.LongSupplier;
import org.photonvision.common.logging.Logger;

/**
 * SpectrumJetson (photonvision-27): whether one camera's direct MJPEG decode path (gray or colour,
 * in the detector library) is in use, and when to give it up, try it again, or fix the camera.
 *
 * <ul>
 *   <li>30 real decode failures in a row (bad JPEGs) switch to cscore's own, slower, conversion. The
 *       direct path is then tried again every 10 s, and one failed retry switches straight back.
 *       Before, the switch was permanent until the next restart, costing about 1.2 CPU cores.
 *   <li>A size mismatch never switches over. It means the camera is streaming a different video mode
 *       than the one PhotonVision set: on 2026-09-24 cscore's mode restore on connect raced
 *       PhotonVision's and left a camera at 320x240, while cscore believed 1280x800. cscore's
 *       conversion then silently upscaled 320x240 to 1280x800 for the detector. Instead the frame
 *       is dropped, and once the mismatch has lasted 1 s the caller reconnects the camera (at most
 *       every 3 s), so cscore applies the mode again.
 *   <li>A detector library without the decoder is never retried.
 * </ul>
 */
final class DirectDecode {
    private static final int MAX_FAILURES = 30;
    private static final long MISMATCH_RECONNECT_NS = 1_000_000_000L;
    private static final long RECONNECT_EVERY_NS = 3_000_000_000L;
    private static final long RETRY_NS = 10_000_000_000L;

    private final Logger logger;
    private final String what; // e.g. "Direct MJPEG->gray decode"
    private final String fallback; // e.g. "cscore's conversion"
    private final LongSupplier clockNs;

    private boolean on = true;
    private boolean unavailable = false;
    private boolean retrying = false;
    private int failures = 0;
    private long mismatchSinceNs = 0;
    private long lastReconnectNs = 0;
    private int reconnects = 0;
    private long offSinceNs = 0;

    DirectDecode(Logger logger, String what, String fallback) {
        this(logger, what, fallback, System::nanoTime);
    }

    /** With a clock, for tests. */
    DirectDecode(Logger logger, String what, String fallback, LongSupplier clockNs) {
        this.logger = logger;
        this.what = what;
        this.fallback = fallback;
        this.clockNs = clockNs;
    }

    /** Whether to try the direct path for this frame. */
    boolean shouldTry() {
        if (on) return true;
        if (unavailable) return false;
        long now = clockNs.getAsLong();
        if (now - offSinceNs < RETRY_NS) return false;
        on = true;
        retrying = true;
        failures = MAX_FAILURES - 1; // one more failure switches back
        return true;
    }

    void succeeded() {
        if (retrying) logger.info(what + " works again; using it for this camera");
        if (mismatchSinceNs != 0 && reconnects > 0) {
            logger.info("The camera is streaming the video mode PhotonVision set again");
        }
        retrying = false;
        failures = 0;
        mismatchSinceNs = 0;
        reconnects = 0;
    }

    /** A real failure (bad JPEG, not MJPEG). */
    void failed(String why) {
        mismatchSinceNs = 0;
        if (++failures >= MAX_FAILURES) {
            switchOff(retrying ? "still failing (" + why + ")" : "failed 30 times in a row (" + why + ")");
        }
    }

    /**
     * The frame isn't the size of the current video mode. Returns true when the caller should
     * reconnect the camera: once the mismatch has lasted 1 s, then at most every 3 s.
     */
    boolean sizeMismatch() {
        long now = clockNs.getAsLong();
        if (mismatchSinceNs == 0) mismatchSinceNs = now;
        if (now - mismatchSinceNs < MISMATCH_RECONNECT_NS) return false;
        if (reconnects > 0 && now - lastReconnectNs < RECONNECT_EVERY_NS) return false;
        lastReconnectNs = now;
        reconnects++;
        return true;
    }

    /** Reconnects asked for during the current mismatch. */
    int reconnects() {
        return reconnects;
    }

    /** The detector library doesn't have this decoder. Never retried. */
    void unavailable(String message) {
        unavailable = true;
        on = false;
        logger.warn(message);
    }

    boolean isOn() {
        return on;
    }

    private void switchOff(String why) {
        boolean wasRetry = retrying;
        on = false;
        retrying = false;
        failures = 0;
        mismatchSinceNs = 0;
        offSinceNs = clockNs.getAsLong();
        if (!wasRetry) {
            logger.error(what + " " + why + "; using " + fallback + " and retrying every 10 s");
        }
    }
}
