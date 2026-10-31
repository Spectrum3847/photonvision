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

package org.photonvision.common.hardware.metrics;

/**
 * SpectrumJetson (photonvision-82): the module's 5 V input (the INA3221's VDD_IN; photonvision-85:
 * on the Orin Nano dev kit that's after the carrier board's regulator, about 4.95 V, and the
 * carrier's own 12 V input isn't measured), sampled often so a short dip between once-a-second
 * publishes still shows up. Keeps the latest reading, the lowest since the last {@link
 * #takeSecond()}, and the lowest since PhotonVision started.
 *
 * <p>Why: at TXMAN1 cameras dropped off USB mid-match ("disabled by hub (EMI?)") and only the power
 * could be suspected, never checked: the voltage was read for {@code powerW} and thrown away. The
 * INA3221 averages over a few milliseconds, so a dip shorter than that can still be missed.
 *
 * <p>Sampled and published by JetsonTelemetry (Jetson telemetry, photonvision-16), which isn't in
 * this commit; the wiring comes with it.
 */
final class InputVoltage {
    /**
     * Below this, a dip is also written to PhotonVision's log (at most every 5 s). photonvision-85:
     * VDD_IN is the module's 5 V input (after the carrier board's regulator), not the 12 V supply,
     * which no sensor measures: 4.75 V is the bottom of 5 V +-5%. At 12 V every reading was a "dip",
     * logged every 5 s.
     */
    static final double LOW_INPUT_V = 4.75;

    /** How often to sample, in ms. */
    static final long INPUT_SAMPLE_MS = 20;

    private double lastMv = Double.NaN;
    private double secondMinMv = Double.NaN;
    private double lowestMv = Double.NaN;

    /** One reading, in millivolts. */
    synchronized void sample(double mv) {
        lastMv = mv;
        if (Double.isNaN(secondMinMv) || mv < secondMinMv) secondMinMv = mv;
        if (Double.isNaN(lowestMv) || mv < lowestMv) lowestMv = mv;
    }

    /** {latest, lowest since the last call, lowest since start}, in volts; NaN before any sample. */
    synchronized double[] takeSecond() {
        double[] v = {lastMv / 1000, secondMinMv / 1000, lowestMv / 1000};
        secondMinMv = Double.NaN;
        return v;
    }
}
