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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.photonvision.vision.frame.provider.StuckCameraWatchdog.Action;
import org.photonvision.vision.frame.provider.StuckCameraWatchdog.State;

/** SpectrumJetson (photonvision-29, -74). */
public class StuckCameraWatchdogTest {
    private long nowNs = 1_000_000_000_000L;

    private StuckCameraWatchdog make() {
        return new StuckCameraWatchdog(() -> nowNs);
    }

    /** Polls every 250 ms (the grab timeout) for {@code seconds}, collecting actions with times. */
    private ArrayList<String> stuckFor(StuckCameraWatchdog w, double seconds) {
        var out = new ArrayList<String>();
        long start = nowNs;
        while (nowNs - start < (long) (seconds * 1e9)) {
            nowNs += 250_000_000L;
            var a = w.noFrame();
            if (a != Action.NONE) out.add(a + "@" + Math.round((nowNs - start) / 1e9));
        }
        return out;
    }

    @Test
    public void shortGapsDoNothing() {
        var w = make();
        assertEquals(0, stuckFor(w, 0.5).size());
        assertEquals("", w.good());
        assertEquals(0, stuckFor(w, 0.5).size());
    }

    @Test
    public void escalatesReconnectThenUsbResetThenEvery5s() {
        var w = make();
        var actions = stuckFor(w, 28);
        // photonvision-84: reconnect at 0.75 s; USB reset 0.5 s later (1.25 s); then every 5 s until
        // it gives up after 6. (Times are rounded to whole seconds.)
        assertEquals(
                java.util.List.of(
                        "RECONNECT@1",
                        "USB_RESET@1",
                        "USB_RESET@6",
                        "USB_RESET@11",
                        "USB_RESET@16",
                        "USB_RESET@21",
                        "USB_RESET@26"),
                actions);
        assertEquals(7, w.recoveries());
        assertEquals(6, w.usbResetsSinceFrame());
        assertEquals("being given up on (replugged, or a later USB reset)", w.good());
        assertEquals(0, w.usbResetsSinceFrame());
        assertEquals("", w.good());
    }

    @Test
    public void aUsbResetThatWorksSaysSo() {
        var w = make();
        assertEquals(java.util.List.of("RECONNECT@1", "USB_RESET@1", "USB_RESET@6"), stuckFor(w, 7));
        assertEquals(2, w.usbResetsSinceFrame());
        assertEquals("a USB reset", w.good());
    }

    @Test
    public void aReconnectThatWorksStopsThere() {
        var w = make();
        assertEquals(java.util.List.of("RECONNECT@1"), stuckFor(w, 1));
        assertEquals("a reconnect", w.good());
        assertEquals(0, stuckFor(w, 0.5).size());
    }

    @Test
    public void modeFixReconnectsShareTheThrottle() {
        var w = make();
        assertEquals(0, stuckFor(w, 0.5).size());
        w.noteReconnect(); // photonvision-27 reconnected at 0.5 s
        // Stuck at 0.75 s, but no reconnect of our own until 0.5 s after that one (1 s): 0.5 s into
        // this second look (shown rounded, as 1). Without the shared throttle it would be at 0.
        assertEquals(java.util.List.of("RECONNECT@1"), stuckFor(w, 0.75));
    }

    @Test
    public void aPauseInReadingIsNotStuck() {
        // photonvision-66: frames, then nobody reads the camera for 10 s (robot code disabled it),
        // then the first grabs fail. That's 0.75 s of failures from here, not 10 s already.
        var w = make();
        nowNs += 250_000_000L;
        assertEquals("", w.good());
        nowNs += 10_000_000_000L;
        assertEquals(0, stuckFor(w, 0.5).size());
        assertEquals(java.util.List.of("RECONNECT@1"), stuckFor(w, 0.5)); // at 0.5 s, rounded
    }

    @Test
    public void slowReadsStillCount() {
        // A camera with a low frame-rate limit is read every 1.25 s (the limit's sleep and the grab
        // timeout): below NOT_READ_NS, so a stuck one is still found, at the first failed read.
        // (A healthy one at a low rate isn't: each of its reads brings a frame.)
        var w = make();
        var actions = new ArrayList<String>();
        for (int i = 1; i <= 1; i++) {
            nowNs += 1_250_000_000L;
            if (w.noFrame() != Action.NONE) actions.add("at " + i * 1.25 + " s");
        }
        assertEquals(java.util.List.of("at 1.25 s"), actions);
    }

    @Test
    public void disconnectedIsNotStuck() {
        var w = make();
        // Unplugged for 8 s: photonvision-84 tries its port's power at 0.5 s and 5.5 s, but it isn't
        // stuck.
        assertEquals(java.util.List.of("USB_RESET@1", "USB_RESET@6"), disconnectedFor(w, 8, false));
        assertEquals(State.DISCONNECTED, w.state());
        assertEquals(0, stuckFor(w, 0.5).size());
    }

    // photonvision-81

    /**
     * Polls a disconnected camera every 250 ms for {@code seconds}, collecting actions with times.
     */
    private ArrayList<String> disconnectedFor(
            StuckCameraWatchdog w, double seconds, boolean devicePresent) {
        var out = new ArrayList<String>();
        long start = nowNs;
        while (nowNs - start < (long) (seconds * 1e9)) {
            nowNs += 250_000_000L;
            var a = w.notConnected(devicePresent);
            if (a != Action.NONE) out.add(a + "@" + Math.round((nowNs - start) / 1e9));
        }
        return out;
    }

    @Test
    public void disconnectedWithItsDeviceStillThereGetsUsbResets() {
        // TXMAN1: the camera's own USB reset left it disconnected, and nothing more was tried.
        var w = make();
        assertEquals(
                java.util.List.of("USB_RESET@1", "USB_RESET@6", "USB_RESET@11"),
                disconnectedFor(w, 12, true));
        assertEquals(3, w.recoveries());
        assertEquals(State.DISCONNECTED, w.state());
    }

    @Test
    public void goneFromUsbIsResetThenGivesUp() {
        // photonvision-84: a camera gone from USB gets resets (its port's power switched off and on)
        // from 0.5 s, every 5 s, and gives up after 6, like one that's there.
        var w = make();
        assertEquals(java.util.List.of("USB_RESET@1", "USB_RESET@6"), disconnectedFor(w, 9, false));
        assertEquals(State.DISCONNECTED, w.state());
        assertFalse(w.goneFromUsb());
        // 10.5, 15.5, 20.5 and 25.5 s, counted from 9 s; gave up at 30.5 s.
        assertEquals(
                java.util.List.of("USB_RESET@2", "USB_RESET@7", "USB_RESET@12", "USB_RESET@17"),
                disconnectedFor(w, 22, false));
        assertEquals(State.GAVE_UP, w.state());
        assertTrue(w.goneFromUsb());
        // Replugged: it streams again.
        assertEquals("being given up on (replugged, or a later USB reset)", w.good());
        assertEquals(State.STREAMING, w.state());
        assertFalse(w.goneFromUsb());
    }

    @Test
    public void givesUpAfterSixUsbResetsThenRetriesEveryTwoMinutes() {
        // photonvision-84: six resets every 5 s, then every 2 min.
        var w = make();
        var actions = stuckFor(w, 300);
        assertEquals(
                java.util.List.of(
                        "RECONNECT@1",
                        "USB_RESET@1",
                        "USB_RESET@6",
                        "USB_RESET@11",
                        "USB_RESET@16",
                        "USB_RESET@21",
                        "USB_RESET@26",
                        "USB_RESET@146",
                        "USB_RESET@266"),
                actions);
        assertEquals(State.GAVE_UP, w.state());
        assertFalse(w.goneFromUsb()); // still connected
    }

    @Test
    public void statesAndFrameAge() {
        var w = make();
        assertEquals(-1, w.lastFrameAgeMs());
        nowNs += 250_000_000L;
        w.noFrame();
        assertEquals(State.STARTING, w.state());
        w.good();
        assertEquals(State.STREAMING, w.state());
        assertEquals(0, w.lastFrameAgeMs());
        // photonvision-84: the reconnect (0.75 s) now comes before the last frame is old enough (1 s)
        // to stop counting as streaming, so it shows as recovering from 1 s, never as stalled.
        stuckFor(w, 0.5);
        assertEquals(State.STREAMING, w.state());
        assertEquals(500, w.lastFrameAgeMs());
        stuckFor(w, 0.5); // reconnect at 0.75 s
        assertEquals(State.RECOVERING, w.state());
        assertEquals(1000, w.lastFrameAgeMs());
        nowNs += 5_000_000_000L; // nobody reads it (robot code disabled it)
        assertEquals(State.PAUSED, w.state());
        w.good();
        assertEquals(State.STREAMING, w.state());
    }

    @Test
    public void bandwidthRetriesCountAsRecoveries() {
        var w = make();
        for (int i = 0; i < 16; i++) { // 4 s
            nowNs += 250_000_000L;
            w.noFrameNoBandwidth();
        }
        assertEquals(1, w.recoveries());
    }
}
