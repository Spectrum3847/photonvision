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

/**
 * SpectrumJetson (photonvision-29): decides when a connected camera that delivers no usable frames
 * is stuck, and what to do about it.
 *
 * <p>The known case: after several quick PhotonVision restarts, a camera sent only corrupt JPEGs.
 * cscore dropped every one ("invalid JPEG image received from camera"), so nothing reached the
 * pipeline, while the camera still looked connected. A PhotonVision restart or a replug fixed it.
 *
 * <ul>
 *   <li>No good frame for 3 s: reconnect the camera: close and reopen the device in cscore
 *       (photonvision-43: by switching to another video mode and back; before, a FORCE_CLOSE only
 *       restarted the stream).
 *   <li>Still none 5 s after a reconnect: reset it at the USB level (like a replug), at most every
 *       30 s while it stays stuck.
 *   <li>Reconnects for a video-mode mismatch (photonvision-27) share the throttle, so the two never
 *       reconnect the camera at once.
 * </ul>
 *
 * A camera that's unplugged, or disabled by robot code, isn't stuck: the caller only reports frames
 * while the camera is connected and being read. photonvision-66: and a gap of over {@link
 * #NOT_READ_NS} between reports means nobody was reading it, so the clock restarts. Before, the
 * first failed grab after such a pause (robot code re-enabling the camera, or the vision thread
 * held up by a pipeline switch or the shared CUDA lock) counted the pause as 3 s without frames,
 * and the camera was reconnected for nothing: a 0.5-1 s blackout.
 *
 * <p>photonvision-81, after TXMAN1 (cameras died mid-match and stayed dead):
 *
 * <ul>
 *   <li>A camera that disconnects and stays disconnected for {@link #DISCONNECTED_RESET_NS} is
 *       reset at the USB level if its USB device is still there, every {@link #USB_RESET_EVERY_NS}.
 *       Before, a disconnected camera was never acted on: at TXMAN1 one camera's own USB reset left
 *       it disconnected, and nothing more was tried for the rest of the match.
 *   <li>After {@link #MAX_USB_RESETS} USB resets without a frame, or with the device gone from USB
 *       for {@link #GONE_NS}, the camera has given up: a Thriftiest Cam in that state needs its
 *       power cut. One more reset is tried every {@link #GAVE_UP_RETRY_NS}, in case it's something
 *       else.
 *   <li>{@link #state()} says which of these the camera is in, for health/state on NetworkTables.
 * </ul>
 *
 * <p>photonvision-84, from the 2026-10-29 USB tests (Thriftiest Cams on the "usbrecov" firmware
 * come back from a USB drop, but only if the port's link is restarted; a reset takes ~2 s). Most of
 * a recovery was waiting to act (11 s for a port the hub disabled), so every wait is shorter:
 *
 * <ul>
 *   <li>Stuck after {@link #STUCK_NS} (1.5 s, was 3 s; a camera runs at 30-120 fps, and a pause in
 *       reading it still restarts the clock); a USB reset {@link #AFTER_RECONNECT_NS} (2 s, was 5
 *       s) after the reconnect; a disconnected camera reset after {@link #DISCONNECTED_RESET_NS} (2
 *       s, was 5 s).
 *   <li>USB resets every {@link #USB_RESET_EVERY_NS} (5 s), not 30 s, and {@link #MAX_USB_RESETS}
 *       (6) before giving up: each is ~2-3 s now.
 *   <li>A camera gone from USB is reset too (its port's power switched off and on, which makes the
 *       kernel enumerate it again), from {@link #GONE_RESET_NS}. Before, it was only declared given
 *       up. Giving up now comes from {@link #MAX_USB_RESETS} resets without a frame, as for a
 *       camera that's there.
 *   <li>{@link #usbResetsSinceFrame()} lets the caller escalate: the first reset is the gentle one.
 * </ul>
 */
final class StuckCameraWatchdog {
    enum Action {
        NONE,
        RECONNECT,
        USB_RESET
    }

    /** Published as health/state. */
    enum State {
        /** No frame yet since PhotonVision started (and not given up). */
        STARTING("starting"),
        STREAMING("streaming"),
        /** Connected, no usable frame for a while, nothing tried yet. */
        STALLED("stalled"),
        /** A reconnect or USB reset was tried and hasn't brought a frame yet. */
        RECOVERING("recovering"),
        DISCONNECTED("disconnected"),
        GAVE_UP("gave-up"),
        /** Nobody is reading it: disabled by robot code, or the vision thread held up. */
        PAUSED("paused");

        final String topicValue;

        State(String topicValue) {
            this.topicValue = topicValue;
        }
    }

    static final long STUCK_NS = 750_000_000L; // photonvision-84: was 3 s
    static final long AFTER_RECONNECT_NS = 500_000_000L; // photonvision-84: was 5 s
    static final long USB_RESET_EVERY_NS = 5_000_000_000L; // photonvision-84: was 30 s
    // SpectrumJetson (photonvision-31): with the kernel refusing the camera USB bandwidth, a reset
    // can't help (the camera asks for the same reservation again): just retry now and then, in case
    // another camera has gone and freed some.
    static final long BANDWIDTH_RETRY_NS = 60_000_000_000L;

    /**
     * While the camera is being read, reports come at least every grab timeout (250 ms) plus any
     * frame-rate limit's sleep, so a longer gap is a pause in reading, not the camera.
     */
    static final long NOT_READ_NS = 2_000_000_000L;

    // photonvision-81
    /** Disconnected this long (not just mid-reconnect), with its USB device still there: reset it. */
    static final long DISCONNECTED_RESET_NS = 1_000_000_000L; // photonvision-84: was 5 s

    /** Gone from USB this long: said so (with {@link #goneFromUsb()}, once it has given up). */
    static final long GONE_NS = 10_000_000_000L;

    /** photonvision-84: gone from USB this long: switch its port's power off and on. */
    static final long GONE_RESET_NS = 500_000_000L;

    /** USB resets without a frame before giving up (~30 s at one per 5 s; photonvision-84: was 4). */
    static final int MAX_USB_RESETS = 6;

    static final long GAVE_UP_RETRY_NS = 120_000_000_000L;

    /** No usable frame for this long while being read: stalled (above any frame-rate limit's gap). */
    static final long STALLED_NS = 1_000_000_000L;

    private static final long NEVER = Long.MIN_VALUE / 2;

    private final LongSupplier clockNs;
    private long lastGoodNs;
    private long lastReportNs;
    private long lastReconnectNs = NEVER;
    private long lastUsbResetNs = NEVER;
    private int stage = 0; // 0: fine, 1: reconnected, 2: USB reset
    private long recoveries = 0;
    // photonvision-81
    private long lastFrameNs = NEVER;
    private long disconnectedSinceNs = NEVER;
    private int usbResetsSinceFrame = 0;
    private boolean gaveUp = false;
    private boolean goneFromUsb = false;

    StuckCameraWatchdog(LongSupplier clockNs) {
        this.clockNs = clockNs;
        this.lastGoodNs = clockNs.getAsLong();
        this.lastReportNs = lastGoodNs;
    }

    /** The time of this report; after a pause in reading, the stuck clock starts again from it. */
    private long report() {
        long now = clockNs.getAsLong();
        if (now - lastReportNs > NOT_READ_NS) lastGoodNs = now;
        lastReportNs = now;
        return now;
    }

    /** A usable frame arrived. Returns what fixed it, if the camera was stuck ("" otherwise). */
    synchronized String good() {
        lastGoodNs = report();
        lastFrameNs = lastGoodNs;
        String fixedBy =
                gaveUp
                        ? "being given up on (replugged, or a later USB reset)"
                        : stage == 1 ? "a reconnect" : stage == 2 ? "a USB reset" : "";
        stage = 0;
        disconnectedSinceNs = NEVER;
        usbResetsSinceFrame = 0;
        gaveUp = false;
        goneFromUsb = false;
        return fixedBy;
    }

    /**
     * The camera isn't connected: unplugged, mid-reconnect, or dropped off USB. Not stuck, so the
     * stuck clock restarts. photonvision-81: but a camera that stays disconnected while its USB
     * device is still there gets USB resets, and one gone from USB for {@link #GONE_NS} has given up.
     *
     * @param devicePresent its USB device is still in sysfs (enumerated)
     */
    synchronized Action notConnected(boolean devicePresent) {
        long now = report();
        lastGoodNs = now;
        if (disconnectedSinceNs == NEVER) disconnectedSinceNs = now;
        long disconnected = now - disconnectedSinceNs;
        if (!devicePresent) {
            // photonvision-84: reset it (the caller switches its port's power off and on) instead of
            // only giving up: a Thriftiest Cam on the usbrecov firmware comes back from that.
            if (disconnected >= GONE_NS) goneFromUsb = true;
            if (disconnected < GONE_RESET_NS) return Action.NONE;
            return usbResetIfDue(now);
        }
        goneFromUsb = false;
        if (disconnected < DISCONNECTED_RESET_NS) return Action.NONE;
        return usbResetIfDue(now);
    }

    /** Someone else reconnected the camera (photonvision-27's mode fix): share the throttle. */
    synchronized void noteReconnect() {
        lastReconnectNs = clockNs.getAsLong();
    }

    /** No usable frame this time, and the kernel is refusing the camera USB bandwidth. */
    synchronized Action noFrameNoBandwidth() {
        long now = report();
        disconnectedSinceNs = NEVER;
        if (now - lastGoodNs < STUCK_NS) return Action.NONE;
        if (now - lastReconnectNs < BANDWIDTH_RETRY_NS) return Action.NONE;
        lastReconnectNs = now;
        stage = 1;
        recoveries++; // photonvision-81: these count too
        return Action.RECONNECT;
    }

    /** No usable frame this time. What to do now. */
    synchronized Action noFrame() {
        long now = report();
        disconnectedSinceNs = NEVER;
        goneFromUsb = false;
        if (now - lastGoodNs < STUCK_NS) return Action.NONE;
        if (now - lastReconnectNs < AFTER_RECONNECT_NS) return Action.NONE;
        if (stage == 0) {
            stage = 1;
            lastReconnectNs = now;
            recoveries++;
            return Action.RECONNECT;
        }
        return usbResetIfDue(now);
    }

    /** A USB reset, at most every 5 s; after {@link #MAX_USB_RESETS}, gave up: every 2 min. */
    private Action usbResetIfDue(long now) {
        if (usbResetsSinceFrame >= MAX_USB_RESETS) gaveUp = true;
        long every = gaveUp ? GAVE_UP_RETRY_NS : USB_RESET_EVERY_NS;
        if (now - lastUsbResetNs < every) return Action.NONE;
        stage = 2;
        lastUsbResetNs = now;
        lastReconnectNs = now; // the reset reconnects too; wait before judging it
        recoveries++;
        usbResetsSinceFrame++;
        return Action.USB_RESET;
    }

    /** photonvision-84: USB resets since the last usable frame, counting the one just returned. */
    synchronized int usbResetsSinceFrame() {
        return usbResetsSinceFrame;
    }

    /** Seconds since the last usable frame. */
    synchronized double secondsWithoutFrames() {
        return (clockNs.getAsLong() - lastGoodNs) / 1e9;
    }

    /** photonvision-81: seconds since it disconnected (0 while connected). */
    synchronized double secondsDisconnected() {
        return disconnectedSinceNs == NEVER ? 0 : (clockNs.getAsLong() - disconnectedSinceNs) / 1e9;
    }

    /** Reconnects and USB resets done since the camera started. */
    synchronized long recoveries() {
        return recoveries;
    }

    /** photonvision-81: milliseconds since the last usable frame, or -1 if there hasn't been one. */
    synchronized long lastFrameAgeMs() {
        return lastFrameNs == NEVER ? -1 : (clockNs.getAsLong() - lastFrameNs) / 1_000_000;
    }

    /** photonvision-81: it has given up, and the camera's USB device is gone (needs a power cut). */
    synchronized boolean goneFromUsb() {
        return gaveUp && goneFromUsb;
    }

    /** photonvision-81: what the camera is doing. */
    synchronized State state() {
        long now = clockNs.getAsLong();
        if (gaveUp) return State.GAVE_UP;
        if (disconnectedSinceNs != NEVER) return State.DISCONNECTED;
        if (now - lastReportNs > NOT_READ_NS) return State.PAUSED;
        if (lastFrameNs != NEVER && now - lastFrameNs < STALLED_NS) return State.STREAMING;
        if (stage != 0) return State.RECOVERING;
        if (lastFrameNs == NEVER) return State.STARTING;
        return State.STALLED;
    }
}
