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
 * SpectrumJetson (photonvision-88): a blocking call made on its own daemon thread, so the caller
 * can stop waiting for it. Only one at a time: while one is still running, {@link #run} doesn't
 * start another, and {@link #runningNs} says how long it has been stuck.
 *
 * <p>For cscore's SetPath, which waits (with no timeout) for the camera's cscore thread to answer.
 * If that thread has stopped, it never returns (see USBFrameProvider#reopenCamera).
 */
final class DetachedCall {
    private final LongSupplier clockNs;
    private Thread thread = null;
    private long startNs = 0;

    DetachedCall(LongSupplier clockNs) {
        this.clockNs = clockNs;
    }

    /**
     * Starts {@code call} on a daemon thread named {@code name} and waits up to {@code waitMs} for
     * it. True if it finished in that time; false if it's still running (it keeps running), or if
     * the last one is still running (then nothing new is started).
     */
    boolean run(String name, Runnable call, long waitMs) throws InterruptedException {
        Thread t;
        synchronized (this) {
            if (thread != null && thread.isAlive()) return false;
            t = new Thread(call, name);
            t.setDaemon(true);
            thread = t;
            startNs = clockNs.getAsLong();
            t.start();
        }
        t.join(Math.max(1, waitMs));
        return !t.isAlive();
    }

    /** True while the last call hasn't returned. */
    synchronized boolean running() {
        return thread != null && thread.isAlive();
    }

    /** How long the last call has been running, or 0 if it has returned (or there was none). */
    synchronized long runningNs() {
        return running() ? clockNs.getAsLong() - startNs : 0;
    }
}
