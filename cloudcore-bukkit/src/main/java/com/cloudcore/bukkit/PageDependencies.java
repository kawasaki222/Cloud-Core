package com.cloudcore.bukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which pages a changed file affects.
 *
 * <p>A page is affected when the changed path is the page itself or one of its includes. The
 * include chain is resolved by reading the YAML manifests, so this is a cheap, dependency-free
 * scan: it runs on the watcher callback and only needs the relative include strings, not the
 * full compiled page. A page that fails to parse is treated as affected, which is the safe
 * answer for a broken fragment.
 *
 * <p>Pure Java and deterministic; the page directory is injected so tests can build a tree.
 */
final class PageDependencies {
    private static final int MAX_DEPTH = 8;
    private static final Pattern INCLUDES_BLOCK = Pattern.compile(
            "includes:\\s*\\n(?:\\s*-\\s*[^\\n]*\\n)*");
    private static final Pattern LIST_ITEM = Pattern.compile("-\\s*(.+)");

    private PageDependencies() {
    }

    /**
     * @param pagesRoot   the pages directory
     * @param pageNames   page names as {@link CloudCorePlugin#listPageNames()} reports them
     * @param changed     slash-separated paths below {@code pagesRoot}
     * @return the page names whose files or includes changed
     */
    static Set<String> affected(Path pagesRoot, List<String> pageNames, Set<String> changed) {
        Set<String> affected = new LinkedHashSet<>();
        if (changed.isEmpty()) {
            return affected;
        }
        Set<String> changedNormalised = new LinkedHashSet<>();
        for (String path : changed) {
            changedNormalised.add(path.replace('\\', '/'));
        }
        for (String name : pageNames) {
            Path pageFile = pageFile(pagesRoot, name);
            if (pageFile == null) {
                // A page that disappeared is affected: the reload must report it.
                continue;
            }
            String pageRelative = relative(pagesRoot, pageFile);
            if (pageRelative != null && changedNormalised.contains(pageRelative)) {
                affected.add(name);
                continue;
            }
            if (dependsOn(pagesRoot, pageFile, changedNormalised)) {
                affected.add(name);
            }
        }
        // A changed page file that no longer parses still needs a reload attempt.
        for (String path : changedNormalised) {
            String name = nameOf(path);
            if (name != null && pageNames.contains(name)) {
                affected.add(name);
            }
        }
        return affected;
    }

    /** Depth-first include walk; returns true when any include was touched. */
    private static boolean dependsOn(Path root, Path pageFile, Set<String> changed) {
        Set<String> visited = new LinkedHashSet<>();
        return walk(root, pageFile, changed, visited, 0);
    }

    private static boolean walk(Path root, Path file, Set<String> changed, Set<String> visited,
            int depth) {
        if (depth > MAX_DEPTH || !visited.add(relative(root, file))) {
            return false;
        }
        List<String> includes = includesOf(file);
        if (includes.isEmpty()) {
            return false;
        }
        Path baseDir = file.getParent();
        for (String include : includes) {
            Path included = baseDir == null ? null : baseDir.resolve(include).normalize();
            if (included == null || !included.toAbsolutePath().normalize().startsWith(
                    root.toAbsolutePath().normalize())) {
                continue;
            }
            String relative = relative(root, included);
            if (relative != null && changed.contains(relative)) {
                return true;
            }
            if (walk(root, included, changed, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code includes:} list without a YAML parser: the block is a list of scalars,
     * which is all the dependency scan needs. Quotes and a trailing comment are stripped.
     */
    static List<String> includesOf(Path file) {
        List<String> includes = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return includes;
        }
        try {
            String text = Files.readString(file);
            Matcher block = INCLUDES_BLOCK.matcher(text);
            if (!block.find()) {
                return includes;
            }
            Matcher item = LIST_ITEM.matcher(block.group());
            while (item.find()) {
                String value = item.group(1).trim();
                int comment = value.indexOf('#');
                if (comment >= 0) {
                    value = value.substring(0, comment).trim();
                }
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                } else if (value.length() >= 2 && value.startsWith("'")
                        && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) {
                    includes.add(value);
                }
            }
        } catch (java.io.IOException exception) {
            // An unreadable page is treated as depending on nothing; the reload reports it.
            return includes;
        }
        return includes;
    }

    /** The page file for a name, mirroring {@code CloudCorePlugin#resolvePageFile}. */
    private static Path pageFile(Path root, String name) {
        if (root == null || name == null) {
            return null;
        }
        if (name.isEmpty()) {
            for (String candidate : List.of("page.yml", "page.yaml")) {
                Path file = root.resolve(candidate);
                if (Files.isRegularFile(file)) {
                    return file;
                }
            }
            return null;
        }
        Path directory = root.resolve(name);
        for (String candidate : List.of("page.yml", "page.yaml")) {
            Path file = directory.resolve(candidate);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        for (String extension : List.of(".yml", ".yaml")) {
            Path file = root.resolve(name + extension);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** Page name for a changed path, or null when it is a fragment rather than a page. */
    private static String nameOf(String relativePath) {
        for (String suffix : List.of("/page.yml", "/page.yaml")) {
            if (relativePath.endsWith(suffix)) {
                return relativePath.substring(0, relativePath.length() - suffix.length());
            }
        }
        if (relativePath.startsWith("page.yml") || relativePath.startsWith("page.yaml")) {
            return "";
        }
        if (!relativePath.contains("/") && (relativePath.endsWith(".yml")
                || relativePath.endsWith(".yaml"))) {
            return relativePath.substring(0, relativePath.lastIndexOf('.'));
        }
        return null;
    }

    private static String relative(Path root, Path file) {
        if (root == null || file == null) {
            return null;
        }
        Path normalised = file.toAbsolutePath().normalize();
        Path rootNormalised = root.toAbsolutePath().normalize();
        return normalised.startsWith(rootNormalised)
                ? rootNormalised.relativize(normalised).toString().replace('\\', '/') : null;
    }
}
