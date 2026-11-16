package com.canefarm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public class CaneIslands extends JavaPlugin implements Listener {

    // ------------------------------------------------------------------ constants
    static final String WORLD_NAME = "cane_islands";
    static final int MIN_Y = 60, MAX_Y = 80;           // vertical scan range for farms
    static final int MAX_CANE_HEIGHT = 2;               // sugar cane never grows taller than this (planted cane = 1)
    static final long SIM_TICKS = 100L;                 // how often the Auto Sell simulation settles (5 s)
    static final List<int[]> ORDER = new ArrayList<>(); // order extra chunks are added around the start chunk
    // default rank tiers (day needed, LuckPerms group, tab/chat tag, scoreboard name)
    static final int[] RANK_DAYS = {50, 100, 150, 200};
    static final String[] RANK_GROUPS = {"farmer", "farmer-red", "farmer-purple", "farmer-plus"};
    static final String[] RANK_PREFIXES = {
            "&8[&a\u2618 Farmer&8] ",
            "&8[&c\u2740 Harvester&8] ",
            "&8[&5\u2726 &5&lCultivator&8] ",
            "&8[&d&l\u2605 &5&lCane Baron &d&l\u2605&8] "
    };
    static final String[] RANK_NAMES = {"&aFarmer", "&cHarvester", "&5Cultivator", "&d&lCane Baron"};

    static {
        for (int r = 1; r <= 2; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) ORDER.add(new int[]{dx, dz});
    }

    // ------------------------------------------------------------------ data classes
    static class PD {
        final UUID id;
        String name = "Unknown";
        int slot, extra, day = 1, bestDay = 0, timeLeft = 1800, rewardGrowth, rewardTime;
        int priceDrop;              // each rebirth makes every shop item one price step (/multiplier) cheaper
        int runBest = 1;            // highest day reached since the last rebirth / reset (used for rebirth eligibility)
        int rewardDrop, rewardSell; // free Bonus Drops / Coin Boost levels earned from daily bonuses
        int rankTier;               // highest rank tier given so far (0 = none, else index + 1 in tiers)
        long submitted, totalCane;
        int rebirths;
        long lastRebirth;           // ms timestamp of the last prestige (0 = never)
        int fastStreak;             // prestiges in a row that came faster than the fast-prestige limit
        boolean completed, flyOwned, flyOn, autoDayOn, autoRebirth;
        boolean breakerGiven; // the free Line Breaker has been handed out once
        int timeMode; // 0 = normal cycle, 1 = always day, 2 = always night
        final Set<String> off = new HashSet<>(); // options the player switched OFF
        final Set<UUID> trusted = new HashSet<>(); // players allowed to build on this island
        double coins;
        long lastSim;               // ms timestamp of the last Auto Sell settle (0 = no Auto Sell chest placed)
        double carry;               // fractional cane the simulation has earned but not paid out yet
        final Map<String, Integer> bought = new HashMap<>();
        final List<Integer> pending = new ArrayList<>();

        PD(UUID id) {
            this.id = id;
        }

        boolean on(String k) {
            return !off.contains(k);
        }

        int lvl(String k) {
            return bought.getOrDefault(k, 0);
        }
    }

    static class Special {
        final String type;
        final int x, y, z;
        final UUID owner;

        Special(String type, int x, int y, int z, UUID owner) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
        }

        String key() {
            return x + "," + y + "," + z;
        }

        String ser() {
            return type + "|" + x + "|" + y + "|" + z + "|" + owner;
        }

        static Special parse(String s) {
            String[] p = s.split("\\|");
            if (p.length < 5) return null;
            try {
                return new Special(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), UUID.fromString(p[4]));
            } catch (Exception e) {
                return null;
            }
        }
    }

    static class Tier {
        final int day, weight;
        final String group, prefix, name;

        Tier(int day, String group, String prefix, int weight, String name) {
            this.day = day;
            this.group = group;
            this.prefix = prefix;
            this.weight = weight;
            this.name = name;
        }
    }

    static class ShopItem {
        final String id, name;
        final Material icon;
        final double price;
        final int max;
        final String[] desc;
        final boolean glow;
        final BiPredicate<Player, PD> apply;

        ShopItem(String id, Material icon, String name, double price, int max, String[] desc, boolean glow, BiPredicate<Player, PD> apply) {
            this.id = id;
            this.icon = icon;
            this.name = name;
            this.price = price;
            this.max = max;
            this.desc = desc;
            this.glow = glow;
            this.apply = apply;
        }
    }

    static class ShopHolder implements InventoryHolder {
        final int page;
        Inventory inv;

        ShopHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class Opt {
        final String id, name, desc;
        final Material icon;

        Opt(String id, String name, Material icon, String desc) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.desc = desc;
        }
    }

    static class ChunksHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class CommandsHolder implements InventoryHolder {
        final List<String> names = new ArrayList<>(); // command name for each slot
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** The Infinite Chest menu: one icon per stored item type (any item, unlimited amount). */
    static class InfHolder implements InventoryHolder {
        final String key;                              // the chest's block key (x,y,z)
        int page;
        final List<ItemStack> keys = new ArrayList<>(); // item type shown in each slot of the current page
        Inventory inv;

        InfHolder(String key, int page) {
            this.key = key;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class OptionsHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class Rw {
        final String desc;
        final BiConsumer<Player, PD> act;

        Rw(String desc, BiConsumer<Player, PD> act) {
            this.desc = desc;
            this.act = act;
        }
    }

    static class VoidGen extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World w, Random r) {
            return new Location(w, 8.5, 65, 8.5);
        }
    }

    static class PlainsBiomes extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo wi, int x, int y, int z) {
            return Biome.PLAINS;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo wi) {
            return List.of(Biome.PLAINS);
        }
    }

    // ------------------------------------------------------------------ fields
    World world;
    final Map<UUID, PD> players = new HashMap<>();
    final Map<Long, UUID> chunkOwners = new HashMap<>();
    final Map<String, Special> specials = new LinkedHashMap<>();
    final List<ShopItem> shop = new ArrayList<>();
    final Map<String, PotionEffectType> effects = new LinkedHashMap<>();
    final Random rng = new Random();
    final List<Opt> options = new ArrayList<>();
    final Map<UUID, Long> boundaryWarned = new HashMap<>();
    final Map<UUID, UUID> visiting = new HashMap<>(); // visitor -> island owner
    final Map<UUID, Long> resetConfirm = new HashMap<>();
    final Map<UUID, Long> islandResetConfirm = new HashMap<>();
    final Set<UUID> chunkBorders = new HashSet<>(); // players who currently have the chunk border particles switched on
    final Set<UUID> islandResetting = new HashSet<>();
    final Map<UUID, Long> rebirthConfirm = new HashMap<>();
    NamespacedKey KEY_INF, KEY_SPECIAL, KEY_POUCH, KEY_CPOUCH, KEY_WPOUCH, KEY_GPOUCH, KEY_BREAKER, KEY_WAND;
    static final int POUCH_CAP = 2304;          // max a pouch can hold (36 stacks)
    static final long POUCH_COOLDOWN_MS = 120;  // min time between two pouch placements
    final Map<UUID, Long> pouchCooldown = new HashMap<>();
    final Map<UUID, Clip> clipboards = new HashMap<>(); // Copy & Paste Wand clipboards (memory only)
    static final long AUTOSUBMIT_NOTIFY_MS = 5 * 60 * 1000L;
    final Map<UUID, double[]> autoSubWindow = new HashMap<>(); // owner -> {window start ms, cane, coins}
    // Infinite Chest contents by block key. Key = one item (amount 1) incl. its meta, value = how many are stored.
    final Map<String, Map<ItemStack, Long>> infStore = new HashMap<>();
    // cached cane plant count per island chunk, so unloaded chunks still count for the Auto Sell simulation
    final Map<Long, Integer> chunkStalks = new HashMap<>();
    double simMaxOfflineHours = 8;
    File dataFile;
    boolean dirty;
    int nextSlot = 0;

    double baseChance, perLevel, maxChance, coinsPerCane, priceMult, reqMult;
    int timerMinutes, dailyDrop, dailySell, rebirthInterval = 23;
    double rebirthBonus = 0.1;
    final List<Tier> tiers = new ArrayList<>(); // rank tiers, sorted by day
    long capReq;
    boolean rankChat = true, rankTab = true;
    String serverIp = "CaneFarming.minehut.gg";
    boolean scoreboardOn = true;
    int fastPrestigeSeconds = 30, fastPrestigeStreak = 3, fastPrestigeMult = 3;
    final Map<UUID, Board> boards = new HashMap<>();
    final Map<UUID, long[]> stalkCache = new HashMap<>(); // player -> {last refresh ms, cane plants placed}
    static final int BOARD_LINES = 13;
    static final String[] BOARD_ENTRIES = new String[15];

    static {
        for (int i = 0; i < BOARD_ENTRIES.length; i++) BOARD_ENTRIES[i] = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
    }

    /** One player's own sidebar scoreboard. */
    static class Board {
        Scoreboard sb;
        Objective obj;
        final Team[] teams = new Team[BOARD_LINES];
        final String[] last = new String[BOARD_LINES];
    }
    boolean lagEnabled = true;
    int lagThreshold = 20, lagTicks = 100, lagMinAge = 100;

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        if (!Credit.valid(this)) {
            getLogger().severe("The ownership credit was removed or changed - disabling. Original plugin: " + Credit.LINE);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info(Credit.LINE);
        getConfig().addDefault("growth.base-chance", 0.03);
        getConfig().addDefault("growth.per-level", 0.015);
        getConfig().addDefault("growth.max-chance", 0.5);
        getConfig().addDefault("timer.minutes", 30);
        getConfig().addDefault("coins-per-cane", 0.5);
        getConfig().addDefault("shop.price-multiplier", 2.0);
        getConfig().addDefault("quota.multiplier", 2.0);
        getConfig().addDefault("quota.cap", 0);
        getConfig().addDefault("sim.max-offline-hours", 8.0);
        getConfig().addDefault("rebirth.interval-days", 23);
        getConfig().addDefault("rebirth.bonus-per-rebirth", 0.1);
        getConfig().addDefault("lightblock.min-y", 65);
        getConfig().addDefault("lightblock.max-y", 100);
        getConfig().addDefault("lag-remover.enabled", true);
        getConfig().addDefault("lag-remover.entity-threshold", 20);
        getConfig().addDefault("lag-remover.interval-seconds", 5);
        getConfig().addDefault("lag-remover.item-age-seconds", 5);
        getConfig().addDefault("daily-bonus.drop-levels", 1);
        getConfig().addDefault("daily-bonus.sell-levels", 1);
        // rank tiers (LuckPerms groups). Prefix/weight here are only used when a group is first created -
        // after that you edit the groups in LuckPerms and the plugin never overwrites them.
        // show the LuckPerms prefix in chat / tab. Turn these off if another plugin (EssentialsChat, TAB, ...) already does it.
        getConfig().addDefault("ranks.show-in-chat", true);
        getConfig().addDefault("ranks.show-in-tab", true);
        getConfig().addDefault("server-ip", "CaneFarming.minehut.gg");
        getConfig().addDefault("scoreboard.enabled", true);
        getConfig().addDefault("rebirth.fast.seconds", 30);
        getConfig().addDefault("rebirth.fast.streak", 3);
        getConfig().addDefault("rebirth.fast.cost-multiplier", 3);
        getConfig().addDefault("ranks.style-version", 0);
        for (int i = 0; i < RANK_DAYS.length; i++) {
            String k = "ranks.tier" + (i + 1) + ".";
            getConfig().addDefault(k + "day", RANK_DAYS[i]);
            getConfig().addDefault(k + "group", RANK_GROUPS[i]);
            getConfig().addDefault(k + "prefix", RANK_PREFIXES[i]);
            getConfig().addDefault(k + "name", RANK_NAMES[i]);
            getConfig().addDefault(k + "weight", RANK_DAYS[i]);
        }
        getConfig().options().copyDefaults(true);
        // one-time restyle: old configs keep the plain "[Farmer]" prefixes, so overwrite them with the fancy ones once
        boolean restyle = getConfig().getInt("ranks.style-version", 0) < 2;
        if (restyle) {
            for (int i = 0; i < RANK_DAYS.length; i++) {
                String k = "ranks.tier" + (i + 1) + ".";
                getConfig().set(k + "prefix", RANK_PREFIXES[i]);
                getConfig().set(k + "name", RANK_NAMES[i]);
            }
            getConfig().set("ranks.style-version", 2);
        }
        saveConfig();
        loadSettings();

        KEY_INF = new NamespacedKey(this, "infinite");
        KEY_SPECIAL = new NamespacedKey(this, "special");
        KEY_POUCH = new NamespacedKey(this, "dirtpouch");
        KEY_CPOUCH = new NamespacedKey(this, "canepouch");
        KEY_WPOUCH = new NamespacedKey(this, "waterpouch");
        KEY_GPOUCH = new NamespacedKey(this, "gratepouch");
        KEY_BREAKER = new NamespacedKey(this, "linebreaker");
        KEY_WAND = new NamespacedKey(this, "copywand");
        buildPouches();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");

        WorldCreator wc = new WorldCreator(WORLD_NAME);
        wc.generator(new VoidGen());
        wc.biomeProvider(new PlainsBiomes());
        wc.environment(World.Environment.NORMAL);
        wc.generateStructures(false);
        world = wc.createWorld();
        if (world == null) {
            getLogger().severe("Could not create island world! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        world.setSpawnLocation(8, 65, 8);

        buildShop();
        buildOptions();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);
        String[] cmds = {"start", "canehome", "sell", "caneday", "caneinfo", "canerewards", "canetop", "caneshop", "isfly", "caneadmin", "options", "autocaneday", "daytoggle", "is", "reset", "rebirth", "autorebirth", "commands", "lightblock", "removesponge", "drain", "chunkmanagement", "pay"};
        for (String c : cmds) {
            if (getCommand(c) != null) getCommand(c).setExecutor((CommandExecutor) this::onCommand);
        }

        if (lpAvailable()) {
            // create the rank groups up front so you can edit their permissions in LuckPerms right away
            getServer().getScheduler().runTask(this, () -> {
                for (Tier t : tiers) {
                    try {
                        LuckPermsHook.ensureGroup(t.group, t.prefix, t.weight, getLogger(), restyle);
                    } catch (Throwable ex) {
                        getLogger().warning("Could not create LuckPerms group " + t.group + ": " + ex);
                    }
                }
            });
        }

        var sch = getServer().getScheduler();
        sch.runTaskTimer(this, () -> {
            if (!Credit.valid(this)) {
                getLogger().severe("The ownership credit was removed or changed - disabling. Original plugin: " + Credit.LINE);
                getServer().getPluginManager().disablePlugin(this);
            }
        }, 6000L, 6000L);
        sch.runTaskTimer(this, this::secondTick, 20L, 20L);
        sch.runTaskTimer(this, this::fastTick, 5L, 5L);
        sch.runTaskTimer(this, this::boundaryTick, 5L, 5L);
        sch.runTaskTimer(this, this::growthTick, 40L, 40L);
        sch.runTaskTimer(this, this::simTick, SIM_TICKS, SIM_TICKS);
        sch.runTaskTimer(this, this::chunkHopperTick, 4L, 4L);
        sch.runTaskTimer(this, this::chunkBorderTick, 10L, 10L);
        sch.runTaskTimer(this, this::perkTick, 100L, 100L);
        if (lagEnabled) sch.runTaskTimer(this, this::lagTick, lagTicks, lagTicks);
        sch.runTaskTimer(this, () -> {
            if (dirty) saveData();
        }, 1200L, 1200L);
    }

    /** (Re)reads everything from config.yml. Used on startup. */
    void loadSettings() {
        baseChance = getConfig().getDouble("growth.base-chance");
        perLevel = getConfig().getDouble("growth.per-level");
        maxChance = getConfig().getDouble("growth.max-chance");
        timerMinutes = getConfig().getInt("timer.minutes");
        coinsPerCane = getConfig().getDouble("coins-per-cane");
        priceMult = getConfig().getDouble("shop.price-multiplier");
        reqMult = getConfig().getDouble("quota.multiplier");
        capReq = getConfig().getLong("quota.cap");
        simMaxOfflineHours = Math.max(0.1, getConfig().getDouble("sim.max-offline-hours"));
        rankChat = getConfig().getBoolean("ranks.show-in-chat");
        rankTab = getConfig().getBoolean("ranks.show-in-tab");
        serverIp = getConfig().getString("server-ip", "CaneFarming.minehut.gg");
        scoreboardOn = getConfig().getBoolean("scoreboard.enabled", true);
        fastPrestigeSeconds = Math.max(1, getConfig().getInt("rebirth.fast.seconds", 30));
        fastPrestigeStreak = Math.max(1, getConfig().getInt("rebirth.fast.streak", 3));
        fastPrestigeMult = Math.max(1, getConfig().getInt("rebirth.fast.cost-multiplier", 3));
        lagEnabled = getConfig().getBoolean("lag-remover.enabled");
        lagThreshold = Math.max(1, getConfig().getInt("lag-remover.entity-threshold"));
        lagTicks = Math.max(1, getConfig().getInt("lag-remover.interval-seconds")) * 20;
        lagMinAge = Math.max(0, getConfig().getInt("lag-remover.item-age-seconds")) * 20;
        dailyDrop = Math.max(0, getConfig().getInt("daily-bonus.drop-levels"));
        dailySell = Math.max(0, getConfig().getInt("daily-bonus.sell-levels"));
        rebirthInterval = Math.max(1, getConfig().getInt("rebirth.interval-days"));
        rebirthBonus = getConfig().getDouble("rebirth.bonus-per-rebirth");
        tiers.clear();
        ConfigurationSection rs = getConfig().getConfigurationSection("ranks");
        if (rs != null) {
            for (String key : rs.getKeys(false)) {
                ConfigurationSection t = rs.getConfigurationSection(key);
                if (t == null) continue;
                String g = t.getString("group", "");
                if (g.isBlank()) continue;
                tiers.add(new Tier(Math.max(1, t.getInt("day", 1)), g, t.getString("prefix", ""), t.getInt("weight", 0), t.getString("name", g)));
            }
            tiers.sort(java.util.Comparator.comparingInt(t -> t.day));
        }
    }

    @Override
    public void onDisable() {
        if (world != null) saveData();
    }

    // ------------------------------------------------------------------ helpers
    static Component txt(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    void msg(CommandSender s, String m) {
        s.sendMessage(txt("&6[Cane] &f" + m));
    }

    void snd(Player p, PD d, Sound s, float v, float pitch) {
        if (d == null || d.on("sounds")) p.playSound(p.getLocation(), s, v, pitch);
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0001) return String.format(Locale.US, "%,.0f", v);
        return String.format(Locale.US, "%,.1f", v);
    }

    static String fmt(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    static String mmss(int s) {
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String nice(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    static String keyOf(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
    }

    ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(txt(s));
        im.lore(l);
        it.setItemMeta(im);
        return it;
    }

    // ------------------------------------------------------------------ island geometry
    int baseX(PD d) {
        return (d.slot % 64) * 8 + 3;
    }

    int baseZ(PD d) {
        return (d.slot / 64) * 8 + 3;
    }

    Location home(PD d) {
        return new Location(world, baseX(d) * 16 + 8.5, 65, baseZ(d) * 16 + 8.5, 0f, 0f);
    }

    List<int[]> chunksOf(PD d) {
        List<int[]> l = new ArrayList<>();
        l.add(new int[]{baseX(d), baseZ(d)});
        for (int i = 0; i < d.extra && i < ORDER.size(); i++) {
            int[] o = ORDER.get(i);
            l.add(new int[]{baseX(d) + o[0], baseZ(d) + o[1]});
        }
        return l;
    }

    void indexChunks(PD d) {
        for (int[] c : chunksOf(d)) chunkOwners.put(chunkKey(c[0], c[1]), d.id);
    }

    static boolean isWaterCol(int lx) {
        return (lx % 3 == 1 && lx <= 13) || lx == 15;
    }

    void buildChunk(int cx, int cz, boolean plant) {
        world.getChunkAt(cx, cz);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cx * 16 + lx, z = cz * 16 + lz;
                world.getBlockAt(x, 63, z).setType(Material.DIRT, false);
                boolean water = isWaterCol(lx);
                world.getBlockAt(x, 64, z).setType(water ? Material.WATER : Material.SAND, false);
                if (plant && !water && lz == 7) world.getBlockAt(x, 65, z).setType(Material.SUGAR_CANE, false);
            }
        }
    }

    /** Starter island: a single bedrock block and nothing else. */
    void buildStarter(int cx, int cz) {
        world.getChunkAt(cx, cz);
        world.getBlockAt(cx * 16 + 8, 64, cz * 16 + 8).setType(Material.BEDROCK, false);
    }

    /** Used by /reset: the whole home chunk gets a solid bedrock layer to stand on. */
    void buildBedrockLayer(int cx, int cz) {
        world.getChunkAt(cx, cz);
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++)
                world.getBlockAt(cx * 16 + lx, 64, cz * 16 + lz).setType(Material.BEDROCK, false);
    }

    /** Starting items (new island and /reset): Infinite Dirt, 64 sugar cane, Infinite Water Bucket, an Auto Sell Chunk Collector and the Line Breaker. */
    void giveStarterKit(Player p) {
        give(p, infinite(Material.DIRT));
        give(p, new ItemStack(Material.SUGAR_CANE, 64));
        give(p, infinite(Material.WATER_BUCKET));
        give(p, specialItem("sellcollector"));
        give(p, lineBreaker());
    }

    /** Every chunk a player's island can ever cover: the home chunk plus all chunks /caneshop "New Chunk" can add. */
    List<int[]> fullArea(PD d) {
        List<int[]> l = new ArrayList<>();
        l.add(new int[]{baseX(d), baseZ(d)});
        for (int[] o : ORDER) l.add(new int[]{baseX(d) + o[0], baseZ(d) + o[1]});
        return l;
    }

    boolean addChunk(PD d) {
        if (d.extra >= ORDER.size()) return false;
        int[] o = ORDER.get(d.extra);
        d.extra++;
        int cx = baseX(d) + o[0], cz = baseZ(d) + o[1];
        chunkOwners.put(chunkKey(cx, cz), d.id);
        if (d.on("chunkblocks")) buildChunk(cx, cz, false); // toggle in /chunkmanagement: off = unlocked but empty
        dirty = true;
        return true;
    }

    boolean canBuild(Player p, Location l) {
        if (!l.getWorld().equals(world)) return true;
        if (p.hasPermission("caneislands.admin")) return true;
        UUID o = chunkOwners.get(chunkKey(l.getBlockX() >> 4, l.getBlockZ() >> 4));
        if (o == null) return false;
        if (o.equals(p.getUniqueId())) return true;
        PD od = players.get(o);
        return od != null && od.trusted.contains(p.getUniqueId());
    }

    void deny(Player p) {
        p.sendActionBar(txt("&cYou can't build here! Only the owner (or players they /is trust) can."));
    }

    // ------------------------------------------------------------------ quota / days
    long required(int day) {
        double v = Math.pow(reqMult, day - 1);
        if (v > 4.0e18) v = 4.0e18;
        long r = (long) v;
        if (r < 1) r = 1;
        if (capReq > 0 && r > capReq) r = capReq;
        return r;
    }

    int timeFor(PD d) {
        return timerMinutes * 60 + 300 * (d.lvl("timeext") + d.rewardTime);
    }

    int growthLevel(PD d) {
        return d.rewardGrowth + d.lvl("growth");
    }

    double dropChance(PD d) {
        return 0.2 * (d.lvl("doubledrop") + d.rewardDrop);
    }

    void submit(PD d, long amt) {
        if (amt <= 0) return;
        double rm = rebirthMult(d);
        d.totalCane += Math.round(amt * rm);
        d.coins += amt * coinsPerCane * (1.0 + 0.10 * (d.lvl("coinboost") + d.rewardSell)) * rm;
        dirty = true;
        if (!d.completed) {
            d.submitted += amt;
            if (d.submitted >= required(d.day)) completeDay(d);
        }
    }

    void completeDay(PD d) {
        d.completed = true;
        Player p = Bukkit.getPlayer(d.id);
        boolean first = d.day > d.bestDay;
        int rewardDay = d.day <= 100 ? d.day : 1 + rng.nextInt(100);
        if (first) {
            d.bestDay = d.day;
            if (p != null) applyReward(p, d, rewardDay, d.day > 100);
            else d.pending.add(rewardDay);
            // daily bonus: every NEW day passed gives permanent free Bonus Drops + Coin Boost levels
            d.rewardDrop += dailyDrop;
            d.rewardSell += dailySell;
            if (p != null && (dailyDrop > 0 || dailySell > 0))
                msg(p, "&6Daily bonus: &f+" + dailyDrop + " Bonus Drop level &7and &f+" + dailySell + " Coin Boost level &7(permanent)");
        }
        checkRank(d, p);
        boolean auto = d.autoDayOn && d.lvl("autoday") > 0;
        if (p != null) {
            if (auto) msg(p, "&a&lDay " + d.day + " complete! &7Auto Day is on - moving to Day " + (d.day + 1) + ".");
            else msg(p, "&a&lDay " + d.day + " complete! &7Use &e/caneday &7to start Day " + (d.day + 1) + ".");
            snd(p, d, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            if (!first)
                msg(p, "&7No reward - you already claimed this day. New rewards start again at Day " + (d.bestDay + 1) + ".");
        }
        if (auto) nextDay(d, p);
        dirty = true;
    }

    boolean lpAvailable() {
        return getServer().getPluginManager().isPluginEnabled("LuckPerms");
    }

    List<String> tierGroups() {
        List<String> l = new ArrayList<>();
        for (Tier t : tiers) l.add(t.group);
        return l;
    }

    /** Gives the highest rank tier the player qualifies for (via LuckPerms), replacing the lower tiers. */
    void checkRank(PD d, Player p) {
        int idx = 0;
        int reached = Math.max(d.bestDay, d.day); // reaching the day is enough
        for (int i = 0; i < tiers.size(); i++) if (reached >= tiers.get(i).day) idx = i + 1;
        if (idx <= d.rankTier) return;
        d.rankTier = idx;
        dirty = true;
        Tier t = tiers.get(idx - 1);
        if (lpAvailable()) {
            try {
                LuckPermsHook.grant(d.id, tierGroups(), t.group, t.prefix, t.weight, getLogger())
                        .thenRun(() -> getServer().getScheduler().runTask(this, () -> {
                            Player on = Bukkit.getPlayer(d.id);
                            if (on != null) refreshTab(on);
                        }));
            } catch (Throwable ex) {
                getLogger().warning("Could not give the LuckPerms rank: " + ex);
            }
        } else {
            getLogger().warning("LuckPerms is not enabled - " + d.name + " earned group '" + t.group + "' but it could not be applied.");
        }
        String shown = t.prefix.trim();
        if (p != null) {
            msg(p, "&a&lYou reached Day " + t.day + " and earned the " + shown + " &a&lrank!");
            snd(p, d, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        Bukkit.broadcast(txt("&6[Cane] &e" + d.name + " &freached Day " + t.day + " and earned the " + shown + " &frank!"));
    }

    /** Re-applies the rank group (join / startup) in case it was lost, e.g. LuckPerms wasn't ready when it was earned. */
    void syncRank(PD d) {
        if (d.rankTier <= 0 || d.rankTier > tiers.size() || !lpAvailable()) return;
        Tier t = tiers.get(d.rankTier - 1);
        try {
            LuckPermsHook.grant(d.id, tierGroups(), t.group, t.prefix, t.weight, getLogger())
                    .thenRun(() -> getServer().getScheduler().runTask(this, () -> {
                        Player on = Bukkit.getPlayer(d.id);
                        if (on != null) refreshTab(on);
                    }));
        } catch (Throwable ex) {
            getLogger().warning("Could not re-apply the LuckPerms rank for " + d.name + ": " + ex);
        }
    }

    static String lpColor(String s) {
        return s == null ? "" : s.replace('\u00a7', '&');
    }

    /** The player's LuckPerms prefix ("" if none / no LuckPerms). */
    String rankPrefix(Player p) {
        if (!lpAvailable()) return "";
        try {
            return lpColor(LuckPermsHook.prefixOf(p));
        } catch (Throwable t) {
            return "";
        }
    }

    /** Tab list: rank tag + name + prestige count, plus a header / footer with the server IP. */
    void refreshTab(Player p) {
        PD d = players.get(p.getUniqueId());
        if (rankTab) {
            String line = rankPrefix(p) + "&f" + p.getName();
            if (d != null) line += " &8[&dPrestige &f" + d.rebirths + "&8]";
            Component want = txt(line);
            if (!want.equals(p.playerListName())) p.playerListName(want);
        }
        Component header = txt("&8&m        &r &a&l\u2756 CANE FARMING \u2756 &8&m        ")
                .append(Component.newline()).append(txt("&7Farm \u2022 Sell \u2022 Prestige"));
        Component footer = txt("&7Server IP: &e" + serverIp)
                .append(Component.newline()).append(txt("&7Online: &a" + Bukkit.getOnlinePlayers().size()));
        p.sendPlayerListHeaderAndFooter(header, footer);
    }

    /** Chat: [rank tag] Name: message - the tag is the only decoration, the name and message are plain white. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        if (!rankChat) return;
        final String prefix = rankPrefix(e.getPlayer());
        final Component pre = prefix.isEmpty() ? Component.empty() : txt(prefix);
        e.renderer(io.papermc.paper.chat.ChatRenderer.viewerUnaware((source, display, message) ->
                pre.append(display.color(NamedTextColor.WHITE))
                        .append(Component.text(": ", NamedTextColor.WHITE))
                        .append(message.color(NamedTextColor.WHITE))));
    }

    // ------------------------------------------------------------------ sidebar scoreboard
    static String shortNum(double v) {
        String[] suf = {"", "K", "M", "B", "T", "Qa", "Qi"};
        int i = 0;
        double a = Math.abs(v);
        while (a >= 1000 && i < suf.length - 1) {
            a /= 1000.0;
            i++;
        }
        if (i == 0) return fmt(v);
        return String.format(Locale.US, "%.2f", Math.signum(v) * a).replaceAll("\\.?0+$", "") + suf[i];
    }

    /** Coins one cane is worth for this player (Coin Boost, daily bonus and prestige multiplier included). */
    double coinsPerCaneOf(PD d) {
        return coinsPerCane * (1.0 + 0.10 * (d.lvl("coinboost") + d.rewardSell)) * rebirthMult(d);
    }

    void removeBoard(Player p) {
        if (boards.remove(p.getUniqueId()) == null) return;
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm != null) p.setScoreboard(sm.getMainScoreboard());
    }

    Board createBoard(Player p) {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        if (sm == null) return null;
        Board b = new Board();
        b.sb = sm.getNewScoreboard();
        b.obj = b.sb.registerNewObjective("cane", Criteria.DUMMY, txt("&2&l\u2756 &a&lCANE FARMING &2&l\u2756"));
        b.obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        b.obj.numberFormat(NumberFormat.blank()); // hide the red score numbers
        for (int i = 0; i < BOARD_LINES; i++) {
            Team t = b.sb.registerNewTeam("l" + i);
            t.addEntry(BOARD_ENTRIES[i]);
            b.teams[i] = t;
            b.obj.getScore(BOARD_ENTRIES[i]).setScore(BOARD_LINES - i);
        }
        p.setScoreboard(b.sb);
        return b;
    }

    /** Once a second: refreshes every online player's sidebar. The /canetop order is sorted once for everybody. */
    void scoreboardTick() {
        if (!scoreboardOn) {
            for (Player p : Bukkit.getOnlinePlayers()) removeBoard(p);
            return;
        }
        List<PD> top = new ArrayList<>(players.values());
        top.sort((x, y) -> Long.compare(y.totalCane, x.totalCane));
        PD leader = top.isEmpty() ? null : top.get(0);
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int i = 0; i < top.size(); i++) ranks.put(top.get(i).id, i + 1);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PD d = players.get(p.getUniqueId());
            if (d == null) continue;
            if (!d.on("scoreboard")) {
                removeBoard(p);
                continue;
            }
            Board b = boards.get(p.getUniqueId());
            if (b == null) {
                b = createBoard(p);
                if (b == null) return;
                boards.put(p.getUniqueId(), b);
            } else if (p.getScoreboard() != b.sb) {
                p.setScoreboard(b.sb); // something else took the scoreboard away
            }
            long[] sc = stalkCache.get(d.id);
            if (sc == null || now - sc[0] >= 5000) {
                sc = new long[]{now, stalksOf(d)};
                stalkCache.put(d.id, sc);
            }
            String rank = d.rankTier > 0 && d.rankTier <= tiers.size() ? tiers.get(d.rankTier - 1).name : "&7None";
            String[] lines = new String[BOARD_LINES];
            lines[0] = "&8&m                    ";
            lines[1] = "&7Player: &f" + p.getName();
            lines[2] = "&7Balance: &6" + shortNum(d.coins);
            lines[3] = "&7Rank: " + rank;
            lines[4] = "&7Prestige: &d" + d.rebirths;
            lines[5] = "&7Cane Placed: &a" + fmt(sc[1]);
            lines[6] = "&7Per Sell: &e" + String.format(Locale.US, "%.2f", coinsPerCaneOf(d)) + " &7/cane";
            lines[7] = " ";
            lines[8] = "&6&lCaneTop #1";
            lines[9] = leader == null ? "&7-" : "&f" + leader.name + " &7(" + shortNum(leader.totalCane) + ")";
            lines[10] = "&6&lYour CaneTop: &f#" + ranks.getOrDefault(d.id, 0);
            lines[11] = "  ";
            lines[12] = "&e" + serverIp;
            for (int i = 0; i < BOARD_LINES; i++) {
                if (lines[i].equals(b.last[i])) continue;
                b.last[i] = lines[i];
                b.teams[i].prefix(txt(lines[i]));
            }
        }
    }

    // ------------------------------------------------------------------ rebirth
    double rebirthMult(PD d) {
        return 1.0 + rebirthBonus * d.rebirths;
    }

    /** Day the player must reach for their next rebirth. */
    int rebirthDay(PD d) {
        long day = (long) rebirthInterval * (penaltyActive(d) ? fastPrestigeMult : 1);
        return (int) Math.min(day, Integer.MAX_VALUE);
    }

    /** True after N prestiges in a row that each came less than X seconds after the one before: the next one costs more. */
    boolean penaltyActive(PD d) {
        return fastPrestigeMult > 1 && d.fastStreak >= fastPrestigeStreak;
    }

    boolean rebirthReady(PD d) {
        return d.runBest >= rebirthDay(d);
    }

    void doRebirth(PD d, Player p) {
        long nowMs = System.currentTimeMillis();
        boolean paidPenalty = penaltyActive(d);
        if (paidPenalty) d.fastStreak = 0; // paid the higher price, start counting again
        else if (d.lastRebirth > 0 && nowMs - d.lastRebirth < fastPrestigeSeconds * 1000L) d.fastStreak++;
        else d.fastStreak = 0;
        d.lastRebirth = nowMs;
        d.rebirths++;
        d.totalCane = 0; // resets the /canetop count
        // back to the very start: Day 1 with a fresh quota + timer. bestDay is kept, so days you already
        // reached never give their reward (or daily bonus) again until you pass your best day.
        d.day = 1;
        d.runBest = 1;
        d.submitted = 0;
        d.completed = false;
        d.timeLeft = timeFor(d);
        d.priceDrop++; // shop prices fall by one step (the same factor they rise per purchase)
        dirty = true;
        if (p != null) {
            msg(p, "&d&lREBIRTH! &fYou have rebirthed &e" + d.rebirths + " &ftime(s). You're back on &eDay 1&f, your /canetop count was reset and all cane + coins from submits are now &ex" + String.format(Locale.ROOT, "%.2f", rebirthMult(d)) + "&f.");
            msg(p, "&7Shop prices dropped by &ex" + fmt(priceMult) + " &7(one purchase step) for everything you own.");
            msg(p, "&7No rewards for days you already reached - new rewards start again at Day " + (d.bestDay + 1) + ". Next rebirth at Day " + rebirthDay(d) + ".");
            if (penaltyActive(d))
                msg(p, "&c&lToo fast! &cYou prestiged " + fastPrestigeStreak + " times in a row in under " + fastPrestigeSeconds + "s, so your next prestige costs &ex" + fastPrestigeMult + " &c- it needs &eDay " + rebirthDay(d) + "&c.");
            else if (paidPenalty)
                msg(p, "&aThe fast-prestige penalty is gone - your next prestige is back to Day " + rebirthDay(d) + ".");
            snd(p, d, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            refreshTab(p);
        }
    }

    /** Tells the player they can rebirth, or does it for them when /autorebirth is on. */
    void checkRebirthReady(PD d, Player p) {
        if (!rebirthReady(d)) return;
        if (d.autoRebirth) {
            doRebirth(d, p);
        } else if (p != null && d.day == rebirthDay(d)) {
            msg(p, "&d&lYou can rebirth! &7Type &e/rebirth &7(or turn on &e/autorebirth&7).");
        }
    }

    void cmdRebirth(Player p, PD d, String[] a) {
        if (!rebirthReady(d)) {
            msg(p, "&cYou need to reach &eDay " + rebirthDay(d) + " &cto rebirth. &7(You are on Day " + d.day + ", best this run: Day " + d.runBest + ")");
            return;
        }
        long now = System.currentTimeMillis();
        if (a.length > 0 && a[0].equalsIgnoreCase("confirm")) {
            Long t = rebirthConfirm.remove(p.getUniqueId());
            if (t == null || now - t > 30000) {
                msg(p, "&cNothing to confirm. Type &e/rebirth &cfirst.");
                return;
            }
            doRebirth(d, p);
            return;
        }
        rebirthConfirm.put(p.getUniqueId(), now);
        double next = 1.0 + rebirthBonus * (d.rebirths + 1);
        msg(p, "&d&lREBIRTH: &fThis sends you back to &eDay 1 &fand resets your &e/canetop &fcane count (" + fmt(d.totalCane) + ") to 0. Your coins and upgrades are kept.");
        msg(p, "&7You won't earn rewards again for days you already reached (best: Day " + d.bestDay + ").");
        msg(p, "&fAfter rebirthing, cane count and coins from every submit are multiplied by &ex" + String.format(Locale.ROOT, "%.2f", next) + "&f.");
        if (penaltyActive(d))
            msg(p, "&cYou prestiged too fast, so this prestige cost &ex" + fastPrestigeMult + " &c(Day " + rebirthDay(d) + " instead of Day " + rebirthInterval + ").");
        msg(p, "&7Type &e/rebirth confirm &7within 30 seconds to continue.");
    }

    void cmdAutoRebirth(Player p, PD d) {
        d.autoRebirth = !d.autoRebirth;
        dirty = true;
        if (d.autoRebirth) {
            msg(p, "&aAuto Rebirth &2enabled&a. &7You'll rebirth automatically whenever you reach Day " + rebirthDay(d) + ".");
            if (rebirthReady(d)) doRebirth(d, p);
        } else {
            msg(p, "&cAuto Rebirth disabled. &7Use &e/rebirth &7manually.");
        }
    }

    void nextDay(PD d, Player p) {
        d.day++;
        if (d.day > d.runBest) d.runBest = d.day;
        d.completed = false;
        d.submitted = 0;
        d.timeLeft = timeFor(d);
        dirty = true;
        if (p != null) {
            msg(p, "&6&lDay " + d.day + " started! &fSubmit &e" + fmt(required(d.day)) + " &fcane within &e" + mmss(d.timeLeft) + "&f.");
            snd(p, d, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }
        checkRank(d, p);
        checkRebirthReady(d, p);
    }

    void failDay(Player p, PD d) {
        int old = d.day;
        d.day = 1;
        d.submitted = 0;
        d.completed = false;
        d.timeLeft = timeFor(d);
        dirty = true;
        msg(p, "&c&lYou failed Day " + old + "! &cYou're back to Day 1. Rewards you already earned won't repeat until you pass Day " + (d.bestDay + 1) + ".");
        snd(p, d, Sound.ENTITY_WITHER_DEATH, 0.6f, 1f);
    }

    // ------------------------------------------------------------------ rewards
    Rw reward(int rd) {
        List<String> ds = new ArrayList<>();
        List<BiConsumer<Player, PD>> acts = new ArrayList<>();
        final double coins = rd * 2.0 + 5;
        ds.add("+" + fmt(coins) + " coins");
        acts.add((p, d) -> d.coins += coins);

        if (rd == 1) {
            ds.add("Starter pack: 32 Sugar Cane");
            acts.add((p, d) -> give(p, new ItemStack(Material.SUGAR_CANE, 32)));
        }
        switch (rd % 10) {
            case 1, 4, 6 -> {
                ds.add("Growth Boost +1");
                acts.add((p, d) -> d.rewardGrowth += 1);
            }
            case 2 -> {
                ds.add("Bonus Drops +1");
                acts.add((p, d) -> d.rewardDrop += 1);
            }
            case 3 -> {
                ds.add("Chunk Hopper");
                acts.add((p, d) -> give(p, specialItem("chunkhopper")));
            }
            case 5 -> {
                ds.add("Auto Sell Chunk Collector");
                acts.add((p, d) -> give(p, specialItem("sellcollector")));
            }
            case 7 -> {
                final int pick = (rd / 10) % 5;
                if (pick == 3) {
                    ds.add("Infinite Chest");
                    acts.add((p, d) -> give(p, specialItem("infchest")));
                } else {
                    Material[] inf = {Material.DIRT, Material.SAND, Material.GLASS, Material.AIR, Material.COARSE_DIRT};
                    final Material mm = inf[pick];
                    ds.add("Infinite " + nice(mm));
                    acts.add((p, d) -> give(p, infinite(mm)));
                }
            }
            case 8 -> {
                final int amt = 32 + rd;
                ds.add(amt + " Sugar Cane");
                acts.add((p, d) -> give(p, new ItemStack(Material.SUGAR_CANE, amt)));
            }
            case 9 -> {
                final double bonus = rd * 10.0;
                ds.add("Coin pouch: +" + fmt(bonus) + " coins");
                acts.add((p, d) -> d.coins += bonus);
            }
            case 0 -> {
                ds.add("Free Chunk (1,000 coins if you own them all) + Growth Boost +2");
                acts.add((p, d) -> {
                    if (!addChunk(d)) d.coins += 1000;
                    d.rewardGrowth += 2;
                });
            }
            default -> {
            }
        }
        if (rd % 25 == 0) {
            ds.add("+5 minutes on your daily timer");
            acts.add((p, d) -> d.rewardTime += 1);
        }
        return new Rw(String.join(", ", ds), (p, d) -> {
            for (BiConsumer<Player, PD> a : acts) a.accept(p, d);
        });
    }

    void applyReward(Player p, PD d, int rd, boolean random) {
        Rw r = reward(rd);
        r.act.accept(p, d);
        String head = random ? "&6Random reward (from Day " + rd + "): " : "&6Day reward: ";
        msg(p, head + "&f" + r.desc);
        snd(p, d, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        dirty = true;
    }

    // ------------------------------------------------------------------ special / infinite items
    ItemStack infinite(Material m) {
        ItemStack it = new ItemStack(m, m.getMaxStackSize());
        ItemMeta im = it.getItemMeta();
        im.displayName(txt("&b\u221E Infinite " + nice(m)));
        im.lore(List.of(txt("&7Never runs out when placed!")));
        im.setEnchantmentGlintOverride(true);
        im.getPersistentDataContainer().set(KEY_INF, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    ItemStack specialItem(String type) {
        Material m;
        String name;
        List<String> lore = new ArrayList<>();
        switch (type) {
            case "sellcollector" -> {
                m = Material.CHEST;
                name = "&6Auto Sell Chunk Collector";
                lore.add("&7Counts every sugar cane plant on your whole island");
                lore.add("&7(all chunks, all layers) and automatically pays you");
                lore.add("&7the cane it would have grown - with your Growth Boost,");
                lore.add("&7Bonus Drops, Coin Boost and Rebirth multipliers.");
                lore.add("&7Adds to your balance, /canetop and daily quota.");
            }
            case "chunkhopper" -> {
                m = Material.HOPPER;
                name = "&6Chunk Hopper";
                lore.add("&7Collects EVERY dropped item in its chunk.");
                lore.add("&7Items go into the chest / Infinite Chest it points at.");
                lore.add("&7With nothing to point at it keeps them itself.");
            }
            default -> {
                m = Material.CHEST;
                name = "&6Infinite Chest";
                lore.add("&7Stores any item, with no limit.");
                lore.add("&7Hoppers and Chunk Hoppers can fill it.");
                lore.add("&7Right-click to open it. Empty it before breaking it.");
            }
        }
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(txt(s));
        im.lore(l);
        im.setEnchantmentGlintOverride(true);
        im.getPersistentDataContainer().set(KEY_SPECIAL, PersistentDataType.STRING, type);
        it.setItemMeta(im);
        return it;
    }

    // ------------------------------------------------------------------ pouches (dirt + sugar cane)
    /** One kind of pouch: which block it stores, which (inert) item it looks like, and how it behaves. */
    static class PouchType {
        final Material store, item;
        final NamespacedKey key;
        final String name, what;
        final Sound sound;
        final boolean infinite; // an Infinite version of the block exists, so the lore can mention it

        PouchType(Material store, Material item, NamespacedKey key, String name, String what, Sound sound, boolean infinite) {
            this.store = store;
            this.item = item;
            this.key = key;
            this.name = name;
            this.what = what;
            this.sound = sound;
            this.infinite = infinite;
        }
    }

    final Map<Material, PouchType> pouchByStore = new LinkedHashMap<>();
    final Map<Material, PouchType> pouchByItem = new HashMap<>();

    void regPouch(Material store, Material item, NamespacedKey key, String name, String what, Sound sound, boolean infinite) {
        PouchType t = new PouchType(store, item, key, name, what, sound, infinite);
        pouchByStore.put(store, t);
        pouchByItem.put(item, t);
    }

    void buildPouches() {
        // the first three keep their original keys so pouches players already own keep working
        regPouch(Material.DIRT, Material.LEATHER, KEY_POUCH, "Dirt Pouch", "dirt", Sound.BLOCK_GRAVEL_PLACE, true);
        regPouch(Material.SUGAR_CANE, Material.RABBIT_HIDE, KEY_CPOUCH, "Cane Pouch", "sugar cane", Sound.BLOCK_GRASS_PLACE, false);
        regPouch(Material.COPPER_GRATE, Material.COPPER_INGOT, KEY_GPOUCH, "Copper Grate Pouch", "copper grates", Sound.BLOCK_COPPER_PLACE, true);
        regPouch(Material.SAND, Material.CLAY_BALL, new NamespacedKey(this, "pouch_sand"), "Sand Pouch", "sand", Sound.BLOCK_SAND_PLACE, true);
        regPouch(Material.OAK_LEAVES, Material.PHANTOM_MEMBRANE, new NamespacedKey(this, "pouch_oak_leaves"), "Oak Leaves Pouch", "oak leaves", Sound.BLOCK_GRASS_PLACE, false);
        regPouch(Material.SEA_LANTERN, Material.PRISMARINE_CRYSTALS, new NamespacedKey(this, "pouch_sea_lantern"), "Sea Lantern Pouch", "sea lanterns", Sound.BLOCK_GLASS_PLACE, false);
        regPouch(Material.OCHRE_FROGLIGHT, Material.GOLD_NUGGET, new NamespacedKey(this, "pouch_ochre_froglight"), "Ochre Froglight Pouch", "ochre froglights", Sound.BLOCK_FROGLIGHT_PLACE, false);
        regPouch(Material.VERDANT_FROGLIGHT, Material.SLIME_BALL, new NamespacedKey(this, "pouch_verdant_froglight"), "Verdant Froglight Pouch", "verdant froglights", Sound.BLOCK_FROGLIGHT_PLACE, false);
        regPouch(Material.PEARLESCENT_FROGLIGHT, Material.AMETHYST_SHARD, new NamespacedKey(this, "pouch_pearlescent_froglight"), "Pearlescent Froglight Pouch", "pearlescent froglights", Sound.BLOCK_FROGLIGHT_PLACE, false);
        regPouch(Material.SPONGE, Material.HONEYCOMB, new NamespacedKey(this, "pouch_sponge"), "Sponge Pouch", "sponges", Sound.BLOCK_GRASS_PLACE, false);
    }

    /** Every pouch is unlimited: it never runs out (no need to store anything or carry an Infinite item). */
    boolean unlimited(Material m) {
        return pouchByStore.containsKey(m);
    }

    /** Which block a pouch stores, or null if the item is not a pouch. */
    Material pouchStore(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        PouchType t = pouchByItem.get(it.getType());
        if (t == null) return null;
        return it.getItemMeta().getPersistentDataContainer().has(t.key, PersistentDataType.INTEGER) ? t.store : null;
    }

    NamespacedKey pouchKey(Material store) {
        return pouchByStore.get(store).key;
    }

    String pouchName(Material store) {
        return pouchByStore.get(store).name;
    }

    String pouchWhat(Material store) {
        return pouchByStore.get(store).what;
    }

    ItemStack pouchItem(Material store, int amount) {
        PouchType t = pouchByStore.get(store);
        ItemStack it = new ItemStack(t.item);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt("&6" + t.name));
        im.setMaxStackSize(1); // pouches never stack
        im.setEnchantmentGlintOverride(true);
        im.getPersistentDataContainer().set(t.key, PersistentDataType.INTEGER, Math.max(0, Math.min(amount, POUCH_CAP)));
        it.setItemMeta(im);
        refreshPouchLore(it);
        return it;
    }

    boolean isPouch(ItemStack it) {
        return pouchStore(it) != null;
    }

    int pouchAmount(ItemStack it) {
        Material store = pouchStore(it);
        if (store == null) return 0;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(pouchKey(store), PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, Math.min(v, POUCH_CAP));
    }

    void setPouchAmount(ItemStack it, int amount) {
        Material store = pouchStore(it);
        if (store == null) return;
        final int a = Math.max(0, Math.min(amount, POUCH_CAP));
        it.editMeta(im -> im.getPersistentDataContainer().set(pouchKey(store), PersistentDataType.INTEGER, a));
        refreshPouchLore(it);
    }

    void refreshPouchLore(ItemStack it) {
        final Material store = pouchStore(it);
        if (store == null) return;
        final PouchType t = pouchByStore.get(store);
        final int a = pouchAmount(it);
        final boolean cane = store == Material.SUGAR_CANE;
        List<Component> lore = new ArrayList<>();
        lore.add(txt(cane ? "&7Right-click to plant a line of sugar cane the way you face," : "&7Right-click to place a line of " + t.what + " the way you face,"));
        lore.add(txt(cane ? "&7skipping spots where cane can't grow, until the island edge." : "&7until it hits a block or the edge of your island."));
        if (unlimited(store)) {
            lore.add(txt("&bIt never runs out - unlimited " + t.what + "!"));
            lore.add(txt("&7Only works on islands you can build on."));
        } else {
            lore.add(txt("&7Sneak + right-click to store " + t.what + " from your inventory."));
            if (t.infinite) lore.add(txt("&bCarry an Infinite " + nice(store) + " and it never uses up your stored " + t.what + "."));
            lore.add(txt("&7Only works on islands you can build on."));
            lore.add(txt(" "));
            lore.add(txt("&eStored: &f" + fmt(a) + "&7/" + fmt(POUCH_CAP)));
        }
        it.editMeta(im -> im.lore(lore));
    }

    // ------------------------------------------------------------------ infinite water pouch
    static final int WATER_LINE_MAX = 32; // water sources per click

    ItemStack waterPouchItem() {
        ItemStack it = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt("&b\u221E Water Pouch"));
        im.setMaxStackSize(1);
        im.setEnchantmentGlintOverride(true);
        im.lore(List.of(
                txt("&7Right-click to place a line of water the way you face,"),
                txt("&7waterlogging leaves, slabs, stairs, grates etc. on the way,"),
                txt("&7until it hits a block or the edge of your island."),
                txt("&7It never runs out! Use a Sponge to clean up."),
                txt("&7Only works on islands you can build on.")));
        im.getPersistentDataContainer().set(KEY_WPOUCH, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    boolean isWaterPouch(ItemStack it) {
        if (it == null || it.getType() != Material.NAUTILUS_SHELL || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_WPOUCH, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWaterPouchUse(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!isWaterPouch(item)) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.RIGHT_CLICK_AIR) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        if (clicked != null && !p.isSneaking() && clicked.getType().isInteractable()) return;
        if (clicked != null && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (!p.getWorld().equals(world)) {
            msg(p, "&cThe Water Pouch only works on the cane islands.");
            return;
        }
        if (clicked == null) return;
        waterPouchPlace(p, hand, clicked, e.getBlockFace());
    }

    /** Right-click: a line of water sources the way the player faces; stops at obstacles / the island edge. Never uses anything up. */
    void waterPouchPlace(Player p, EquipmentSlot hand, Block clicked, BlockFace face) {
        long now = System.currentTimeMillis();
        Long last = pouchCooldown.get(p.getUniqueId());
        if (last != null && now - last < POUCH_COOLDOWN_MS) return;
        pouchCooldown.put(p.getUniqueId(), now);
        ItemStack pouch = p.getInventory().getItem(hand);
        if (!isWaterPouch(pouch)) return;
        Block first = clicked.isReplaceable() || clicked.getBlockData() instanceof org.bukkit.block.data.Waterlogged ? clicked : clicked.getRelative(face);
        BlockFace dir = p.getFacing();
        if (dir == BlockFace.UP || dir == BlockFace.DOWN) return;
        int placed = 0, steps = 0;
        Block cur = first;
        Block against = clicked;
        while (steps < WATER_LINE_MAX) {
            if (!isWaterPouch(p.getInventory().getItem(hand))) break;
            if (!cur.getWorld().equals(world) || cur.getY() < world.getMinHeight() || cur.getY() >= world.getMaxHeight()) break;
            if (steps == 0 && !canBuild(p, clicked.getLocation())) {
                deny(p);
                return;
            }
            if (!canBuild(p, cur.getLocation())) {
                if (steps == 0) deny(p);
                break;
            }
            if (specials.containsKey(keyOf(cur))) break;
            if (cur.getType() == Material.WATER) { // already water: carry on past it
                steps++;
                against = cur;
                cur = cur.getRelative(dir);
                continue;
            }
            // waterloggable blocks (leaves, slabs, stairs, fences, copper grates...) get waterlogged and the line carries on through them
            if (cur.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl) {
                if (!wl.isWaterlogged()) {
                    BlockState old = cur.getState();
                    wl.setWaterlogged(true);
                    cur.setBlockData(wl, true);
                    BlockPlaceEvent wev = new BlockPlaceEvent(cur, old, against, pouch, p, true, hand);
                    getServer().getPluginManager().callEvent(wev);
                    if (wev.isCancelled() || !wev.canBuild()) {
                        old.update(true, false);
                        break;
                    }
                    placed++;
                }
                steps++;
                against = cur;
                cur = cur.getRelative(dir);
                continue;
            }
            boolean free = steps == 0 ? cur.isReplaceable() : airish(cur);
            if (!free || specials.containsKey(keyOf(cur))) break;
            BlockState old = cur.getState();
            cur.setType(Material.WATER, true);
            BlockPlaceEvent ev = new BlockPlaceEvent(cur, old, against, pouch, p, true, hand);
            getServer().getPluginManager().callEvent(ev);
            if (ev.isCancelled() || !ev.canBuild()) {
                old.update(true, false);
                break;
            }
            placed++;
            steps++;
            against = cur;
            cur = cur.getRelative(dir);
        }
        if (placed == 0) return;
        world.playSound(first.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_EMPTY, 1f, 1f);
        p.sendActionBar(txt("&bPlaced &f" + placed + " &bwater &7- &b\u221E infinite"));
    }

    // ------------------------------------------------------------------ copy & paste wand
    static final int WAND_HALF = 32; // copy area is 64 x 64 (-32..+31 around the clicked block)
    static final long WAND_COOLDOWN_MS = 400;

    /** One copied layer: blocks stored as offsets from the clicked block. */
    static class Clip {
        int y;
        final List<int[]> off = new ArrayList<>();
        final List<org.bukkit.block.data.BlockData> data = new ArrayList<>();
    }

    ItemStack wandItem() {
        ItemStack it = new ItemStack(Material.BLAZE_ROD);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt("&d&lCopy & Paste Wand"));
        im.setMaxStackSize(1);
        im.setEnchantmentGlintOverride(true);
        im.lore(List.of(
                txt("&7Shift + right-click a block: copy its layer"),
                txt("&7(one layer, up to 64 x 64 around that block)."),
                txt("&7Right-click: paste the layer where you click."),
                txt("&7Only copies and pastes where you can build.")));
        im.getPersistentDataContainer().set(KEY_WAND, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    boolean isWand(ItemStack it) {
        if (it == null || it.getType() != Material.BLAZE_ROD || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_WAND, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWandUse(PlayerInteractEvent e) {
        if (!isWand(e.getItem())) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.RIGHT_CLICK_AIR) return;
        if (e.getHand() == null) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        if (clicked != null && !p.isSneaking() && clicked.getType().isInteractable()) return; // still open chests, doors...
        if (clicked != null && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (!p.getWorld().equals(world)) {
            msg(p, "&cThe Copy & Paste Wand only works on the cane islands.");
            return;
        }
        if (clicked == null) return;
        long now = System.currentTimeMillis();
        Long last = pouchCooldown.get(p.getUniqueId());
        if (last != null && now - last < WAND_COOLDOWN_MS) return;
        pouchCooldown.put(p.getUniqueId(), now);
        if (p.isSneaking()) wandCopy(p, clicked);
        else wandPaste(p, clicked, e.getBlockFace());
    }

    /** Shift + right-click: copies ONE layer (the clicked block's height), 64 x 64 around it, only blocks the player may build on. */
    void wandCopy(Player p, Block clicked) {
        Clip c = new Clip();
        c.y = clicked.getY();
        for (int dx = -WAND_HALF; dx < WAND_HALF; dx++) {
            for (int dz = -WAND_HALF; dz < WAND_HALF; dz++) {
                Block b = world.getBlockAt(clicked.getX() + dx, c.y, clicked.getZ() + dz);
                Material t = b.getType();
                if (t.isAir() || t == Material.BEDROCK) continue;
                if (specials.containsKey(keyOf(b)) || !canBuild(p, b.getLocation())) continue;
                c.off.add(new int[]{dx, dz});
                c.data.add(b.getBlockData().clone());
            }
        }
        if (c.off.isEmpty()) {
            msg(p, "&cNothing to copy on this layer (only blocks on islands you can build on count).");
            return;
        }
        clipboards.put(p.getUniqueId(), c);
        msg(p, "&aCopied &e" + fmt((long) c.off.size()) + " &ablocks from layer &eY " + c.y + "&a. &7Right-click to paste it.");
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
    }

    /** Right-click: pastes the copied layer onto the layer of the clicked spot. Only fills empty spots in chunks the player may build on. */
    void wandPaste(Player p, Block clicked, BlockFace face) {
        Clip c = clipboards.get(p.getUniqueId());
        if (c == null || c.off.isEmpty()) {
            msg(p, "&cNothing copied yet. &7Shift + right-click a block to copy its layer.");
            return;
        }
        Block anchor = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        int ay = anchor.getY();
        if (ay < world.getMinHeight() || ay >= world.getMaxHeight()) return;
        if (!canBuild(p, anchor.getLocation())) {
            deny(p);
            return;
        }
        int placed = 0;
        for (int i = 0; i < c.off.size(); i++) {
            int[] o = c.off.get(i);
            Block t = world.getBlockAt(anchor.getX() + o[0], ay, anchor.getZ() + o[1]);
            if (!canBuild(p, t.getLocation()) || specials.containsKey(keyOf(t))) continue;
            if (!(airish(t) || (t.isReplaceable() && !t.isLiquid()))) continue; // never overwrite existing blocks
            t.setBlockData(c.data.get(i), false);
            placed++;
        }
        if (placed == 0) {
            p.sendActionBar(txt("&cNo free spots to paste here."));
            return;
        }
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 1f);
        p.sendActionBar(txt("&dPasted &f" + fmt((long) placed) + " &dblocks on layer Y " + ay));
    }

    // ------------------------------------------------------------------ line breaker
    ItemStack lineBreaker() {
        ItemStack it = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt("&c&lLine Breaker"));
        im.setUnbreakable(true); // never loses durability, never breaks
        im.addEnchant(Enchantment.EFFICIENCY, 5, true);
        im.setEnchantmentGlintOverride(true);
        im.lore(List.of(
                txt("&7Sneak + right-click a block to break a line of"),
                txt("&7blocks the way you face, until the island edge."),
                txt("&7Never breaks. Only works on islands you can build on.")));
        im.getPersistentDataContainer().set(KEY_BREAKER, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }

    boolean isBreaker(ItemStack it) {
        if (it == null || it.getType() != Material.NETHERITE_PICKAXE || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_BREAKER, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreakerUse(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!isBreaker(item)) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.RIGHT_CLICK_AIR) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return; // only Shift + right-click triggers the line
        EquipmentSlot hand = e.getHand();
        Block clicked = e.getClickedBlock();
        if (hand == null || clicked == null) return;
        e.setCancelled(true);
        if (!p.getWorld().equals(world)) {
            msg(p, "&cThe Line Breaker only works on the cane islands.");
            return;
        }
        breakerUse(p, hand, clicked);
    }

    /** Breaks the clicked block and every block after it in the facing direction, stopping at the edge of what the player may build on. */
    void breakerUse(Player p, EquipmentSlot hand, Block clicked) {
        long now = System.currentTimeMillis();
        Long last = pouchCooldown.get(p.getUniqueId());
        if (last != null && now - last < POUCH_COOLDOWN_MS) return;
        pouchCooldown.put(p.getUniqueId(), now);
        ItemStack tool = p.getInventory().getItem(hand);
        if (!isBreaker(tool)) return;
        BlockFace dir = p.getFacing();
        if (dir == BlockFace.UP || dir == BlockFace.DOWN) return;
        int broken = 0, steps = 0;
        Block cur = clicked;
        while (steps < POUCH_MAX_LINE) {
            if (!isBreaker(p.getInventory().getItem(hand))) break;
            if (cur.getY() < world.getMinHeight() || cur.getY() >= world.getMaxHeight()) break;
            if (!canBuild(p, cur.getLocation())) {
                if (steps == 0) deny(p);
                break;
            }
            Material t = cur.getType();
            if (!t.isAir() && !cur.isLiquid() && t != Material.BEDROCK) {
                BlockBreakEvent ev = new BlockBreakEvent(cur, p);
                getServer().getPluginManager().callEvent(ev); // our own onBreak (specials, bonus cane) + other plugins
                if (!ev.isCancelled()) {
                    if (ev.isDropItems()) {
                        cur.breakNaturally(tool);
                    } else {
                        if (cur.getState() instanceof Container c) {
                            for (ItemStack in : c.getInventory().getContents())
                                if (in != null && !in.getType().isAir()) world.dropItemNaturally(cur.getLocation().add(0.5, 0.5, 0.5), in);
                            c.getInventory().clear();
                        }
                        cur.setType(Material.AIR, true);
                    }
                    broken++;
                }
            }
            steps++;
            cur = cur.getRelative(dir);
        }
        if (broken == 0) return;
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 1f, 1f);
        p.sendActionBar(txt("&cBroke &f" + broken + " &cblock(s) &7- tool never wears out"));
    }

    // ------------------------------------------------------------------ /removesponge
    final Set<UUID> spongeJobs = new HashSet<>();

    /** Removes every sponge (dry and wet) on the player's own island (only chunks they own), one chunk per tick. */
    void cmdRemoveSponge(Player p, PD d) {
        final UUID pid = p.getUniqueId();
        if (!spongeJobs.add(pid)) {
            msg(p, "&cAlready removing sponges - give it a moment.");
            return;
        }
        final List<int[]> chunks = chunksOf(d);
        final UUID ownerId = d.id;
        final int[] idx = {0};
        final int[] removed = {0};
        msg(p, "&eRemoving all sponges (wet and dry) from your island...");
        getServer().getScheduler().runTaskTimer(this, task -> {
            try {
                if (idx[0] >= chunks.size()) {
                    task.cancel();
                    spongeJobs.remove(pid);
                    Player on = Bukkit.getPlayer(pid);
                    if (on != null) msg(on, removed[0] == 0 ? "&7No sponges found on your island." : "&aRemoved &e" + fmt((long) removed[0]) + " &asponge(s).");
                    return;
                }
                int[] c = chunks.get(idx[0]++);
                // only ever touch chunks that really belong to this player
                if (!ownerId.equals(chunkOwners.get(chunkKey(c[0], c[1])))) return;
                ChunkSnapshot snap = world.getChunkAt(c[0], c[1]).getChunkSnapshot(false, false, false);
                int minY = world.getMinHeight(), maxY = world.getMaxHeight();
                for (int y = minY; y < maxY; y++) {
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            Material t = snap.getBlockType(lx, y, lz);
                            if (t != Material.SPONGE && t != Material.WET_SPONGE) continue;
                            world.getBlockAt(c[0] * 16 + lx, y, c[1] * 16 + lz).setType(Material.AIR, false);
                            removed[0]++;
                        }
                    }
                }
            } catch (Throwable ex) {
                task.cancel();
                spongeJobs.remove(pid);
                getLogger().warning("/removesponge failed: " + ex);
            }
        }, 1L, 1L);
    }

    boolean isInf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(KEY_INF, PersistentDataType.BYTE);
    }

    String specialOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY_SPECIAL, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ shop definition
    void add(String id, Material icon, String name, double price, int max, boolean glow, String[] desc, BiPredicate<Player, PD> apply) {
        shop.add(new ShopItem(id, icon, name, price, max, desc, glow, apply));
    }

    void inf(String id, Material m, double price) {
        add(id, m, "&b\u221E Infinite " + nice(m), price, 0, true, new String[]{"&7Place it forever - it never runs out!"}, (p, d) -> {
            give(p, infinite(m));
            return true;
        });
    }

    void pack(String id, Material m, int amt, double price) {
        add(id, m, "&e" + amt + "x " + nice(m), price, 0, false, new String[]{"&7A handy stack for your island."}, (p, d) -> {
            give(p, new ItemStack(m, amt));
            return true;
        });
    }

    void perk(String id, Material icon, String name, double price, int max, String... desc) {
        add(id, icon, name, price, max, false, desc, (p, d) -> true);
    }

    void effect(String id, Material icon, String name, double price, int max, PotionEffectType t, String... desc) {
        perk(id, icon, name, price, max, desc);
        effects.put(id, t);
    }

    void buildShop() {
        // requested items
        inf("inf_dirt", Material.DIRT, 20);
        inf("inf_sand", Material.SAND, 20);
        inf("inf_glass", Material.GLASS, 20);
        add("sellcollector", Material.CHEST, "&6Auto Sell Chunk Collector", 125, 0, true,
                new String[]{"&7Counts every cane plant on your island and", "&7automatically pays you what it would grow,", "&7with all your multipliers. No breaking needed."},
                (p, d) -> {
                    give(p, specialItem("sellcollector"));
                    return true;
                });
        add("chunkhopper", Material.HOPPER, "&6Chunk Hopper", 100, 0, true,
                new String[]{"&7Collects every dropped item in its chunk", "&7into the chest it points at."},
                (p, d) -> {
                    give(p, specialItem("chunkhopper"));
                    return true;
                });
        add("infchest", Material.ENDER_CHEST, "&6Infinite Chest", 150, 0, true,
                new String[]{"&7A chest that stores any item with no limit.", "&7Right-click to take items out."},
                (p, d) -> {
                    give(p, specialItem("infchest"));
                    return true;
                });
        add("chunk", Material.GRASS_BLOCK, "&aNew Chunk", 2500, ORDER.size(), true,
                new String[]{"&7Expands your island by one more chunk.", "&7Max " + ORDER.size() + " extra chunks."},
                (p, d) -> {
                    if (!addChunk(d)) {
                        msg(p, "&cYou already own every chunk!");
                        return false;
                    }
                    msg(p, "&aA new chunk was added around your island!");
                    return true;
                });
        add("flight", Material.FEATHER, "&bFlight", 1000, 1, true,
                new String[]{"&7Fly forever!", "&eUse /isfly after buying this to be able to fly"},
                (p, d) -> {
                    d.flyOwned = true;
                    return true;
                });
        add("autoday", Material.REPEATER, "&dAuto Day", 500, 1, true,
                new String[]{"&7Unlocks &e/autocaneday&7.", "&7Automatically start the next day as soon", "&7as your quota is submitted. Toggle it on/off."},
                (p, d) -> {
                    msg(p, "&aAuto Day unlocked! Use &e/autocaneday &ato turn it on or off.");
                    return true;
                });
        add("daytoggle", Material.DAYLIGHT_DETECTOR, "&eDay / Night Control", 500, 1, true,
                new String[]{"&7Unlocks &e/daytoggle&7.", "&7Make your island permanently day or night.", "&7Only you see it - nobody else is affected."},
                (p, d) -> {
                    msg(p, "&aDay/Night Control unlocked! Use &e/daytoggle &ato switch (&e/daytoggle reset &ato go back to normal).");
                    return true;
                });

        // more infinite blocks
        inf("inf_redsand", Material.RED_SAND, 20);
        inf("inf_coarse", Material.COARSE_DIRT, 20);
        inf("inf_podzol", Material.PODZOL, 25);
        inf("inf_grass", Material.GRASS_BLOCK, 25);
        inf("inf_stonebricks", Material.STONE_BRICKS, 20);
        inf("inf_pane", Material.GLASS_PANE, 15);
        inf("inf_torch", Material.TORCH, 15);
        inf("inf_ladder", Material.LADDER, 15);
        inf("inf_scaffold", Material.SCAFFOLDING, 15);
        inf("inf_barrel", Material.BARREL, 25);
        add("dirtpouch", Material.LEATHER, "&6Dirt Pouch", 150, 0, true,
                new String[]{"&7Never runs out - unlimited dirt!", "&7Right-click: place a line of dirt in the direction you face.", "&7Only works on your island (or ones that trust you)."},
                (p, d) -> {
                    give(p, pouchItem(Material.DIRT, 0));
                    return true;
                });
        add("canepouch", Material.RABBIT_HIDE, "&6Cane Pouch", 150, 0, true,
                new String[]{"&7Never runs out - unlimited sugar cane!", "&7Right-click: plant a line of cane in the direction you face", "&7(skips spots where cane can't grow).", "&7Only works on your island (or ones that trust you)."},
                (p, d) -> {
                    give(p, pouchItem(Material.SUGAR_CANE, 0));
                    return true;
                });
        add("waterpouch", Material.NAUTILUS_SHELL, "&b\u221E Water Pouch", 150, 0, true,
                new String[]{"&7Never runs out of water!", "&7Right-click: place a line of water in the direction you face.", "&7Waterlogs leaves, slabs, stairs, grates etc.", "&7Pair it with an Infinite Sponge to clean up.", "&7Only works on your island (or ones that trust you)."},
                (p, d) -> {
                    give(p, waterPouchItem());
                    return true;
                });
        inf("inf_sponge", Material.SPONGE, 25);
        for (Material pm : new Material[]{Material.SAND, Material.OAK_LEAVES, Material.SEA_LANTERN, Material.OCHRE_FROGLIGHT, Material.VERDANT_FROGLIGHT, Material.PEARLESCENT_FROGLIGHT, Material.SPONGE}) {
            final PouchType pt = pouchByStore.get(pm);
            add("pouch_" + pm.name().toLowerCase(Locale.ROOT), pt.item, "&6" + pt.name, 150, 0, true,
                    new String[]{"&7Never runs out - unlimited " + pt.what + "!", "&7Right-click: place a line of " + pt.what + " in the direction you face.", "&7Only works on your island (or ones that trust you)."},
                    (p, d) -> {
                        give(p, pouchItem(pt.store, 0));
                        return true;
                    });
        }
        inf("inf_coppergrate", Material.COPPER_GRATE, 25);
        add("gratepouch", Material.COPPER_INGOT, "&6Copper Grate Pouch", 150, 0, true,
                new String[]{"&7Never runs out - unlimited copper grates!", "&7Right-click: place a line of grates in the direction you face.", "&7Only works on your island (or ones that trust you)."},
                (p, d) -> {
                    give(p, pouchItem(Material.COPPER_GRATE, 0));
                    return true;
                });
        pack("p_dirt", Material.DIRT, 64, 10);
        add("inf_water", Material.WATER_BUCKET, "&b\u221E Infinite Water Bucket", 60, 0, true,
                new String[]{"&7A water bucket that never empties."}, (p, d) -> {
                    give(p, infinite(Material.WATER_BUCKET));
                    return true;
                });

        // growth / economy perks
        perk("growth", Material.BONE_MEAL, "&aGrowth Boost", 50, 0,
                "&7Your sugar cane grows faster (max 2 blocks).", "&7Also speeds up your Auto Sell income. Stacks forever!");
        perk("doubledrop", Material.GOLDEN_HOE, "&6Bonus Drops", 150, 10,
                "&7+20% extra cane per level when you hand-break", "&7cane AND on everything Auto Sell earns.");
        perk("coinboost", Material.GOLD_INGOT, "&eCoin Boost", 250, 10,
                "&7+10% coins from every submit per level", "&7(Auto Sell included).");
        perk("timeext", Material.CLOCK, "&bExtra Time", 200, 6,
                "&7+5 minutes on your daily timer per level.");

        // potion perks
        effect("speed", Material.SUGAR, "&bSpeed", 40, 3, PotionEffectType.SPEED, "&7Permanent Speed. Each level is stronger.");
        effect("jump", Material.RABBIT_FOOT, "&aJump Boost", 40, 3, PotionEffectType.JUMP_BOOST, "&7Permanent Jump Boost.");
        effect("nightvision", Material.GOLDEN_CARROT, "&9Night Vision", 60, 1, PotionEffectType.NIGHT_VISION, "&7See clearly all night.");
        effect("waterbreath", Material.HEART_OF_THE_SEA, "&3Water Breathing", 60, 1, PotionEffectType.WATER_BREATHING, "&7Breathe underwater.");
        effect("saturation", Material.COOKED_BEEF, "&6Never Hungry", 120, 1, PotionEffectType.SATURATION, "&7Your hunger bar stays full.");
        effect("regen", Material.GHAST_TEAR, "&cRegeneration", 150, 2, PotionEffectType.REGENERATION, "&7Heal over time.");
        effect("resist", Material.IRON_CHESTPLATE, "&7Resistance", 200, 2, PotionEffectType.RESISTANCE, "&7Take less damage.");
        effect("slowfall", Material.FEATHER, "&fSlow Falling", 80, 1, PotionEffectType.SLOW_FALLING, "&7Float gently down.");
        effect("fireres", Material.MAGMA_CREAM, "&6Fire Resistance", 80, 1, PotionEffectType.FIRE_RESISTANCE, "&7Immune to fire and lava.");
        effect("health", Material.GLISTERING_MELON_SLICE, "&dHealth Boost", 200, 5, PotionEffectType.HEALTH_BOOST, "&7More hearts per level.");

        // packs
        add("caneseeds", Material.SUGAR_CANE, "&e16x Sugar Cane", 10, 0, false, new String[]{"&7Extra cane to plant."}, (p, d) -> {
            give(p, new ItemStack(Material.SUGAR_CANE, 16));
            return true;
        });
        pack("p_bucket", Material.BUCKET, 1, 20);
        pack("p_water", Material.WATER_BUCKET, 1, 30);
        pack("p_composter", Material.COMPOSTER, 4, 20);
        pack("p_bed", Material.RED_BED, 1, 40);
        pack("p_sign", Material.OAK_SIGN, 16, 15);
        pack("p_cake", Material.CAKE, 1, 30);
        pack("p_beef", Material.COOKED_BEEF, 64, 30);
        pack("p_carrot", Material.GOLDEN_CARROT, 32, 40);
        pack("p_rocket", Material.FIREWORK_ROCKET, 64, 25);
        pack("p_lantern", Material.LANTERN, 16, 20);
        pack("p_sealantern", Material.SEA_LANTERN, 32, 40);
        pack("p_glowstone", Material.GLOWSTONE, 32, 30);
        pack("p_quartz", Material.QUARTZ_BLOCK, 64, 40);
        pack("p_bricks", Material.BRICKS, 64, 30);
        pack("p_oak", Material.OAK_PLANKS, 64, 20);
        pack("p_cherry", Material.CHERRY_PLANKS, 64, 25);
        pack("p_shulker", Material.SHULKER_BOX, 1, 150);
        pack("p_enderchest", Material.ENDER_CHEST, 1, 120);
        pack("p_nametag", Material.NAME_TAG, 1, 30);
        pack("p_totem", Material.TOTEM_OF_UNDYING, 1, 400);
        pack("p_elytra", Material.ELYTRA, 1, 500);

        // redstone / farm engineering
        pack("r_observer", Material.OBSERVER, 8, 40);
        pack("r_piston", Material.PISTON, 8, 40);
        pack("r_sticky", Material.STICKY_PISTON, 8, 50);
        pack("r_slime", Material.SLIME_BLOCK, 16, 50);
        pack("r_honey", Material.HONEY_BLOCK, 16, 50);
        pack("r_redstone", Material.REDSTONE, 64, 25);
        pack("r_repeater", Material.REPEATER, 8, 25);
        pack("r_comparator", Material.COMPARATOR, 8, 30);
        pack("r_lever", Material.LEVER, 8, 15);
        pack("r_dropper", Material.DROPPER, 8, 30);
        pack("r_dispenser", Material.DISPENSER, 8, 30);

        // tools
        add("linebreaker", Material.NETHERITE_PICKAXE, "&c&lLine Breaker", 25, 0, true,
                new String[]{"&7Never breaks. Sneak + right-click to break a line.", "&7Handy if you lost yours."}, (p, d) -> {
                    give(p, lineBreaker());
                    return true;
                });
        add("copywand", Material.BLAZE_ROD, "&d&lCopy & Paste Wand", 100000, 0, true,
                new String[]{"&7Shift + right-click: copy one layer (up to 64x64).", "&7Right-click: paste that layer where you click.", "&7Only copies/pastes where you can build."}, (p, d) -> {
                    give(p, wandItem());
                    return true;
                });
        add("t_axe", Material.NETHERITE_AXE, "&5Cane Axe", 300, 0, true,
                new String[]{"&7Netherite axe, Efficiency V, Unbreaking III."}, (p, d) -> {
                    ItemStack it = new ItemStack(Material.NETHERITE_AXE);
                    it.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
                    it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                    give(p, it);
                    return true;
                });
        add("t_pick", Material.DIAMOND_PICKAXE, "&bCane Pickaxe", 200, 0, true,
                new String[]{"&7Diamond pickaxe, Efficiency V, Unbreaking III."}, (p, d) -> {
                    ItemStack it = new ItemStack(Material.DIAMOND_PICKAXE);
                    it.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
                    it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                    give(p, it);
                    return true;
                });
        add("t_shovel", Material.DIAMOND_SHOVEL, "&bCane Shovel", 150, 0, true,
                new String[]{"&7Diamond shovel, Efficiency V, Unbreaking III."}, (p, d) -> {
                    ItemStack it = new ItemStack(Material.DIAMOND_SHOVEL);
                    it.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
                    it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                    give(p, it);
                    return true;
                });
    }

    double priceOf(PD d, ShopItem s) {
        return Math.min(s.price * Math.pow(priceMult, Math.max(0, d.lvl(s.id) - d.priceDrop)), 1.0e15);
    }

    ItemStack iconFor(PD d, ShopItem s) {
        int n = d.lvl(s.id);
        ItemStack it = new ItemStack(s.icon);
        ItemMeta im = it.getItemMeta();
        im.displayName(txt(s.name));
        List<Component> lore = new ArrayList<>();
        for (String line : s.desc) lore.add(txt(line.startsWith("&") ? line : "&7" + line));
        lore.add(txt(" "));
        final boolean fxOwned = effects.containsKey(s.id) && n > 0;
        if (fxOwned) {
            lore.add(txt(d.on("fx_" + s.id) ? "&aEffect: Enabled" : "&cEffect: Disabled"));
            lore.add(txt("&eClick to turn it on / off"));
            lore.add(txt(" "));
        }
        if (s.max > 0 && n >= s.max) {
            lore.add(txt("&c&lSOLD OUT &7(" + n + "/" + s.max + ")"));
        } else {
            double price = priceOf(d, s);
            lore.add(txt("&6Price: &f" + fmt(price) + " coins"));
            lore.add(txt(s.max > 0 ? "&7Owned: " + n + "/" + s.max : "&7Bought: " + n));
            lore.add(txt("&8Price doubles every time you buy."));
            if (fxOwned) lore.add(txt(d.coins + 1e-9 >= price ? "&aRight-click to buy the next level!" : "&cNot enough coins"));
            else lore.add(txt(d.coins + 1e-9 >= price ? "&aClick to buy!" : "&cNot enough coins"));
        }
        if (s.glow) im.setEnchantmentGlintOverride(true);
        im.lore(lore);
        it.setItemMeta(im);
        return it;
    }

    void fillShop(Inventory inv, PD d, int page) {
        inv.clear();
        int pages = (shop.size() + 44) / 45;
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= shop.size()) break;
            inv.setItem(i, iconFor(d, shop.get(idx)));
        }
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        if (page > 0) inv.setItem(45, named(Material.ARROW, "&ePrevious page"));
        if (page < pages - 1) inv.setItem(53, named(Material.ARROW, "&eNext page"));
        inv.setItem(49, named(Material.GOLD_INGOT, "&6Your coins: &f" + fmt(d.coins),
                "&7Earn 0.5 coins for every cane you submit.", "&7Page " + (page + 1) + "/" + pages));
    }

    void openShop(Player p, PD d, int page) {
        int pages = (shop.size() + 44) / 45;
        page = Math.max(0, Math.min(page, pages - 1));
        ShopHolder h = new ShopHolder(page);
        Inventory inv = Bukkit.createInventory(h, 54, txt("&6&lCane Shop"));
        h.inv = inv;
        fillShop(inv, d, page);
        p.openInventory(inv);
    }

    void buy(Player p, PD d, ShopItem s) {
        int n = d.lvl(s.id);
        if (s.max > 0 && n >= s.max) {
            msg(p, "&cYou already have the maximum of this item!");
            return;
        }
        double price = priceOf(d, s);
        if (d.coins + 1e-9 < price) {
            msg(p, "&cYou need &e" + fmt(price) + " &ccoins (you have &e" + fmt(d.coins) + "&c).");
            snd(p, d, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        if (!s.apply.test(p, d)) return;
        d.coins -= price;
        d.bought.merge(s.id, 1, Integer::sum);
        if (effects.containsKey(s.id)) d.off.remove("fx_" + s.id); // a new level switches the effect back on
        dirty = true;
        applyEffects(p, d);
        msg(p, "&aBought &f" + s.name.replaceAll("&[0-9a-fk-or]", "") + " &afor &e" + fmt(price) + " &acoins. Next price: &e" + fmt(priceOf(d, s)));
        snd(p, d, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof ShopHolder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        PD d = players.get(p.getUniqueId());
        if (d == null) return;
        int slot = e.getRawSlot();
        if (slot == 45 && h.page > 0) {
            int np = h.page - 1;
            getServer().getScheduler().runTask(this, () -> openShop(p, d, np));
        } else if (slot == 53 && h.page < (shop.size() + 44) / 45 - 1) {
            int np = h.page + 1;
            getServer().getScheduler().runTask(this, () -> openShop(p, d, np));
        } else if (slot >= 0 && slot < 45) {
            int idx = h.page * 45 + slot;
            if (idx < shop.size()) {
                ShopItem si = shop.get(idx);
                // owned potion effect: plain click toggles it, right-click buys the next level
                if (effects.containsKey(si.id) && d.lvl(si.id) > 0 && !e.isRightClick()) toggleEffect(p, d, si);
                else buy(p, d, si);
                fillShop(top, d, h.page);
            }
        }
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder(false) instanceof ShopHolder || top.getHolder(false) instanceof OptionsHolder
                || top.getHolder(false) instanceof CommandsHolder || top.getHolder(false) instanceof InfHolder
                || top.getHolder(false) instanceof ChunksHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ perks / flight
    void toggleEffect(Player p, PD d, ShopItem si) {
        String key = "fx_" + si.id;
        boolean nowOn = !d.on(key);
        if (nowOn) d.off.remove(key);
        else d.off.add(key);
        dirty = true;
        String nm = si.name.replaceAll("&[0-9a-fk-or]", "");
        if (nowOn) {
            applyEffects(p, d);
            msg(p, "&f" + nm + " &2enabled&f.");
        } else {
            p.removePotionEffect(effects.get(si.id));
            msg(p, "&f" + nm + " &cdisabled&f.");
        }
        snd(p, d, Sound.UI_BUTTON_CLICK, 0.7f, nowOn ? 1.3f : 0.8f);
    }

    void applyEffects(Player p, PD d) {
        for (Map.Entry<String, PotionEffectType> en : effects.entrySet()) {
            int lvl = d.lvl(en.getKey());
            if (lvl > 0 && d.on("fx_" + en.getKey())) p.addPotionEffect(new PotionEffect(en.getValue(), 600, lvl - 1, true, false, true));
        }
    }

    void applyFly(Player p, PD d) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (d.flyOwned && d.flyOn) {
            if (!p.getAllowFlight()) p.setAllowFlight(true);
        }
    }

    void perkTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PD d = players.get(p.getUniqueId());
            if (d == null) continue;
            applyEffects(p, d);
            applyFly(p, d);
            applyTime(p, d);
            refreshTab(p);
        }
    }

    // ------------------------------------------------------------------ ticking tasks
    void secondTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PD d = players.get(p.getUniqueId());
            if (d == null) continue;
            long req = required(d.day);
            if (!d.completed) {
                d.timeLeft--;
                if (d.timeLeft <= 0) {
                    failDay(p, d);
                    req = required(d.day);
                } else if (d.on("warnings") && (d.timeLeft == 300 || d.timeLeft == 60 || d.timeLeft == 10)) {
                    msg(p, "&c" + mmss(d.timeLeft) + " left to finish Day " + d.day + "! (" + fmt(d.submitted) + "/" + fmt(req) + ")");
                }
                if (d.on("actionbar")) p.sendActionBar(txt("&6Day " + d.day + " &7| &f" + fmt(d.submitted) + "/" + fmt(req) + " cane &7| &c" + mmss(Math.max(d.timeLeft, 0))));
            } else {
                if (d.on("actionbar")) p.sendActionBar(txt("&aDay " + d.day + " complete! &7Use &e/caneday &7for the next day."));
            }
        }
        sleepCheck();
        autoSubmitTick();
        scoreboardTick();
    }

    void sleepCheck() {
        boolean sleeping = false;
        for (Player p : world.getPlayers()) {
            if (p.isDeeplySleeping()) {
                sleeping = true;
                break;
            }
        }
        if (!sleeping) return;
        long t = world.getTime();
        if ((t >= 12541 && t <= 23458) || world.hasStorm()) {
            world.setTime(0);
            world.setStorm(false);
            world.setThundering(false);
        }
    }

    void fastTick() {
        for (Player p : world.getPlayers()) {
            if (p.getLocation().getY() < 20) {
                PD d = players.get(p.getUniqueId());
                p.setFallDistance(0f);
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                if (d != null) p.teleport(home(d));
                else p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
        }
    }

    /** Every sugar cane base (cane with no cane below it) in the chunk, on ANY Y level. Uses a snapshot so it stays cheap. */
    List<int[]> caneBases(int cx, int cz) {
        List<int[]> out = new ArrayList<>();
        ChunkSnapshot snap = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
        int minY = world.getMinHeight();
        int sections = (world.getMaxHeight() - minY) >> 4;
        for (int sec = 0; sec < sections; sec++) {
            if (snap.isSectionEmpty(sec)) continue;
            int y0 = minY + (sec << 4);
            for (int y = y0; y < y0 + 16; y++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (snap.getBlockType(lx, y, lz) != Material.SUGAR_CANE) continue;
                        if (y > minY && snap.getBlockType(lx, y - 1, lz) == Material.SUGAR_CANE) continue;
                        out.add(new int[]{cx * 16 + lx, y, cz * 16 + lz});
                    }
                }
            }
        }
        return out;
    }

    void growthTick() {
        for (PD d : players.values()) {
            double chance = Math.min(maxChance, baseChance + perLevel * growthLevel(d));
            for (int[] c : chunksOf(d)) {
                if (!world.isChunkLoaded(c[0], c[1])) continue;
                for (int[] bp : caneBases(c[0], c[1])) {
                    Block b = world.getBlockAt(bp[0], bp[1], bp[2]);
                    int h = 1;
                    Block top = b;
                    while (h < 8 && top.getRelative(BlockFace.UP).getType() == Material.SUGAR_CANE) {
                        top = top.getRelative(BlockFace.UP);
                        h++;
                    }
                    if (h >= MAX_CANE_HEIGHT) continue; // cane only ever grows to 2 blocks
                    Block above = top.getRelative(BlockFace.UP);
                    if (airish(above) && rng.nextDouble() < chance) {
                        above.setType(Material.SUGAR_CANE, false);
                    }
                }
            }
        }
    }

    /**
     * Lag remover: if an island holds more than the threshold of entities (anything that isn't a player)
     * at once, the dropped items on that island that have been there for more than 5 seconds are cleared.
     */
    void lagTick() {
        for (PD d : players.values()) {
            int count = 0;
            List<Item> drops = new ArrayList<>();
            for (int[] c : chunksOf(d)) {
                if (!world.isChunkLoaded(c[0], c[1])) continue;
                for (Entity en : world.getChunkAt(c[0], c[1]).getEntities()) {
                    if (en instanceof Player) continue;
                    count++;
                    // only items that have been lying around longer than the min age get cleared,
                    // so fresh drops can still be picked up by a Chunk Hopper
                    if (en instanceof Item it && it.getTicksLived() > lagMinAge) drops.add(it);
                }
            }
            if (count <= lagThreshold || drops.isEmpty()) continue;
            for (Item it : drops) it.remove();
            Player op = Bukkit.getPlayer(d.id);
            if (op != null && d.on("lagmsg"))
                msg(op, "&eLag remover: your island had &c" + count + " &eentities, so &c" + drops.size() + " &eold dropped item(s) were cleared.");
        }
    }

    // ------------------------------------------------------------------ Auto Sell simulation
    /** How many cane plants (stalk bases, ANY Y level) are in a chunk. Loaded chunks are scanned; unloaded ones use the cached number. */
    int stalksIn(int cx, int cz) {
        long key = chunkKey(cx, cz);
        Integer cached = chunkStalks.get(key);
        if (cached == null || world.isChunkLoaded(cx, cz)) {
            int n = caneBases(cx, cz).size();
            chunkStalks.put(key, n);
            return n;
        }
        return cached;
    }

    /** Every cane plant on the player's island: all owned chunks, all layers. */
    int stalksOf(PD d) {
        int n = 0;
        for (int[] c : chunksOf(d)) n += stalksIn(c[0], c[1]);
        return n;
    }

    /** Chance that one plant grows a block on one growth roll (a roll every 2 seconds) - the same maths as the real growth. */
    double growthChance(PD d) {
        return Math.min(maxChance, baseChance + perLevel * growthLevel(d));
    }

    /** Cane per second the simulation earns: every growth would have been harvested, and Bonus Drops add extra cane on top. */
    double simRate(PD d, int stalks) {
        return stalks * growthChance(d) / 2.0 * (1.0 + dropChance(d));
    }

    /** Cane + coins per minute (after Coin Boost and Rebirth), for /caneinfo. */
    double simCoinsPerMinute(PD d, int stalks) {
        return simRate(d, stalks) * 60.0 * coinsPerCane * (1.0 + 0.10 * (d.lvl("coinboost") + d.rewardSell)) * rebirthMult(d);
    }

    boolean hasCollector(UUID owner) {
        for (Special s : specials.values())
            if (s.type.equals("sellcollector") && owner.equals(s.owner)) return true;
        return false;
    }

    static boolean isSpecialType(String t) {
        return t.equals("sellcollector") || t.equals("chunkhopper") || t.equals("infchest");
    }

    /** Settles the cane an owner's island "grew" since the last settle and pays it out through the normal submit() path. */
    void simulate(PD d) {
        long now = System.currentTimeMillis();
        if (d.lastSim <= 0) {
            d.lastSim = now;
            return;
        }
        long capMs = (long) (simMaxOfflineHours * 3600_000L);
        long dt = Math.max(0L, Math.min(now - d.lastSim, capMs));
        d.lastSim = now;
        if (dt <= 0) return;
        int stalks = stalksOf(d);
        if (stalks <= 0) return;
        d.carry += simRate(d, stalks) * (dt / 1000.0);
        long whole = (long) Math.floor(d.carry);
        if (whole <= 0) return;
        d.carry -= whole;
        double before = d.coins;
        submit(d, whole); // Coin Boost, daily-bonus levels, Rebirth multiplier, canetop and quota all happen in here
        double[] w = autoSubWindow.computeIfAbsent(d.id, k -> new double[]{now, 0, 0});
        w[1] += whole;
        w[2] += d.coins - before;
    }

    /** Every few seconds: drop Auto Sell / Infinite chests whose block is gone, then settle every online owner that has an Auto Sell chest. */
    void simTick() {
        Set<UUID> owners = new HashSet<>();
        for (Special s : new ArrayList<>(specials.values())) {
            if (!s.type.equals("sellcollector") && !s.type.equals("infchest")) continue;
            if (world.isChunkLoaded(s.x >> 4, s.z >> 4) && world.getBlockAt(s.x, s.y, s.z).getType() != Material.CHEST) {
                specials.remove(s.key());
                infStore.remove(s.key());
                dirty = true;
                continue;
            }
            if (s.type.equals("sellcollector")) owners.add(s.owner);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            PD d = players.get(p.getUniqueId());
            if (d == null) continue;
            if (owners.contains(d.id)) simulate(d);
            else d.lastSim = 0; // no Auto Sell chest: nothing accrues until one is placed
        }
    }

    // ------------------------------------------------------------------ Chunk Hopper + Infinite Chest
    /** Chunk Hopper: collects every dropped item in its chunk into the container it points at (or an Infinite Chest), else keeps it. */
    void chunkHopperTick() {
        for (Special s : new ArrayList<>(specials.values())) {
            if (!s.type.equals("chunkhopper")) continue;
            int cx = s.x >> 4, cz = s.z >> 4;
            if (!world.isChunkLoaded(cx, cz)) continue;
            Block hb = world.getBlockAt(s.x, s.y, s.z);
            if (hb.getType() != Material.HOPPER) {
                specials.remove(s.key());
                dirty = true;
                continue;
            }
            Block target = hb;
            if (hb.getBlockData() instanceof org.bukkit.block.data.type.Hopper hd) target = hb.getRelative(hd.getFacing());
            Special ts = specials.get(keyOf(target));
            final boolean toStore = ts != null && ts.type.equals("infchest");
            Inventory dest = null;
            if (!toStore) {
                if (ts == null && target != hb && target.getState() instanceof Container tc) dest = tc.getInventory();
                else if (hb.getState() instanceof Container own) dest = own.getInventory();
            }
            if (!toStore && dest == null) continue;
            for (Entity en : world.getChunkAt(cx, cz).getEntities()) {
                if (!(en instanceof Item it) || it.isDead() || it.getPickupDelay() >= 32767) continue;
                ItemStack st = it.getItemStack();
                if (st.getType().isAir()) continue;
                if (toStore) {
                    addStore(ts.key(), st);
                    it.remove();
                } else {
                    Map<Integer, ItemStack> left = dest.addItem(st.clone());
                    if (left.isEmpty()) it.remove();
                    else it.setItemStack(left.values().iterator().next());
                }
            }
        }
    }

    void addStore(String key, ItemStack st) {
        ItemStack one = st.clone();
        one.setAmount(1);
        infStore.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(one, (long) st.getAmount(), Long::sum);
        dirty = true;
    }

    /** Moves whatever a hopper pushed into an Infinite Chest's real inventory into its unlimited storage. */
    void absorbChest(String key) {
        Special s = specials.get(key);
        if (s == null || !s.type.equals("infchest")) return;
        if (!(world.getBlockAt(s.x, s.y, s.z).getState() instanceof Chest ch)) return;
        Inventory inv = ch.getBlockInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.getType().isAir()) continue;
            addStore(key, st);
            inv.setItem(i, null);
        }
    }

    void openInfChest(Player p, String key, int page) {
        InfHolder h = new InfHolder(key, page);
        Inventory inv = Bukkit.createInventory(h, 54, txt("&6&lInfinite Chest"));
        h.inv = inv;
        fillInf(h);
        p.openInventory(inv);
    }

    void fillInf(InfHolder h) {
        Inventory inv = h.inv;
        inv.clear();
        h.keys.clear();
        List<Map.Entry<ItemStack, Long>> list = new ArrayList<>(infStore.getOrDefault(h.key, new LinkedHashMap<>()).entrySet());
        int pages = Math.max(1, (list.size() + 44) / 45);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        long totalItems = 0;
        for (Map.Entry<ItemStack, Long> en : list) totalItems += en.getValue();
        for (int i = 0; i < 45; i++) {
            int idx = h.page * 45 + i;
            if (idx >= list.size()) break;
            ItemStack key = list.get(idx).getKey();
            long n = list.get(idx).getValue();
            h.keys.add(key);
            ItemStack icon = key.clone();
            ItemMeta im = icon.getItemMeta();
            List<Component> lore = im.hasLore() ? new ArrayList<>(im.lore()) : new ArrayList<>();
            lore.add(txt(" "));
            lore.add(txt("&6Stored: &f" + fmt(n)));
            lore.add(txt("&eLeft-click: take a stack"));
            lore.add(txt("&eRight-click: take 1"));
            lore.add(txt("&eShift-click: take 10 stacks"));
            im.lore(lore);
            icon.setItemMeta(im);
            inv.setItem(i, icon);
        }
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        if (h.page > 0) inv.setItem(45, named(Material.ARROW, "&ePrevious page"));
        if (h.page < pages - 1) inv.setItem(53, named(Material.ARROW, "&eNext page"));
        inv.setItem(49, named(Material.ENDER_CHEST, "&6Infinite Chest",
                "&7Item types: &f" + fmt((long) list.size()), "&7Items stored: &f" + fmt(totalItems), "&7Page " + (h.page + 1) + "/" + pages));
    }

    /** Bonus Drops: how many extra cane one hand-broken block gives (chance per level, can be above 100%). */
    int bonusCane(PD d) {
        double chance = dropChance(d);
        if (chance <= 0) return 0;
        int n = (int) chance;
        if (rng.nextDouble() < chance - n) n++;
        return n;
    }

    void autoSubmitTick() {
        flushAutoSubmitNotices();
    }

    /** Sends one summary per owner once their 5 minute window is over (only if the option is on and they are online). */
    void flushAutoSubmitNotices() {
        if (autoSubWindow.isEmpty()) return;
        long now = System.currentTimeMillis();
        var it = autoSubWindow.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            double[] w = en.getValue();
            if (now - (long) w[0] < AUTOSUBMIT_NOTIFY_MS) continue;
            it.remove();
            Player op = Bukkit.getPlayer(en.getKey());
            PD d = players.get(en.getKey());
            if (op != null && d != null && d.on("autosubmit"))
                msg(op, "&aYour Auto Sell Chunk Collector earned &e" + fmt((long) w[1]) + " &acane for &e" + fmt(w[2]) + " &acoins in the last 5 minutes.");
        }
    }

    // ------------------------------------------------------------------ world protection / events
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) p.sendMessage(txt(Credit.CHAT));
        }, 60L);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) refreshTab(p);
        }, 10L);
        PD d = players.get(p.getUniqueId());
        if (d == null) {
            createIsland(p);
            getServer().getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) whisperCommands(p);
            }, 20L);
            return;
        }
        d.name = p.getName();
        checkRank(d, p);
        syncRank(d);
        if (hasCollector(d.id)) simulate(d); // pay out what the island grew while they were away (capped by sim.max-offline-hours)
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) refreshTab(p);
        }, 40L);
        if (d.autoRebirth && rebirthReady(d)) doRebirth(d, p);
        if (!d.breakerGiven) {
            d.breakerGiven = true;
            give(p, lineBreaker());
            dirty = true;
            msg(p, "&aYou received a &c&lLine Breaker&a! &7Sneak + right-click a block to break a whole line.");
        }
        visiting.remove(p.getUniqueId());
        p.teleport(home(d));
        applyFly(p, d);
        applyTime(p, d);
        if (!d.pending.isEmpty()) {
            for (int rd : new ArrayList<>(d.pending)) applyReward(p, d, rd, false);
            d.pending.clear();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PD d = players.get(e.getPlayer().getUniqueId());
        if (d != null) {
            e.setRespawnLocation(home(d));
            Player p = e.getPlayer();
            getServer().getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) {
                    applyFly(p, d);
                    applyEffects(p, d);
                    applyTime(p, d);
                }
            }, 5L);
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity().getWorld().equals(world) && e.getEntity() instanceof Monster) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Block f = e.getBlock(), t = e.getToBlock();
        if (!f.getWorld().equals(world)) return;
        UUID a = chunkOwners.get(chunkKey(f.getX() >> 4, f.getZ() >> 4));
        UUID b = chunkOwners.get(chunkKey(t.getX() >> 4, t.getZ() >> 4));
        if (b == null || !b.equals(a)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getAction() == Action.PHYSICAL) return;
        if (!b.getWorld().equals(world)) return;
        if (!canBuild(e.getPlayer(), b.getLocation())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ pouch use
    @EventHandler(priority = EventPriority.HIGH)
    public void onPouchUse(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!isPouch(item)) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_BLOCK && act != Action.RIGHT_CLICK_AIR) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        // let people still open chests, doors, etc. (sneak is reserved for storing)
        if (clicked != null && !p.isSneaking() && clicked.getType().isInteractable()) return;
        // another plugin / our own protection already denied the click
        if (clicked != null && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true); // the pouch itself never does anything vanilla
        if (!p.getWorld().equals(world)) {
            msg(p, "&cPouches only work on the cane islands.");
            return;
        }
        if (p.isSneaking()) {
            pouchDeposit(p, hand);
            return;
        }
        if (clicked == null) return;
        pouchPlace(p, hand, clicked, e.getBlockFace());
    }

    void pouchDeposit(Player p, EquipmentSlot hand) {
        ItemStack pouch = p.getInventory().getItem(hand);
        Material store = pouchStore(pouch);
        if (store == null) return;
        String what = pouchWhat(store);
        if (unlimited(store)) {
            p.sendActionBar(txt("&b\u221E Your " + pouchName(store) + " never runs out - nothing to store."));
            return;
        }
        int cur = pouchAmount(pouch);
        int space = POUCH_CAP - cur;
        if (space <= 0) {
            p.sendActionBar(txt("&cYour " + pouchName(store) + " is full!"));
            return;
        }
        int moved = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36 && moved < space; i++) {
            ItemStack it = inv.getItem(i);
            // only plain items: infinite / renamed / special items are never accepted
            if (it == null || it.getType() != store || it.hasItemMeta()) continue;
            int take = Math.min(it.getAmount(), space - moved);
            moved += take;
            if (take >= it.getAmount()) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - take);
        }
        if (moved == 0) {
            p.sendActionBar(txt("&cYou have no plain " + what + " to store."));
            return;
        }
        pouch = inv.getItem(hand);
        if (pouchStore(pouch) != store) return;
        setPouchAmount(pouch, cur + moved);
        p.sendActionBar(txt("&aStored &e" + fmt(moved) + " &a" + what + " &7(" + fmt(cur + moved) + "/" + fmt(POUCH_CAP) + ")"));
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 1f);
    }

    static final int POUCH_MAX_LINE = 80; // hard cap per click (5 chunks)

    /** True if the player carries an Infinite version of this block (e.g. Infinite Dirt) - pouches then never run out. */
    boolean hasInfinite(Player p, Material m) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == m && isInf(it)) return true;
        }
        return false;
    }

    /**
     * Right-click: places a line of blocks in the direction the player is facing.
     * Dirt stops at the first obstacle or island edge. Sugar cane skips spots where it can't survive
     * (needs sand/dirt next to water) so one click can plant a whole farm row, and stops at obstacles / the edge.
     */
    void pouchPlace(Player p, EquipmentSlot hand, Block clicked, BlockFace face) {
        long now = System.currentTimeMillis();
        Long last = pouchCooldown.get(p.getUniqueId());
        if (last != null && now - last < POUCH_COOLDOWN_MS) return;
        pouchCooldown.put(p.getUniqueId(), now);

        ItemStack pouch = p.getInventory().getItem(hand);
        final Material store = pouchStore(pouch);
        if (store == null) return;
        final boolean cane = store == Material.SUGAR_CANE;
        final boolean infinite = unlimited(store) || hasInfinite(p, store);
        int amount = infinite ? Integer.MAX_VALUE : pouchAmount(pouch);
        if (amount <= 0) {
            p.sendActionBar(txt("&cYour " + pouchName(store) + " is empty! &7Sneak + right-click to store " + pouchWhat(store) + "."));
            return;
        }
        // the first block goes where vanilla would put it; the line then runs the way the player is facing
        Block first = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        BlockFace dir = p.getFacing(); // always one of the 4 horizontal directions
        if (dir == BlockFace.UP || dir == BlockFace.DOWN) return;

        int placed = 0, steps = 0;
        Block cur = first;
        Block against = clicked;
        org.bukkit.block.data.BlockData caneData = cane ? Material.SUGAR_CANE.createBlockData() : null;
        while (placed < amount && steps < POUCH_MAX_LINE) {
            if (pouchStore(p.getInventory().getItem(hand)) != store) break; // pouch gone: stop right away
            // stop at the world edge
            if (!cur.getWorld().equals(world) || cur.getY() < world.getMinHeight() || cur.getY() >= world.getMaxHeight()) break;
            // the first block may replace grass etc; every block after it must be empty air ("a block in front" = stop)
            boolean free = steps == 0 ? cur.isReplaceable() : airish(cur);
            if (store == Material.SPONGE && cur.getType() == Material.WATER) free = true; // sponges may soak up water in the line
            if (!free || specials.containsKey(keyOf(cur))) break;
            // ownership: stops at the edge of your island - never builds on / into anyone else's chunks
            if (steps == 0 && !canBuild(p, clicked.getLocation())) {
                deny(p);
                return;
            }
            if (!canBuild(p, cur.getLocation())) {
                if (steps == 0) deny(p);
                break;
            }
            // sugar cane can only go where it survives (sand/dirt next to water, or on top of cane): skip the rest
            if (cane && !caneData.isSupported(cur)) {
                steps++;
                against = cur;
                cur = cur.getRelative(dir);
                continue;
            }
            // never place inside a player / mob (also stops trapping people)
            boolean blocked = false;
            for (Entity en : world.getNearbyEntities(BoundingBox.of(cur))) {
                if (en instanceof LivingEntity le && !le.isDead()
                        && !(en instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR)) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) break;

            BlockState old = cur.getState();
            cur.setType(store, store == Material.SPONGE); // sponges need physics so they absorb nearby water
            // player-placed leaves never decay
            if (cur.getBlockData() instanceof org.bukkit.block.data.type.Leaves lv) {
                lv.setPersistent(true);
                cur.setBlockData(lv, false);
            }
            // fire a normal place event so our protection (and other plugins) get the final say
            BlockPlaceEvent ev = new BlockPlaceEvent(cur, old, against, pouch, p, true, hand);
            getServer().getPluginManager().callEvent(ev);
            if (ev.isCancelled() || !ev.canBuild()) {
                old.update(true, false);
                break;
            }
            placed++;
            steps++;
            against = cur;
            cur = cur.getRelative(dir);
        }
        if (placed == 0) {
            if (cane) p.sendActionBar(txt("&cNo valid spot - sugar cane needs sand or dirt next to water."));
            return;
        }
        ItemStack now2 = p.getInventory().getItem(hand);
        if (pouchStore(now2) != store) return; // pouch vanished mid-line
        world.playSound(first.getLocation().add(0.5, 0.5, 0.5), pouchByStore.get(store).sound, 1f, 1f);
        if (infinite) {
            refreshPouchLore(now2); // old pouches get the new "never runs out" lore
            p.sendActionBar(txt("&6Placed &f" + placed + " &6" + pouchWhat(store) + " &7- &b\u221E infinite &7(pouch untouched)"));
        } else {
            setPouchAmount(now2, amount - placed);
            p.sendActionBar(txt("&6Placed &f" + placed + " &6" + pouchWhat(store) + " &7- pouch: &f" + fmt(amount - placed) + "&7/" + fmt(POUCH_CAP)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceProtect(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        String sp = specialOf(e.getItemInHand());
        if (sp != null && !isSpecialType(sp)) {
            e.setCancelled(true);
            msg(p, "&cThis item was removed (Harvesters are gone - the &6Auto Sell Chunk Collector &cdoes the work now).");
            return;
        }
        // Infinite Hopper / Infinite Chest blocks were replaced by the Chunk Hopper and the Infinite Chest
        if (sp == null && isInf(e.getItemInHand()) && (b.getType() == Material.HOPPER || b.getType() == Material.CHEST)) {
            e.setCancelled(true);
            msg(p, "&cInfinite Hoppers / Chests were removed. Use the &6Chunk Hopper &cand &6Infinite Chest &cinstead.");
            return;
        }
        if (sp != null && !b.getWorld().equals(world)) {
            e.setCancelled(true);
            msg(p, "&cThis can only be placed on your cane island.");
            return;
        }
        if (!b.getWorld().equals(world)) return;
        if (!canBuild(p, b.getLocation())) {
            e.setCancelled(true);
            deny(p);
            return;
        }
        // sugar cane is never taller than MAX_CANE_HEIGHT (placing it on top of cane included)
        if (b.getType() == Material.SUGAR_CANE) {
            int h = 1;
            Block down = b.getRelative(BlockFace.DOWN);
            while (down.getType() == Material.SUGAR_CANE) {
                h++;
                down = down.getRelative(BlockFace.DOWN);
            }
            if (h > MAX_CANE_HEIGHT) {
                e.setCancelled(true);
                p.sendActionBar(txt("&cSugar cane can only be " + MAX_CANE_HEIGHT + " blocks tall."));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceDone(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItemInHand();
        String sp = specialOf(it);
        if (sp != null) {
            Block b = e.getBlock();
            UUID owner = chunkOwners.get(chunkKey(b.getX() >> 4, b.getZ() >> 4));
            if (owner == null) owner = p.getUniqueId();
            specials.put(keyOf(b), new Special(sp, b.getX(), b.getY(), b.getZ(), owner));
            dirty = true;
            if (sp.equals("sellcollector")) {
                PD od = players.get(owner);
                if (od != null && od.lastSim <= 0) od.lastSim = System.currentTimeMillis(); // income starts counting now
                msg(p, "&aPlaced Auto Sell Chunk Collector! &7It now pays you for every cane plant on your island.");
            } else if (sp.equals("chunkhopper")) {
                msg(p, "&aPlaced Chunk Hopper! &7Point it at a chest or Infinite Chest.");
            } else {
                msg(p, "&aPlaced Infinite Chest!");
            }
        }
        if (isInf(it)) {
            EquipmentSlot hs = e.getHand();
            ItemStack copy = it.clone();
            copy.setAmount(copy.getMaxStackSize());
            getServer().getScheduler().runTask(this, () -> {
                ItemStack cur = p.getInventory().getItem(hs);
                if (cur == null || cur.getType() == Material.AIR || cur.isSimilar(copy)) p.getInventory().setItem(hs, copy);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        if (!b.getWorld().equals(world)) return;
        if (!canBuild(p, b.getLocation())) {
            e.setCancelled(true);
            deny(p);
            return;
        }
        Special peek = specials.get(keyOf(b));
        if (peek != null && peek.type.equals("infchest")) {
            Map<ItemStack, Long> stored = infStore.get(peek.key());
            if (stored != null && !stored.isEmpty() && !p.hasPermission("caneislands.admin")) {
                e.setCancelled(true);
                msg(p, "&cEmpty the Infinite Chest first - it still stores items.");
                return;
            }
            infStore.remove(peek.key());
        }
        Special sp = specials.remove(keyOf(b));
        if (sp != null) {
            dirty = true;
            e.setDropItems(false);
            if (sp.type.equals("sellcollector")) {
                // pay out what the island grew up to now; the clock stops when the last Auto Sell chest is gone
                PD od = players.get(sp.owner);
                if (od != null && !hasCollector(sp.owner)) {
                    simulate(od);
                    od.lastSim = 0;
                }
            }
            if (p.getGameMode() != GameMode.CREATIVE)
                world.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), specialItem(sp.type));
            return;
        }
        if (b.getType() == Material.SUGAR_CANE) {
            // Cane only drops when a PLAYER breaks it. Break the whole stack here so nothing pops off by itself
            // (those pops are cancelled in onCaneItemSpawn), then drop everything once the block is gone.
            PD d = players.get(p.getUniqueId());
            int count = 1;
            Block up = b.getRelative(BlockFace.UP);
            while (up.getType() == Material.SUGAR_CANE) {
                up.setType(Material.AIR, false);
                count++;
                up = up.getRelative(BlockFace.UP);
            }
            e.setDropItems(false);
            if (p.getGameMode() != GameMode.CREATIVE) {
                int total = count;
                if (d != null) for (int i = 0; i < count; i++) total += bonusCane(d);
                final int amount = total;
                final Location at = b.getLocation().add(0.5, 0.5, 0.5);
                getServer().getScheduler().runTask(this, () -> world.dropItemNaturally(at, new ItemStack(Material.SUGAR_CANE, amount)));
            }
        }
    }

    // ------------------------------------------------------------------ cane rules
    /** Vanilla cane growth is off on the islands: cane only grows through the plugin's growth (Growth Boost), to 2 blocks. */
    @EventHandler(ignoreCancelled = true)
    public void onCaneGrow(BlockGrowEvent e) {
        if (e.getBlock().getWorld().equals(world) && e.getNewState().getType() == Material.SUGAR_CANE) e.setCancelled(true);
    }

    /**
     * Cane never drops by itself (support lost, water, pistons...): a cane item that would appear INSIDE a cane block is cancelled.
     * Player breaks are not affected - the block is already air when their drops appear.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCaneItemSpawn(ItemSpawnEvent e) {
        Item it = e.getEntity();
        if (!it.getWorld().equals(world) || it.getItemStack().getType() != Material.SUGAR_CANE) return;
        if (e.getLocation().getBlock().getType() == Material.SUGAR_CANE) e.setCancelled(true);
    }

    /** A piston that crushes cane drops nothing (the cane block is destroyed before the push). */
    void caneCrushedByPiston(Block piston, int moved, BlockFace dir) {
        Block next = piston.getRelative(dir, moved + 1);
        if (next.getType() == Material.SUGAR_CANE) next.setType(Material.AIR, false);
    }

    /** Remember how many cane plants an island chunk has when it unloads, so the Auto Sell simulation keeps counting it. */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (!e.getWorld().equals(world)) return;
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        long key = chunkKey(cx, cz);
        if (chunkOwners.containsKey(key)) chunkStalks.put(key, caneBases(cx, cz).size());
    }

    // ------------------------------------------------------------------ Infinite Chest events
    /** A hopper (or dropper...) pushed something into an Infinite Chest: move it into the unlimited storage next tick. */
    @EventHandler(ignoreCancelled = true)
    public void onHopperIntoInfChest(InventoryMoveItemEvent e) {
        if (specials.isEmpty()) return;
        Location l = e.getDestination().getLocation();
        if (l == null || !world.equals(l.getWorld())) return;
        final String key = keyOf(l.getBlock());
        Special s = specials.get(key);
        if (s == null || !s.type.equals("infchest")) return;
        getServer().getScheduler().runTask(this, () -> absorbChest(key));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInfChestOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !b.getWorld().equals(world) || b.getType() != Material.CHEST) return;
        Special s = specials.get(keyOf(b));
        if (s == null || !s.type.equals("infchest")) return;
        Player p = e.getPlayer();
        // sneak + a block in hand = place that block against the chest like normal
        if (p.isSneaking() && p.getInventory().getItemInMainHand().getType().isBlock()
                && !p.getInventory().getItemInMainHand().getType().isAir()) return;
        e.setCancelled(true);
        if (!canBuild(p, b.getLocation())) {
            deny(p);
            return;
        }
        openInfChest(p, s.key(), 0);
    }

    @EventHandler
    public void onInfClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof InfHolder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot == 45 && h.page > 0) {
            h.page--;
            fillInf(h);
            return;
        }
        if (slot == 53) {
            h.page++;
            fillInf(h);
            return;
        }
        if (slot < 0 || slot >= h.keys.size()) return;
        ItemStack key = h.keys.get(slot);
        Map<ItemStack, Long> store = infStore.get(h.key);
        Long have = store == null ? null : store.get(key);
        if (have == null || have <= 0) {
            fillInf(h);
            return;
        }
        int max = key.getMaxStackSize();
        long want = e.isShiftClick() ? 10L * max : (e.isRightClick() ? 1L : max);
        long remaining = Math.min(have, want);
        long given = 0;
        while (remaining > 0) {
            int n = (int) Math.min(remaining, max);
            ItemStack st = key.clone();
            st.setAmount(n);
            Map<Integer, ItemStack> left = p.getInventory().addItem(st);
            int notGiven = left.isEmpty() ? 0 : left.values().iterator().next().getAmount();
            given += n - notGiven;
            remaining -= n;
            if (notGiven > 0) break;
        }
        if (given <= 0) {
            p.sendActionBar(txt("&cYour inventory is full!"));
            return;
        }
        if (have - given <= 0) store.remove(key);
        else store.put(key, have - given);
        dirty = true;
        fillInf(h);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        if (e.getBlock().getWorld().equals(world) && !canBuild(p, e.getBlock().getLocation())) {
            e.setCancelled(true);
            deny(p);
            return;
        }
        ItemStack hand = p.getInventory().getItem(e.getHand());
        if (hand != null && isInf(hand)) e.setItemStack(hand.clone());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Player p = e.getPlayer();
        if (e.getBlock().getWorld().equals(world) && !canBuild(p, e.getBlock().getLocation())) {
            e.setCancelled(true);
            deny(p);
        }
    }

    @EventHandler
    public void onCraftPrep(PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (i != null && (isInf(i) || specialOf(i) != null || isPouch(i) || isWaterPouch(i) || isBreaker(i) || isWand(i))) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ island boundaries
    static final int BORDER_MARGIN = 3; // blocks players may stand outside their own chunks

    /** bx/bz are BLOCK coordinates. Own chunks + a margin of BORDER_MARGIN blocks around them are allowed. */
    boolean mayBeAt(Player p, int bx, int bz) {
        if (p.hasPermission("caneislands.admin")) return true;
        UUID me = p.getUniqueId();
        UUID here = chunkOwners.get(chunkKey(bx >> 4, bz >> 4));
        if (!players.containsKey(me)) return here == null; // no island: stay out of everyone's island
        if (me.equals(here)) return true;
        UUID vis = visiting.get(me);
        if (here != null) return here.equals(vis); // someone else's chunk: only the island being visited
        return nearOwned(me, bx, bz) || (vis != null && nearOwned(vis, bx, bz));
    }

    boolean nearOwned(UUID u, int bx, int bz) {
        int m = BORDER_MARGIN;
        for (int dx = -m; dx <= m; dx += m) {
            for (int dz = -m; dz <= m; dz += m) {
                if (dx == 0 && dz == 0) continue;
                if (u.equals(chunkOwners.get(chunkKey((bx + dx) >> 4, (bz + dz) >> 4)))) return true;
            }
        }
        return false;
    }

    void warnBoundary(Player p) {
        long now = System.currentTimeMillis();
        Long last = boundaryWarned.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        boundaryWarned.put(p.getUniqueId(), now);
        p.sendActionBar(txt("&cYou can't leave your island!"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null || !to.getWorld().equals(world)) return;
        Location from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) return;
        Player p = e.getPlayer();
        if (mayBeAt(p, to.getBlockX(), to.getBlockZ())) return;
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        warnBoundary(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleportBoundary(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (to == null || !to.getWorld().equals(world)) return;
        Player p = e.getPlayer();
        if (!mayBeAt(p, to.getBlockX(), to.getBlockZ())) {
            e.setCancelled(true);
            warnBoundary(p);
            return;
        }
        if (p.getUniqueId().equals(chunkOwners.get(chunkKey(to.getBlockX() >> 4, to.getBlockZ() >> 4))))
            visiting.remove(p.getUniqueId()); // back on own island
    }

    /** Safety net: vehicles, lag-back, other plugins etc. - anyone found outside their island is sent home. */
    void boundaryTick() {
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (l.getY() < 20) continue; // void rescue is handled in fastTick
            if (p.getUniqueId().equals(chunkOwners.get(chunkKey(l.getBlockX() >> 4, l.getBlockZ() >> 4))))
                visiting.remove(p.getUniqueId());
            if (mayBeAt(p, l.getBlockX(), l.getBlockZ())) continue;
            PD d = players.get(p.getUniqueId());
            p.setFallDistance(0f);
            p.teleport(d != null ? home(d) : Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    UUID ownerAt(Block b) {
        return chunkOwners.get(chunkKey(b.getX() >> 4, b.getZ() >> 4));
    }

    boolean pistonCrosses(Block piston, List<Block> moved, BlockFace dir, boolean extend) {
        if (!piston.getWorld().equals(world)) return false;
        UUID own = ownerAt(piston);
        if (extend && !Objects.equals(own, ownerAt(piston.getRelative(dir)))) return true;
        for (Block b : moved) {
            if (!Objects.equals(own, ownerAt(b)) || !Objects.equals(own, ownerAt(b.getRelative(dir)))) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection(), true)) {
            e.setCancelled(true);
            return;
        }
        if (e.getBlock().getWorld().equals(world)) caneCrushedByPiston(e.getBlock(), e.getBlocks().size(), e.getDirection());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection().getOppositeFace(), false)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ /options
    void buildOptions() {
        options.add(new Opt("autosubmit", "&6Auto Sell Messages", Material.CHEST, "Chat summary (at most every 5 minutes) of the cane and coins from all your Auto Sell Chunk Collectors."));
        options.add(new Opt("actionbar", "&eAction Bar", Material.OAK_SIGN, "Day, quota and timer shown above your hotbar."));
        options.add(new Opt("warnings", "&cTimer Warnings", Material.CLOCK, "Chat warnings at 5:00, 1:00 and 0:10 left."));
        options.add(new Opt("lagmsg", "&eLag Remover Notice", Material.HOPPER, "Chat message when the lag remover clears old dropped items on your island."));
        options.add(new Opt("scoreboard", "&aScoreboard", Material.PAINTING, "The sidebar with your balance, rank, cane placed and /canetop position."));
        options.add(new Opt("sounds", "&bSound Effects", Material.NOTE_BLOCK, "Sounds for completed days, rewards and purchases."));
    }

    void fillOptions(Inventory inv, PD d) {
        inv.clear();
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        for (int i = 0; i < options.size() && i < 6; i++) {
            Opt o = options.get(i);
            boolean on = d.on(o.id);
            ItemStack it = new ItemStack(o.icon);
            ItemMeta im = it.getItemMeta();
            im.displayName(txt(o.name));
            im.lore(List.of(txt("&7" + o.desc), txt(" "), txt(on ? "&aEnabled" : "&cDisabled"), txt("&eClick to toggle")));
            if (on) im.setEnchantmentGlintOverride(true);
            it.setItemMeta(im);
            inv.setItem(11 + i, it);
        }
    }

    void openOptions(Player p, PD d) {
        OptionsHolder h = new OptionsHolder();
        Inventory inv = Bukkit.createInventory(h, 27, txt("&6&lCane Options"));
        h.inv = inv;
        fillOptions(inv, d);
        p.openInventory(inv);
    }

    @EventHandler
    public void onOptionsClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof OptionsHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        PD d = players.get(p.getUniqueId());
        if (d == null) return;
        int slot = e.getRawSlot();
        if (slot < 11) return;
        int i = slot - 11;
        if (i >= options.size() || i >= 6) return;
        Opt o = options.get(i);
        if (!d.off.remove(o.id)) d.off.add(o.id);
        dirty = true;
        if (o.id.equals("scoreboard") && !d.on("scoreboard")) removeBoard(p);
        fillOptions(top, d);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1f);
    }

    // ------------------------------------------------------------------ shop commands: /autocaneday, /daytoggle
    void cmdAutoDay(Player p, PD d) {
        if (d.lvl("autoday") < 1) {
            msg(p, "&cBuy &dAuto Day &cin &e/caneshop &cfirst!");
            return;
        }
        d.autoDayOn = !d.autoDayOn;
        dirty = true;
        if (d.autoDayOn) {
            msg(p, "&aAuto Day &2enabled&a. The next day starts as soon as you finish the current one.");
            if (d.completed) nextDay(d, p); // today is already done - move on right away
        } else {
            msg(p, "&cAuto Day disabled. &7Use &e/caneday &7to move on manually.");
        }
    }

    void applyTime(Player p, PD d) {
        if (d.timeMode == 0 || d.lvl("daytoggle") < 1 || !p.getWorld().equals(world)) {
            p.resetPlayerTime();
            return;
        }
        p.setPlayerTime(d.timeMode == 1 ? 6000L : 18000L, false);
    }

    void cmdDayToggle(Player p, PD d, String[] a) {
        if (d.lvl("daytoggle") < 1) {
            msg(p, "&cBuy &eDay / Night Control &cin &e/caneshop &cfirst!");
            return;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("reset")) {
            d.timeMode = 0;
            msg(p, "&aYour island time is back to the normal day/night cycle.");
        } else {
            d.timeMode = d.timeMode == 1 ? 2 : 1;
            msg(p, d.timeMode == 1 ? "&eYour island is now always &6day&e." : "&9Your island is now always &1night&9.");
        }
        dirty = true;
        applyTime(p, d);
    }

    // ------------------------------------------------------------------ /is (visit / trust) and ground-item protection
    PD findPD(String name) {
        for (PD x : players.values()) if (x.name.equalsIgnoreCase(name)) return x;
        return null;
    }

    void cmdIs(Player p, PD d, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "visit", "v" -> {
                if (a.length < 2) {
                    msg(p, "&cUsage: &e/is visit <player>");
                    return;
                }
                PD t = findPD(a[1]);
                if (t == null) {
                    msg(p, "&cNo island found for &e" + a[1] + "&c.");
                    return;
                }
                if (t.id.equals(d.id)) {
                    msg(p, "&cThat's your own island! Use &e/canehome&c.");
                    return;
                }
                visiting.put(p.getUniqueId(), t.id);
                if (!p.teleport(home(t))) {
                    visiting.remove(p.getUniqueId());
                    return;
                }
                if (t.trusted.contains(d.id))
                    msg(p, "&aVisiting &e" + t.name + "&a's island. &7They trust you, so you can build here. Use &e/canehome &7to leave.");
                else
                    msg(p, "&aVisiting &e" + t.name + "&a's island. &7You can look around but not build unless they run &e/is trust " + p.getName() + "&7. Use &e/canehome &7to leave.");
                Player owner = Bukkit.getPlayer(t.id);
                if (owner != null) msg(owner, "&e" + p.getName() + " &7is visiting your island.");
            }
            case "trust" -> {
                if (a.length < 2) {
                    msg(p, "&cUsage: &e/is trust <player>");
                    return;
                }
                PD t = findPD(a[1]);
                if (t == null) {
                    msg(p, "&cNo player named &e" + a[1] + " &cwith an island found (they must have used /start).");
                    return;
                }
                if (t.id.equals(d.id)) {
                    msg(p, "&cYou can't trust yourself.");
                    return;
                }
                if (!d.trusted.add(t.id)) {
                    msg(p, "&e" + t.name + " &7is already trusted.");
                    return;
                }
                dirty = true;
                msg(p, "&aYou trusted &e" + t.name + "&a. They can now build and break on your island.");
                Player tp = Bukkit.getPlayer(t.id);
                if (tp != null) msg(tp, "&e" + p.getName() + " &atrusted you! Use &e/is visit " + p.getName() + " &ato go there.");
            }
            case "untrust" -> {
                if (a.length < 2) {
                    msg(p, "&cUsage: &e/is untrust <player>");
                    return;
                }
                PD t = findPD(a[1]);
                if (t == null || !d.trusted.remove(t.id)) {
                    msg(p, "&cThat player isn't trusted on your island.");
                    return;
                }
                dirty = true;
                msg(p, "&aYou removed trust from &e" + t.name + "&a.");
            }
            case "trusted", "list" -> {
                List<String> names = new ArrayList<>();
                for (UUID u : d.trusted) {
                    PD t = players.get(u);
                    if (t != null) names.add(t.name);
                }
                msg(p, names.isEmpty() ? "&7You haven't trusted anyone." : "&eTrusted: &f" + String.join(", ", names));
            }
            case "home" -> {
                p.teleport(home(d));
                msg(p, "&aWelcome home!");
            }
            case "reset" -> cmdIslandReset(p, d, a);
            default -> {
                p.sendMessage(txt("&6&l=== Island Commands ==="));
                p.sendMessage(txt("&e/is reset &7- wipe ONLY your island (progress is kept)"));
                p.sendMessage(txt("&e/is visit <player> &7- visit someone's island"));
                p.sendMessage(txt("&e/is trust <player> &7- let them build on yours"));
                p.sendMessage(txt("&e/is untrust <player> &7- remove their access"));
                p.sendMessage(txt("&e/is trusted &7- list trusted players"));
                p.sendMessage(txt("&e/is home &7- go back to your island"));
            }
        }
    }

    /** /is reset - wipes every block on the player's island but keeps all progress, coins, shop upgrades and unlocked chunks. */
    void cmdIslandReset(Player p, PD d, String[] a) {
        long now = System.currentTimeMillis();
        if (a.length > 1 && a[1].equalsIgnoreCase("confirm")) {
            Long t = islandResetConfirm.remove(p.getUniqueId());
            if (t == null || now - t > 30000) {
                msg(p, "&cNothing to confirm. Type &e/is reset &cfirst.");
                return;
            }
            doIslandReset(p, d);
            return;
        }
        islandResetConfirm.put(p.getUniqueId(), now);
        msg(p, "&c&lWARNING: &cThis wipes your whole island and everything built on it (all your chunks).");
        msg(p, "&7Your day, coins, shop upgrades and unlocked chunks are &akept&7. Placed Auto Sell Collectors, Chunk Hoppers and Infinite Chests are returned to you (Infinite Chest contents are lost).");
        msg(p, "&cThis cannot be undone. Type &e/is reset confirm &cwithin 30 seconds to continue.");
    }

    void doIslandReset(Player p, PD d) {
        if (!islandResetting.add(d.id)) {
            msg(p, "&cYour island is already being reset - give it a moment.");
            return;
        }
        // send visitors home
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(visiting.entrySet())) {
            if (!en.getValue().equals(d.id)) continue;
            visiting.remove(en.getKey());
            Player v = Bukkit.getPlayer(en.getKey());
            if (v == null) continue;
            PD vd = players.get(v.getUniqueId());
            v.teleport(vd != null ? home(vd) : Bukkit.getWorlds().get(0).getSpawnLocation());
            msg(v, "&cThis island was reset by its owner.");
        }
        final List<int[]> chunks = chunksOf(d);
        final List<int[]> area = fullArea(d);
        final Set<Long> islandChunks = new HashSet<>();
        for (int[] c : area) islandChunks.add(chunkKey(c[0], c[1]));
        // special blocks (collectors, hoppers, infinite chests): hand the items back, forget the blocks
        final List<String> refunds = new ArrayList<>();
        for (Special sp : specials.values())
            if (d.id.equals(sp.owner) || islandChunks.contains(chunkKey(sp.x >> 4, sp.z >> 4))) {
                if (d.id.equals(sp.owner)) refunds.add(sp.type);
            }
        specials.values().removeIf(sp -> d.id.equals(sp.owner) || islandChunks.contains(chunkKey(sp.x >> 4, sp.z >> 4)));
        infStore.keySet().removeIf(k -> !specials.containsKey(k));
        for (int[] c : area) chunkStalks.remove(chunkKey(c[0], c[1]));
        stalkCache.remove(d.id);
        d.lastSim = 0;
        d.carry = 0;
        p.teleport(home(d));
        for (String type : refunds) give(p, specialItem(type));
        dirty = true;
        msg(p, "&eWiping your island...");
        final UUID pid = p.getUniqueId();
        final int[] idx = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                if (idx[0] >= area.size()) {
                    buildBedrockLayer(chunks.get(0)[0], chunks.get(0)[1]);
                    islandResetting.remove(d.id);
                    Player pl = Bukkit.getPlayer(pid);
                    if (pl != null) {
                        pl.teleport(home(d));
                        msg(pl, "&a&lYour island was reset! &7Your progress and chunks were kept. Fresh bedrock floor in your home chunk.");
                    }
                    cancel();
                    return;
                }
                int[] c = area.get(idx[0]++);
                clearChunk(c[0], c[1]);
            }
        }.runTaskTimer(CaneIslands.this, 1L, 1L);
    }

    /** Players can't pick up items lying on someone else's island (stops trusted players stealing cane). */
    @EventHandler(ignoreCancelled = true)
    public void onPickupOnOthers(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Location l = e.getItem().getLocation();
        if (!l.getWorld().equals(world) || p.hasPermission("caneislands.admin")) return;
        UUID o = chunkOwners.get(chunkKey(l.getBlockX() >> 4, l.getBlockZ() >> 4));
        if (o != null && !o.equals(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuitCleanup(PlayerQuitEvent e) {
        pouchCooldown.remove(e.getPlayer().getUniqueId());
        clipboards.remove(e.getPlayer().getUniqueId());
        UUID id = e.getPlayer().getUniqueId();
        visiting.remove(id);
        resetConfirm.remove(id);
        islandResetConfirm.remove(id);
        chunkBorders.remove(id);
        rebirthConfirm.remove(id);
        boundaryWarned.remove(id);
        autoSubWindow.remove(id);
        spongeJobs.remove(id);
        boards.remove(id);
        stalkCache.remove(id);
    }

    // ------------------------------------------------------------------ /reset
    void cmdReset(Player p, PD d, String[] a) {
        long now = System.currentTimeMillis();
        if (a.length > 0 && a[0].equalsIgnoreCase("confirm")) {
            Long t = resetConfirm.remove(p.getUniqueId());
            if (t == null || now - t > 30000) {
                msg(p, "&cNothing to confirm. Type &e/reset &cfirst.");
                return;
            }
            doReset(p, d);
            return;
        }
        resetConfirm.put(p.getUniqueId(), now);
        msg(p, "&c&lWARNING: &cThis wipes EVERYTHING - day, best day, coins, shop upgrades, rewards, extra chunks, your whole island (and everything built on it) and your inventory + ender chest.");
        msg(p, "&cThis cannot be undone. Type &e/reset confirm &cwithin 30 seconds to continue.");
    }

    void doReset(Player p, PD d) {
        // send visitors home
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(visiting.entrySet())) {
            if (!en.getValue().equals(d.id)) continue;
            visiting.remove(en.getKey());
            Player v = Bukkit.getPlayer(en.getKey());
            if (v == null) continue;
            PD vd = players.get(v.getUniqueId());
            v.teleport(vd != null ? home(vd) : Bukkit.getWorlds().get(0).getSpawnLocation());
            msg(v, "&cThis island was reset by its owner.");
        }
        // forget special blocks + extra chunks
        List<int[]> chunks = chunksOf(d);
        final List<int[]> area = fullArea(d); // owned chunks + the ones they could still buy
        final Set<Long> islandChunks = new HashSet<>();
        for (int[] c : area) islandChunks.add(chunkKey(c[0], c[1]));
        specials.values().removeIf(sp -> d.id.equals(sp.owner) || islandChunks.contains(chunkKey(sp.x >> 4, sp.z >> 4)));
        infStore.keySet().removeIf(k -> !specials.containsKey(k));
        d.lastSim = 0;
        d.carry = 0;
        for (int i = 1; i < chunks.size(); i++) chunkOwners.remove(chunkKey(chunks.get(i)[0], chunks.get(i)[1]));
        // reset progress
        d.extra = 0;
        d.day = 1;
        d.bestDay = 0;
        d.runBest = 1;
        d.submitted = 0;
        d.totalCane = 0;
        d.rebirths = 0;
        d.lastRebirth = 0;
        d.fastStreak = 0;
        d.autoRebirth = false;
        d.completed = false;
        d.coins = 0;
        d.rewardGrowth = 0;
        d.rewardTime = 0;
        d.rewardDrop = 0;
        d.rewardSell = 0;
        if (d.rankTier > 0 && lpAvailable()) {
            try {
                LuckPermsHook.revokeAll(d.id, tierGroups(), getLogger());
            } catch (Throwable ex) {
                getLogger().warning("Could not remove the LuckPerms rank: " + ex);
            }
        }
        d.rankTier = 0;
        d.flyOwned = false;
        d.flyOn = false;
        d.autoDayOn = false;
        d.timeMode = 0;
        d.bought.clear();
        d.priceDrop = 0; // back to base prices
        d.off.removeIf(k -> k.startsWith("fx_"));
        d.pending.clear();
        d.trusted.clear();
        d.timeLeft = timeFor(d);
        stalkCache.remove(d.id);
        // reset the player
        p.teleport(home(d));
        p.getInventory().clear();
        p.getEnderChest().clear();
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        p.resetPlayerTime();
        // wipe EVERY block of the whole expandable island area (owned chunks AND the chunks they could still buy), all heights, pouch-placed blocks, water, light blocks...
        // right now, then give the home chunk its bedrock floor back
        for (int[] c : area) clearChunk(c[0], c[1]);
        buildBedrockLayer(chunks.get(0)[0], chunks.get(0)[1]);
        giveStarterKit(p);
        dirty = true;
        saveData();
        msg(p, "&a&lYour progress was reset! &7You're back on Day 1 with a fresh island.");
        msg(p, "&7You keep &bInfinite Dirt&7, &e64 sugar cane&7, an &bInfinite Water Bucket &7and an &6Auto Sell Chunk Collector &7on a fresh bedrock chunk.");
        msg(p, "&7Day 1 needs &e1 &7cane. You have &e" + mmss(d.timeLeft) + " &7per day.");
    }

    /** Removes every block (any height) and every non-player entity in the chunk. */
    void clearChunk(int cx, int cz) {
        if (!world.isChunkGenerated(cx, cz)) return; // never touched: nothing to remove
        org.bukkit.Chunk ch = world.getChunkAt(cx, cz);
        for (Entity en : ch.getEntities()) if (!(en instanceof Player)) en.remove();
        ChunkSnapshot snap = ch.getChunkSnapshot(false, false, false);
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        for (int y = minY; y < maxY; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (snap.getBlockType(lx, y, lz).isAir()) continue;
                    world.getBlockAt(cx * 16 + lx, y, cz * 16 + lz).setType(Material.AIR, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ commands
    @SuppressWarnings("unused")
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("caneadmin")) return admin(s, a);
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (n.equals("commands")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("list")) cmdCommands(p);
            else openCommands(p);
            return true;
        }
        PD d = players.get(p.getUniqueId());
        if (n.equals("start")) {
            cmdStart(p, d);
            return true;
        }
        if (d == null) {
            msg(p, "&cYou don't have an island yet. Use &e/start&c!");
            return true;
        }
        switch (n) {
            case "canehome" -> {
                p.teleport(home(d));
                msg(p, "&aWelcome home!");
            }
            case "sell" -> cmdSell(p, d);
            case "caneday" -> cmdDay(p, d);
            case "caneinfo" -> cmdInfo(p, d);
            case "canerewards" -> cmdRewards(p, d);
            case "canetop" -> cmdTop(p);
            case "caneshop" -> openShop(p, d, 0);
            case "isfly" -> cmdFly(p, d);
            case "options" -> openOptions(p, d);
            case "autocaneday" -> cmdAutoDay(p, d);
            case "daytoggle" -> cmdDayToggle(p, d, a);
            case "is" -> cmdIs(p, d, a);
            case "reset" -> cmdReset(p, d, a);
            case "rebirth" -> cmdRebirth(p, d, a);
            case "autorebirth" -> cmdAutoRebirth(p, d);
            case "lightblock" -> cmdLightBlock(p, d, a);
            case "removesponge" -> cmdRemoveSponge(p, d);
            case "drain" -> cmdDrain(p);
            case "chunkmanagement" -> openChunkManagement(p, d);
            case "pay" -> cmdPay(p, d, a);
            default -> {
            }
        }
        return true;
    }

    /** /commands - lists every command with its description, read straight from plugin.yml so it never goes stale. */
    void cmdCommands(Player p) {
        p.sendMessage(txt("&6&l=== Cane Islands Commands ==="));
        p.sendMessage(txt(Credit.LORE));
        for (Map.Entry<String, Map<String, Object>> en : getDescription().getCommands().entrySet()) {
            String name = en.getKey();
            if (name.equals("caneadmin") && !p.hasPermission("caneislands.admin")) continue;
            Map<String, Object> info = en.getValue();
            Object desc = info.get("description");
            Object usage = info.get("usage");
            Object aliases = info.get("aliases");
            String u = usage == null ? "/" + name : usage.toString();
            StringBuilder line = new StringBuilder("&e").append(u).append(" &7- ").append(desc == null ? "" : desc.toString());
            if (aliases instanceof List<?> al && !al.isEmpty()) {
                List<String> as = new ArrayList<>();
                for (Object o : al) as.add("/" + o);
                line.append(" &8(").append(String.join(", ", as)).append(")");
            }
            p.sendMessage(txt(line.toString()));
        }
    }

    // ------------------------------------------------------------------ /pay
    /** Parses 1500, 1.5k, 2m, 3b (case-insensitive). Returns -1 when it is not a valid number. */
    static double parseAmount(String in) {
        String t = in.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double mult = 1;
        if (t.endsWith("k")) mult = 1e3;
        else if (t.endsWith("m")) mult = 1e6;
        else if (t.endsWith("b")) mult = 1e9;
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mult;
            return Double.isNaN(v) || Double.isInfinite(v) ? -1 : v;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** /pay <player> <amount> - sends coins to another player (works for offline players that have an island). */
    void cmdPay(Player p, PD d, String[] a) {
        if (a.length < 2) {
            msg(p, "&cUsage: &e/pay <player> <amount>");
            return;
        }
        PD t = findPD(a[0]);
        if (t == null) {
            msg(p, "&cNo player named &e" + a[0] + " &cwith an island found (they must have used /start).");
            return;
        }
        if (t.id.equals(d.id)) {
            msg(p, "&cYou can't pay yourself.");
            return;
        }
        double amt = Math.floor(parseAmount(a[1]));
        if (amt < 1) {
            msg(p, "&cEnter a whole amount of at least 1 coin, e.g. &e/pay " + t.name + " 500 &cor &e1.5k&c.");
            return;
        }
        if (d.coins < amt) {
            msg(p, "&cYou only have &e" + fmt((long) d.coins) + " &ccoins.");
            return;
        }
        d.coins -= amt;
        t.coins += amt;
        dirty = true;
        msg(p, "&aYou paid &e" + fmt((long) amt) + " &acoins to &e" + t.name + "&a.");
        Player tp = Bukkit.getPlayer(t.id);
        if (tp != null) msg(tp, "&e" + p.getName() + " &apaid you &e" + fmt((long) amt) + " &acoins!");
    }

    // ------------------------------------------------------------------ /chunkmanagement
    static final int CM_BORDERS = 47, CM_INFO = 49, CM_BLOCKS = 51;

    void openChunkManagement(Player p, PD d) {
        ChunksHolder h = new ChunksHolder();
        Inventory inv = Bukkit.createInventory(h, 54, txt("&6&lChunk Management"));
        h.inv = inv;
        fillChunkManagement(inv, p, d);
        p.openInventory(inv);
    }

    void fillChunkManagement(Inventory inv, Player p, PD d) {
        inv.clear();
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        int bx = baseX(d), bz = baseZ(d);
        // 5x5 map of the island area, north (-Z) at the top, east (+X) on the right, in columns 2-6
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int cx = bx + dx, cz = bz + dz;
                int slot = (dz + 2) * 9 + (dx + 2) + 2;
                int order = -1;
                for (int i = 0; i < ORDER.size(); i++) if (ORDER.get(i)[0] == dx && ORDER.get(i)[1] == dz) order = i;
                ItemStack it;
                if (dx == 0 && dz == 0) {
                    it = named(Material.LIME_CONCRETE, "&a&lHome chunk", "&7Chunk &f" + cx + ", " + cz, "&7Blocks &f" + (cx * 16) + " &7to &f" + (cx * 16 + 15) + "&7, &f" + (cz * 16) + " &7to &f" + (cz * 16 + 15), "&aUnlocked");
                } else if (order >= 0 && order < d.extra) {
                    it = named(Material.GREEN_STAINED_GLASS, "&aChunk #" + (order + 2), "&7Chunk &f" + cx + ", " + cz, "&7Blocks &f" + (cx * 16) + " &7to &f" + (cx * 16 + 15) + "&7, &f" + (cz * 16) + " &7to &f" + (cz * 16 + 15), "&aUnlocked");
                } else {
                    it = named(Material.RED_STAINED_GLASS, "&cLocked chunk", "&7Buy &aNew Chunk &7in &e/caneshop &7to unlock it.");
                }
                inv.setItem(slot, it);
            }
        }
        boolean borders = chunkBorders.contains(p.getUniqueId());
        ItemStack b = named(Material.ENDER_EYE, "&bShow My Chunks",
                "&7Draws particle lines around the chunks you own.",
                "&7Green = edge of your island, aqua = chunk lines.",
                " ", borders ? "&aEnabled" : "&cDisabled", "&eClick to toggle");
        if (borders) {
            ItemMeta im = b.getItemMeta();
            im.setEnchantmentGlintOverride(true);
            b.setItemMeta(im);
        }
        inv.setItem(CM_BORDERS, b);
        boolean blocks = d.on("chunkblocks");
        ItemStack t = named(blocks ? Material.GRASS_BLOCK : Material.GLASS, "&6New Chunks Have Blocks",
                "&7When &aON&7, newly unlocked chunks come with the", "&7dirt, sand and water layout like the starter island.",
                "&7When &cOFF&7, you only get the chunk and building", "&7permission - no blocks are placed.",
                " ", blocks ? "&aEnabled" : "&cDisabled", "&eClick to toggle");
        if (blocks) {
            ItemMeta im = t.getItemMeta();
            im.setEnchantmentGlintOverride(true);
            t.setItemMeta(im);
        }
        inv.setItem(CM_BLOCKS, t);
        inv.setItem(CM_INFO, named(Material.FILLED_MAP, "&6Your chunks", "&7Unlocked: &f" + (d.extra + 1) + "&7/" + (ORDER.size() + 1), "&8North is up, east is right."));
    }

    @EventHandler
    public void onChunkManagementClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof ChunksHolder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        PD d = players.get(p.getUniqueId());
        if (d == null) return;
        int slot = e.getRawSlot();
        if (slot == CM_BORDERS) {
            if (!chunkBorders.remove(p.getUniqueId())) chunkBorders.add(p.getUniqueId());
        } else if (slot == CM_BLOCKS) {
            if (!d.off.remove("chunkblocks")) d.off.add("chunkblocks");
            dirty = true;
        } else return;
        fillChunkManagement(top, p, d);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1f);
    }

    /** Draws particle lines (only the viewer sees them) around the player's own chunks while the toggle is on. */
    void chunkBorderTick() {
        if (chunkBorders.isEmpty()) return;
        for (UUID id : new ArrayList<>(chunkBorders)) {
            Player p = Bukkit.getPlayer(id);
            PD d = players.get(id);
            if (p == null || d == null) {
                chunkBorders.remove(id);
                continue;
            }
            if (!p.getWorld().equals(world)) continue;
            Location pl = p.getLocation();
            double py = pl.getY();
            final double range = 40;
            Set<Long> mine = new HashSet<>();
            List<int[]> list = chunksOf(d);
            for (int[] c : list) mine.add(chunkKey(c[0], c[1]));
            Particle.DustOptions outer = new Particle.DustOptions(org.bukkit.Color.fromRGB(60, 255, 60), 1.3f);
            Particle.DustOptions inner = new Particle.DustOptions(org.bukkit.Color.fromRGB(80, 220, 255), 0.8f);
            for (int[] c : list) {
                double x0 = c[0] * 16, z0 = c[1] * 16, x1 = x0 + 16, z1 = z0 + 16;
                if (x1 < pl.getX() - range || x0 > pl.getX() + range || z1 < pl.getZ() - range || z0 > pl.getZ() + range) continue;
                // sides: 0 = north, 1 = south, 2 = west, 3 = east
                for (int side = 0; side < 4; side++) {
                    int nx = c[0] + (side == 2 ? -1 : side == 3 ? 1 : 0), nz = c[1] + (side == 0 ? -1 : side == 1 ? 1 : 0);
                    boolean neighbourMine = mine.contains(chunkKey(nx, nz));
                    if (neighbourMine && (side == 0 || side == 2)) continue; // that line is drawn by the neighbour
                    Particle.DustOptions opt = neighbourMine ? inner : outer;
                    double step = neighbourMine ? 4 : 2;
                    for (double t = 0; t <= 16; t += step) {
                        double x = side < 2 ? x0 + t : (side == 2 ? x0 : x1);
                        double z = side < 2 ? (side == 0 ? z0 : z1) : z0 + t;
                        if (Math.abs(x - pl.getX()) > range || Math.abs(z - pl.getZ()) > range) continue;
                        p.spawnParticle(Particle.DUST, x, py + 0.3, z, 1, 0, 0, 0, 0, opt);
                        p.spawnParticle(Particle.DUST, x, py + 1.7, z, 1, 0, 0, 0, 0, opt);
                    }
                }
                // vertical lines on the corners that touch the outside
                double[][] corners = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}};
                for (double[] k : corners) {
                    if (Math.abs(k[0] - pl.getX()) > range || Math.abs(k[1] - pl.getZ()) > range) continue;
                    for (double y = py - 2; y <= py + 4; y += 1)
                        p.spawnParticle(Particle.DUST, k[0], y, k[1], 1, 0, 0, 0, 0, outer);
                }
            }
        }
    }

    // ------------------------------------------------------------------ /commands GUI
    static Material cmdIcon(String name) {
        return switch (name) {
            case "start" -> Material.GRASS_BLOCK;
            case "canehome" -> Material.RED_BED;
            case "sell" -> Material.SUGAR_CANE;
            case "caneday" -> Material.CLOCK;
            case "caneinfo" -> Material.BOOK;
            case "canerewards" -> Material.CHEST;
            case "canetop" -> Material.NETHER_STAR;
            case "caneshop" -> Material.EMERALD;
            case "isfly" -> Material.FEATHER;
            case "options" -> Material.COMPARATOR;
            case "autocaneday" -> Material.REPEATER;
            case "daytoggle" -> Material.DAYLIGHT_DETECTOR;
            case "is" -> Material.OAK_DOOR;
            case "rebirth" -> Material.TOTEM_OF_UNDYING;
            case "autorebirth" -> Material.ENDER_EYE;
            case "reset" -> Material.TNT;
            case "lightblock" -> Material.GLOWSTONE;
            case "removesponge" -> Material.WET_SPONGE;
            case "drain" -> Material.BUCKET;
            case "chunkmanagement" -> Material.FILLED_MAP;
            case "pay" -> Material.GOLD_INGOT;
            case "commands" -> Material.KNOWLEDGE_BOOK;
            case "caneadmin" -> Material.COMMAND_BLOCK;
            default -> Material.PAPER;
        };
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + w.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** /commands - a chest menu with every command and what it does (read from plugin.yml, so it never goes stale). */
    void openCommands(Player p) {
        CommandsHolder h = new CommandsHolder();
        Inventory inv = Bukkit.createInventory(h, 54, txt("&6&lCane Commands"));
        h.inv = inv;
        int slot = 0;
        for (Map.Entry<String, Map<String, Object>> en : getDescription().getCommands().entrySet()) {
            String name = en.getKey();
            if (name.equals("caneadmin") && !p.hasPermission("caneislands.admin")) continue;
            if (slot >= 45) break;
            Map<String, Object> info = en.getValue();
            Object desc = info.get("description");
            Object usage = info.get("usage");
            Object aliases = info.get("aliases");
            ItemStack it = new ItemStack(cmdIcon(name));
            ItemMeta im = it.getItemMeta();
            im.displayName(txt("&e" + (usage == null ? "/" + name : usage.toString())));
            List<Component> lore = new ArrayList<>();
            if (desc != null) for (String ln : wrap(desc.toString(), 38)) lore.add(txt("&7" + ln));
            if (aliases instanceof List<?> al && !al.isEmpty()) {
                List<String> as = new ArrayList<>();
                for (Object o : al) as.add("/" + o);
                lore.add(txt(" "));
                lore.add(txt("&8Aliases: " + String.join(", ", as)));
            }
            lore.add(txt(" "));
            lore.add(txt("&aClick to run /" + name));
            im.lore(lore);
            it.setItemMeta(im);
            h.names.add(name);
            inv.setItem(slot++, it);
        }
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(49, named(Material.BOOK, "&6Cane Islands", "&7" + slot + " commands available to you.", "&8Click a command to run it.", Credit.LORE));
        p.openInventory(inv);
    }

    @EventHandler
    public void onCommandsClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof CommandsHolder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= h.names.size()) return;
        final String name = h.names.get(slot);
        if (name.equals("commands")) return; // already in the menu
        // close the menu, then run the command next tick (so GUIs like /caneshop open cleanly)
        getServer().getScheduler().runTask(this, () -> {
            p.closeInventory();
            p.performCommand(name);
        });
    }

    /** Pressing the offhand key (F by default) on the cane islands opens the /commands menu. */
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!p.getWorld().equals(world)) return;
        e.setCancelled(true);
        openCommands(p);
    }

    // ------------------------------------------------------------------ /drain
    /** /drain - removes all water from the chunk the player stands in, on the layer (Y) they stand at only. */
    void cmdDrain(Player p) {
        if (!p.getWorld().equals(world)) {
            msg(p, "&cThis only works on the cane islands.");
            return;
        }
        if (!canBuild(p, p.getLocation())) {
            msg(p, "&cYou can only use this in a chunk of your island (or one that trusts you).");
            return;
        }
        final int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        final int y = p.getLocation().getBlockY();
        int drained = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                Block b = world.getBlockAt(cx * 16 + lx, y, cz * 16 + lz);
                Material t = b.getType();
                if (t == Material.WATER || t == Material.BUBBLE_COLUMN || t == Material.SEAGRASS
                        || t == Material.TALL_SEAGRASS || t == Material.KELP || t == Material.KELP_PLANT) {
                    b.setType(Material.AIR, false);
                    drained++;
                } else if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
                    wl.setWaterlogged(false); // leaves, slabs, stairs, grates... keep the block, lose the water
                    b.setBlockData(wl, false);
                    drained++;
                }
            }
        }
        if (drained == 0) {
            msg(p, "&7There is no water on this layer (Y " + y + ") of this chunk.");
            return;
        }
        p.playSound(p.getLocation(), Sound.ITEM_BUCKET_FILL, 1f, 1f);
        msg(p, "&aDrained &e" + fmt((long) drained) + " &awater block(s) from layer &eY " + y + " &ain this chunk.");
    }

    // ------------------------------------------------------------------ /lightblock
    final Set<Long> lightJobs = new HashSet<>();

    static boolean airish(Block b) {
        Material t = b.getType();
        return t.isAir() || t == Material.LIGHT;
    }

    /** Fills every empty block of the chunk you stand in with a light level 15 block (/lightblock remove undoes it). */
    void cmdLightBlock(Player p, PD d, String[] a) {
        if (!p.getWorld().equals(world)) {
            msg(p, "&cThis only works on the cane islands.");
            return;
        }
        if (!canBuild(p, p.getLocation())) {
            msg(p, "&cYou can only use this in a chunk of your island (or one that trusts you).");
            return;
        }
        final int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        final long key = chunkKey(cx, cz);
        if (!lightJobs.add(key)) {
            msg(p, "&cThis chunk is already being processed - give it a moment.");
            return;
        }
        final boolean remove = a.length > 0 && a[0].equalsIgnoreCase("remove");
        final int minY = Math.max(world.getMinHeight(), getConfig().getInt("lightblock.min-y"));
        final int maxY = Math.min(world.getMaxHeight() - 1, getConfig().getInt("lightblock.max-y"));
        final UUID pid = p.getUniqueId();
        final org.bukkit.block.data.type.Light lightData = (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
        lightData.setLevel(15);
        msg(p, remove ? "&eRemoving light blocks from this chunk..." : "&eFilling this chunk with light level 15 (Y " + minY + "-" + maxY + ")...");
        final int[] y = {minY};
        final int[] changed = {0};
        getServer().getScheduler().runTaskTimer(this, task -> {
            // two layers per tick so big chunks don't lag the server
            for (int n = 0; n < 2 && y[0] <= maxY; n++, y[0]++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Block b = world.getBlockAt(cx * 16 + lx, y[0], cz * 16 + lz);
                        if (remove) {
                            if (b.getType() == Material.LIGHT) {
                                b.setType(Material.AIR, false);
                                changed[0]++;
                            }
                        } else if (b.getType().isAir()) {
                            b.setBlockData(lightData, false);
                            changed[0]++;
                        }
                    }
                }
            }
            if (y[0] > maxY) {
                task.cancel();
                lightJobs.remove(key);
                Player on = Bukkit.getPlayer(pid);
                if (on != null)
                    msg(on, remove ? "&aRemoved &e" + fmt((long) changed[0]) + " &alight blocks." : "&aDone! &e" + fmt((long) changed[0]) + " &ablocks now have light level 15. &7(/lightblock remove to undo)");
            }
        }, 1L, 1L);
    }

    void cmdStart(Player p, PD existing) {
        if (existing != null) {
            msg(p, "&cYou already have an island! Use &e/canehome &cto go back.");
            return;
        }
        createIsland(p);
    }

    /** Creates a brand new island for the player, gives the starter kit and teleports them there. */
    PD createIsland(Player p) {
        PD d = new PD(p.getUniqueId());
        d.name = p.getName();
        d.slot = nextSlot++;
        d.timeLeft = timeFor(d);
        players.put(d.id, d);
        indexChunks(d);
        buildBedrockLayer(baseX(d), baseZ(d));
        d.breakerGiven = true;
        giveStarterKit(p);
        p.teleport(home(d));
        dirty = true;
        saveData();
        msg(p, "&aYour island is ready! Farm sugar cane, then &e/sell &ato hand it in.");
        msg(p, "&7Day 1 needs &e1 &7cane. Each day doubles. You have &e" + mmss(d.timeLeft) + " &7per day.");
        return d;
    }

    /** Server whisper with the key commands, sent to brand new players. */
    void whisperCommands(Player p) {
        p.sendMessage(txt("&7&o[Server -> You] Welcome to Cane Islands! Useful commands:"));
        p.sendMessage(txt("&7&o[Server -> You] &e/caneshop &7&o- buy upgrades"));
        p.sendMessage(txt("&7&o[Server -> You] &e/sell &7&o- sell your sugar cane"));
        p.sendMessage(txt("&7&o[Server -> You] &e/isfly &7&o- toggle flight"));
        p.sendMessage(txt("&7&o[Server -> You] &e/is help &7&o- visit and trust other islands"));
        p.sendMessage(txt("&7&o[Server -> You] &e/commands &7&o- list every command"));
    }

    void cmdSell(Player p, PD d) {
        long total = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == Material.SUGAR_CANE) {
                total += it.getAmount();
                inv.setItem(i, null);
            }
        }
        if (total == 0) {
            msg(p, "&cYou have no sugar cane in your inventory!");
            return;
        }
        double before = d.coins;
        submit(d, total);
        msg(p, "&aSubmitted &e" + fmt(total) + " &acane! &7(+" + fmt(d.coins - before) + " coins)");
        if (!d.completed) msg(p, "&7Progress: &f" + fmt(d.submitted) + "/" + fmt(required(d.day)));
    }

    void cmdDay(Player p, PD d) {
        if (!d.completed) {
            msg(p, "&cFinish Day " + d.day + " first! &7(" + fmt(d.submitted) + "/" + fmt(required(d.day)) + ", " + mmss(Math.max(0, d.timeLeft)) + " left)");
            return;
        }
        nextDay(d, p);
    }

    void cmdInfo(Player p, PD d) {
        p.sendMessage(txt("&6&l=== Cane Island ==="));
        p.sendMessage(txt(Credit.LORE));
        p.sendMessage(txt("&eDay: &f" + d.day + " &7(best passed: " + d.bestDay + ")"));
        p.sendMessage(txt("&eQuota: &f" + fmt(d.submitted) + "/" + fmt(required(d.day)) + (d.completed ? " &a(complete - /caneday)" : "")));
        if (!d.completed) p.sendMessage(txt("&eTime left: &f" + mmss(Math.max(0, d.timeLeft))));
        p.sendMessage(txt("&eCoins: &f" + fmt(d.coins)));
        p.sendMessage(txt("&eTotal cane submitted: &f" + fmt(d.totalCane)));
        p.sendMessage(txt("&eRebirths: &f" + d.rebirths + " &7(x" + String.format(Locale.ROOT, "%.2f", rebirthMult(d)) + " cane + coins)  &eNext rebirth: &fDay " + rebirthDay(d)));
        p.sendMessage(txt("&eBonus Drops: &f" + (d.lvl("doubledrop") + d.rewardDrop) + " &7(" + d.rewardDrop + " free)   &eCoin Boost: &f" + (d.lvl("coinboost") + d.rewardSell) + " &7(" + d.rewardSell + " free)"));
        p.sendMessage(txt("&eChunks: &f" + (d.extra + 1) + "&7/" + (ORDER.size() + 1) + "   &eGrowth level: &f" + growthLevel(d)));
        if (hasCollector(d.id)) {
            int st = stalksOf(d);
            p.sendMessage(txt("&eAuto Sell: &f" + fmt((long) st) + " &7plants = &f" + fmt(simRate(d, st) * 60.0) + " &7cane/min = &f"
                    + fmt(simCoinsPerMinute(d, st)) + " &7coins/min (with all your multipliers)"));
        } else {
            p.sendMessage(txt("&eAuto Sell: &7place an &6Auto Sell Chunk Collector &7(/caneshop) to earn from your plants automatically."));
        }
        String next = d.day <= 100 ? reward(d.day).desc : "Random reward from the Day 1-100 pool";
        p.sendMessage(txt("&eThis day's reward: &f" + (d.day > d.bestDay ? next : "&7none (already claimed)")));
    }

    void cmdRewards(Player p, PD d) {
        p.sendMessage(txt("&6&l=== Upcoming Rewards ==="));
        for (int rd = d.day; rd < d.day + 6; rd++) {
            String line = rd <= 100 ? reward(rd).desc : "Random reward from Day 1-100";
            boolean claimed = rd <= d.bestDay;
            p.sendMessage(txt("&eDay " + rd + ": " + (claimed ? "&8" : "&f") + line + (claimed ? " &7(already claimed)" : "")));
        }
    }

    void cmdTop(Player p) {
        List<PD> list = new ArrayList<>(players.values());
        list.sort((x, y) -> Long.compare(y.totalCane, x.totalCane));
        p.sendMessage(txt("&6&l=== Cane Top ==="));
        int shown = Math.min(10, list.size());
        for (int i = 0; i < shown; i++) {
            PD d = list.get(i);
            p.sendMessage(txt("&6#" + (i + 1) + " &f" + d.name + " &7- &e" + fmt(d.totalCane) + " cane &7- &bDay " + d.day + (d.rebirths > 0 ? " &7- &dR" + d.rebirths : "")));
        }
        int rank = list.indexOf(players.get(p.getUniqueId())) + 1;
        if (rank > 0) p.sendMessage(txt("&7Your rank: &e#" + rank));
    }

    void cmdFly(Player p, PD d) {
        if (!d.flyOwned) {
            msg(p, "&cBuy &bFlight &cin &e/caneshop &cfirst!");
            return;
        }
        d.flyOn = !d.flyOn;
        dirty = true;
        if (d.flyOn) {
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) p.setAllowFlight(true);
            msg(p, "&aFlight &2enabled&a.");
        } else {
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            msg(p, "&cFlight disabled.");
        }
    }

    boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("caneislands.admin")) {
            msg(s, "&cNo permission.");
            return true;
        }
        if (a.length < 2) {
            msg(s, "&e/caneadmin <coins|day|chunk> <player> [amount]");
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        PD d = t == null ? null : players.get(t.getUniqueId());
        if (d == null) {
            msg(s, "&cPlayer must be online and have an island.");
            return true;
        }
        double val = 0;
        if (a.length > 2) {
            try {
                val = Double.parseDouble(a[2]);
            } catch (NumberFormatException ex) {
                msg(s, "&cInvalid number.");
                return true;
            }
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "coins" -> {
                d.coins += val;
                msg(s, "&aGave " + fmt(val) + " coins.");
            }
            case "day" -> {
                d.day = Math.max(1, (int) val);
                if (d.day > d.runBest) d.runBest = d.day;
                d.submitted = 0;
                d.completed = false;
                d.timeLeft = timeFor(d);
                msg(s, "&aSet day to " + d.day + ".");
                checkRank(d, t);
                checkRebirthReady(d, t);
            }
            case "chunk" -> msg(s, addChunk(d) ? "&aAdded a chunk." : "&cAlready at max chunks.");
            default -> msg(s, "&e/caneadmin <coins|day|chunk> <player> [amount]");
        }
        dirty = true;
        return true;
    }

    // ------------------------------------------------------------------ persistence
    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("nextSlot", nextSlot);
        for (PD d : players.values()) {
            String k = "players." + d.id + ".";
            y.set(k + "name", d.name);
            y.set(k + "slot", d.slot);
            y.set(k + "extra", d.extra);
            y.set(k + "day", d.day);
            y.set(k + "bestDay", d.bestDay);
            y.set(k + "runBest", d.runBest);
            y.set(k + "priceDrop", d.priceDrop);
            y.set(k + "timeLeft", d.timeLeft);
            y.set(k + "rewardGrowth", d.rewardGrowth);
            y.set(k + "rewardTime", d.rewardTime);
            y.set(k + "rewardDrop", d.rewardDrop);
            y.set(k + "rewardSell", d.rewardSell);
            y.set(k + "rankTier", d.rankTier);
            y.set(k + "submitted", d.submitted);
            y.set(k + "totalCane", d.totalCane);
            y.set(k + "rebirths", d.rebirths);
            y.set(k + "lastRebirth", d.lastRebirth);
            y.set(k + "fastStreak", d.fastStreak);
            y.set(k + "autoRebirth", d.autoRebirth);
            y.set(k + "breakerGiven", d.breakerGiven);
            y.set(k + "completed", d.completed);
            y.set(k + "flyOwned", d.flyOwned);
            y.set(k + "flyOn", d.flyOn);
            y.set(k + "autoDayOn", d.autoDayOn);
            y.set(k + "timeMode", d.timeMode);
            y.set(k + "off", new ArrayList<>(d.off));
            List<String> tl = new ArrayList<>();
            for (UUID tu : d.trusted) tl.add(tu.toString());
            y.set(k + "trusted", tl);
            y.set(k + "coins", d.coins);
            y.set(k + "lastSim", d.lastSim);
            y.set(k + "carry", d.carry);
            y.set(k + "pending", d.pending);
            for (Map.Entry<String, Integer> en : d.bought.entrySet()) y.set(k + "bought." + en.getKey(), en.getValue());
        }
        List<String> sp = new ArrayList<>();
        for (Special s : specials.values()) sp.add(s.ser());
        y.set("specials", sp);
        List<String> inf = new ArrayList<>();
        for (Map.Entry<String, Map<ItemStack, Long>> chest : infStore.entrySet()) {
            for (Map.Entry<ItemStack, Long> row : chest.getValue().entrySet()) {
                if (row.getValue() <= 0) continue;
                inf.add(chest.getKey() + "|" + row.getValue() + "|" + Base64.getEncoder().encodeToString(row.getKey().serializeAsBytes()));
            }
        }
        y.set("infstore", inf);
        try {
            y.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().severe("Could not save data: " + ex.getMessage());
        }
    }

    void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        nextSlot = y.getInt("nextSlot", 0);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                ConfigurationSection c = ps.getConfigurationSection(k);
                if (c == null) continue;
                PD d;
                try {
                    d = new PD(UUID.fromString(k));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                d.name = c.getString("name", "Unknown");
                d.slot = c.getInt("slot");
                d.extra = c.getInt("extra");
                d.day = Math.max(1, c.getInt("day", 1));
                d.bestDay = c.getInt("bestDay");
                d.runBest = Math.max(1, c.getInt("runBest", d.day));
                d.priceDrop = Math.max(0, c.getInt("priceDrop"));
                d.timeLeft = c.getInt("timeLeft", 1800);
                d.rewardGrowth = c.getInt("rewardGrowth");
                d.rewardTime = c.getInt("rewardTime");
                d.rewardDrop = c.getInt("rewardDrop");
                d.rewardSell = c.getInt("rewardSell");
                d.rankTier = c.getInt("rankTier");
                d.submitted = c.getLong("submitted");
                d.totalCane = c.getLong("totalCane");
                d.rebirths = c.getInt("rebirths");
                d.lastRebirth = c.getLong("lastRebirth");
                d.fastStreak = c.getInt("fastStreak");
                d.autoRebirth = c.getBoolean("autoRebirth");
                d.breakerGiven = c.getBoolean("breakerGiven");
                d.completed = c.getBoolean("completed");
                d.flyOwned = c.getBoolean("flyOwned");
                d.flyOn = c.getBoolean("flyOn");
                d.autoDayOn = c.getBoolean("autoDayOn");
                d.timeMode = c.getInt("timeMode");
                d.off.addAll(c.getStringList("off"));
                for (String ts : c.getStringList("trusted")) {
                    try {
                        d.trusted.add(UUID.fromString(ts));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                d.coins = c.getDouble("coins");
                d.lastSim = c.getLong("lastSim");
                d.carry = c.getDouble("carry");
                d.pending.addAll(c.getIntegerList("pending"));
                ConfigurationSection b = c.getConfigurationSection("bought");
                if (b != null) for (String id : b.getKeys(false)) d.bought.put(id, b.getInt(id));
                players.put(d.id, d);
                indexChunks(d);
            }
        }
        for (String line : y.getStringList("specials")) {
            Special s = Special.parse(line);
            // old Harvesters (and anything else that no longer exists) are simply forgotten
            if (s != null && isSpecialType(s.type)) specials.put(s.key(), s);
        }
        for (String line : y.getStringList("infstore")) {
            try {
                String[] part = line.split("\\|", 3);
                if (part.length < 3) continue;
                ItemStack st = ItemStack.deserializeBytes(Base64.getDecoder().decode(part[2]));
                st.setAmount(1);
                infStore.computeIfAbsent(part[0], k -> new LinkedHashMap<>()).merge(st, Long.parseLong(part[1]), Long::sum);
            } catch (Exception ex) {
                getLogger().warning("Skipped an unreadable Infinite Chest entry: " + ex);
            }
        }
    }
}
