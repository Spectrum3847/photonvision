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

package org.photonvision.common.logging;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.wpilib.vision.camera.CameraServerJNI;

/** Redirect cscore logs to our logger */
public class PvCSCoreLogger {
    private static PvCSCoreLogger INSTANCE;

    public static PvCSCoreLogger getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PvCSCoreLogger();
        }
        return INSTANCE;
    }

    private Logger logger;

    private PvCSCoreLogger() {
        CameraServerJNI.setLogger(this::logMsg, 7);
        this.logger = new Logger(getClass(), LogGroup.CSCore);
    }

    /**
     * SpectrumJetson (photonvision-89): UsbCameras (by name) whose cscore camera thread has ended.
     * UsbCameraImpl::CameraThreadMain ends for good when its select() fails, EINTR included
     * ("select(): Interrupted system call", WPILib 2027 alpha-8), and logs that as an error first;
     * nothing on that UsbCamera can work again (photonvision-88). Its USBFrameProvider restarts the
     * camera as soon as it sees it here, instead of after a USB reset and 13 s of cscore SetPath
     * waits.
     */
    private static final Set<String> endedCameraThreads = ConcurrentHashMap.newKeySet();

    /** The camera whose cscore thread this log message says has ended, or null. */
    static String endedCameraThread(int level, String file, String msg) {
        if (level < 40 || file == null || msg == null || !file.endsWith("UsbCameraImpl.cpp")) {
            return null;
        }
        int i = msg.indexOf(": select(): "); // SERROR prefixes the source's name
        return i > 0 ? msg.substring(0, i) : null;
    }

    /** Whether cscore has said this UsbCamera's camera thread ended (photonvision-89). */
    public static boolean cameraThreadEnded(String cameraName) {
        return endedCameraThreads.contains(cameraName);
    }

    /** A new UsbCamera with this name has its own thread (photonvision-89). */
    public static void clearCameraThreadEnded(String cameraName) {
        endedCameraThreads.remove(cameraName);
    }

    /** Notes a camera whose thread this cscore message says has ended. */
    static void noteCameraThread(int level, String file, String msg) {
        String ended = endedCameraThread(level, file, msg);
        if (ended != null) endedCameraThreads.add(ended);
    }

    private void logMsg(int level, String file, int line, String msg) {
        noteCameraThread(level, file, msg);
        if (level == 20) {
            logger.info(msg);
            return;
        }

        file = Path.of(file).getFileName().toString();

        String levelmsg;
        LogLevel pvlevel;
        if (level >= 50) {
            levelmsg = "CRITICAL";
            pvlevel = LogLevel.ERROR;
        } else if (level >= 40) {
            levelmsg = "ERROR";
            pvlevel = LogLevel.ERROR;
        } else if (level >= 30) {
            levelmsg = "WARNING";
            pvlevel = LogLevel.WARN;
        } else if (level >= 20) {
            levelmsg = "INFO";
            pvlevel = LogLevel.INFO;
        } else {
            levelmsg = "DEBUG";
            pvlevel = LogLevel.DEBUG;
        }
        logger.log(
                "CS: " + levelmsg + " " + level + ": " + msg + " (" + file + ":" + line + ")", pvlevel);
    }
}
