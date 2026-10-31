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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SpectrumJetson (photonvision-16, 19): the sysfs search fails soft and finds files by glob. */
public class JetsonSysfsTest {
    @Test
    public void findsByGlobInNameOrder(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("hwmon3"));
        Files.createDirectories(dir.resolve("hwmon1"));
        Files.writeString(dir.resolve("hwmon3/pwm1"), "128\n");
        Files.writeString(dir.resolve("hwmon1/pwm1"), "64\n");
        Path p = JetsonSysfs.firstMatch(dir, "hwmon*", "pwm1");
        assertEquals(dir.resolve("hwmon1/pwm1"), p);
        assertEquals(64.0, JetsonSysfs.number(p));
    }

    @Test
    public void missingIsNull(@TempDir Path dir) throws IOException {
        assertNull(JetsonSysfs.firstMatch(dir.resolve("nope"), "*", "load"));
        assertNull(JetsonSysfs.number(null));
        Files.writeString(dir.resolve("bad"), "n/a");
        assertNull(JetsonSysfs.number(dir.resolve("bad")));
        assertEquals(0, JetsonSysfs.children(dir.resolve("nope"), "*").size());
    }

    @Test
    public void zoneNamesMatchWithOrWithoutSuffix() {
        assertEquals("soc0", JetsonSysfs.zoneName("soc0-thermal"));
        assertEquals("soc0", JetsonSysfs.zoneName("soc0"));
        assertEquals("tj", JetsonSysfs.zoneName("tj-thermal"));
    }
}
