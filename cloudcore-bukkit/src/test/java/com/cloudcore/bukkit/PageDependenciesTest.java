package com.cloudcore.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageDependenciesTest {

    @TempDir
    Path pages;

    @Test
    void aPageChangeAffectsOnlyThatPage() throws IOException {
        write("shop.yml", page("shop"));
        write("bank.yml", page("bank"));

        Set<String> affected = PageDependencies.affected(pages, List.of("shop", "bank"),
                Set.of("shop.yml"));

        assertEquals(Set.of("shop"), affected);
    }

    @Test
    void aFragmentChangeAffectsItsDependents() throws IOException {
        write("common/header.yml", "id: header\nelements: []\n");
        write("shop.yml", pageWithIncludes("shop", List.of("common/header.yml")));
        write("bank.yml", page("bank"));

        Set<String> affected = PageDependencies.affected(pages, List.of("shop", "bank"),
                Set.of("common/header.yml"));

        assertEquals(Set.of("shop"), affected);
    }

    @Test
    void nestedIncludesAreFollowed() throws IOException {
        write("common/title.yml", "id: title\nelements: []\n");
        write("common/header.yml", "id: header\nincludes:\n  - title.yml\nelements: []\n");
        write("shop.yml", pageWithIncludes("shop", List.of("common/header.yml")));

        Set<String> affected = PageDependencies.affected(pages, List.of("shop"),
                Set.of("common/title.yml"));

        assertEquals(Set.of("shop"), affected);
    }

    @Test
    void unrelatedFragmentsAffectNothing() throws IOException {
        write("common/header.yml", "id: header\nelements: []\n");
        write("shop.yml", pageWithIncludes("shop", List.of("common/header.yml")));

        assertTrue(PageDependencies.affected(pages, List.of("shop"),
                Set.of("common/footer.yml")).isEmpty());
    }

    @Test
    void aDirectoryPageIsFoundByItsPageYml() throws IOException {
        write("shops/main/page.yml", page("main"));

        Set<String> affected = PageDependencies.affected(pages, List.of("shops/main"),
                Set.of("shops/main/page.yml"));

        assertEquals(Set.of("shops/main"), affected);
    }

    @Test
    void includeListParsingHandlesQuotesAndComments() throws IOException {
        write("shop.yml", """
                id: shop
                includes:
                  - common/header.yml   # 公共头
                  - "common/theme.yml"
                elements: []
                """);

        assertEquals(List.of("common/header.yml", "common/theme.yml"),
                PageDependencies.includesOf(pages.resolve("shop.yml")));
    }

    @Test
    void aPageWithoutIncludesReportsNone() throws IOException {
        write("shop.yml", page("shop"));

        assertTrue(PageDependencies.includesOf(pages.resolve("shop.yml")).isEmpty());
    }

    private String page(String id) {
        return "schema: 1\nid: " + id + "\nsurface: custom_screen\ntitle: \"x\"\n"
                + "size: [100, 100]\nelements: []\n";
    }

    private String pageWithIncludes(String id, List<String> includes) {
        StringBuilder yaml = new StringBuilder("schema: 1\nid: ").append(id)
                .append("\nsurface: custom_screen\ntitle: \"x\"\nsize: [100, 100]\nincludes:\n");
        for (String include : includes) {
            yaml.append("  - ").append(include).append('\n');
        }
        return yaml.append("elements: []\n").toString();
    }

    private void write(String relative, String content) throws IOException {
        Path file = pages.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
