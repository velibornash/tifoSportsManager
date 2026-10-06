package org.example.commonmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dumps the whole database to a timestamped file and reads one back (owner, 2026-10-06).
 *
 * <p><b>Why the PostgreSQL tools and not a copy.</b> The owner wants to snapshot a world he is happy
 * with - a clean season 1, week 1, day 1, every team seeded and every cup drawn - and to get that
 * world back exactly, on demand. {@code pg_dump} writes a logical archive of the whole database and
 * {@code pg_restore} replays it, which is the only way to do that through an application that is
 * running, connected and holding rows in flight. Copying {@code base/} underneath a live PostgreSQL
 * produces an archive that is corrupt the moment it is used.
 *
 * <p><b>No shell, ever.</b> Every command is built as a list and handed to {@link ProcessBuilder}, so
 * a filename can never become a command. The restore path comes from a request, which is why that
 * matters here and not in the dump.
 *
 * <p><b>The password is passed in the environment</b> ({@code PGPASSWORD}), never as an argument, so
 * it does not appear in {@code ps} output for every other process on the machine to read, and never
 * reaches the log.
 *
 * <p><b>An archive is proved readable before anything is destroyed.</b> A restore that drops the
 * schema and then fails on a truncated file leaves no world at all, so {@code pg_restore --list} runs
 * first: it reads the archive's table of contents and proves the file is a complete dump of a
 * database before a single row is dropped.
 */
@Service
@Slf4j
public class DatabaseBackupService {

    /** The owner's name: {@code yyyy-mm-dd-HH-mm-ss}. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter READABLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A dump this service created, and the only name shape it will restore from. */
    private static final Pattern BACKUP_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.dump");

    private static final Pattern CLIENT_VERSION = Pattern.compile("(\\d+)\\.\\d+");
    private static final Pattern JDBC_URL =
            Pattern.compile("jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?]+)");
    private static final Pattern STAMP_IN_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})");

    private static final long TIMEOUT_MINUTES = 15;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Path directory;
    private final String configuredTools;

    /** The server's major version, read once per run; see {@link #serverMajor()}. */
    private Integer cachedServerMajor;

    public DatabaseBackupService(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${app.backup.dir:backups}") String backupDirectory,
            @Value("${app.backup.pg-tools:}") String pgTools) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.directory = Paths.get(backupDirectory).toAbsolutePath().normalize();
        this.configuredTools = pgTools == null ? "" : pgTools.trim();
    }

    /** The name a dump taken at this moment gets: the owner's {@code yyyy-mm-dd-HH-mm-ss}, to the second. */
    String nameFor(LocalDateTime moment) {
        return moment.format(STAMP) + ".dump";
    }

    /** One dump on disk. */
    public record Backup(String name, long bytes, String createdAt) {
    }

    /** What a restore did, in the words the admin screen shows. */
    public record Restore(String name, int objects, String note) {
    }

    /**
     * Dumps the database to {@code yyyy-mm-dd-HH-mm-ss.dump} and reports what was written.
     *
     * <p>The name is the timestamp the owner asked for, so a set of backups reads as a timeline when
     * they are listed in a file browser or pasted into a message.
     */
    public Backup create() {
        String name = nameFor(LocalDateTime.now());
        Path target = resolve(name);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the backup directory " + directory + ": " + e.getMessage(), e);
        }

        List<String> command = new ArrayList<>(List.of(
                tool("pg_dump"),
                "--format=custom",
                "--no-owner",
                "--no-privileges",
                "--host=" + host(),
                "--port=" + port(),
                "--username=" + username,
                "--dbname=" + database(),
                "--file=" + target));
        try {
            run(command, "dump the database");
        } catch (RuntimeException e) {
            // A half-written dump is worse than none: it is listed next to the real ones and it is the
            // file somebody picks when they want last night's world back.
            deleteQuietly(target);
            throw e;
        }

        long bytes = sizeOf(target);
        log.info("Database dumped to {} ({} bytes).", target, bytes);
        return new Backup(name, bytes, READABLE.format(LocalDateTime.now()));
    }

    /** Every dump this service created, newest first. */
    public List<Backup> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> BACKUP_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .map(path -> {
                        String fileName = path.getFileName().toString();
                        return new Backup(fileName, sizeOf(path), readable(fileName));
                    })
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list the backups in " + directory + ": " + e.getMessage(), e);
        }
    }

    /**
     * Replaces the database with the contents of one dump.
     *
     * <p><b>This destroys the current world.</b> That is what "restore" means, and the admin screen
     * says so before it asks. What this method adds is the order of operations: prove the archive
     * first, drop the schema second, replay third, and say loudly afterwards that the application
     * must be restarted, because the process that just replaced the database still holds a connection
     * pool and a persistence context that were built against the old one.
     */
    public Restore restore(String requestedName) {
        Path archive = resolve(requestedName);
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("There is no backup called '" + requestedName + "'.");
        }

        // Prove the archive and the credentials before the schema goes. A truncated file, a dump of a
        // different server, or a user who may not drop a schema all fail here, with the world intact.
        run(psql(List.of("--command", "select current_user")), "check the connection");
        int objects = countArchiveObjects(archive);

        // A clean slate. `pg_restore --clean` drops objects one at a time in the archive's own order
        // and can stop halfway through a dependency chain, which is how a restore ends with half the
        // old world and half the new one. Dropping the schema is one statement and it cannot.
        run(psql(List.of("--command",
                "drop schema if exists public cascade; create schema public;")), "clear the current schema");

        run(List.of(
                tool("pg_restore"),
                "--no-owner",
                "--no-privileges",
                "--exit-on-error",
                "--host=" + host(),
                "--port=" + port(),
                "--username=" + username,
                "--dbname=" + database(),
                archive.toString()), "replay the archive");

        log.warn("Database restored from {}. The application must be restarted before it is used again.",
                archive.getFileName());
        return new Restore(archive.getFileName().toString(), objects,
                "The database was replaced with " + archive.getFileName()
                        + ". Restart the application before using it.");
    }

    /**
     * Reads the archive's table of contents and counts what is in it.
     *
     * <p>{@code pg_restore --list} is the cheapest possible proof that a file is a complete dump: it
     * reads the archive index and prints it without touching the database, so a file that was cut
     * short halfway through fails here instead of after the schema has been dropped.
     */
    private int countArchiveObjects(Path archive) {
        // The archive is positional. `--file` belongs to pg_dump; given to pg_restore it is ignored,
        // so the tool reads standard input instead, finds nothing there, and fails with "input file is
        // too short" - a message about a short file when the file was never opened at all.
        String listing = run(List.of(tool("pg_restore"), "--list", archive.toString()), "read the archive");
        int objects = 0;
        for (String line : listing.split("\n")) {
            boolean isEntry = !line.isBlank() && !line.startsWith(";") && !line.startsWith("--");
            if (isEntry && (line.contains(" TABLE ") || line.contains(" SEQUENCE ")
                    || line.contains(" CONSTRAINT ") || line.contains(" INDEX "))) {
                objects++;
            }
        }
        if (objects == 0) {
            throw new IllegalStateException(archive.getFileName()
                    + " lists no tables, so it is not a usable dump of this application.");
        }
        return objects;
    }

    /** {@code psql} with the connection arguments and the password in the environment. */
    private List<String> psql(List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(
                tool("psql"),
                "--no-psqlrc",
                "--quiet",
                "--host=" + host(),
                "--port=" + port(),
                "--username=" + username,
                "--dbname=" + database()));
        command.addAll(arguments);
        return command;
    }

    /**
 * Finds the {@code pg_dump}, {@code pg_restore} and {@code psql} that match the server.
 *
 * <p><b>This is not fussiness, it is the whole feature.</b> PostgreSQL refuses to let an older
 * {@code pg_dump} touch a newer server - "aborting because of server version mismatch" - because the
 * archive format is not backwards compatible. On the owner's machine that is the live situation: the
 * server is Postgres.app 18.4 and the {@code pg_dump} first on {@code PATH} is Homebrew 16.15, so a
 * backup built naively on {@code PATH} fails every single time with no output file and a message
 * about versions rather than about backups.
 *
 * <p>So the tools are chosen against the server rather than trusted: read the server's major version
 * over JDBC - the driver is already here, so this costs nothing and cannot itself be mismatched - and
 * take the first client whose major version is new enough. Postgres.app's own bundled tools are
 * checked, because that is where the matching pair usually is.
 *
 * <p>{@code app.backup.pg-tools} overrides the search with a directory, for a server this does not
 * guess at.
 */
private String tool(String name) {
        for (String candidate : candidates(name)) {
            Integer major = majorOf(candidate);
            if (major != null && major >= serverMajor()) {
                return candidate;
            }
        }
        throw new IllegalStateException("No " + name + " new enough for this database was found. The server is "
                + "PostgreSQL " + serverMajor() + " and " + name + " must be the same major version or newer. "
                + "Tried: " + String.join(", ", candidates(name))
                + ". Point app.backup.pg-tools at a directory holding matching tools.");
    }

    /** Where a tool may be, in the order they are worth trying. */
    private List<String> candidates(String name) {
        List<String> found = new ArrayList<>();
        if (!configuredTools.isEmpty()) {
            found.add(Paths.get(configuredTools, name).toString());
        }
        found.add(name);
        for (String directory : List.of(
                "/Applications/Postgres.app/Contents/Versions/latest/bin",
                "/Applications/Postgres.app/Contents/Versions/18/bin",
                "/opt/homebrew/opt/postgresql@18/bin",
                "/opt/homebrew/opt/postgresql@17/bin",
                "/usr/local/opt/postgresql@18/bin",
                "/usr/local/opt/postgresql@17/bin")) {
            Path path = Paths.get(directory, name);
            if (Files.isExecutable(path)) {
                found.add(path.toString());
            }
        }
        return found.stream().distinct().toList();
    }

    /** The major version of one client binary, or null when it cannot be run or has no version. */
    private Integer majorOf(String binary) {
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(binary, "--version"));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Matcher matcher = CLIENT_VERSION.matcher(output);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The server's major version, over JDBC, once per run. */
    private int serverMajor() {
        if (cachedServerMajor != null) {
            return cachedServerMajor;
        }
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, username, password);
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("show server_version_num")) {
            if (!rows.next()) {
                throw new IllegalStateException("The database did not report its version.");
            }
            String versionNumber = rows.getString(1);
            // server_version_num is 180004 for 18.4, not "18.4": the major version is the number with
            // the last four digits taken off, which is why splitting it on a dot would have compared
            // 180004 against 16 and rejected every tool on the machine.
            int numeric = Integer.parseInt(versionNumber.trim());
            cachedServerMajor = numeric / 10_000;
            return cachedServerMajor;
        } catch (java.sql.SQLException | NumberFormatException e) {
            throw new IllegalStateException("Could not read the database version from " + jdbcUrl
                    + ": " + e.getMessage(), e);
        }
    }

    /** Runs a command, throws on failure, and returns the output for the caller that wants it. */
    private String run(List<String> command, String what) {
        String output = execute(command);
        log.debug("{}: {}", what, output.strip());
        return output;
    }

    /**
     * Runs one command and returns its combined output, failing loudly.
     *
     * <p>stderr is merged into stdout on purpose. Reading two streams sequentially deadlocks as soon
     * as the one not being read fills its buffer, and {@code pg_dump} is chatty on stderr.
     */
    private String execute(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // stdin closed: a client tool that decides to ask a question - "force?", "password?" - would
        // otherwise read from the console this process was started from and hang the request forever.
        // With no stdin it fails at once, which is what a server-side action should do.
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        builder.environment().put("PGPASSWORD", password);
        try {
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out after " + TIMEOUT_MINUTES + " minutes trying to "
                    + binaryName(command.get(0)) + ".");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IllegalStateException(binaryName(command.get(0)) + " failed (exit " + exit + "): " + tail(output));
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Could not run " + binaryName(command.get(0)) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + binaryName(command.get(0)) + ".", e);
        }
    }

    /**
     * Turns a requested name into a path inside the backup directory, or refuses.
     *
     * <p>Three checks, because this is the one place a request becomes a filename. The name must look
     * like a backup this service wrote, it must not escape the directory, and the resolved path must
     * still be inside it once symlinks and {@code ..} are followed.
     */
    private Path resolve(String requestedName) {
        if (requestedName == null || !BACKUP_NAME.matcher(requestedName).matches()) {
            throw new IllegalArgumentException("'" + requestedName
                    + "' is not a backup name. A backup is called yyyy-mm-dd-HH-mm-ss.dump.");
        }
        Path resolved = directory.resolve(requestedName).normalize();
        if (!resolved.startsWith(directory) || !resolved.getParent().equals(directory)) {
            throw new IllegalArgumentException("Refusing a backup outside " + directory + ".");
        }
        return resolved;
    }

    /** The timestamp in the file name, spelled out for the admin screen. */
    private String readable(String fileName) {
        Matcher matcher = STAMP_IN_NAME.matcher(fileName);
        if (!matcher.find()) {
            return "";
        }
        return LocalDateTime.parse(matcher.group(1), STAMP).format(READABLE);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove the incomplete dump {}: {}", path, e.getMessage());
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The tool's name, without the whole path, so a message reads "pg_dump failed" not a path. */
    private String binaryName(String binary) {
        int slash = binary.lastIndexOf('/');
        return slash < 0 ? binary : binary.substring(slash + 1);
    }

    /** The last few lines of a failure, which is where PostgreSQL puts the reason. */
    private String tail(String output) {
        String[] lines = output.strip().split("\n");
        int from = Math.max(0, lines.length - 6);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    private String host() {
        return jdbc().group(1);
    }

    private int port() {
        return Integer.parseInt(jdbc().group(2) == null ? "5432" : jdbc().group(2));
    }

    private String database() {
        return jdbc().group(3);
    }

    /**
     * The host, port and database out of the JDBC URL.
     *
     * <p>Parsed rather than configured twice, because a second copy of the connection details is a
     * second thing to forget. It is read on use and not in the constructor, so an unusual URL cannot
     * stop the application from booting over a feature only the admin panel uses.
     */
    private Matcher jdbc() {
        Matcher matcher = JDBC_URL.matcher(jdbcUrl);
        if (!matcher.matches()) {
            throw new IllegalStateException("Cannot work out the database from '" + jdbcUrl
                    + "'. A backup needs a jdbc:postgresql://host:port/database URL.");
        }
        return matcher;
    }
}