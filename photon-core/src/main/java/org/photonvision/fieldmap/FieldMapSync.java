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

import io.avaje.json.node.JsonArray;
import io.avaje.json.node.JsonNodeMapper;
import io.avaje.json.node.JsonObject;
import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.wpilib.fields.Field;
import org.wpilib.fields.FieldTag;

/**
 * SpectrumJetson (photonvision-74): the AprilTag field map comes from robot code. The robot
 * publishes a map with its hash ({@link FieldMapHash}); this checks and loads it, keeps it on disk
 * for the next boot, and reports the hash of the map actually in use, so the robot can tell whether
 * the Jetson agrees with it. {@link FieldMapNT} connects it to NetworkTables.
 *
 * <p>The map is WPILib 2027 Field JSON ({@code field-tags}, {@code field-dimensions}), read with
 * WPILib's own parser, as upstream's {@code /photonvision/field_layout} is. The 2026 form ({@code
 * tags}, {@code field}: what a 2026 Jetson saved for its next boot) is read too, renamed as
 * upstream's database migration does; both hash the same.
 *
 * <p>A map is loaded only if its JSON is a sane field and hashes to the hash the robot sent with
 * it. Otherwise the map in use stays, and {@link #error()} says why. Loading is a reference swap in
 * PhotonVision's configuration; each pipeline reads the layout once per frame, so a change lands
 * between frames and costs a camera nothing.
 *
 * <p>Not thread-safe on its own: {@link FieldMapNT} calls it from one thread.
 */
public final class FieldMapSync {
    private static final Logger logger = new Logger(FieldMapSync.class, LogGroup.General);
    private static final JsonNodeMapper json = JsonNodeMapper.builder().build();

    /** Shown as the name of a map that didn't come from the robot (PhotonVision's own setting). */
    public static final String LOCAL_NAME = "photonvision-settings";

    // Sanity limits. An FRC field is about 16.5 x 8.1 m; a tag can sit just outside the carpet.
    static final double MIN_FIELD_M = 1.0;
    static final double MAX_FIELD_M = 30.0;
    static final double TAG_MARGIN_M = 2.0;
    static final double MAX_TAG_HEIGHT_M = 5.0;

    private static final Pattern LEGACY_TAGS = Pattern.compile("\"tags\"\\s*:");
    private static final Pattern LEGACY_FIELD = Pattern.compile("\"field\"\\s*:");

    private final Path file;
    private final Supplier<Field> current;
    private final Consumer<Field> apply;
    private final DoubleSupplier unixSeconds;

    private Field seen;
    private String seenHash = "";
    private String name = LOCAL_NAME;
    private double loadedAt = Double.NaN;
    private String error = "";
    private Field ours;
    private String savedHash = "";
    private String savedName = "";

    /**
     * @param file where the robot's map is kept between boots
     * @param current the layout PhotonVision is using now
     * @param apply makes a layout the one PhotonVision uses
     * @param unixSeconds wall-clock time, for {@link #loadedAt()}
     */
    public FieldMapSync(
            Path file, Supplier<Field> current, Consumer<Field> apply, DoubleSupplier unixSeconds) {
        this.file = file;
        this.current = current;
        this.apply = apply;
        this.unixSeconds = unixSeconds;
    }

    /** A checked map: the layout and its hash. */
    record Checked(Field layout, String hash) {}

    /**
     * At boot: loads the map the robot last sent, if one was saved and it still checks out.
     *
     * @return whether a saved map is now in use
     */
    public boolean loadSaved() {
        if (!Files.exists(file)) return false;
        try {
            JsonObject saved = json.fromJsonObject(Files.readString(file, StandardCharsets.UTF_8));
            String hash = saved.extract("hash", "");
            String savedAs = saved.extract("name", "");
            Checked c = check(saved.extract("json", ""));
            if (!c.hash().equals(hash)) {
                throw new IllegalArgumentException("its hash is " + c.hash() + ", saved as " + hash);
            }
            use(c, savedAs);
            savedHash = hash;
            savedName = savedAs;
            logger.info("Field map: loaded the robot's saved map '" + savedAs + "' (" + hash + ")");
            return true;
        } catch (IOException | RuntimeException e) {
            error = "Saved field map " + file + " unusable, kept PhotonVision's: " + e.getMessage();
            logger.error(error);
            return false;
        }
    }

    /**
     * A map from the robot. Loads it if it checks out and differs from the one in use, and saves it
     * for the next boot. An empty JSON (the robot isn't publishing one) is ignored.
     *
     * @return whether the robot's map is now in use
     */
    public boolean offer(String mapJson, String hash, String robotName) {
        if (mapJson == null || mapJson.isBlank()) return false;
        String expected = hash == null ? "" : hash.strip().toLowerCase(Locale.ROOT);
        Checked c;
        try {
            c = check(mapJson);
        } catch (IOException | RuntimeException e) {
            return refuse("Field map from the robot is invalid, kept the current map: " + e.getMessage());
        }
        if (!c.hash().equals(expected)) {
            return refuse(
                    "Field map from the robot hashes to "
                            + c.hash()
                            + " but the robot sent hash '"
                            + expected
                            + "'; kept the current map");
        }
        String newName = robotName == null || robotName.isBlank() ? "robot" : robotName.strip();
        if (!c.hash().equals(currentHash())) {
            use(c, newName);
            logger.info("Field map: loaded the robot's map '" + newName + "' (" + c.hash() + ")");
        } else {
            // Already in use (saved from an earlier boot, or the same map from PhotonVision's own
            // setting): nothing to reload.
            ours = current.get();
            name = newName;
            if (Double.isNaN(loadedAt)) loadedAt = unixSeconds.getAsDouble();
        }
        error = "";
        if (!c.hash().equals(savedHash) || !newName.equals(savedName) || !Files.exists(file)) {
            try {
                save(mapJson, c.hash(), newName);
                savedHash = c.hash();
                savedName = newName;
            } catch (IOException e) {
                error = "Field map in use but not saved for the next boot: " + e.getMessage();
                logger.error(error);
            }
        }
        return true;
    }

    /** Hash of the map in use now, whoever set it. */
    public String currentHash() {
        refreshSeen();
        return seenHash;
    }

    /** The robot's name for the map in use, or {@link #LOCAL_NAME}. */
    public String name() {
        refreshSeen();
        return name;
    }

    /** When the map in use was loaded, unix seconds; NaN before any. */
    public double loadedAt() {
        refreshSeen();
        return loadedAt;
    }

    /** Why the last map wasn't used, or "". */
    public String error() {
        return error;
    }

    /** Forgets the saved map, so PhotonVision's own layout is used at the next boot. */
    public static void forgetSaved(Path file) {
        try {
            if (Files.deleteIfExists(file)) {
                logger.info(
                        "Field map: forgot the robot's saved map; PhotonVision's own is used at the next boot");
            }
        } catch (IOException e) {
            logger.error("Could not delete " + file, e);
        }
    }

    // Notices a layout set some other way (the settings page, field calibration, upstream's
    // /photonvision/field_layout), so the hash reported is always the one in use.
    private void refreshSeen() {
        var cur = current.get();
        if (cur == seen) return;
        seen = cur;
        seenHash = cur == null || !cur.hasTags() ? "" : FieldMapHash.hash(cur);
        if (cur != ours) {
            name = LOCAL_NAME;
            loadedAt = unixSeconds.getAsDouble();
        }
    }

    private void use(Checked c, String newName) {
        apply.accept(c.layout());
        ours = c.layout();
        name = newName;
        loadedAt = unixSeconds.getAsDouble();
        refreshSeen();
    }

    private boolean refuse(String why) {
        if (!why.equals(error)) logger.warn(why);
        error = why;
        return false;
    }

    /**
     * Parses and sanity-checks a field map: WPILib 2027 Field JSON, or the 2026 AprilTagFieldLayout
     * JSON.
     */
    static Checked check(String mapJson) throws IOException {
        // Read by WPILib from the text as sent: re-written by a JSON library first, a -0.0 can become
        // 0 (avaje's JSON nodes do that), and the sign is part of the hash.
        String text = mapJson;
        JsonObject root = json.fromJsonObject(text);
        // The 2026 form: renamed as upstream's DbMigration (202704) does. Neither key name appears
        // anywhere else in a field map.
        if (!root.containsKey("field-tags") && root.containsKey("tags")) {
            text = LEGACY_TAGS.matcher(text).replaceFirst("\"field-tags\":");
            text = LEGACY_FIELD.matcher(text).replaceFirst("\"field-dimensions\":");
            root = json.fromJsonObject(text);
        }
        if (!(root.extractNode("field-tags", JsonArray.empty()) instanceof JsonArray tags)
                || tags.isEmpty()) {
            throw new IllegalArgumentException("no tags");
        }
        var ids = new HashSet<Integer>();
        for (var t : tags.elements()) {
            if (!ids.add(t.extract("ID", -1))) {
                throw new IllegalArgumentException("tag " + t.extract("ID", "?") + " listed twice");
            }
        }
        Field layout = Jsonb.instance().type(Field.class).fromJson(text);
        if (layout == null || layout.getTags() == null)
            throw new IllegalArgumentException("not a field map");
        double length = layout.getFieldLength();
        double width = layout.getFieldWidth();
        if (!(length >= MIN_FIELD_M
                && length <= MAX_FIELD_M
                && width >= MIN_FIELD_M
                && width <= MAX_FIELD_M)) {
            throw new IllegalArgumentException(String.format("field size %s x %s m", length, width));
        }
        if (layout.getTags().size() != tags.size()) {
            throw new IllegalArgumentException("tags lost in parsing");
        }
        for (FieldTag t : layout.getTags()) {
            var p = t.getPose();
            if (p == null) throw new IllegalArgumentException("tag " + t.getID() + " has no pose");
            var q = p.getRotation().getQuaternion();
            boolean finite =
                    Double.isFinite(p.getX())
                            && Double.isFinite(p.getY())
                            && Double.isFinite(p.getZ())
                            && Double.isFinite(q.getW())
                            && Double.isFinite(q.getX())
                            && Double.isFinite(q.getY())
                            && Double.isFinite(q.getZ());
            boolean onField =
                    p.getX() >= -TAG_MARGIN_M
                            && p.getX() <= length + TAG_MARGIN_M
                            && p.getY() >= -TAG_MARGIN_M
                            && p.getY() <= width + TAG_MARGIN_M
                            && p.getZ() >= -TAG_MARGIN_M
                            && p.getZ() <= MAX_TAG_HEIGHT_M;
            if (t.getID() < 0 || !finite || !onField) {
                throw new IllegalArgumentException(
                        "tag " + t.getID() + " at " + p + " is not on the field");
            }
        }
        return new Checked(layout, FieldMapHash.hash(layout));
    }

    /**
     * Written beside the old file, flushed, then renamed over it: a power cut leaves one or the
     * other.
     */
    private void save(String mapJson, String hash, String robotName) throws IOException {
        var root = JsonObject.create().add("name", robotName).add("hash", hash).add("json", mapJson);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var ch =
                FileChannel.open(
                        tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(json.toJson(root).getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        try (var dir = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
            dir.force(true); // the rename itself; Linux only, Windows can't open a directory
        } catch (IOException e) {
            // best effort
        }
    }
}
