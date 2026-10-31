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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/** SpectrumJetson (photonvision-27). */
public class DirectDecodeTest {
    private long nowNs = 1_000_000_000L;
    private final Logger logger = new Logger(DirectDecodeTest.class, LogGroup.Camera);

    private DirectDecode make() {
        return new DirectDecode(logger, "test decode", "the fallback", () -> nowNs);
    }

    private void advanceMs(long ms) {
        nowNs += ms * 1_000_000L;
    }

    @Test
    public void shortSizeMismatchIsJustSkipped() {
        var d = make();
        for (int i = 0; i < 100; i++) { // 0.8 s of mismatched frames at 120 fps
            assertTrue(d.shouldTry());
            assertFalse(d.sizeMismatch());
            advanceMs(8);
        }
        d.succeeded();
        assertTrue(d.isOn());
    }

    @Test
    public void sustainedSizeMismatchAsksForThrottledReconnectsAndNeverSwitchesOff() {
        var d = make();
        int reconnects = 0;
        for (int i = 0; i < 1250; i++) { // 10 s of mismatched frames
            if (d.sizeMismatch()) reconnects++;
            advanceMs(8);
        }
        // First after 1 s, then every 3 s: at about 1, 4 and 7 s (10 s isn't reached).
        assertTrue(reconnects == 3, "reconnects: " + reconnects);
        assertTrue(d.isOn());
        d.succeeded();
        assertTrue(d.reconnects() == 0);
    }

    @Test
    public void thirtyFailuresSwitchOffAndAFailedRetrySwitchesStraightBack() {
        var d = make();
        for (int i = 0; i < 29; i++) d.failed("bad JPEG");
        assertTrue(d.isOn());
        d.failed("bad JPEG");
        assertFalse(d.isOn());
        advanceMs(10_001);
        assertTrue(d.shouldTry());
        d.failed("bad JPEG"); // one failure on a retry is enough
        assertFalse(d.isOn());
        advanceMs(5_000);
        assertFalse(d.shouldTry()); // not yet 10 s since the retry
        advanceMs(5_001);
        assertTrue(d.shouldTry());
    }

    @Test
    public void unavailableIsNeverRetried() {
        var d = make();
        d.unavailable("no decoder");
        advanceMs(60_000);
        assertFalse(d.shouldTry());
    }
}
