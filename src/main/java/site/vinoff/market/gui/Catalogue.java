package site.vinoff.market.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * Every item in the game, searchable.
 *
 * <p>A player asking for something in return is asking for what they do <em>not</em> have, so the wish list cannot be
 * built out of their own inventory. This is the list they pick from instead.
 *
 * <p>Names shown in a window come from the client, so every player sees them in their own language. Search cannot work
 * that way — the server has no idea what the client calls anything — so a query is matched against the item id
 * ({@code diamond_pickaxe}) and, for the things people actually trade, against a Russian word for it. The Russian side
 * is built out of three small tables rather than one enormous one: a word per item where it matters, plus colour and
 * wood prefixes crossed with category suffixes, which covers the hundreds of dyed and wooden variants for free.
 */
public final class Catalogue {

    private Catalogue() {}

    private static List<Material> all;

    /** Every material that can exist as an item, cached: the list never changes while the server runs. */
    public static synchronized List<Material> items() {
        if (all == null) {
            List<Material> materials = new ArrayList<>();
            for (Material material : Material.values()) {
                if (!material.isLegacy() && material.isItem() && !material.isAir()) {
                    materials.add(material);
                }
            }
            materials.sort(Comparator.comparing(Material::name));
            all = List.copyOf(materials);
        }
        return all;
    }

    /**
     * Items matching a query, best first. An empty query is everything, which is a perfectly good way to browse.
     */
    public static List<Material> search(String query) {
        String needle = normalise(query);
        if (needle.isEmpty()) {
            return items();
        }
        List<Material> starts = new ArrayList<>();
        List<Material> contains = new ArrayList<>();
        for (Material material : items()) {
            String id = id(material);
            String russian = russian(id);
            Match match = match(id, russian, needle);
            if (match == Match.STARTS) {
                starts.add(material);
            } else if (match == Match.CONTAINS) {
                contains.add(material);
            }
        }
        starts.addAll(contains);
        return starts;
    }

    public static String id(Material material) {
        return material.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** How well an item answers a query. Package-private so it can be tested without a server. */
    static Match match(String id, String russian, String needle) {
        String haystack = normalise(id);
        if (haystack.startsWith(needle)) {
            return Match.STARTS;
        }
        if (russian != null) {
            String russianHaystack = normalise(russian);
            if (russianHaystack.startsWith(needle)) {
                return Match.STARTS;
            }
            if (russianHaystack.contains(needle)) {
                return Match.CONTAINS;
            }
        }
        return haystack.contains(needle) ? Match.CONTAINS : Match.NONE;
    }

    enum Match {
        STARTS,
        CONTAINS,
        NONE
    }

    /**
     * Lowercases, drops the namespace, and treats underscores, hyphens and the letter ё as nothing special, so
     * "Алмазная Кирка", "diamond-pickaxe" and "minecraft:diamond_pickaxe" all lead to the same place.
     */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        return value.replace('_', ' ').replace('-', ' ').replace('ё', 'е').trim();
    }

    /** A Russian word for an item id, or null when nobody is likely to search for it in Russian. */
    static String russian(String id) {
        String exact = EXACT.get(id);
        if (exact != null) {
            return exact;
        }
        String head = id;
        String extra = "";
        if (head.startsWith("stripped_")) {
            head = head.substring("stripped_".length());
            extra = " очищенное";
        }
        for (Map.Entry<String, String> suffix : SUFFIXES.entrySet()) {
            if (!head.endsWith(suffix.getKey())) {
                continue;
            }
            String prefix = head.substring(0, head.length() - suffix.getKey().length());
            String prefixWord = PREFIXES.get(prefix);
            // an unknown first half still leaves a usable word: searching "руда" should find every ore
            return prefixWord == null ? suffix.getValue() + extra : suffix.getValue() + " " + prefixWord + extra;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Colours and wood types, used as the first half of a made-up name. */
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    /** Categories, used as the second half. Longest first, so concrete powder never looks like concrete. */
    private static final Map<String, String> SUFFIXES = new LinkedHashMap<>();
    /** Items worth naming one by one: the things people actually put on a marketplace. */
    private static final Map<String, String> EXACT = new LinkedHashMap<>();

    private static void prefix(String id, String word) {
        PREFIXES.put(id, word);
    }

    private static void suffix(String id, String word) {
        SUFFIXES.put(id, word);
    }

    private static void exact(String id, String word) {
        EXACT.put(id, word);
    }

    static {
        prefix("white", "белый");
        prefix("orange", "оранжевый");
        prefix("magenta", "пурпурный");
        prefix("light_blue", "голубой");
        prefix("yellow", "жёлтый");
        prefix("lime", "лаймовый");
        prefix("pink", "розовый");
        prefix("gray", "серый");
        prefix("light_gray", "светло-серый");
        prefix("cyan", "бирюзовый");
        prefix("purple", "фиолетовый");
        prefix("blue", "синий");
        prefix("brown", "коричневый");
        prefix("green", "зелёный");
        prefix("red", "красный");
        prefix("black", "чёрный");

        prefix("oak", "дубовый дуб");
        prefix("spruce", "еловый ель");
        prefix("birch", "берёзовый берёза");
        prefix("jungle", "тропический джунгли");
        prefix("acacia", "акациевый акация");
        prefix("dark_oak", "тёмный дуб");
        prefix("pale_oak", "бледный дуб");
        prefix("mangrove", "мангровый мангр");
        prefix("cherry", "вишнёвый вишня");
        prefix("bamboo", "бамбуковый бамбук");
        prefix("crimson", "багровый");
        prefix("warped", "искажённый");

        prefix("iron", "железный");
        prefix("gold", "золотой");
        prefix("golden", "золотой");
        prefix("diamond", "алмазный");
        prefix("netherite", "незеритовый");
        prefix("stone", "каменный");
        prefix("wooden", "деревянный");
        prefix("leather", "кожаный");
        prefix("chainmail", "кольчужный");
        prefix("copper", "медный");

        // longest first: the map is walked in order
        suffix("_concrete_powder", "бетонная пыль");
        suffix("_glazed_terracotta", "глазурованная терракота");
        suffix("_stained_glass_pane", "стеклянная панель");
        suffix("_stained_glass", "стекло");
        suffix("_pressure_plate", "нажимная плита");
        suffix("_shulker_box", "шалкеровый ящик");
        suffix("_hanging_sign", "подвесная табличка");
        suffix("_glass_pane", "стеклянная панель");
        suffix("_fence_gate", "калитка");
        suffix("_chest_boat", "лодка с сундуком");
        suffix("_trapdoor", "люк");
        suffix("_terracotta", "терракота");
        suffix("_concrete", "бетон");
        suffix("_leggings", "поножи штаны");
        suffix("_chestplate", "нагрудник кираса");
        suffix("_helmet", "шлем");
        suffix("_boots", "ботинки сапоги");
        suffix("_pickaxe", "кирка");
        suffix("_shovel", "лопата");
        suffix("_sword", "меч");
        suffix("_axe", "топор");
        suffix("_hoe", "мотыга");
        suffix("_planks", "доски");
        suffix("_stairs", "ступеньки лестница");
        suffix("_button", "кнопка");
        suffix("_carpet", "ковёр");
        suffix("_banner", "флаг знамя");
        suffix("_candle", "свеча");
        suffix("_sapling", "саженец");
        suffix("_leaves", "листва листья");
        suffix("_fence", "забор");
        suffix("_slab", "плита");
        suffix("_door", "дверь");
        suffix("_sign", "табличка");
        suffix("_boat", "лодка");
        suffix("_wool", "шерсть");
        suffix("_glass", "стекло");
        suffix("_log", "бревно");
        suffix("_wood", "древесина");
        suffix("_dye", "краситель");
        suffix("_bed", "кровать");
        suffix("_ore", "руда");
        suffix("_ingot", "слиток");
        suffix("_nugget", "самородок");
        suffix("_block", "блок");

        exact("diamond", "алмаз");
        exact("emerald", "изумруд");
        exact("gold_ingot", "золотой слиток золото");
        exact("iron_ingot", "железный слиток железо");
        exact("copper_ingot", "медный слиток медь");
        exact("netherite_ingot", "незеритовый слиток незерит");
        exact("netherite_scrap", "незеритовый обломок");
        exact("ancient_debris", "древние обломки");
        exact("coal", "уголь");
        exact("charcoal", "древесный уголь");
        exact("redstone", "редстоун красная пыль");
        exact("lapis_lazuli", "лазурит");
        exact("quartz", "кварц");
        exact("amethyst_shard", "аметист");
        exact("flint", "кремень");
        exact("gunpowder", "порох");
        exact("bone", "кость");
        exact("bone_meal", "костная мука");
        exact("string", "нить верёвка");
        exact("leather", "кожа");
        exact("feather", "перо");
        exact("slime_ball", "слизь");
        exact("ender_pearl", "жемчуг края эндер");
        exact("ender_eye", "око края");
        exact("blaze_rod", "огненный стержень");
        exact("blaze_powder", "огненный порошок");
        exact("ghast_tear", "слеза гаста");
        exact("magma_cream", "магмовый крем");
        exact("nether_star", "звезда нижнего мира");
        exact("totem_of_undying", "тотем бессмертия");
        exact("elytra", "элитры крылья");
        exact("shulker_shell", "панцирь шалкера");
        exact("phantom_membrane", "мембрана фантома");
        exact("prismarine_shard", "призмариновый осколок");
        exact("prismarine_crystals", "призмариновые кристаллы");
        exact("nautilus_shell", "раковина наутилуса");
        exact("heart_of_the_sea", "сердце моря");
        exact("trident", "трезубец");
        exact("bow", "лук");
        exact("crossbow", "арбалет");
        exact("arrow", "стрела");
        exact("spectral_arrow", "спектральная стрела");
        exact("shield", "щит");
        exact("fishing_rod", "удочка");
        exact("flint_and_steel", "огниво");
        exact("shears", "ножницы");
        exact("bucket", "ведро");
        exact("water_bucket", "ведро воды");
        exact("lava_bucket", "ведро лавы");
        exact("milk_bucket", "ведро молока");
        exact("saddle", "седло");
        exact("name_tag", "бирка");
        exact("lead", "поводок");
        exact("compass", "компас");
        exact("clock", "часы");
        exact("spyglass", "подзорная труба");
        exact("recovery_compass", "компас возвращения");
        exact("map", "карта");
        exact("filled_map", "заполненная карта");
        exact("book", "книга");
        exact("writable_book", "книга с пером");
        exact("written_book", "подписанная книга");
        exact("enchanted_book", "зачарованная книга");
        exact("paper", "бумага");
        exact("experience_bottle", "бутылёк опыта");
        exact("potion", "зелье");
        exact("splash_potion", "взрывное зелье");
        exact("lingering_potion", "оседающее зелье");
        exact("glass_bottle", "стеклянная бутылка");
        exact("brewing_stand", "варочная стойка");
        exact("cauldron", "котёл");
        exact("enchanting_table", "стол зачарования");
        exact("anvil", "наковальня");
        exact("grindstone", "точило");
        exact("smithing_table", "кузнечный стол");
        exact("crafting_table", "верстак");
        exact("furnace", "печь");
        exact("blast_furnace", "плавильная печь");
        exact("smoker", "коптильня");
        exact("chest", "сундук");
        exact("ender_chest", "сундук края");
        exact("barrel", "бочка");
        exact("hopper", "воронка");
        exact("dropper", "выбрасыватель");
        exact("dispenser", "раздатчик");
        exact("observer", "наблюдатель");
        exact("piston", "поршень");
        exact("sticky_piston", "липкий поршень");
        exact("comparator", "компаратор");
        exact("repeater", "повторитель");
        exact("lever", "рычаг");
        exact("torch", "факел");
        exact("redstone_torch", "красный факел");
        exact("soul_torch", "душевный факел");
        exact("lantern", "фонарь");
        exact("soul_lantern", "душевный фонарь");
        exact("glowstone", "светокамень");
        exact("sea_lantern", "морской фонарь");
        exact("beacon", "маяк");
        exact("conduit", "проводник");
        exact("tnt", "динамит тнт");
        exact("obsidian", "обсидиан");
        exact("crying_obsidian", "плачущий обсидиан");
        exact("bedrock", "коренная порода");
        exact("stone", "камень");
        exact("cobblestone", "булыжник");
        exact("deepslate", "глубинный сланец");
        exact("granite", "гранит");
        exact("diorite", "диорит");
        exact("andesite", "андезит");
        exact("calcite", "кальцит");
        exact("tuff", "туф");
        exact("dirt", "земля");
        exact("grass_block", "трава дёрн");
        exact("sand", "песок");
        exact("red_sand", "красный песок");
        exact("gravel", "гравий");
        exact("clay", "глина");
        exact("clay_ball", "глиняный шарик");
        exact("brick", "кирпич");
        exact("bricks", "кирпичи");
        exact("nether_bricks", "незерские кирпичи");
        exact("netherrack", "незерак");
        exact("soul_sand", "песок душ");
        exact("soul_soil", "почва душ");
        exact("end_stone", "камень края");
        exact("purpur_block", "пурпурный блок");
        exact("ice", "лёд");
        exact("packed_ice", "плотный лёд");
        exact("blue_ice", "голубой лёд");
        exact("snowball", "снежок");
        exact("snow_block", "снежный блок");
        exact("hay_block", "сноп сена");
        exact("sponge", "губка");
        exact("scaffolding", "леса");
        exact("ladder", "лестница");
        exact("rail", "рельсы");
        exact("powered_rail", "электрические рельсы");
        exact("detector_rail", "нажимные рельсы");
        exact("activator_rail", "активирующие рельсы");
        exact("minecart", "вагонетка");
        exact("chest_minecart", "вагонетка с сундуком");
        exact("hopper_minecart", "вагонетка с воронкой");
        exact("bread", "хлеб");
        exact("wheat", "пшеница");
        exact("wheat_seeds", "семена пшеницы");
        exact("carrot", "морковь");
        exact("potato", "картофель");
        exact("baked_potato", "печёный картофель");
        exact("beetroot", "свёкла");
        exact("apple", "яблоко");
        exact("golden_apple", "золотое яблоко");
        exact("enchanted_golden_apple", "зачарованное золотое яблоко");
        exact("golden_carrot", "золотая морковь");
        exact("melon_slice", "ломтик арбуза");
        exact("pumpkin", "тыква");
        exact("carved_pumpkin", "вырезанная тыква");
        exact("sugar", "сахар");
        exact("sugar_cane", "сахарный тростник");
        exact("cocoa_beans", "какао бобы");
        exact("cookie", "печенье");
        exact("cake", "торт");
        exact("pumpkin_pie", "тыквенный пирог");
        exact("beef", "сырая говядина");
        exact("cooked_beef", "стейк говядина");
        exact("porkchop", "сырая свинина");
        exact("cooked_porkchop", "жареная свинина");
        exact("chicken", "сырая курица");
        exact("cooked_chicken", "жареная курица");
        exact("mutton", "баранина");
        exact("cooked_mutton", "жареная баранина");
        exact("rabbit", "крольчатина");
        exact("cod", "треска");
        exact("salmon", "лосось");
        exact("cooked_cod", "жареная треска");
        exact("cooked_salmon", "жареный лосось");
        exact("tropical_fish", "тропическая рыба");
        exact("pufferfish", "иглобрюх");
        exact("rotten_flesh", "гнилая плоть");
        exact("spider_eye", "паучий глаз");
        exact("honey_bottle", "бутылочка мёда");
        exact("honeycomb", "соты");
        exact("bamboo", "бамбук");
        exact("kelp", "ламинария");
        exact("nether_wart", "адский нарост");
        exact("chorus_fruit", "плод хоруса");
        exact("glow_ink_sac", "светящийся мешок чернил");
        exact("ink_sac", "мешок чернил");
        exact("firework_rocket", "фейерверк ракета");
        exact("armor_stand", "стойка для брони");
        exact("item_frame", "рамка");
        exact("glow_item_frame", "светящаяся рамка");
        exact("painting", "картина");
        exact("flower_pot", "горшок");
        exact("jukebox", "проигрыватель");
        exact("note_block", "нотный блок");
        exact("bell", "колокол");
        exact("campfire", "костёр");
        exact("soul_campfire", "душевный костёр");
        exact("lodestone", "магнетит");
        exact("respawn_anchor", "якорь возрождения");
        exact("dragon_egg", "яйцо дракона");
        exact("dragon_breath", "дыхание дракона");
        exact("end_crystal", "кристалл края");
        exact("turtle_helmet", "черепаший панцирь шлем");
        exact("scute", "щиток черепахи");
        exact("mace", "булава");
        exact("wind_charge", "заряд ветра");
        exact("breeze_rod", "стержень бриза");
        exact("heavy_core", "тяжёлое ядро");
        exact("trial_key", "испытательный ключ");
        exact("ominous_trial_key", "зловещий ключ");
        exact("echo_shard", "осколок эха");
        exact("disc_fragment_5", "фрагмент пластинки");
        exact("goat_horn", "козий рог");
        exact("brush", "кисть");
        exact("sniffer_egg", "яйцо нюхача");
        exact("torchflower_seeds", "семена факелоцвета");
        exact("pitcher_pod", "семена кувшинки");
        exact("suspicious_stew", "подозрительный суп");
        exact("mushroom_stew", "грибной суп");
        exact("rabbit_stew", "суп из кролика");
        exact("beetroot_soup", "свекольный суп");
        exact("bundle", "мешок");
        exact("wolf_armor", "броня для волка");
        exact("glass", "стекло");
        exact("glass_pane", "стеклянная панель");
    }
}
