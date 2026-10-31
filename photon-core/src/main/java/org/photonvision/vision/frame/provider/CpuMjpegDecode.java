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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Spectrum (photonvision-98): MJPEG straight to 8-bit gray on the CPU, with no detector library
 * (the SystemCore; any machine without CUDA). OpenCV's imdecode (libjpeg-turbo inside WPILib's
 * OpenCV) with IMREAD_GRAYSCALE decodes only the brightness channel: no colour upsampling or
 * conversion, and no BGR frame made only to be converted to gray as cscore's gray sink does.
 * {@code half} uses libjpeg's scaled IDCT (IMREAD_REDUCED_GRAYSCALE_2): a half-resolution image
 * straight from the JPEG, for the low-CPU decode.
 *
 * <p>Measured on the Jetson's Cortex-A78AE at 1.2 GHz, 1280x800 field frames
 * (tests/systemcore-cpu): colour decode + cvtColor 9.8 ms, gray 5.0 ms, half-res gray 4.2 ms.
 *
 * <p>The JPEG is read from cscore's native frame (WPI_RawFrame) through the FFM API: the Java
 * RawFrame's fields aren't filled by CscoreExtras' grab. The layout (data, freeFunc, freeCbData,
 * capacity, size, pixelFormat, width, height, ...) is checked against the video mode each frame,
 * and a frame that doesn't match is refused (-4), never read.
 */
final class CpuMjpegDecode {
    private CpuMjpegDecode() {}

    private static final long OFF_DATA = 0, OFF_CAPACITY = 24, OFF_SIZE = 32;
    private static final long OFF_FORMAT = 40, OFF_WIDTH = 44, OFF_HEIGHT = 48;
    private static final int WPI_PIXFMT_MJPEG = 1;

    /**
     * Decode the MJPEG in cscore's native frame into {@code out[0]}: gray, full size or (half)
     * ceil(width/2) x ceil(height/2). Returns 0, or as GpuDetectorJNI.decodeMjpegGray: -1 not
     * MJPEG, -2 bad JPEG, -3 not the video mode's size; -4 the native frame doesn't look like a
     * WPI_RawFrame (another WPILib's layout): don't use this decoder.
     */
    static int decode(long rawFramePtr, int width, int height, boolean half, Mat[] out) {
        if (rawFramePtr == 0) return -4;
        MemorySegment f = MemorySegment.ofAddress(rawFramePtr).reinterpret(56);
        long data = f.get(ValueLayout.JAVA_LONG, OFF_DATA);
        long capacity = f.get(ValueLayout.JAVA_LONG, OFF_CAPACITY);
        long size = f.get(ValueLayout.JAVA_LONG, OFF_SIZE);
        int format = f.get(ValueLayout.JAVA_INT, OFF_FORMAT);
        int w = f.get(ValueLayout.JAVA_INT, OFF_WIDTH);
        int h = f.get(ValueLayout.JAVA_INT, OFF_HEIGHT);
        if (data == 0 || size <= 0 || size > capacity || capacity > (1L << 28)) return -4;
        if (format != WPI_PIXFMT_MJPEG) return format >= 0 && format <= 8 ? -1 : -4;
        if (w != width || h != height) return w > 0 && w < 16384 && h > 0 && h < 16384 ? -3 : -4;
        byte[] jpeg = MemorySegment.ofAddress(data).reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        // a JPEG starts FF D8
        if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) return -2;
        var buf = new MatOfByte(jpeg);
        Mat img;
        try {
            img = Imgcodecs.imdecode(buf, half ? Imgcodecs.IMREAD_REDUCED_GRAYSCALE_2 : Imgcodecs.IMREAD_GRAYSCALE);
        } finally {
            buf.release();
        }
        int wantW = half ? (width + 1) / 2 : width, wantH = half ? (height + 1) / 2 : height;
        if (img.empty()) {
            img.release();
            return -2;
        }
        if (img.cols() != wantW || img.rows() != wantH) {
            img.release();
            return -3;
        }
        out[0] = img;
        return 0;
    }
}
