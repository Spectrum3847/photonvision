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

package org.photonvision.common.dataflow.networktables;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.photonvision.common.LoadJNI;
import org.wpilib.hardware.hal.ControlWord;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.networktables.NetworkTableInstance;

/**
 * SpectrumJetson (photonvision-62): the robot's control word, in WPILib 2026's and 2027's formats.
 */
public class NTDriverStationTest {
    @BeforeAll
    public static void init() {
        LoadJNI.loadLibraries();
    }

    // WPILib 2027 hal ControlWord: mode in bits 56-57, enabled 58, e-stop 59, FMS 60, DS 61.
    static byte[] word2027(int mode, boolean enabled, boolean estop, boolean fms, boolean ds) {
        long w =
                0x1234L // an opmode hash in the low bits must be ignored
                        | ((long) mode << 56)
                        | (enabled ? 1L << 58 : 0)
                        | (estop ? 1L << 59 : 0)
                        | (fms ? 1L << 60 : 0)
                        | (ds ? 1L << 61 : 0);
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(w).array();
    }

    // The same, packed by WPILib's own ControlWord struct.
    static ControlWord wpilibWord(RobotMode mode, boolean enabled, boolean fms) {
        var w = new ControlWord();
        w.update(0x1234L, mode, enabled, false, fms, true);
        return w;
    }

    // Each test makes its own instance and leaves it open (see the finally blocks).
    static void waitFor(BooleanSupplier ok) throws InterruptedException {
        long until = System.nanoTime() + 2_000_000_000L;
        while (!ok.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(ok.getAsBoolean());
    }

    @Test
    public void decodes2027() {
        var w = NTDriverStation.getControlWord2027(word2027(2, true, false, true, true));
        assertEquals(new NTDriverStation.NtControlWord(true, false, false, false, true, true), w);
        w = NTDriverStation.getControlWord2027(word2027(1, true, false, false, true));
        assertTrue(w.m_enabled() && w.m_autonomous() && !w.m_fmsAttached());
        w = NTDriverStation.getControlWord2027(word2027(3, false, true, false, true));
        assertTrue(!w.m_enabled() && w.m_test() && w.m_emergencyStop());
        assertNull(NTDriverStation.getControlWord2027(new byte[4]));
        assertNull(NTDriverStation.getControlWord2027(new byte[0]));
        assertNull(NTDriverStation.getControlWord2027(null));
    }

    @Test
    public void bitLayoutMatchesWpilib() {
        // The hand-built words above are what WPILib's struct packs (alpha-8's ControlWordStruct).
        var buf = ByteBuffer.allocate(ControlWord.struct.getSize()).order(ByteOrder.LITTLE_ENDIAN);
        ControlWord.struct.pack(buf, wpilibWord(RobotMode.TELEOPERATED, true, true));
        assertArrayEquals(word2027(2, true, false, true, true), buf.array());
    }

    @Test
    public void readsTheStructTopic() throws InterruptedException {
        // WPILib 2027 alpha-8: /DriverStation/ControlWord, published through ControlWord.struct.
        var inst = NetworkTableInstance.create();
        inst.startLocal();
        try {
            var pub =
                    inst.getTable("DriverStation")
                            .getStructTopic("ControlWord", ControlWord.struct)
                            .publish();
            var ds = new NTDriverStation(inst);
            assertFalse(ds.hasControlWord(), "nothing published yet");
            pub.set(wpilibWord(RobotMode.TELEOPERATED, false, true));
            waitFor(ds::hasControlWord);
            assertFalse(ds.current().m_enabled());
            pub.set(wpilibWord(RobotMode.AUTONOMOUS, true, true));
            waitFor(() -> ds.current().m_enabled());
            assertTrue(ds.current().m_autonomous());
            assertTrue(ds.current().m_fmsAttached());
        } finally {
            // Not closed: after one closed instance, the next instance's subscriber listeners never
            // fired here (a test-only effect; PhotonVision has one instance for its whole run).
            inst.stopLocal();
        }
    }

    @Test
    public void readsTheAlpha6StructTopic() throws InterruptedException {
        // WPILib 2027 alpha-6 (and SpectrumJetson's FakeRobot): /FMSInfo/ControlWord.
        var inst = NetworkTableInstance.create();
        inst.startLocal();
        try {
            var pub = inst.getTable("FMSInfo").getRawTopic("ControlWord").publish("struct:ControlWord");
            var ds = new NTDriverStation(inst);
            assertFalse(ds.hasControlWord(), "nothing published yet");
            pub.set(word2027(2, false, false, true, true));
            waitFor(ds::hasControlWord);
            assertFalse(ds.current().m_enabled());
            pub.set(word2027(2, true, false, true, true));
            waitFor(() -> ds.current().m_enabled());
            assertTrue(ds.current().m_fmsAttached());
        } finally {
            inst.stopLocal();
        }
    }

    @Test
    public void readsThe2026Integer() throws InterruptedException {
        var inst = NetworkTableInstance.create();
        inst.startLocal();
        try {
            var pub = inst.getTable("FMSInfo").getIntegerTopic("FMSControlData").publish();
            pub.set(32 | 16); // DS and FMS attached, disabled
            var ds = new NTDriverStation(inst);
            waitFor(ds::hasControlWord);
            assertFalse(ds.current().m_enabled());
            pub.set(32 | 16 | 1);
            waitFor(() -> ds.current().m_enabled());
        } finally {
            inst.stopLocal();
        }
    }
}
