package com.dragonsmith.universe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Universe extends JavaPlugin implements Listener {

    private final HashMap<UUID, Location> islandCenters = new HashMap<>();
    private final HashMap<UUID, Integer> islandSizes = new HashMap<>();
    private final HashMap<UUID, Integer> islandGeneratorLevels = new HashMap<>(); // Track the level of ore generator
    private Set<UUID> ignoreClaims = new HashSet<>();
    private final Random random = new Random();
    private final Map<UUID, Location[]> mineSelections = new HashMap<>();
    private final Map<String, Location[]> definedMines = new HashMap<>();
    Map<UUID, Location> visitSpawns = new HashMap<>();
    Map<UUID, Boolean> islandLocks = new HashMap<>();

    // Initialize the islandBiomes map if not already initialized
    // Map to store island ownership: Location -> Owner UUID
    private Map<Location, UUID> islandOwnershipMap = new HashMap<>();
    private Map<UUID, Integer> islandMinX = new HashMap<>();
    private Map<UUID, Integer> islandMaxX = new HashMap<>();
    private Map<UUID, Integer> islandMinY = new HashMap<>();
    private Map<UUID, Integer> islandMaxY = new HashMap<>();
    private Map<UUID, Integer> islandMinZ = new HashMap<>();
    private Map<UUID, Integer> islandMaxZ = new HashMap<>();

    // When an island is created or assigned to a player, save the ownership
    public void assignIslandToPlayer(Location islandLocation, UUID playerId) {
        islandOwnershipMap.put(islandLocation, playerId);
    }
    private Map<UUID, Biome> islandBiomes = new HashMap<>();
       // Add a map to track which players are trusted on an island
// Map to store the trusted players for each island
       private Map<UUID, Set<UUID>> trustedPlayers = new HashMap<>();
    private MoneyManager moneyManager;
    private BlockTracker blockTracker;
    private Economy economy;

    private int nextIslandX = 0;
    private int nextIslandZ = 0;

    private int islandSpacing; // Loaded from config
    private int defaultIslandSize; // Loaded from config
    private int maxIslandSize; // Loaded from config
    private int spawnHeight; // Loaded from config
    private File minesFile;
    private FileConfiguration minesConfig;
    private SellBoostManager sellBoostManager;
    @Override
    public void onEnable() {

        scheduleMineResets();
        getServer().getPluginManager().registerEvents(this, this);
        // Load configuration
        if (!setupEconomy()) {
            getLogger().severe("Vault dependency not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Load or create mines.yml
        minesFile = new File(getDataFolder(), "mines.yml");
        if (!minesFile.exists()) {
            try {
                minesFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create mines.yml!");
                e.printStackTrace();
            }
        }

        minesConfig = YamlConfiguration.loadConfiguration(minesFile);
        this.moneyManager = new MoneyManager(getDataFolder());
        saveDefaultConfig();
        this.sellBoostManager = new SellBoostManager();

        // Schedule periodic cleanup of expired boosts (every 5 minutes)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            sellBoostManager.clearExpired();
        }, 20L * 60 * 5, 20L * 60 * 5);
        FileConfiguration config = getConfig();

        islandSpacing = config.getInt("island.spacing", 1012);
        defaultIslandSize = config.getInt("island.default_size", 32);
        maxIslandSize = config.getInt("island.max_size", 128);
        spawnHeight = 57;

        // Initialize BlockTracker listener
        blockTracker = new BlockTracker();
        getServer().getPluginManager().registerEvents(blockTracker, this);

        // Generate/load universe_world
        WorldCreator worldCreator = new WorldCreator("universe_world");
        worldCreator.generator(new EmptyWorldGenerator());
        World world = worldCreator.createWorld();
        if (world != null) {
            world.setMonsterSpawnLimit(70);
            world.setAnimalSpawnLimit(10);
            world.setSpawnFlags(true, true);
        }

        // Load island data IMMEDIATELY
        getLogger().info("Loading island data from disk...");
        loadIslandData();

        // Also still listen for WorldLoadEvent as fallback (optional)
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWorldLoad(WorldLoadEvent event) {
                if (event.getWorld().getName().equalsIgnoreCase("universe_world")) {
                    getLogger().info("Universe world loaded via event. Re-loading island data just in case...");
                    loadIslandData();
                }
            }
        }, this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onMenuOpen(PlayerInteractEvent event) {
                Player player = event.getPlayer();
                if (event.getItem() != null && event.getItem().getType() == Material.NETHER_STAR) {
                    if (event.getItem().getItemMeta() != null && ChatColor.stripColor(event.getItem().getItemMeta().getDisplayName()).equalsIgnoreCase("Universe Menu")) {
                        event.setCancelled(true);
                        openUniverseMenu(player);
                    }
                }
            }
        }, this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent event) {
                if (event.getView().getTitle().equals(ChatColor.DARK_PURPLE + "Universe Menu") || event.getView().getTitle().equals(ChatColor.RED + "Confirm Delete?")) {
                    event.setCancelled(true);
                    Player player = (Player) event.getWhoClicked();
                    if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

                    String display = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());

                    switch (display.toLowerCase()) {
                        case "confirm delete":
                            player.performCommand("deleteisland confirm");
                            break;
                        case "create island":
                            player.performCommand("createisland");
                            break;
                        case "delete island":
                            openDeleteConfirmMenu(player);
                            break;
                        case "go home":
                            player.performCommand("home");
                            break;
                        case "expand island":
                            player.performCommand("expandisland");
                            break;
                        case "upgrade generator":
                            player.performCommand("upgradegenerator");
                            break;
                    }
                    player.closeInventory();
                }
            }
        }, this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWandClick(PlayerInteractEvent event) {
                Player player = event.getPlayer();
                ItemStack item = event.getItem();

                if (item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()) {
                    String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
                    if (!"Mine Wand".equalsIgnoreCase(name)) return;

                    event.setCancelled(true); // Prevent block placement

                    Location clicked = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null;
                    if (clicked == null) return;

                    UUID playerId = player.getUniqueId();
                    Location[] selections = mineSelections.getOrDefault(playerId, new Location[2]);

                    if (event.getAction().toString().contains("LEFT")) {
                        selections[0] = clicked;
                        player.sendMessage(ChatColor.YELLOW + "Position 1 set to " + formatLoc(clicked));
                    } else if (event.getAction().toString().contains("RIGHT")) {
                        selections[1] = clicked;
                        player.sendMessage(ChatColor.YELLOW + "Position 2 set to " + formatLoc(clicked));
                    }

                    mineSelections.put(playerId, selections);
                }
            }
        }, this);

        // Register commands
        getCommand("createisland").setTabCompleter(this);
        getCommand("expandisland").setTabCompleter(this);
        getCommand("balance").setTabCompleter(this);
        getCommand("setbiome").setExecutor(this);
        this.getCommand("setbiome").setTabCompleter(new SetBiomeTabCompleter());
        getCommand("ignoreclaims").setExecutor(this);
        getCommand("islandinfo").setExecutor(this);
        getCommand("wand").setExecutor(this);
        getCommand("createmine").setExecutor(this);
        getCommand("resetmine").setExecutor(this);
        getCommand("setminespawn").setExecutor(this);
        getCommand("setvisitspawn").setExecutor(this);
        getCommand("islandlock").setExecutor(this);
        getCommand("help").setExecutor(this);
        getCommand("convert").setExecutor(this);
        getCommand("boostsell").setExecutor(this);
        getLogger().info("Universe plugin has been enabled!");
    }


@Override
public void onDisable() {
    moneyManager.saveBalances();
    int savedIslands = 0;

    for (UUID playerId : islandCenters.keySet()) {
        try {
            Location center = islandCenters.get(playerId);
            int size = islandSizes.getOrDefault(playerId, defaultIslandSize);

            saveIslandData(playerId, center, size); // Save the island data
            savedIslands++; // Increment the counter on successful save
        } catch (Exception e) {
            getLogger().severe("Failed to save island data for player: " + playerId + ". Error: " + e.getMessage());
        }
    }

    // Clear memory if needed
    islandCenters.clear();
    islandSizes.clear();

    getLogger().info("Saved " + savedIslands + " islands successfully. Plugin disabled.");
}
    private String formatLoc(Location loc) {
        return ChatColor.GRAY + loc.getWorld().getName() +
                ChatColor.WHITE + " (" +
                loc.getBlockX() + ", " +
                loc.getBlockY() + ", " +
                loc.getBlockZ() + ")";
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Optional: Delay slightly to avoid inventory timing bugs
        Bukkit.getScheduler().runTaskLater(this, () -> {
            giveUniverseMenuItem(player);
        }, 10L);
    }

    private void openDeleteConfirmMenu(Player player) {
        org.bukkit.inventory.Inventory confirmGui = getServer().createInventory(null, 9, ChatColor.RED + "Confirm Delete?");
        confirmGui.setItem(4, createMenuItem(Material.TNT, ChatColor.RED + "Confirm Delete"));
        player.openInventory(confirmGui);
    }

    private void openUniverseMenu(Player player) {
        // Create 1-row GUI titled "Universe Menu"
        org.bukkit.inventory.Inventory gui = getServer().createInventory(null, 9, ChatColor.DARK_PURPLE + "Universe Menu");

        gui.setItem(0, createMenuItem(Material.GRASS_BLOCK, ChatColor.GREEN + "Create Island"));
        gui.setItem(1, createMenuItem(Material.BARRIER, ChatColor.RED + "Delete Island"));
        gui.setItem(2, createMenuItem(Material.OAK_DOOR, ChatColor.YELLOW + "Go Home"));
        gui.setItem(3, createMenuItem(Material.IRON_BLOCK, ChatColor.AQUA + "Expand Island"));
        gui.setItem(4, createMenuItem(Material.ANVIL, ChatColor.LIGHT_PURPLE + "Upgrade Generator"));

        // Open the GUI for the player
        player.openInventory(gui);
    }

    private org.bukkit.inventory.ItemStack createMenuItem(Material type, String name) {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(type);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }


private void giveUniverseMenuItem(Player player) {
    org.bukkit.inventory.ItemStack menuItem = new org.bukkit.inventory.ItemStack(Material.NETHER_STAR);
    org.bukkit.inventory.meta.ItemMeta meta = menuItem.getItemMeta();
    if (meta != null) {
        meta.setDisplayName(ChatColor.DARK_PURPLE + "Universe Menu");
        menuItem.setItemMeta(meta);
    }

    if (!player.getInventory().contains(menuItem)) {
        player.getInventory().addItem(menuItem);
    }
}
    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        UUID playerId = player.getUniqueId();

        // Handle the "/createisland" command
        if (command.getName().equalsIgnoreCase("createisland")) {
            if (islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You already have an island!");
                return true;
            }

            World world = Bukkit.getWorld("universe_world");
            if (world == null) {
                player.sendMessage(ChatColor.RED + "The world is not available!");
                return true;
            }

            int attempts = 0;
            Location center = null;

            outerSearchLoop:
            while (attempts < 10000) {
                center = new Location(world, nextIslandX, spawnHeight, nextIslandZ);
                // ❌ Skip origin or near-origin locations (64-block buffer around 0,0)
                if (Math.abs(center.getBlockX()) <= 64 && Math.abs(center.getBlockZ()) <= 64) {
                    nextIslandX += islandSpacing;
                    if (nextIslandX > 6000) {
                        nextIslandX = 0;
                        nextIslandZ += islandSpacing;
                    }
                    attempts++;
                    continue;
                }
                int halfSize = defaultIslandSize / 2;
                int minX = center.getBlockX() - halfSize;
                int maxX = center.getBlockX() + halfSize;
                int minZ = center.getBlockZ() - halfSize;
                int maxZ = center.getBlockZ() + halfSize;

                for (UUID otherId : islandCenters.keySet()) {
                    if (otherId.equals(playerId)) continue; // ✅ Skip own island if rechecking

                    Location other = islandCenters.get(otherId);
                    int otherHalf = islandSizes.getOrDefault(otherId, defaultIslandSize) / 2;
                    int oX = other.getBlockX();
                    int oZ = other.getBlockZ();

                    int oMinX = oX - otherHalf;
                    int oMaxX = oX + otherHalf;
                    int oMinZ = oZ - otherHalf;
                    int oMaxZ = oZ + otherHalf;

                    // AABB overlap check
                    if (minX <= oMaxX && maxX >= oMinX &&
                            minZ <= oMaxZ && maxZ >= oMinZ) {
                        nextIslandX += islandSpacing;
                        if (nextIslandX > 6000) {
                            nextIslandX = 0;
                            nextIslandZ += islandSpacing;
                        }
                        attempts++;
                        continue outerSearchLoop;
                    }
                }

                break; // No overlap, good spot
            }


            if (attempts >= 10000 || center == null) {
                player.sendMessage(ChatColor.RED + "Failed to find a safe island location.");
                return true;
            }

            assignIslandToPlayer(center, playerId);
            islandCenters.put(playerId, center);
            islandSizes.put(playerId, defaultIslandSize);
            islandGeneratorLevels.put(playerId, 1);
            islandBiomes.put(playerId, Biome.PLAINS);

            generateIsland(center, defaultIslandSize, playerId);
            world.getChunkAt(center).load();
            player.teleport(center.clone().add(0, 57, 0));
            giveStarterChest(center);

            player.sendMessage(ChatColor.GREEN + "Island created successfully!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("ignoreclaims")) {
            if (!player.hasPermission("universe.ignoreclaims")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            UUID uuid = player.getUniqueId();
            if (ignoreClaims.contains(uuid)) {
                ignoreClaims.remove(uuid);
                player.sendMessage(ChatColor.YELLOW + "You are now respecting island claims.");
            } else {
                ignoreClaims.add(uuid);
                player.sendMessage(ChatColor.GREEN + "You are now ignoring island claims.");
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("resetmine")) {
            if (!player.hasPermission("universe.resetmine")) {
                player.sendMessage(ChatColor.RED + "You lack permission to reset mines.");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /resetmine <ore|wood|nether|crystal>");
                return true;
            }

            String mineName = args[0].toLowerCase();
            if (!minesConfig.contains("mines." + mineName)) {
                player.sendMessage(ChatColor.RED + "Mine '" + mineName + "' does not exist.");
                return true;
            }

            String worldName = minesConfig.getString("mines." + mineName + ".world");
            ConfigurationSection pos1Section = minesConfig.getConfigurationSection("mines." + mineName + ".pos1");
            ConfigurationSection pos2Section = minesConfig.getConfigurationSection("mines." + mineName + ".pos2");
            Location pos1 = deserializeLocation(pos1Section, worldName);
            Location pos2 = deserializeLocation(pos2Section, worldName);

            List<String> blockNames = minesConfig.getStringList("mines." + mineName + ".blocks");
            List<Material> specialBlocks = blockNames.stream().map(Material::valueOf).toList();

            List<Material> fillerBlocks = switch (mineName) {
                case "ore" -> Arrays.asList(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.TUFF);
                case "wood" -> Arrays.asList(Material.DIRT, Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.PALE_MOSS_BLOCK);
                case "nether" -> Arrays.asList(Material.GLOWSTONE, Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.SHROOMLIGHT);
                case "crystal" -> Arrays.asList(Material.GLASS, Material.WHITE_STAINED_GLASS, Material.ORANGE_STAINED_GLASS,
                        Material.MAGENTA_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS,
                        Material.LIME_STAINED_GLASS, Material.PINK_STAINED_GLASS, Material.GRAY_STAINED_GLASS,
                        Material.LIGHT_GRAY_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.PURPLE_STAINED_GLASS,
                        Material.BLUE_STAINED_GLASS, Material.BROWN_STAINED_GLASS, Material.GREEN_STAINED_GLASS,
                        Material.RED_STAINED_GLASS, Material.BLACK_STAINED_GLASS, Material.TINTED_GLASS);
                default -> Collections.singletonList(Material.STONE);
            };

            assert worldName != null;
            World world = Bukkit.getWorld(worldName);
            if (world == null || pos1 == null || pos2 == null) {
                player.sendMessage(ChatColor.RED + "Failed to load mine world or positions.");
                return true;
            }

            int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
            int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
            int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
            int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

            int teleportX = (minX + maxX) / 2;
            int teleportZ = (minZ + maxZ) / 2;
            int teleportY = maxY - 1;

            for (Player online : world.getPlayers()) {
                Location loc = online.getLocation();
                int blockX = loc.getBlockX();
                int blockY = loc.getBlockY();
                int blockZ = loc.getBlockZ();

                if (blockX >= minX && blockX <= maxX &&
                        blockY >= minY && blockY <= maxY &&
                        blockZ >= minZ && blockZ <= maxZ) {

                    ConfigurationSection spawnSection = minesConfig.getConfigurationSection("mines." + mineName + ".spawn");
                    Location teleportLoc = deserializeLocation(spawnSection, worldName);
                    if (teleportLoc == null) {
                        teleportLoc = new Location(world, teleportX + 0.5, teleportY, teleportZ + 0.5);
                        online.sendMessage(ChatColor.RED + "Mine spawn not set. You were moved to the top center.");
                    }

                    online.teleport(teleportLoc);
                    online.sendMessage(ChatColor.YELLOW + "You've been moved while the mine resets.");
                }
            }

            // Async-style block reset
            int fillMinX = minX + 2;
            int fillMaxX = maxX - 2;
            int fillMinZ = minZ + 2;
            int fillMaxZ = maxZ - 2;
            int baseY = minY + 1;
            int topY = baseY + 58;

            List<Block> blocksToSet = new ArrayList<>();
            for (int x = fillMinX; x < fillMaxX; x++) {
                for (int z = fillMinZ; z < fillMaxZ; z++) {
                    for (int y = baseY; y < topY; y++) {
                        blocksToSet.add(world.getBlockAt(x, y, z));
                    }
                }
            }

            Collections.shuffle(blocksToSet); // Optional spreading

            Player finalPlayer1 = player;
            new BukkitRunnable() {
                final Iterator<Block> iterator = blocksToSet.iterator();
                final Random rand = new Random();
                final int BLOCKS_PER_TICK = 1000;

                @Override
                public void run() {
                    int count = 0;
                    while (iterator.hasNext() && count < BLOCKS_PER_TICK) {
                        Block block = iterator.next();
                        Material mat = rand.nextDouble() < 0.20
                                ? specialBlocks.get(rand.nextInt(specialBlocks.size()))
                                : fillerBlocks.get(rand.nextInt(fillerBlocks.size()));
                        block.setType(mat, false);
                        count++;
                    }

                    if (!iterator.hasNext()) {
                        cancel();
                        finalPlayer1.sendMessage(ChatColor.GREEN + "Mine '" + mineName + "' has been reset!");
                    }
                }
            }.runTaskTimer(this, 0L, 1L);

            return true;
        }

        if (command.getName().equalsIgnoreCase("setminespawn")) {
            if (!player.hasPermission("universe.setminespawn")) {
                player.sendMessage(ChatColor.RED + "You lack permission to set mine spawns.");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /setminespawn <mine>");
                return true;
            }

            String mineName = args[0].toLowerCase();

            File minesFile = new File(getDataFolder(), "mines.yml");
            FileConfiguration minesConfig = YamlConfiguration.loadConfiguration(minesFile);

            if (!minesConfig.contains("mines." + mineName)) {
                player.sendMessage(ChatColor.RED + "Mine '" + mineName + "' does not exist.");
                return true;
            }

            Location loc = player.getLocation();
            String path = "mines." + mineName + ".spawn";

            minesConfig.set(path + ".world", loc.getWorld().getName());
            minesConfig.set(path + ".x", loc.getX());
            minesConfig.set(path + ".y", loc.getY());
            minesConfig.set(path + ".z", loc.getZ());
            minesConfig.set(path + ".yaw", loc.getYaw());
            minesConfig.set(path + ".pitch", loc.getPitch());

            try {
                minesConfig.save(minesFile);
                player.sendMessage(ChatColor.GREEN + "Mine spawn for '" + mineName + "' set to your current location.");
            } catch (IOException e) {
                player.sendMessage(ChatColor.RED + "Failed to save mine spawn location.");
                e.printStackTrace();
            }

            return true;
        }

        if (command.getName().equalsIgnoreCase("createmine")) {
            if (!player.hasPermission("universe.createmine")) {
                player.sendMessage(ChatColor.RED + "You lack permission to create mines.");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /createmine <ore|wood|nether>");
                return true;
            }

            String name = args[0].toLowerCase();
            if (!Arrays.asList("ore", "wood", "nether", "crystal").contains(name)) {
                player.sendMessage(ChatColor.RED + "Invalid mine type. Choose ore, wood, crystal, or nether.");
                return true;
            }

            Location[] selections = mineSelections.get(playerId);
            if (selections == null || selections[0] == null || selections[1] == null) {
                player.sendMessage(ChatColor.RED + "Select two corners with the Mine Wand first.");
                return true;
            }

            Location pos1 = selections[0].clone();
            Location pos2 = selections[1].clone();
            World world = pos1.getWorld();

            int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
            int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
            int baseY = Math.min(pos1.getBlockY(), pos2.getBlockY());
            int topY = baseY + 59;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, baseY, z).setType(Material.BEDROCK);
                }
            }

            minesConfig.set("mines." + name + ".world", world.getName());
            minesConfig.set("mines." + name + ".pos1", serializeLocation(pos1));
            minesConfig.set("mines." + name + ".pos2", serializeLocation(pos2));

            List<String> blockTypes;
            List<Material> fillerMaterials;
            switch (name) {
                case "ore":
                    blockTypes = Arrays.asList("COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE", "LAPIS_ORE", "DIAMOND_ORE", "EMERALD_ORE");
                    fillerMaterials = Arrays.asList(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.TUFF);
                    break;
                case "wood":
                    blockTypes = Arrays.asList("OAK_LOG", "BIRCH_LOG", "SPRUCE_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG", "BAMBOO_BLOCK", "PALE_OAK_LOG");
                    fillerMaterials =  Arrays.asList(Material.DIRT, Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.PALE_MOSS_BLOCK);
                    break;
                case "nether":
                    blockTypes = Arrays.asList("NETHERRACK", "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS", "MAGMA_BLOCK", "SOUL_SAND", "SOUL_SOIL", "BASALT", "BLACKSTONE");
                    fillerMaterials = Arrays.asList(Material.GLOWSTONE, Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.SHROOMLIGHT);
                    break;
                case "crystal":
                    blockTypes = Arrays.asList("SEA_LANTERN", "OCHRE_FROGLIGHT", "VERDANT_FROGLIGHT", "PEARLESCENT_FROGLIGHT");
                    fillerMaterials = Arrays.asList(Material.GLASS, Material.WHITE_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material. MAGENTA_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS, Material.LIME_STAINED_GLASS, Material.PINK_STAINED_GLASS, Material.GRAY_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.PURPLE_STAINED_GLASS, Material.BLUE_STAINED_GLASS, Material.BROWN_STAINED_GLASS, Material.GREEN_STAINED_GLASS, Material.RED_STAINED_GLASS, Material.BLACK_STAINED_GLASS, Material.TINTED_GLASS);
                    break;
                default:
                    blockTypes = Collections.singletonList("STONE");
                    fillerMaterials = Collections.singletonList(Material.STONE);
            }

            minesConfig.set("mines." + name + ".blocks", blockTypes);
            try {
                minesConfig.save(minesFile);
            } catch (IOException e) {
                e.printStackTrace();
                player.sendMessage(ChatColor.RED + "Failed to save mine config file.");
                return true;
            }

            int wallMinX = minX + 1;
            int wallMaxX = maxX - 1;
            int wallMinZ = minZ + 1;
            int wallMaxZ = maxZ - 1;

            Material wallMaterial = Material.STONE_BRICKS;
            for (int y = baseY; y <= topY; y++) {
                for (int x = wallMinX; x <= wallMaxX; x++) {
                    world.getBlockAt(x, y, wallMinZ).setType(wallMaterial);
                    world.getBlockAt(x, y, wallMaxZ).setType(wallMaterial);
                }
                for (int z = wallMinZ; z <= wallMaxZ; z++) {
                    world.getBlockAt(wallMinX, y, z).setType(wallMaterial);
                    world.getBlockAt(wallMaxX, y, z).setType(wallMaterial);
                }
            }

            int midX = (minX + maxX) / 2;
            int midZ = (minZ + maxZ) / 2;

            for (int y = baseY + 1; y < topY; y++) {
                placeLadder(world, midX, y, wallMinZ + 1, BlockFace.SOUTH);
                placeLadder(world, midX, y, wallMaxZ - 1, BlockFace.NORTH);
                placeLadder(world, wallMinX + 1, y, midZ, BlockFace.EAST);
                placeLadder(world, wallMaxX - 1, y, midZ, BlockFace.WEST);
            }

            Random rand = new Random();
            for (int x = wallMinX + 1; x < wallMaxX; x++) {
                for (int z = wallMinZ + 1; z < wallMaxZ; z++) {
                    for (int y = baseY + 1; y < topY; y++) {
                        Material mat = rand.nextDouble() < 0.20
                                ? Material.valueOf(blockTypes.get(rand.nextInt(blockTypes.size())))
                                : fillerMaterials.get(rand.nextInt(fillerMaterials.size()));
                        world.getBlockAt(x, y, z).setType(mat);
                    }
                }
            }

            player.sendMessage(ChatColor.GREEN + "Mine '" + name + "' has been created!");
            return true;
        }



        if (command.getName().equalsIgnoreCase("islandinfo")) {

            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }


            int size = islandSizes.getOrDefault(playerId, 32);
            int level = islandGeneratorLevels.getOrDefault(playerId, 0);
            Set<UUID> trusted = trustedPlayers.getOrDefault(playerId, new HashSet<>());

            player.sendMessage(ChatColor.GOLD + "------ Island Info ------");
            player.sendMessage(ChatColor.YELLOW + "Size: " + ChatColor.WHITE + size + "x" + size);
            player.sendMessage(ChatColor.YELLOW + "Generator Level: " + ChatColor.WHITE + level);
            player.sendMessage(ChatColor.YELLOW + "Trusted: " + ChatColor.WHITE + (trusted.isEmpty() ? "None" : trusted.size() + " players"));
            return true;
        }
        if (command.getName().equalsIgnoreCase("wand")) {
            ItemStack wand = new ItemStack(Material.BLAZE_ROD);
            ItemMeta meta = wand.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + "Mine Wand");
                wand.setItemMeta(meta);
            }
            player.getInventory().addItem(wand);
            player.sendMessage(ChatColor.GREEN + "Mine Wand added to your inventory!");
            return true;
        }
        if (command.getName().equalsIgnoreCase("deleteisland")) {

            // Confirmation step
            if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
                player.sendMessage(ChatColor.RED + "Are you sure you want to delete your island?");
                player.sendMessage(ChatColor.YELLOW + "Type /deleteisland confirm to permanently delete it.");
                return true;
            }

            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island to delete!");
                return true;
            }

            World world = Bukkit.getWorld("universe_world");
            if (world == null) {
                player.sendMessage(ChatColor.RED + "The world is not available!");
                return true;
            }

            Location center = islandCenters.get(playerId);
            int size = islandSizes.get(playerId);

            int startX = center.getBlockX() - (size / 2) - 5;
            int endX = center.getBlockX() + (size / 2) + 5;
            int startZ = center.getBlockZ() - (size / 2) - 5;
            int endZ = center.getBlockZ() + (size / 2) + 5;

            Player finalPlayer = player;
            new BukkitRunnable() {
                @Override
                public void run() {
                    for (int x = startX; x <= endX; x++) {
                        for (int z = startZ; z <= endZ; z++) {
                            for (int y = -64; y < 256; y++) {
                                world.getBlockAt(x, y, z).setType(Material.AIR);
                            }
                        }
                    }

                    islandCenters.remove(playerId);
                    islandSizes.remove(playerId);
                    islandGeneratorLevels.remove(playerId);
                    islandBiomes.remove(playerId);

                    finalPlayer.sendMessage(ChatColor.GREEN + "Your island has been successfully deleted!");
                    finalPlayer.teleport(world.getSpawnLocation());
                }
            }.runTask(this);

            return true;
        }




        // Handle the "/expandisland" command
        if (command.getName().equalsIgnoreCase("expandisland")) {

            // Check if the player has an island
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }
            UUID uuid = player.getUniqueId();
            // Calculate the cost for expansion based on the current island size
            int currentSize = islandSizes.get(playerId);
            int expansionCost = 10000 * currentSize;

            // Check if the player has enough balance
            if (getMoneyManager().getBalance(uuid) < expansionCost) {
                player.sendMessage(ChatColor.RED + "You need at least " + expansionCost + " tokens to expand the realm!");
                return true;
            }

            // Determine the new island size
            int newSize = currentSize * 2;

            // Ensure the island size does not exceed the maximum allowed
            if (newSize > maxIslandSize) {
                player.sendMessage(ChatColor.RED + "Maximum island size reached!");
                return true;
            }

            // Withdraw the cost from the player's balance
            getMoneyManager().withdraw(uuid, expansionCost);

            // Get the current center of the island
            Location center = islandCenters.get(playerId);

            // Call the expandIsland method to handle the expansion
            extendIsland(center, newSize, playerId);

            // Update the island size
            islandSizes.put(playerId, newSize);

            // Notify the player that their island was expanded
            player.sendMessage(ChatColor.GREEN + "Island expanded to " + newSize + "x" + newSize + "!");

            // Optional: Notify player of remaining balance
            double remainingBalance = getMoneyManager().getBalance(uuid);
            player.sendMessage(ChatColor.YELLOW + "Your remaining balance: " + remainingBalance + " coins.");

            return true;
        }
        if (command.getName().equalsIgnoreCase("boostsell")) {
            if (!player.hasPermission("universe.adminboostsell")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /boostsell <multiplier>");
                return true;
            }

            double multiplier;
            try {
                multiplier = Double.parseDouble(args[0]);
                if (multiplier <= 1.0) {
                    player.sendMessage(ChatColor.RED + "Multiplier must be greater than 1.0.");
                    return true;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid number.");
                return true;
            }

            long duration = 30 * 60 * 1000L; // 30 minutes
            for (Player online : Bukkit.getOnlinePlayers()) {
                sellBoostManager.giveBoost(online.getUniqueId(), multiplier, duration);
                online.sendMessage(ChatColor.GOLD + "A global sell boost of " + multiplier + "x has been activated for 30 minutes!");
            }

            player.sendMessage(ChatColor.GREEN + "Sell boost applied to all players.");
            return true;
        }


        if (command.getName().equalsIgnoreCase("upgradegenerator")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }

            int currentLevel = islandGeneratorLevels.getOrDefault(playerId, 0);
            if (currentLevel >= 6) {
                player.sendMessage(ChatColor.RED + "Maximum generator level reached!");
                return true;
            }

            // Example prices for each upgrade level
            int[] upgradeCosts = {0, 1000, 5000, 25000, 100000, 500000, 2500000};
            int cost = upgradeCosts[currentLevel];

            if (!getServer().getPluginManager().isPluginEnabled("Vault") || getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class) == null) {
                player.sendMessage(ChatColor.RED + "Vault is not set up properly.");
                return true;
            }
            UUID uuid = player.getUniqueId();
            if (getMoneyManager().getBalance(uuid) < cost) {
                player.sendMessage(ChatColor.RED + "You need " + cost + " tokens to upgrade your generator.");
                return true;
            }

            getMoneyManager().withdraw(uuid, cost);
            islandGeneratorLevels.put(playerId, currentLevel + 1);
            player.sendMessage(ChatColor.GREEN + "Generator upgraded to level " + (currentLevel + 1) + "!");
            return true;
        }


        // Handle the "/home" command to teleport to the island
        if (command.getName().equalsIgnoreCase("home")) {
            // Get the island center directly, defaulting to null if not present
            Location center = islandCenters.getOrDefault(playerId, null);

            // If the player doesn't have an island
            if (center == null) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true; // Return early to prevent further execution
            }

            // Teleport player to the island, adding a fixed Y offset
            player.teleport(center.clone().add(0, 57, 0));

            // Success message
            player.sendMessage(ChatColor.GREEN + "Teleported to your island!");

            return true; // Return true to indicate command was processed successfully
        }

        if (command.getName().equalsIgnoreCase("balance")) {
            UUID uuid = player.getUniqueId();

            // Default (Vault) balance, if used
            double vaultBalance = economy.getBalance(player);

            // Custom balance from your MoneyManager
            double universeCoins = getMoneyManager().getBalance(uuid);

            player.sendMessage(ChatColor.GOLD + "=== Your Balances ===");
            player.sendMessage(ChatColor.YELLOW + "Money: " + ChatColor.GREEN + vaultBalance);
            player.sendMessage(ChatColor.AQUA + "Universe tokens: " + ChatColor.LIGHT_PURPLE + universeCoins);
            return true;
        }

        if (command.getName().equalsIgnoreCase("setbiome")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /setbiome <biome>");
                return true;
            }

            String biomeName = args[0].toUpperCase();
            try {
                Biome biome = Biome.valueOf(biomeName);
                islandBiomes.put(playerId, biome);

                Location center = islandCenters.get(playerId);
                int size = islandSizes.get(playerId);
                setBiome(center, size, biome);

                // Force chunk refresh
                World world = center.getWorld();
                int minX = center.getBlockX() - size / 2;
                int minZ = center.getBlockZ() - size / 2;
                int maxX = center.getBlockX() + size / 2;
                int maxZ = center.getBlockZ() + size / 2;

                for (int x = minX; x <= maxX; x += 16) {
                    for (int z = minZ; z <= maxZ; z += 16) {
                        Chunk chunk = world.getChunkAt(new Location(world, x, 0, z));
                        world.refreshChunk(chunk.getX(), chunk.getZ()); // Force client to reload chunk data
                    }
                }

                player.sendMessage(ChatColor.GREEN + "Biome changed to " + biome.name() + "!");
            } catch (IllegalArgumentException e) {
                player.sendMessage(ChatColor.RED + "Invalid biome name!");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("trust") && sender instanceof Player) {
            player = (Player) sender;
            UUID ownerId = player.getUniqueId();

            if (args.length == 1) {
                Player target = Bukkit.getPlayer(args[0]);
                if (target != null) {
                    UUID targetId = target.getUniqueId();

                    // Check if the player is trying to trust themselves
                    if (ownerId.equals(targetId)) {
                        player.sendMessage(ChatColor.RED + "You cannot trust yourself on your own island.");
                        return true; // Exit the command
                    }

                    // Get or create the trusted set for the island owner
                    Set<UUID> trusted = trustedPlayers.computeIfAbsent(ownerId, k -> new HashSet<>());

                    // Now you can safely modify the trusted set
                    if (trusted.add(targetId)) {
                        player.sendMessage(ChatColor.GREEN + target.getName() + " has been trusted on your island.");
                        saveIslandData(ownerId, islandCenters.get(ownerId), islandSizes.get(ownerId));
                    } else {
                        player.sendMessage(ChatColor.RED + target.getName() + " is already trusted on your island.");
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "Player not found.");
                }
                return true;
            } else {
                player.sendMessage(ChatColor.RED + "Usage: /trust <player>");
                return false; // Incorrect usage, show help
            }
        }

        if (command.getName().equalsIgnoreCase("untrust") && sender instanceof Player) {
            player = (Player) sender;
            UUID ownerId = player.getUniqueId();

            if (args.length == 1) {
                Player target = Bukkit.getPlayer(args[0]);
                if (target != null) {
                    UUID targetId = target.getUniqueId();
                    // Get the trusted set for the island owner, or initialize it if it doesn't exist
                    Set<UUID> trusted = trustedPlayers.getOrDefault(ownerId, new HashSet<>());

                    if (trusted.remove(targetId)) {
                        player.sendMessage(ChatColor.YELLOW + target.getName() + " has been untrusted on your island.");
                        saveIslandData(ownerId, islandCenters.get(ownerId), islandSizes.get(ownerId));
                    } else {
                        player.sendMessage(ChatColor.RED + "That player was not trusted on your island.");
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "Player not found.");
                }
                return true;
            } else {
                player.sendMessage(ChatColor.RED + "Usage: /untrust <player>");
                return false; // Incorrect usage, show help
            }
        }
        if (command.getName().equalsIgnoreCase("visit")) {
            Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_AQUA + "Visitable Islands");

            Set<UUID> uniquePlayers = new HashSet<>();
            uniquePlayers.addAll(islandCenters.keySet());

            for (UUID targetId : uniquePlayers) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetId);

                boolean isLocked = islandLocks.getOrDefault(targetId, false);
                String lockStatus = isLocked ? ChatColor.RED + "Locked" : ChatColor.GREEN + "Unlocked";

                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();

                if (meta != null) {
                    meta.setOwningPlayer(offlinePlayer);
                    meta.setDisplayName(ChatColor.GOLD + offlinePlayer.getName());

                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Island Access: " + lockStatus);
                    lore.add(ChatColor.YELLOW + "Click to visit");
                    meta.setLore(lore);
                    head.setItemMeta(meta);
                }

                gui.addItem(head);
            }

            player.openInventory(gui);
            return true;
        }


        if (command.getName().equalsIgnoreCase("setvisitspawn")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't own an island.");
                return true;
            }

            Location loc = player.getLocation();
            visitSpawns.put(playerId, loc);
            player.sendMessage(ChatColor.GREEN + "Visit spawn location set!");
            return true;
        }
        if (command.getName().equalsIgnoreCase("islandlock")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't own an island.");
                return true;
            }

            if (args.length == 0) {
                boolean locked = islandLocks.getOrDefault(playerId, false);
                player.sendMessage(ChatColor.YELLOW + "Your island is currently " + (locked ? "locked" : "unlocked") + ".");
                return true;
            }

            if (args[0].equalsIgnoreCase("on")) {
                islandLocks.put(playerId, true);
                player.sendMessage(ChatColor.RED + "Your island is now locked. Players cannot visit.");
            } else if (args[0].equalsIgnoreCase("off")) {
                islandLocks.put(playerId, false);
                player.sendMessage(ChatColor.GREEN + "Your island is now unlocked. Players can visit.");
            } else {
                player.sendMessage(ChatColor.RED + "Usage: /islandlock [on|off]");
            }

            return true;
        }
        if (command.getName().equalsIgnoreCase("help")) {
            player.sendMessage(ChatColor.GOLD + "----[ Universe Help Commands ]----");
            player.sendMessage(ChatColor.YELLOW + "/createisland" + ChatColor.GRAY + " - Create your personal island.");
            player.sendMessage(ChatColor.YELLOW + "/expandisland" + ChatColor.GRAY + " - Expand your island's borders.");
            player.sendMessage(ChatColor.YELLOW + "/balance" + ChatColor.GRAY + " - Check your island balance.");
            player.sendMessage(ChatColor.YELLOW + "/setbiome" + ChatColor.GRAY + " - Change your island's biome.");
            player.sendMessage(ChatColor.YELLOW + "/ignoreclaims" + ChatColor.GRAY + " - Toggle claim protection bypass.");
            player.sendMessage(ChatColor.YELLOW + "/islandinfo" + ChatColor.GRAY + " - View detailed island info.");
            player.sendMessage(ChatColor.YELLOW + "/wand" + ChatColor.GRAY + " - Get the admin wand for region setup.");
            player.sendMessage(ChatColor.YELLOW + "/createmine" + ChatColor.GRAY + " - Define a new mine region.");
            player.sendMessage(ChatColor.YELLOW + "/resetmine" + ChatColor.GRAY + " - Reset a mine and teleport players.");
            player.sendMessage(ChatColor.YELLOW + "/setminespawn" + ChatColor.GRAY + " - Set the mine's reset respawn.");
            player.sendMessage(ChatColor.YELLOW + "/setvisitspawn" + ChatColor.GRAY + " - Set where visitors land on your island.");
            player.sendMessage(ChatColor.YELLOW + "/islandlock [on/off]" + ChatColor.GRAY + " - Lock/unlock your island.");
            player.sendMessage(ChatColor.YELLOW + "/visit <player>" + ChatColor.GRAY + " - Visit another player's island.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("convert")) {
            if (args.length != 2) {
                player.sendMessage(ChatColor.RED + "Usage: /convert <toTokens|toMoney> <amount>");
                return true;
            }

            String direction = args[0].toLowerCase();
            double amount;

            try {
                amount = Double.parseDouble(args[1]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid amount.");
                return true;
            }

            UUID uuid = player.getUniqueId();
            MoneyManager moneyManager = getMoneyManager();

            if (direction.equals("totokens")) {
                double required = amount * 10.0;

                if (economy.getBalance(player) < required) {
                    player.sendMessage(ChatColor.RED + "You need $" + required + " to get " + amount + " tokens.");
                    return true;
                }

                economy.withdrawPlayer(player, required);
                moneyManager.deposit(uuid, amount);
                player.sendMessage(ChatColor.GREEN + "Converted $" + required + " into " + amount + " tokens.");
                return true;
            }

            if (direction.equals("tomoney")) {
                if (moneyManager.getBalance(uuid) < amount) {
                    player.sendMessage(ChatColor.RED + "You don't have " + amount + " tokens.");
                    return true;
                }

                double returned = amount * 10.0;
                moneyManager.withdraw(uuid, amount);
                economy.depositPlayer(player, returned);
                player.sendMessage(ChatColor.GREEN + "Converted " + amount + " tokens into $" + returned + ".");
                return true;
            }

            player.sendMessage(ChatColor.RED + "Usage: /convert <toTokens|toMoney> <amount>");
            return true;
        }

        return false;
    }
    private Map<String, Object> serializeLocation(Location loc) {
        Map<String, Object> map = new HashMap<>();
        map.put("x", loc.getBlockX());
        map.put("y", loc.getBlockY());
        map.put("z", loc.getBlockZ());
        return map;
    }
    private void placeLadder(World world, int x, int y, int z, BlockFace facing) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.LADDER);
        Ladder ladder = (Ladder) Bukkit.createBlockData(Material.LADDER);
        ladder.setFacing(facing);
        block.setBlockData(ladder);
    }
    private void setBiome(Location center, int size, Biome biome) {
        int half = size / 2;
        World world = center.getWorld();

        if (world == null) {
            return;
        }

        // Loop through the area and set the biome at each point
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                // Set the biome for the specific location (X, Z)
                int blockX = center.getBlockX() + x;
                int blockZ = center.getBlockZ() + z;

                // Make sure the coordinates are within the valid chunk range
                world.setBiome(blockX, blockZ, biome);
            }
        }
    }

    private void generateIsland(Location center, int size, UUID playerId) {
        int half = size / 2;
        Random random = new Random();

        // Step 1: Generate the base island quickly
        new BukkitRunnable() {
            @Override
            public void run() {
                // Generate core of the island (bedrock, stone, dirt, etc.)
                for (int x = -half; x <= half; x++) {
                    for (int z = -half; z <= half; z++) {
                        for (int y = -64; y <= 56; y++) {
                            Location loc = center.clone().add(x, y, z);

                            // Skip blocks that have been broken by the player
                            if (blockTracker.isBlockBroken(playerId, loc)) {
                                continue; // Don't modify this block
                            }

                            // Only modify air blocks (or bedrock for the bottom-most layer)
                            if (loc.getBlock().getType() == Material.AIR || loc.getBlock().getType() == Material.BEDROCK) {
                                Material materialToPlace = determineBlockMaterial(y, random);
                                if (materialToPlace != null) {
                                    loc.getBlock().setType(materialToPlace);
                                }
                            }
                        }
                    }
                }

                // Step 2: Load chunks asynchronously around the island
                loadChunks(center, half); // Load chunks around the island for future expansion

            }
        }.runTask(this); // Run the base island generation asynchronously
    }


private void generateIsland(Location center, int size, UUID playerId) {
    int half = size / 2;
    Random random = new Random();

    // Step 1: Generate the base island quickly
    new BukkitRunnable() {
        @Override
        public void run() {
            // Generate core of the island (bedrock, stone, dirt, etc.)
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    for (int y = -64; y <= 56; y++) {
                        Location loc = center.clone().add(x, y, z);

                        // Skip blocks that have been broken by the player
                        if (blockTracker.isBlockBroken(playerId, loc)) {
                            continue; // Don't modify this block
                        }

                        // Only modify air blocks (or bedrock for the bottom-most layer)
                        if (loc.getBlock().getType() == Material.AIR || loc.getBlock().getType() == Material.BEDROCK) {
                            Material materialToPlace = determineBlockMaterial(y, random);
                            if (materialToPlace != null) {
                                loc.getBlock().setType(materialToPlace);
                            }
                        }
                    }
                }
            }

            // Step 2: Add Oak Trees
            addNaturalOakTrees(center, half, random);

            // Step 3: Load chunks asynchronously around the island
            loadChunks(center, half); // Load chunks around the island for future expansion
        }
    }.runTask(this); // Run the base island generation asynchronously
}

private void addNaturalOakTrees(Location islandCenter, int halfIslandSize, Random random) {
    // Define how many trees to generate (3 to 7 trees)
    int treeCount = random.nextInt(5) + 3;

    // Iterate through and place trees around the island center
    for (int i = 0; i < treeCount; i++) {
        // Randomly calculate x and z offsets from the island center
        int maxDistance = halfIslandSize; // Max distance from center to place trees
        int offsetX = random.nextInt(maxDistance * 2) - maxDistance; // Random x offset within range
        int offsetZ = random.nextInt(maxDistance * 2) - maxDistance; // Random z offset within range

        // Set the Y-coordinate to 57 for the tree base
        Location baseLocation = islandCenter.clone().add(offsetX, 0, offsetZ);
        baseLocation.setY(57);  // Ensure the tree starts at y = 57

        // Check if the base location is on valid ground (grass or dirt)
        if (baseLocation.getBlock().getType() != Material.GRASS_BLOCK && baseLocation.getBlock().getType() != Material.DIRT) {
            continue; // Skip if not on solid ground
        }

        // Log tree generation start (for debugging purposes)
        Bukkit.getLogger().info("Generating tree at: " + baseLocation);

        // Set the base of the tree (wood)
        baseLocation.getBlock().setType(Material.OAK_LOG); // Trunk base at the given location
        updateBlock(baseLocation.getBlock()); // Force update to make it visible

        // Random height for the tree (4 to 7 blocks tall)
        int treeHeight = random.nextInt(4) + 4; // Random height between 4 and 7

        // Generate tree trunk
        for (int j = 1; j < treeHeight; j++) {
            Location trunkLocation = baseLocation.clone().add(0, j, 0);
            trunkLocation.getBlock().setType(Material.OAK_LOG);
            updateBlock(trunkLocation.getBlock()); // Force update to make it visible
        }

        // Generate tree leaves (a simple spherical shape)
        int leafRadius = 2; // A radius for the leaves around the tree
        for (int x = -leafRadius; x <= leafRadius; x++) {
            for (int y = -leafRadius; y <= leafRadius; y++) {
                for (int z = -leafRadius; z <= leafRadius; z++) {
                    // Check if the block is within a spherical radius
                    if (Math.abs(x) + Math.abs(y) + Math.abs(z) <= leafRadius) {
                        Location leafLocation = baseLocation.clone().add(x, treeHeight - 1 + y, z); // Leaves are placed around the top of the trunk
                        if (leafLocation.getBlock().getType() == Material.AIR) { // Only place leaves if the block is air
                            leafLocation.getBlock().setType(Material.OAK_LEAVES);
                            updateBlock(leafLocation.getBlock()); // Force update to make it visible
                        }
                    }
                }
            }
        }

        // Log tree generation completion (for debugging purposes)
        Bukkit.getLogger().info("Tree generated at: " + baseLocation);
    }
}

private void updateBlock(Block block) {
    block.getState().update(true); // Force block update
}

    private void loadChunks(Location center, int half) {
        // Load nearby chunks asynchronously for future island expansion
        new BukkitRunnable() {
            @Override
            public void run() {
                // Asynchronously load chunks around the island
                int chunkRadius = half / 16 + 1; // Radius to load chunks around the center
                for (int x = -chunkRadius; x <= chunkRadius; x++) {
                    for (int z = -chunkRadius; z <= chunkRadius; z++) {
                        center.getWorld().getChunkAt(center.clone().add(x * 16, 0, z * 16)).load();
                    }
                }
            }
        }.runTask(this); // Run asynchronously to load chunks without blocking the main thread
    }
    private void extendIsland(Location center, int size, UUID playerId) {
        int half = size / 2;
        Random random = new Random();

        new BukkitRunnable() {
            int xOffset = -half;  // Start at the negative offset to expand from the center
            int zOffset = -half;
            int yOffset = -64;
            int yLimit = 56;  // Define the highest point for island generation

            @Override
            public void run() {
                // Generate the expanded island terrain section
                for (int x = xOffset; x < xOffset + 10; x++) {
                    for (int z = zOffset; z < zOffset + 10; z++) {
                        for (int y = yOffset; y <= yLimit; y++) { // Ensure we process all Y levels
                            Location loc = center.clone().add(x, y, z);

                            // Skip blocks that have been broken by the player
                            if (blockTracker.isBlockBroken(playerId, loc)) {
                                continue; // Don't modify this block
                            }

                            // Only modify air blocks (or bedrock for the bottom-most layer)
                            if (loc.getBlock().getType() == Material.AIR || loc.getBlock().getType() == Material.BEDROCK) {
                                Material materialToPlace = determineBlockMaterial(y, random);
                                if (materialToPlace != null) {
                                    loc.getBlock().setType(materialToPlace);
                                }
                            }
                        }
                    }
                }

                // Increment the offsets to process the next batch of blocks for island expansion
                xOffset += 10; // Move to the next batch of blocks in the X direction
                if (xOffset > half) {
                    xOffset = -half; // Reset X offset and move Z offset
                    zOffset += 10;  // Move to the next batch in the Z direction
                }

                // Stop when the expansion reaches the full size
                if (zOffset > half) {
                    cancel(); // Expansion finished
                }
            }
        }.runTaskTimer(this, 0L, 2L); // Run the expansion task periodically with a 2-tick delay
    }


    private Material determineBlockMaterial(int y, Random random) {
        // Pre-calculate the material types and randomize it only once per batch to save processing time
        if (y == -64) {
            return Material.BEDROCK;
        } else if (y >= -63 && y <= -6) {
            return Material.DEEPSLATE;
        } else if (y >= -5 && y <= 5) {
            return random.nextBoolean() ? Material.DEEPSLATE : Material.STONE; // Randomize block type only once per batch
        } else if (y >= 0 && y <= 52) {
            return Material.STONE;
        } else if (y >= 53 && y <= 55) {
            return Material.DIRT;
        } else if (y == 56) {
            return Material.GRASS_BLOCK;
        }
        return null;
    }



    private void giveStarterChest(Location center) {
        // Create a chest and populate it with basic starter items
        Location chestLocation = center.clone().add(0, 57, 0);
        chestLocation.getBlock().setType(Material.CHEST);

        Chest chest = (Chest) chestLocation.getBlock().getState();
        Inventory chestInventory = chest.getInventory();

        chestInventory.addItem(new ItemStack(Material.OAK_SAPLING, 6));
        chestInventory.addItem(new ItemStack(Material.CRAFTING_TABLE));
        chestInventory.addItem(new ItemStack(Material.ICE, 2));
        chestInventory.addItem(new ItemStack(Material.LAVA_BUCKET, 1));
        chestInventory.addItem(new ItemStack(Material.LEATHER_HELMET));
        chestInventory.addItem(new ItemStack(Material.LEATHER_CHESTPLATE));
        chestInventory.addItem(new ItemStack(Material.LEATHER_LEGGINGS));
        chestInventory.addItem(new ItemStack(Material.LEATHER_BOOTS));
        chestInventory.addItem(new ItemStack(Material.BONE, 16));
    }

    // FIXED saveIslandData
    private void saveIslandData(UUID playerId, Location center, int size) {
        Map<String, Object> centerMap = new HashMap<>();
        centerMap.put("world", center.getWorld().getName());
        centerMap.put("x", center.getX());
        centerMap.put("y", center.getY());
        centerMap.put("z", center.getZ());
        centerMap.put("yaw", center.getYaw());
        centerMap.put("pitch", center.getPitch());

        getConfig().set("islands." + playerId + ".center", centerMap);
        getConfig().set("islands." + playerId + ".size", size);

        // Save trusted players
        Set<UUID> trusted = trustedPlayers.getOrDefault(playerId, new HashSet<>());
        List<String> trustedList = trusted.stream().map(UUID::toString).collect(Collectors.toList());
        getConfig().set("islands." + playerId + ".trusted", trustedList);

        // Save owner as the playerId itself (they own their own island)
        getConfig().set("islands." + playerId + ".owner", playerId.toString());
        getConfig().set("islands." + playerId + ".generator", islandGeneratorLevels.getOrDefault(playerId, 0));
        saveConfig();
    }
    // FIXED loadIslandData
    private void loadIslandData() {
        FileConfiguration config = getConfig();
        if (!config.contains("islands")) {
            getLogger().warning("No islands data found in the config.");
            return;
        }

        for (String playerIdString : config.getConfigurationSection("islands").getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerIdString);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in config: " + playerIdString);
                continue;
            }

            ConfigurationSection centerSection = config.getConfigurationSection("islands." + playerId + ".center");
            if (centerSection == null) {
                getLogger().warning("Missing center data for island of player " + playerId);
                continue;
            }

            String worldName = centerSection.getString("world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                getLogger().severe("World '" + worldName + "' is not loaded. Skipping island for player " + playerId);
                continue;
            }

            double x = centerSection.getDouble("x");
            double y = centerSection.getDouble("y");
            double z = centerSection.getDouble("z");
            float yaw = (float) centerSection.getDouble("yaw");
            float pitch = (float) centerSection.getDouble("pitch");

            Location center = new Location(world, x, y, z, yaw, pitch);
            int size = config.getInt("islands." + playerId + ".size", defaultIslandSize);

            // Recompute boundaries (don't save/load them unnecessarily)
            int halfSize = size / 2;
            int minX = center.getBlockX() - halfSize;
            int maxX = center.getBlockX() + halfSize;
            int minY = 0;
            int maxY = 255;
            int minZ = center.getBlockZ() - halfSize;
            int maxZ = center.getBlockZ() + halfSize;

            // Store everything using playerId as key
            islandCenters.put(playerId, center);
            islandSizes.put(playerId, size);
            islandMinX.put(playerId, minX);
            islandMaxX.put(playerId, maxX);
            islandMinY.put(playerId, minY);
            islandMaxY.put(playerId, maxY);
            islandMinZ.put(playerId, minZ);
            islandMaxZ.put(playerId, maxZ);
            // Trusted players
            List<String> trustedList = config.getStringList("islands." + playerId + ".trusted");
            Set<UUID> trustedSet = trustedList.stream().map(UUID::fromString).collect(Collectors.toSet());
            trustedPlayers.put(playerId, trustedSet);
            int generatorLevel = config.getInt("islands." + playerId + ".generator", 0);
            islandGeneratorLevels.put(playerId, generatorLevel);

            // Ownership (player owns their own island)
            islandOwnershipMap.put(center, playerId);

            getLogger().info("Loaded island data for player " + playerId);
        }
    }
    private boolean canModifyIsland(Player player, UUID islandOwnerId) {
        // Owner can always modify
        if (islandOwnerId.equals(player.getUniqueId())) {
            return true;
        }

        // Trusted players can modify
        Set<UUID> trusted = trustedPlayers.getOrDefault(islandOwnerId, Collections.emptySet());
        if (trusted.contains(player.getUniqueId())) {
            return true;
        }

        // Players with admin override permission can modify
        if (player.hasPermission("island.modify")) {
            return true;
        }

        return false;
    }
    private boolean hasIslandAccess(Player player, Location location) {
        UUID playerId = player.getUniqueId();

        // Restrict access logic to only the island world
        if (!"universe_world".equals(location.getWorld().getName())) {
            return true; // Allow everything outside the island world
        }

        // Only allow bypass if the player has toggled /ignoreclaims
        if (ignoreClaims.contains(playerId) && player.hasPermission("universe.ignoreclaims")) return true;

        UUID ownerId = getIslandOwner(location);
        if (ownerId == null) return false;

        return ownerId.equals(playerId) ||
                trustedPlayers.getOrDefault(ownerId, Collections.emptySet()).contains(playerId);
    }

    private boolean isWithinIsland(Location location, UUID islandOwnerId) {
        Location center = islandCenters.get(islandOwnerId);
        if (center == null) {
            return false;
        }

        // Ensure same world
        if (!location.getWorld().equals(center.getWorld())) {
            return false;
        }

        int islandSize = islandSizes.getOrDefault(islandOwnerId, defaultIslandSize);
        int halfSize = islandSize / 2;

        int minX = center.getBlockX() - halfSize;
        int maxX = center.getBlockX() + halfSize;
        int minZ = center.getBlockZ() - halfSize;
        int maxZ = center.getBlockZ() + halfSize;
        int minY = 0;
        int maxY = location.getWorld().getMaxHeight();

        return location.getBlockX() >= minX && location.getBlockX() <= maxX &&
                location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ &&
                location.getBlockY() >= minY && location.getBlockY() <= maxY;
    }
    private UUID getIslandOwner(Location location) {
        for (UUID ownerId : islandCenters.keySet()) {
            if (isWithinIsland(location, ownerId)) {
                return ownerId;
            }
        }
        return null;
    }
    private boolean shouldCancel(Player player, Location location, String action) {
        UUID islandOwnerId = getIslandOwner(location);
        if (islandOwnerId == null) {
            return false; // Not inside any island, allow
        }

        if (!canModifyIsland(player, islandOwnerId)) {
            player.sendMessage(ChatColor.RED + "You cannot " + action + " on another player's island.");
            return true;
        }

        return false;
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        if (!hasIslandAccess(player, location)) {
            player.sendMessage(ChatColor.RED + "You cannot break blocks on another player's island.");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        if (!hasIslandAccess(player, location)) {
            player.sendMessage(ChatColor.RED + "You cannot place blocks on another player's island.");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        Player player = event.getPlayer();
        Location location = event.getClickedBlock().getLocation();

        if (!hasIslandAccess(player, location)) {
            player.sendMessage(ChatColor.RED + "You cannot interact with blocks on another player's island.");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCobbleGen(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();

        if ((from.getType() == Material.WATER || from.getType() == Material.LAVA) && to.getType() == Material.AIR) {
            Material opposite = getOppositeLiquid(from.getType());
            for (Block adjacent : getAdjacentBlocks(to)) {
                if (adjacent.getType() == opposite) {
                    UUID ownerId = getIslandOwner(to.getLocation());
                    int level = islandGeneratorLevels.getOrDefault(ownerId, 0);

                    event.setCancelled(true);
                    double roll = random.nextDouble();
                    Material result = Material.COBBLESTONE;

                    switch (level) {
                        case 1:
                            if (roll < 0.10) result = Material.COAL_ORE;
                            break;
                        case 2:
                            if (roll < 0.05) result = Material.IRON_ORE;
                            else if (roll < 0.15) result = Material.COAL_ORE;
                            break;
                        case 3:
                            if (roll < 0.03) result = Material.GOLD_ORE;
                            else if (roll < 0.08) result = Material.IRON_ORE;
                            else if (roll < 0.20) result = Material.COAL_ORE;
                            break;
                        case 4:
                            if (roll < 0.01) result = Material.LAPIS_ORE;
                            else if (roll < 0.04) result = Material.GOLD_ORE;
                            else if (roll < 0.10) result = Material.IRON_ORE;
                            else if (roll < 0.25) result = Material.COAL_ORE;
                            break;
                        case 5:
                            if (roll < 0.005) result = Material.DIAMOND_ORE;
                            else if (roll < 0.02) result = Material.LAPIS_ORE;
                            else if (roll < 0.05) result = Material.GOLD_ORE;
                            else if (roll < 0.10) result = Material.IRON_ORE;
                            else if (roll < 0.20) result = Material.REDSTONE_ORE;
                            else if (roll < 0.35) result = Material.COAL_ORE;
                            else result = Material.COBBLESTONE;
                            break;
                        default:
                            if (roll < 0.10) result = Material.COAL_ORE;
                            else if (roll < 0.20) result = Material.IRON_ORE;
                            break;
                    }

                    to.setType(result);
                    break;
                }
            }
        }
    }


    private Material getOppositeLiquid(Material type) {
        return (type == Material.LAVA) ? Material.WATER : Material.LAVA;
    }

    private List<Block> getAdjacentBlocks(Block block) {
        return Arrays.asList(
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1),
                block.getRelative(0, 1, 0),
                block.getRelative(0, -1, 0)
        );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("createisland") || command.getName().equalsIgnoreCase("expandisland")) {
            return Collections.emptyList();
        }
        return null;
    }
    public Location deserializeLocation(ConfigurationSection section, String worldName) {
        if (section == null || worldName == null) return null;

        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        int x = section.getInt("x");
        int y = section.getInt("y");
        int z = section.getInt("z");

        return new Location(world, x, y, z);
    }



    public void scheduleMineResets() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            File minesFile = new File(getDataFolder(), "mines.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(minesFile);
            if (!config.contains("mines")) return;

            for (String name : config.getConfigurationSection("mines").getKeys(false)) {
                String worldName = config.getString("mines." + name + ".world");

                ConfigurationSection pos1Section = config.getConfigurationSection("mines." + name + ".pos1");
                ConfigurationSection pos2Section = config.getConfigurationSection("mines." + name + ".pos2");

                Location pos1 = deserializeLocation(pos1Section, worldName);
                Location pos2 = deserializeLocation(pos2Section, worldName);
                if (pos1 == null || pos2 == null) continue;

                World world = Bukkit.getWorld(worldName);
                if (world == null) continue;

                int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
                int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
                int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
                int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
                int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

                int teleportX = (minX + maxX) / 2;
                int teleportZ = (minZ + maxZ) / 2;
                int teleportY = maxY - 1;

                ConfigurationSection spawnSection = config.getConfigurationSection("mines." + name + ".spawn");
                Location spawnLoc = deserializeLocation(spawnSection, worldName);
                if (spawnLoc == null) {
                    spawnLoc = new Location(world, teleportX + 0.5, teleportY, teleportZ + 0.5);
                }

                for (Player player : world.getPlayers()) {
                    Location loc = player.getLocation();
                    int bx = loc.getBlockX();
                    int by = loc.getBlockY();
                    int bz = loc.getBlockZ();

                    if (bx >= minX && bx <= maxX &&
                            by >= minY && by <= maxY &&
                            bz >= minZ && bz <= maxZ) {
                        player.teleport(spawnLoc);
                        player.sendMessage(ChatColor.YELLOW + "You've been moved while the mine resets.");
                    }
                }

                List<String> blockNames = config.getStringList("mines." + name + ".blocks");
                List<Material> specialBlocks = blockNames.stream().map(Material::valueOf).toList();

                List<Material> fillerBlocks = switch (name) {
                    case "ore" -> Arrays.asList(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.TUFF);
                    case "wood" -> Arrays.asList(Material.DIRT, Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.PALE_MOSS_BLOCK);
                    case "nether" -> Arrays.asList(Material.GLOWSTONE, Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.SHROOMLIGHT);
                    case "crystal" -> Arrays.asList(Material.GLASS, Material.WHITE_STAINED_GLASS, Material.ORANGE_STAINED_GLASS,
                            Material.MAGENTA_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS,
                            Material.LIME_STAINED_GLASS, Material.PINK_STAINED_GLASS, Material.GRAY_STAINED_GLASS,
                            Material.LIGHT_GRAY_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.PURPLE_STAINED_GLASS,
                            Material.BLUE_STAINED_GLASS, Material.BROWN_STAINED_GLASS, Material.GREEN_STAINED_GLASS,
                            Material.RED_STAINED_GLASS, Material.BLACK_STAINED_GLASS, Material.TINTED_GLASS);
                    default -> Collections.singletonList(Material.STONE);
                };

                int fillMinX = minX + 2;
                int fillMaxX = maxX - 2;
                int fillMinZ = minZ + 2;
                int fillMaxZ = maxZ - 2;
                int fillMinY = minY + 1;
                int fillTopY = fillMinY + 58;

                List<Block> blocksToSet = new ArrayList<>();
                for (int x = fillMinX; x < fillMaxX; x++) {
                    for (int z = fillMinZ; z < fillMaxZ; z++) {
                        for (int y = fillMinY; y < fillTopY; y++) {
                            blocksToSet.add(world.getBlockAt(x, y, z));
                        }
                    }
                }

                Collections.shuffle(blocksToSet); // Optional: spreads load better

                new BukkitRunnable() {
                    final Iterator<Block> iterator = blocksToSet.iterator();
                    final Random rand = new Random();
                    final int BLOCKS_PER_TICK = 1000;

                    @Override
                    public void run() {
                        int count = 0;
                        while (iterator.hasNext() && count < BLOCKS_PER_TICK) {
                            Block block = iterator.next();
                            Material mat = rand.nextDouble() < 0.20
                                    ? specialBlocks.get(rand.nextInt(specialBlocks.size()))
                                    : fillerBlocks.get(rand.nextInt(fillerBlocks.size()));
                            block.setType(mat, false);
                            count++;
                        }

                        if (!iterator.hasNext()) cancel();
                    }
                }.runTaskTimer(this, 0L, 1L); // Smooth reset over multiple ticks
            }
        }, 0L, 20L * 60 * 90); // Every 130 minutes
    }
    @EventHandler
    public void onSignClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (!(event.getClickedBlock().getState() instanceof Sign sign)) return;

        String[] lines = sign.getLines();
        if (!lines[0].equalsIgnoreCase("[Shop]")) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String mode = lines[1].toUpperCase();

        String[] itemSplit = lines[2].split(" ");
        if (itemSplit.length != 2) return;

        int amount;
        double price;
        try {
            amount = Integer.parseInt(itemSplit[0]);
            price = Double.parseDouble(lines[3].replace("$", ""));
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid shop sign format.");
            return;
        }

        Material material = Material.matchMaterial(itemSplit[1]);
        if (material == null) {
            player.sendMessage(ChatColor.RED + "Unknown item type.");
            return;
        }

        ItemStack item = new ItemStack(material, amount);

        if (mode.equals("BUY")) {
            if (getMoneyManager().getBalance(uuid) < price) {
                player.sendMessage(ChatColor.RED + "You don't have enough money.");
                return;
            }

            if (player.getInventory().firstEmpty() == -1) {
                player.sendMessage(ChatColor.RED + "Your inventory is full.");
                return;
            }

            getMoneyManager().withdraw(uuid, price);
            player.getInventory().addItem(item);
            player.sendMessage(ChatColor.GREEN + "You bought " + amount + "x " + material.name() + " for " + price+" tokens.");
        }

        else if (mode.equals("SELL")) {
            if (!player.getInventory().containsAtLeast(item, amount)) {
                player.sendMessage(ChatColor.RED + "You don't have enough items to sell.");
                return;
            }
            double multiplier = sellBoostManager.getMultiplier(player.getUniqueId());
            double price2 = price * multiplier;
            removeItems(player.getInventory(), material, amount);
            getMoneyManager().deposit(uuid, price2);
            player.sendMessage(ChatColor.GREEN + "You sold " + amount + "x " + material.name() + " for " + price2+" tokens.");
        }
    }
    public void removeItems(Inventory inv, Material material, int amount) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == material) {
                int removed = Math.min(item.getAmount(), amount);
                item.setAmount(item.getAmount() - removed);
                amount -= removed;
                if (amount <= 0) break;
            }
        }
    }

    public MoneyManager getMoneyManager() {
        return moneyManager;
    }

    @EventHandler
    public void onVisitGUIClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTitle() == null || !event.getView().getTitle().contains("Visitable Islands")) return;

        event.setCancelled(true); // Prevent taking items

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

        SkullMeta meta = (SkullMeta) clicked.getItemMeta();
        if (meta == null || meta.getOwningPlayer() == null) return;

        OfflinePlayer targetPlayer = meta.getOwningPlayer();
        UUID targetId = targetPlayer.getUniqueId();

        if (!islandCenters.containsKey(targetId)) {
            player.sendMessage(ChatColor.RED + "That player does not have an island.");
            return;
        }

        boolean isLocked = islandLocks.getOrDefault(targetId, false);
        if (isLocked) {
            player.sendMessage(ChatColor.RED + "This island is locked and cannot be visited.");
            return;
        }

        Location visitLoc = visitSpawns.getOrDefault(
                targetId,
                islandCenters.get(targetId).clone().add(0, 57, 0)
        );

        player.teleport(visitLoc);
        player.sendMessage(ChatColor.GREEN + "You are now visiting " + targetPlayer.getName() + "'s island!");
        player.closeInventory();
    }

}
