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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * SpectrumJetson (photonvision-88): cscore's SetPath on a camera whose cscore thread has stopped
 * never returns; the VisionRunner must not hang with it.
 */
public class DetachedCallTest {
    private long nowNs = 1_000_000_000_000L;

    @Test
    public void quickCallFinishes() throws Exception {
        var call = new DetachedCall(() -> nowNs);
        var ran = new AtomicInteger();
        assertTrue(call.run("quick", ran::incrementAndGet, 2000));
        assertEquals(1, ran.get());
        assertFalse(call.running());
        assertEquals(0, call.runningNs());
    }

    @Test
    public void stuckCallDoesNotHangTheCaller() throws Exception {
        var call = new DetachedCall(() -> nowNs);
        var release = new CountDownLatch(1);
        var started = new AtomicInteger();
        Runnable stuck =
                () -> {
                    started.incrementAndGet();
                    try {
                        release.await(); // like SendAndWait with no camera thread to answer
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(5), () -> assertFalse(call.run("stuck", stuck, 100)));
            assertTrue(call.running());
            nowNs += 10_000_000_000L;
            assertEquals(10_000_000_000L, call.runningNs());

            // A second reopen while the first is stuck starts nothing and returns at once.
            assertTimeoutPreemptively(
                    Duration.ofSeconds(1), () -> assertFalse(call.run("again", stuck, 10_000)));
            assertEquals(1, started.get());
        } finally {
            release.countDown();
        }
        // Once it returns, the next call runs.
        for (int i = 0; i < 100 && call.running(); i++) TimeUnit.MILLISECONDS.sleep(10);
        assertFalse(call.running());
        assertEquals(0, call.runningNs());
        assertTrue(call.run("next", () -> {}, 2000));
    }

    @Test
    public void interruptedCallerStopsWaiting() throws Exception {
        var call = new DetachedCall(() -> nowNs);
        var release = new CountDownLatch(1);
        var caught = new AtomicInteger();
        var caller =
                new Thread(
                        () -> {
                            try {
                                call.run(
                                        "stuck",
                                        () -> {
                                            try {
                                                release.await();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        },
                                        60_000);
                            } catch (InterruptedException e) {
                                caught.incrementAndGet(); // VisionModule.close() interrupts the runner
                            }
                        });
        try {
            caller.start();
            for (int i = 0; i < 100 && !call.running(); i++) TimeUnit.MILLISECONDS.sleep(10);
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertEquals(1, caught.get());
        } finally {
            release.countDown();
        }
    }
}
