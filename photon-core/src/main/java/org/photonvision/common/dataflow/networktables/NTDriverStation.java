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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.wpilib.hardware.hal.ControlWord;
import org.wpilib.networktables.BooleanSubscriber;
import org.wpilib.networktables.IntegerSubscriber;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableEvent.Kind;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.RawSubscriber;
import org.wpilib.networktables.StringSubscriber;

// Helper to print when the robot transitions modes
public class NTDriverStation {
    private static final Logger logger = new Logger(NTDriverStation.class, LogGroup.NetworkTables);

    // Copy since stuff was private
    public record NtControlWord(
            boolean m_enabled,
            boolean m_autonomous,
            boolean m_test,
            boolean m_emergencyStop,
            boolean m_fmsAttached,
            boolean m_dsAttached) {
        public NtControlWord() {
            this(false, false, false, false, false, false);
        }
    }

    // SpectrumJetson (photonvision-62): the control word in every form a robot publishes it, and
    // whichever the robot publishes wins. WPILib 2027 publishes a struct, ControlWord (alpha-8:
    // /DriverStation/ControlWord, checked in DriverStationBackend.MatchDataSender; alpha-6 had it
    // in /FMSInfo, which SpectrumJetson's FakeRobot still uses), and no integer FMSControlData;
    // 2026 robot code publishes the integer /FMSInfo/FMSControlData. Upstream reads only
    // /DriverStation/FMSControlData, which no WPILib publishes, so it never saw an enable.
    private static final String[] kControlWordTables = {"DriverStation", "FMSInfo"};

    private final List<IntegerSubscriber> ntControlWords = new ArrayList<>();
    private final List<RawSubscriber> ntControlWords2027 = new ArrayList<>();
    private volatile boolean wordSeen = false;

    private StringSubscriber eventName;
    private IntegerSubscriber matchNumber;
    private IntegerSubscriber replayNumber;
    private IntegerSubscriber matchType;
    private BooleanSubscriber isRedAlliance;
    private IntegerSubscriber stationNumber;

    volatile NtControlWord lastControlWord = new NtControlWord();

    // SpectrumJetson (photonvision-49): the robot's state for idle mode and the GC while disabled.
    private final List<BiConsumer<NtControlWord, NtControlWord>> transitionListeners =
            new CopyOnWriteArrayList<>();

    /** The robot's last control word (all false before a robot has connected). */
    public NtControlWord current() {
        return lastControlWord;
    }

    /**
     * True once a control word has arrived from the robot, in any format. Robot-state features that
     * slow or quiet the Jetson (idle mode, quiet mode) must not act on the all-false default: a robot
     * whose control word we can't read is treated as enabled (photonvision-62).
     */
    public boolean hasControlWord() {
        return wordSeen;
    }

    /** Called with (old, new) on every control word change, on NetworkTables' listener thread. */
    public void addTransitionListener(BiConsumer<NtControlWord, NtControlWord> listener) {
        transitionListeners.add(listener);
    }

    public NTDriverStation(NetworkTableInstance inst) {
        NetworkTable fmsTable = inst.getTable("DriverStation");
        this.eventName = fmsTable.getStringTopic("EventName").subscribe("");
        this.matchType = fmsTable.getIntegerTopic("MatchType").subscribe(0);
        this.matchNumber = fmsTable.getIntegerTopic("MatchNumber").subscribe(0);
        this.replayNumber = fmsTable.getIntegerTopic("ReplayNumber").subscribe(0);

        // SpectrumJetson (photonvision-62): "IsRedAlliance" is what WPILib publishes (2026 and 2027);
        // upstream had "isRedAlliance", so the match log always said RED.
        this.isRedAlliance = fmsTable.getBooleanTopic("IsRedAlliance").subscribe(true);
        this.stationNumber = fmsTable.getIntegerTopic("StationNumber").subscribe(0);

        for (String tableName : kControlWordTables) {
            NetworkTable table = inst.getTable(tableName);
            this.ntControlWords.add(table.getIntegerTopic("FMSControlData").subscribe(0));
            table.addListener(
                    "FMSControlData",
                    EnumSet.of(Kind.VALUE_ALL),
                    (t, key, event) -> {
                        if (event.is(Kind.VALUE_ALL) && event.valueData.value.isInteger()) {
                            onWord(NTDriverStation.getControlWord(event.valueData.value.getInteger()));
                        }
                    });

            // Through a typed subscriber, not a table listener: a table listener subscribes with no
            // type, and NetworkTables disables an untyped subscription to a topic published as
            // "struct:ControlWord" (type mismatch), so it would never fire.
            var sub =
                    table
                            .getRawTopic("ControlWord")
                            .subscribe("struct:" + ControlWord.struct.getTypeName(), new byte[0]);
            this.ntControlWords2027.add(sub);
            inst.addListener(
                    sub,
                    EnumSet.of(Kind.VALUE_ALL),
                    event -> {
                        if (event.valueData != null && event.valueData.value.isRaw()) {
                            var word = getControlWord2027(event.valueData.value.getRaw());
                            if (word != null) onWord(word);
                        }
                    });
        }

        // Whatever is there already (the listeners only see changes from here on).
        NtControlWord word = null;
        for (var sub : ntControlWords2027) {
            if (word == null) word = getControlWord2027(sub.get());
        }
        for (var sub : ntControlWords) {
            if (word == null && sub.exists()) word = NTDriverStation.getControlWord(sub.get());
        }
        if (word != null) {
            onWord(word);
        } else {
            printMatchData();
        }
    }

    // On NetworkTables' listener thread (or the constructor's).
    private synchronized void onWord(NtControlWord word) {
        wordSeen = true;
        var old = this.lastControlWord;
        if (word.equals(old)) return;
        // Logger totally isnt thread safe but whatevs
        printTransition(old, word);
        printMatchData();
        this.lastControlWord = word;
        for (var listener : transitionListeners) {
            try {
                listener.accept(old, word);
            } catch (Exception e) {
                logger.error("Robot state listener failed", e);
            }
        }
    }

    /**
     * SpectrumJetson (photonvision-62): WPILib 2027's ControlWord struct (hal ControlWordStruct),
     * decoded with WPILib's own struct: one little-endian uint64, bits 0-55 the opmode hash, 56-57
     * the robot mode (0 unknown, 1 autonomous, 2 teleoperated, 3 utility), then enabled (58), e-stop
     * (59), FMS attached (60) and DS attached (61). Utility is 2027's test mode. Null if the value
     * isn't one struct's size.
     */
    static NtControlWord getControlWord2027(byte[] raw) {
        if (raw == null || raw.length != ControlWord.struct.getSize()) return null;
        ControlWord word =
                ControlWord.struct.unpack(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN));
        return new NtControlWord(
                word.isEnabled(),
                word.isAutonomous(),
                word.isUtility(),
                word.isEStopped(),
                word.isFMSAttached(),
                word.isDSAttached());
    }

    private void printTransition(NtControlWord old, NtControlWord newWord) {
        logger.info("ROBOT TRANSITIONED MODES! From " + old.toString() + " to " + newWord.toString());
    }

    private void printMatchData() {
        // this information seems to be published at the same time
        String event = eventName.get();
        if (event.isBlank()) {
            // nothing to log
            return;
        }
        String type =
                switch ((int) matchType.get()) {
                    case 1 -> "P";
                    case 2 -> "Q";
                    case 3 -> "E";
                    default -> "";
                };
        var match = String.valueOf(matchNumber.get());
        var replay = replayNumber.get();

        var station = (isRedAlliance.get() ? "RED" : "BLUE") + stationNumber.get();

        var message =
                "Event: "
                        + event
                        + ", Match: "
                        + type
                        + match
                        + ", Replay: "
                        + replay
                        + ", Station: "
                        + station;
        logger.info(message);
    }

    // Copied from
    // https://github.com/wpilibsuite/allwpilib/blob/07192285f65321a2f7363227a2216f09b715252d/hal/src/main/java/org/wpilib/hardware/hal/DriverStationJNI.java#L123C1-L140C4
    // TODO: upstream!
    /**
     * Gets the current control word of the driver station.
     *
     * <p>The control work contains the robot state.
     *
     * @param controlWord the ControlWord to update
     * @return
     * @see "HAL_GetControlWord"
     */
    static NtControlWord getControlWord(long word) {
        return new NtControlWord(
                (word & 1) != 0,
                ((word >> 1) & 1) != 0,
                ((word >> 2) & 1) != 0,
                ((word >> 3) & 1) != 0,
                ((word >> 4) & 1) != 0,
                ((word >> 5) & 1) != 0);
    }
}
