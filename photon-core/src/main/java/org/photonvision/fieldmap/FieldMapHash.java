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

package org.photonvision.fieldmap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import org.wpilib.fields.Field;
import org.wpilib.fields.FieldTag;

/**
 * SpectrumJetson (photonvision-74): the field-map hash robot code and the Jetson both compute, so
 * each can tell the other which map it means. The robot side (FieldMapHash in robot code's
 * spectrumLib) computes the same thing; change it only together with robot code.
 *
 * <p>Tags sorted by ID, one line each, {@code id,x,y,z,qw,qx,qy,qz} (meters, the tag pose's
 * rotation quaternion), then {@code field,<length>,<width>}; every number {@code %.4f} in {@link
 * Locale#ROOT}; lines joined with "\n", no trailing newline; SHA-256 of the UTF-8 bytes, lowercase
 * hex, first 16 characters. The text is the same for a map in WPILib 2027's Field JSON and in the
 * 2026 AprilTagFieldLayout JSON: only the numbers go into it.
 *
 * <p>Java's {@code %.4f} rounds the shortest decimal form of the double half-up, and keeps the sign
 * of a value that rounds to zero ("-0.0000"). Python's {@code f"{v:.4f}"} rounds the exact binary
 * value half-even, so it disagrees on ties: the 2026 field's 0.55245 m tag height is "0.5525" here
 * and "0.5524" in Python. A non-Java implementation has to round like Java.
 */
public final class FieldMapHash {
    private FieldMapHash() {}

    /** The text the hash is taken of. */
    public static String canonical(Field layout) {
        var tags = new ArrayList<FieldTag>(layout.getTags());
        tags.sort(Comparator.comparingInt(FieldTag::getID));
        var sb = new StringBuilder();
        for (FieldTag tag : tags) {
            var p = tag.getPose();
            var q = p.getRotation().getQuaternion();
            sb.append(tag.getID());
            for (double v :
                    new double[] {p.getX(), p.getY(), p.getZ(), q.getW(), q.getX(), q.getY(), q.getZ()}) {
                sb.append(',').append(format(v));
            }
            sb.append('\n');
        }
        sb.append("field,")
                .append(format(layout.getFieldLength()))
                .append(',')
                .append(format(layout.getFieldWidth()));
        return sb.toString();
    }

    public static String hash(Field layout) {
        return hashText(canonical(layout));
    }

    static String format(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    static String hashText(String canonical) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }
}
