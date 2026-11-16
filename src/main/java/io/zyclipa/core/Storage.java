package io.zyclipa.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Moving Zyclipa's folders (clips, sounds) when the person picks a new location. */
public final class Storage {
    private Storage() { }

    /** True when {@code inner} is {@code outer} or inside it (moving a folder into itself is refused). */
    public static boolean inside(Path inner, Path outer) {
        return inner.toAbsolutePath().normalize().startsWith(outer.toAbsolutePath().normalize());
    }

    /**
     * Moves what's in {@code from} into {@code to} (created when missing). Items that already exist in {@code to} are
     * left where they are and counted. Same drive: a rename; another drive: copy, then delete the original.
     * Returns how many items couldn't be moved.
     */
    public static int moveContents(Path from, Path to, Predicate<Path> which) throws IOException {
        if (!Files.isDirectory(from)) return 0;
        if (inside(to, from) || inside(from, to)) throw new IOException("The new folder can't be inside the old one (or the other way round).");
        Files.createDirectories(to);
        int skipped = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(from)) {
            for (Path p : ds) {
                if (!which.test(p)) continue;
                Path dst = to.resolve(p.getFileName().toString());
                if (Files.exists(dst)) { skipped++; continue; }
                try {
                    Files.move(p, dst);
                } catch (IOException crossDrive) {
                    copyTree(p, dst);
                    deleteTree(p);
                }
            }
        }
        return skipped;
    }

    static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
