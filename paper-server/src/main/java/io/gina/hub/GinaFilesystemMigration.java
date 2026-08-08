package io.gina.hub;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gina-Hub restructures the server's runtime filesystem relative to stock Paper:
 *
 * <pre>
 *   config/                     -&gt; configs/   (also collects bukkit.yml, commands.yml,
 *                                               spigot.yml, server.properties, help.yml,
 *                                               permissions.yml, which Paper keeps at root)
 *   banned-ips.json, etc.       -&gt; data/
 *   world, world_nether, ...    -&gt; worlds/
 * </pre>
 *
 * {@code eula.txt} is deliberately NOT part of this restructure and stays at the
 * conventional root ({@code ./eula.txt}) -- this is the one file every hosting panel,
 * Pterodactyl egg, and custom startup script already knows how to find and write,
 * regardless of whatever else a given server software calls its config layout. An earlier
 * build of Gina-Hub briefly moved it into {@code configs/eula.txt}; this class reverses
 * that for anyone who already has one there (see {@link #migrateEulaBackToRoot()}).
 *
 * <p>This class runs once, very early in server bootstrap (before any config, ban list,
 * whitelist, usercache, version history, or world is read), and safely migrates an
 * existing old-layout (stock Paper, or an earlier Gina-Hub build) install to the current
 * layout: existing files are moved (never overwritten -- if a file already exists at the
 * new location too, the old one is archived into a {@code legacy-backup/} folder next to
 * it rather than left sitting at a stale path, so there is never more than one canonical
 * copy of a given file after migration runs), directories are created as needed, and every
 * migrated item is logged. Because each step only acts when the old path exists, this is
 * naturally idempotent: once a file has been moved (or archived), there is nothing left at
 * the old path to act on again on the next boot.</p>
 */
public final class GinaFilesystemMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger(GinaFilesystemMigration.class);

    private static final String DATA_DIR = "data";
    private static final String CONFIGS_DIR = "configs";
    private static final String WORLDS_DIR = "worlds";
    private static final String OLD_CONFIG_DIR = "config";
    private static final String CONFLICT_BACKUP_DIR = CONFIGS_DIR + "/legacy-backup";

    private static final List<String> DATA_FILES = List.of(
        "banned-ips.json",
        "banned-players.json",
        "ops.json",
        "whitelist.json",
        "usercache.json",
        "version_history.json"
    );

    private GinaFilesystemMigration() {
    }

    public static void migrate(final joptsimple.OptionSet optionSet) {
        try {
            createDirectoryIfMissing(DATA_DIR);
            createDirectoryIfMissing(CONFIGS_DIR);
            createDirectoryIfMissing(WORLDS_DIR);

            migrateOldConfigDirectory();
            migrateRootConfigFiles(optionSet);
            migrateEulaBackToRoot();
            migrateDataFiles();
            migrateWorlds(optionSet);
        } catch (final Exception ex) {
            // Never block startup on a migration failure -- worst case, the old files are
            // simply left where they are and the server falls back to fresh defaults, which
            // is safe (if occasionally annoying) rather than a hard crash before logging is
            // even fully available.
            LOGGER.error("Gina-Hub filesystem migration encountered an error (continuing startup; old files, if any, were left in place)", ex);
        }
    }

    private static void createDirectoryIfMissing(final String dir) throws IOException {
        final Path path = Path.of(dir);
        if (Files.notExists(path)) {
            Files.createDirectories(path);
        }
    }

    private static void migrateOldConfigDirectory() throws IOException {
        final File oldConfigDir = new File(OLD_CONFIG_DIR);
        if (!oldConfigDir.isDirectory()) {
            return;
        }
        // Move the whole legacy config/ tree (including any legacy-backup/ subfolder Paper
        // itself created) under configs/, then rename Paper's own file names to Gina's.
        final Path oldPath = oldConfigDir.toPath();
        final Path newPath = Path.of(CONFIGS_DIR);
        for (final Path child : listDirectory(oldPath)) {
            final Path target = newPath.resolve(oldPath.relativize(child));
            moveOrArchive(child, target, "config/" + oldPath.relativize(child));
        }
        deleteIfEmpty(oldPath);

        renameIfPresent(newPath.resolve("paper-global.yml"), newPath.resolve("gina-global.yml"));
        renameIfPresent(newPath.resolve("paper-world-defaults.yml"), newPath.resolve("gina-world-defaults.yml"));
    }

    private static void migrateRootConfigFiles(final joptsimple.OptionSet optionSet) {
        // Only migrate files that are still at their stock-Paper root location AND that the
        // user hasn't pointed elsewhere via CLI flags (-b/-C/-S/-c) -- if they customized
        // those, respect their explicit choice instead of moving files out from under them.
        if (!optionSet.has("bukkit-settings")) {
            moveOrArchive(Path.of("bukkit.yml"), Path.of(CONFIGS_DIR, "bukkit.yml"), "bukkit.yml");
        }
        if (!optionSet.has("commands-settings")) {
            moveOrArchive(Path.of("commands.yml"), Path.of(CONFIGS_DIR, "commands.yml"), "commands.yml");
        }
        if (!optionSet.has("spigot-settings")) {
            moveOrArchive(Path.of("spigot.yml"), Path.of(CONFIGS_DIR, "spigot.yml"), "spigot.yml");
        }
        if (!optionSet.has("config")) {
            moveOrArchive(Path.of("server.properties"), Path.of(CONFIGS_DIR, "server.properties"), "server.properties");
        }
        moveOrArchive(Path.of("help.yml"), Path.of(CONFIGS_DIR, "help.yml"), "help.yml");
        moveOrArchive(Path.of("permissions.yml"), Path.of(CONFIGS_DIR, "permissions.yml"), "permissions.yml");
    }

    /**
     * Reverses the brief {@code configs/eula.txt} experiment: if an earlier Gina-Hub build
     * already moved a real {@code eula.txt} there, and nothing has since been written back to
     * the root (i.e. no root {@code eula.txt} exists yet), move it back so the conventional
     * root path is once again the single canonical EULA file.
     */
    private static void migrateEulaBackToRoot() {
        moveOrArchive(Path.of(CONFIGS_DIR, "eula.txt"), Path.of("eula.txt"), "configs/eula.txt");
    }

    private static void migrateDataFiles() {
        for (final String fileName : DATA_FILES) {
            moveOrArchive(Path.of(fileName), Path.of(DATA_DIR, fileName), fileName);
        }
    }

    private static void migrateWorlds(final joptsimple.OptionSet optionSet) {
        if (optionSet.has("universe")) {
            // A custom world-container was explicitly requested on the command line --
            // respect it rather than relocating worlds out from under an intentional setup.
            return;
        }
        final String levelName = readLevelNameBestEffort();
        for (final String worldDir : List.of(levelName, levelName + "_nether", levelName + "_the_end")) {
            final Path oldPath = Path.of(worldDir);
            if (!Files.isDirectory(oldPath)) {
                continue;
            }
            moveOrArchive(oldPath, Path.of(WORLDS_DIR, worldDir), "world '" + worldDir + "'");
        }
    }

    /**
     * Reads {@code level-name} directly from a raw server.properties file (old or new
     * location, whichever exists), without going through the full config system -- this
     * runs before that system is initialized. Falls back to vanilla's own default, "world".
     */
    private static String readLevelNameBestEffort() {
        for (final String candidate : List.of("server.properties", CONFIGS_DIR + "/server.properties")) {
            final File file = new File(candidate);
            if (file.isFile()) {
                final Properties properties = new Properties();
                try (FileInputStream in = new FileInputStream(file)) {
                    properties.load(in);
                    return properties.getProperty("level-name", "world");
                } catch (final IOException ex) {
                    LOGGER.warn("Gina-Hub migration: could not read level-name from {}, assuming 'world'", candidate, ex);
                }
            }
        }
        return "world";
    }

    /**
     * Moves {@code oldPath} to {@code newPath} if only the old one exists. If BOTH already
     * exist, the new location is treated as canonical (it's what the server actually reads)
     * and the old one is archived into {@code configs/legacy-backup/} instead of being left
     * sitting at its original path -- so there is never a second, competing copy of the same
     * file left in an ambiguous location, but nothing is deleted either.
     */
    private static void moveOrArchive(final Path oldPath, final Path newPath, final String label) {
        if (!Files.exists(oldPath)) {
            return;
        }
        if (Files.exists(newPath)) {
            archiveConflict(oldPath, label);
            return;
        }
        try {
            Files.createDirectories(newPath.toAbsolutePath().getParent());
            Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("Gina-Hub migration: moved {} to {}", label, newPath);
        } catch (final IOException ex) {
            LOGGER.error("Gina-Hub migration: failed to move {} to {}", label, newPath, ex);
        }
    }

    private static void archiveConflict(final Path oldPath, final String label) {
        try {
            final Path backupDir = Path.of(CONFLICT_BACKUP_DIR);
            Files.createDirectories(backupDir);
            final String archivedName = oldPath.getFileName() + "." + Instant.now().getEpochSecond() + ".old";
            final Path archived = backupDir.resolve(archivedName);
            Files.move(oldPath, archived, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.warn("Gina-Hub migration: {} already exists at its new location; archived the old copy of {} to {} instead of leaving two competing copies", label, label, archived);
        } catch (final IOException ex) {
            LOGGER.error("Gina-Hub migration: {} already exists at its new location, and archiving the old copy of {} also failed -- leaving it in place at {}", label, label, oldPath, ex);
        }
    }

    private static void renameIfPresent(final Path oldPath, final Path newPath) {
        if (Files.exists(oldPath) && !Files.exists(newPath)) {
            try {
                Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
                LOGGER.info("Gina-Hub migration: renamed {} to {}", oldPath, newPath);
            } catch (final IOException ex) {
                LOGGER.error("Gina-Hub migration: failed to rename {} to {}", oldPath, newPath, ex);
            }
        }
    }

    private static List<Path> listDirectory(final Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.toList();
        }
    }

    private static void deleteIfEmpty(final Path dir) {
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.delete(dir);
            }
        } catch (final IOException ignored) {
            // non-empty or in-use; leave it, harmless either way
        }
    }
}
