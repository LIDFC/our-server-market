package site.vinoff.market.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The search side of the catalogue. Deliberately tested without a server: these are the parts that decide whether a
 * player typing "алмаз" finds anything, and they are plain string work.
 */
class CatalogueTest {

    private static Catalogue.Match match(String id, String query) {
        return Catalogue.match(id, Catalogue.russian(id), Catalogue.normalise(query));
    }

    @Test
    @DisplayName("a query is read the same however it is typed")
    void normalising() {
        assertEquals("diamond pickaxe", Catalogue.normalise("  Diamond_Pickaxe "));
        assertEquals("diamond pickaxe", Catalogue.normalise("minecraft:diamond_pickaxe"));
        assertEquals("diamond pickaxe", Catalogue.normalise("diamond-pickaxe"));
        assertEquals("зеленый", Catalogue.normalise("Зелёный"), "ё and е are the same letter to a search box");
        assertEquals("", Catalogue.normalise(null));
    }

    @Test
    @DisplayName("the English id always works")
    void byId() {
        assertEquals(Catalogue.Match.STARTS, match("diamond_pickaxe", "diamond"));
        assertEquals(Catalogue.Match.STARTS, match("diamond_pickaxe", "minecraft:diamond_pickaxe"));
        assertEquals(Catalogue.Match.CONTAINS, match("diamond_pickaxe", "pickaxe"));
        assertEquals(Catalogue.Match.NONE, match("diamond_pickaxe", "elytra"));
    }

    @Test
    @DisplayName("the things people actually trade are searchable in Russian")
    void byRussianWord() {
        assertEquals(Catalogue.Match.STARTS, match("diamond", "алмаз"));
        assertEquals(Catalogue.Match.STARTS, match("emerald", "изумруд"));
        assertEquals(Catalogue.Match.STARTS, match("elytra", "элитры"));
        assertEquals(Catalogue.Match.STARTS, match("totem_of_undying", "тотем"));
        assertEquals(Catalogue.Match.STARTS, match("netherite_ingot", "незерит"));
        assertEquals(Catalogue.Match.CONTAINS, match("elytra", "крылья"), "a second word still finds it");
        assertEquals(Catalogue.Match.CONTAINS, match("netherite_ingot", "слиток"));
    }

    @Test
    @DisplayName("colours and wood types are crossed with categories instead of listed one by one")
    void madeUpNames() {
        assertEquals("шерсть красный", Catalogue.russian("red_wool"));
        assertEquals("бетонная пыль голубой", Catalogue.russian("light_blue_concrete_powder"));
        assertEquals("глазурованная терракота чёрный", Catalogue.russian("black_glazed_terracotta"));
        assertEquals("доски тёмный дуб", Catalogue.russian("dark_oak_planks"));
        assertEquals("кирка железный", Catalogue.russian("iron_pickaxe"));
        assertEquals("бревно дубовый дуб очищенное", Catalogue.russian("stripped_oak_log"));

        assertEquals(Catalogue.Match.STARTS, match("red_wool", "шерсть"));
        assertEquals(Catalogue.Match.CONTAINS, match("red_wool", "красный"));
        assertEquals(Catalogue.Match.STARTS, match("lime_concrete", "бетон"));
        assertEquals(Catalogue.Match.STARTS, match("deepslate_gold_ore", "руда"), "an unknown first half still leaves the category");
    }

    @Test
    @DisplayName("a longer category wins, so concrete powder is never concrete")
    void longestCategoryFirst() {
        assertEquals("бетонная пыль белый", Catalogue.russian("white_concrete_powder"));
        assertEquals("бетон белый", Catalogue.russian("white_concrete"));
        assertEquals("стеклянная панель белый", Catalogue.russian("white_stained_glass_pane"));
        assertEquals("стекло белый", Catalogue.russian("white_stained_glass"));
        assertEquals("подвесная табличка дубовый дуб", Catalogue.russian("oak_hanging_sign"));
        assertEquals("табличка дубовый дуб", Catalogue.russian("oak_sign"));
    }

    @Test
    @DisplayName("an item nobody searches for in Russian simply has no Russian name")
    void unnamed() {
        assertNull(Catalogue.russian("sculk_catalyst"));
        assertNotNull(Catalogue.russian("diamond"));
    }

    @Test
    @DisplayName("an empty query means everything, not nothing")
    void emptyQuery() {
        assertTrue(Catalogue.normalise("   ").isEmpty());
    }
}
