package org.tifo.footballmanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the production source lives — <b>derived, not written down</b>.
 *
 * <p><b>Why this class exists.</b> A number of guards in this suite read the production source
 * instead of calling it, because the thing they guard is a <em>shape</em> — one place constructs a
 * {@code Match}, one place enters the engine, no {@code @Transactional} on a private method, no dead
 * helper left behind. Those guards used to name the source path as a literal:
 *
 * <pre>{@code Path.of("src/main/java/org/example/footballmanager/newLogic/sim")}</pre>
 *
 * <p>When the package was renamed from {@code org.example} to {@code org.tifo}, every one of those
 * literals went stale at once, and they did not fail politely: the guards threw
 * {@code NoSuchFileException} and turned red, which is the good case. <b>But a guard that reads a
 * path it was told, rather than one it resolved, is a guard that a rename can blind</b> — and the
 * moment one of them is given a path that exists but points somewhere else, it stops guarding
 * anything at all.
 *
 * <p>So the paths are resolved from the classes themselves. A class literal is checked by the
 * compiler, and the package comes with it, so a rename moves the path with the code and the guard
 * keeps guarding.
 *
 * <p>The one thing that cannot be resolved from a class is a file that <b>no longer exists</b> —
 * which is exactly what the "it is gone" guards are about. Those take the package from an anchor
 * class and the file name as a string.
 */
public final class ProductionSource {

    /** The production source root. */
    private static final Path MAIN = Path.of("src", "main", "java");

    /** The test source root. */
    private static final Path TEST = Path.of("src", "test", "java");

    /**
     * The application's own package, taken from the application's own class.
     *
     * <p>It is the anchor for {@link #gameDir(Class)}: this repository holds several games under
     * one application, and a structural scan must see <b>one</b> of them.
     */
    private static final Path APP_ROOT = MAIN.resolve(
            org.tifo.SportsManagerApplication.class.getPackageName().replace('.', '/'));

    private ProductionSource() {
    }

    /**
     * The production source root — the whole tree.
     *
     * <p>For a guard that asks whether a name appears <b>anywhere</b> in the product. It is the whole
     * tree and not one game's package, because a caller may live in a shared package.
     */
    public static Path root() {
        return MAIN;
    }

    /**
     * The source file of a production class.
     *
     * @param clazz the class; its package is the class's package, so the path moves with a rename
     */
    public static Path file(Class<?> clazz) {
        return file(clazz, clazz.getSimpleName());
    }

    /**
     * A file of the front end — the static resources the application serves.
     *
     * <p><b>The same rule as the Java sources, for the same reason.</b> A guard that reads a front-end
     * file has to find it rather than be told where it is: a literal path is a path that goes stale
     * the day the file is moved, and a stale path in a guard is either an exception or — worse — a
     * guard that quietly reads nothing and passes.
     *
     * @param relative the path under {@code resources/static}, with forward slashes
     */
    public static Path staticFile(String relative) {
        Path resolved = staticRoot().resolve(relative.replace('/', java.io.File.separatorChar));
        if (!java.nio.file.Files.isRegularFile(resolved)) {
            throw new IllegalStateException(
                    "no such static file: " + relative + " (looked in " + staticRoot() + ")");
        }
        return resolved;
    }

    /** The application's static resources — the front end. */
    public static Path staticRoot() {
        return MAIN.getParent().resolve("resources").resolve("static");
    }

    /**
     * The source file named {@code simpleName} in the package of {@code anchor}.
     *
     * <p>For a class that <b>no longer exists</b>: the anchor carries the package, the name carries
     * the file. The anchor's own package is the one the deleted class lived in.
     */
    public static Path file(Class<?> anchor, String simpleName) {
        return dir(anchor).resolve(simpleName + ".java");
    }

    /**
     * The test source file named {@code simpleName} in the package of {@code anchor}.
     */
    public static Path testFile(Class<?> anchor, String simpleName) {
        return TEST.resolve(anchor.getPackageName().replace('.', '/')).resolve(simpleName + ".java");
    }

    /** The source directory of the package {@code anchor} lives in. */
    public static Path dir(Class<?> anchor) {
        return MAIN.resolve(anchor.getPackageName().replace('.', '/'));
    }

    /**
     * The source directory of <b>this game</b> — the package directory that sits directly under the
     * application's root.
     *
     * <p>Several games share this repository and one application
     * ({@code americanfootballmanager}, {@code basketballmanager}, {@code footballtextmanager} beside
     * the football manager). They have their own engines and are deliberately outside a structural
     * scan of this one: a scan that included them would report their code as a violation of this
     * game's rules.
     */
    public static Path gameDir(Class<?> anchor) {
        Path dir = dir(anchor);
        while (dir != null && dir.getParent() != null) {
            if (dir.getParent().equals(APP_ROOT)) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "the package of " + anchor.getName() + " is not inside the application's package "
                        + APP_ROOT);
    }

    /** Every production {@code .java} file under {@code dir}, recursively, in a stable order. */
    public static List<Path> filesUnder(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
