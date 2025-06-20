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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

    @Override
    public void onEnable() {
        giveUniverseMenuToOnlinePlayers();

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

        saveDefaultConfig();
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
        this.getCommand("setbiome").setTabCompleter(new SetBiomeTabCompleter());
        getCommand("ignoreclaims").setExecutor(this);
        getCommand("islandinfo").setExecutor(this);
        getCommand("wand").setExecutor(this);
        getCommand("createmine").setExecutor(this);
        getCommand("resetmine").setExecutor(this);
        getLogger().info("Universe plugin has been enabled!");
    }


@Override
public void onDisable() {
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


    private void giveUniverseMenuToOnlinePlayers() {
        getServer().getScheduler().runTaskLater(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                giveUniverseMenuItem(player);
            }
        }, 20L);
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

    // Calculate the island location
    Location center = new Location(world, nextIslandX, spawnHeight, nextIslandZ);
    nextIslandX += islandSpacing;
    if (nextIslandX > 6000) { // Wrap around after 10 islands horizontally
        nextIslandX = 0;
        nextIslandZ += islandSpacing;
    }

    // Assign the island to the player
    assignIslandToPlayer(center, playerId);

    // Assign ownership
    islandCenters.put(playerId, center); // Link player ID to their island
    islandSizes.put(playerId, defaultIslandSize);
    islandGeneratorLevels.put(playerId, 1);
    islandBiomes.put(playerId, Biome.PLAINS);

    // Generate the island
    generateIsland(center, defaultIslandSize, playerId);
    world.getChunkAt(center).load(); // Load the chunk
    player.teleport(center.clone().add(0, 57, 0));
    giveStarterChest(center);

    // Notify the player
    player.sendMessage(ChatColor.GREEN + "Island created successfully!");
    player.sendMessage(ChatColor.GOLD + "You are now the owner of this island.");
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
                player.sendMessage(ChatColor.RED + "Usage: /resetmine <ore|wood|nether>");
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
                case "ore" ->
                        Arrays.asList(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.TUFF);
                case "wood" ->
                        Arrays.asList(Material.DIRT, Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.PALE_MOSS_BLOCK);
                case "nether" ->
                        Arrays.asList(Material.GLOWSTONE, Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.SHROOMLIGHT);
                default -> Collections.singletonList(Material.STONE);
            };

            assert worldName != null;
            World world = Bukkit.getWorld(worldName);
            if (world == null || pos1 == null || pos2 == null) {
                player.sendMessage(ChatColor.RED + "Failed to load mine world or positions.");
                return true;
            }

            int minX = Math.min(pos1.getBlockX(), pos2.getBlockX()) + 2;
            int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX()) - 2;
            int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ()) + 2;
            int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ()) - 2;
            int baseY = Math.min(pos1.getBlockY(), pos2.getBlockY()) + 1;
            int topY = baseY + 58;

            Random rand = new Random();
            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    for (int y = baseY; y < topY; y++) {
                        Material mat = rand.nextDouble() < 0.20
                                ? specialBlocks.get(rand.nextInt(specialBlocks.size()))
                                : fillerBlocks.get(rand.nextInt(fillerBlocks.size()));
                        world.getBlockAt(x, y, z).setType(mat);
                    }
                }
            }

            player.sendMessage(ChatColor.GREEN + "Mine '" + mineName + "' has been reset!");
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
            if (!Arrays.asList("ore", "wood", "nether").contains(name)) {
                player.sendMessage(ChatColor.RED + "Invalid mine type. Choose ore, wood, or nether.");
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

            // Calculate the cost for expansion based on the current island size
            int currentSize = islandSizes.get(playerId);
            int expansionCost = 50000 * currentSize;

            // Check if the player has enough balance
            if (economy.getBalance(player) < expansionCost) {
                player.sendMessage(ChatColor.RED + "You need at least " + expansionCost + " coins to expand the realm!");
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
            economy.withdrawPlayer(player, expansionCost);

            // Get the current center of the island
            Location center = islandCenters.get(playerId);

            // Call the expandIsland method to handle the expansion
            extendIsland(center, newSize, playerId);

            // Update the island size
            islandSizes.put(playerId, newSize);

            // Notify the player that their island was expanded
            player.sendMessage(ChatColor.GREEN + "Island expanded to " + newSize + "x" + newSize + "!");

            // Optional: Notify player of remaining balance
            double remainingBalance = economy.getBalance(player);
            player.sendMessage(ChatColor.YELLOW + "Your remaining balance: " + remainingBalance + " coins.");

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

            net.milkbowl.vault.economy.Economy econ = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class).getProvider();
            if (econ.getBalance(player) < cost) {
                player.sendMessage(ChatColor.RED + "You need $" + cost + " to upgrade your generator.");
                return true;
            }

            econ.withdrawPlayer(player, cost);
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
            double balance = economy.getBalance(player);
            player.sendMessage(ChatColor.GREEN + "Your balance: " + balance + " coins.");
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
            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /visit <player>");
                return true;
            }

            Player targetPlayer = Bukkit.getPlayer(args[0]);
            if (targetPlayer == null) {
                player.sendMessage(ChatColor.RED + "Player not found or not online.");
                return true;
            }

            UUID targetPlayerId = targetPlayer.getUniqueId();

            if (!islandCenters.containsKey(targetPlayerId)) {
                player.sendMessage(ChatColor.RED + "This player does not have an island.");
                return true;
            }

            Location targetIsland = islandCenters.get(targetPlayerId);
            player.teleport(targetIsland.clone().add(0, 57, 0)); // Teleport just above the ground level
            player.sendMessage(ChatColor.GREEN + "Teleported to " + targetPlayer.getName() + "'s island!");
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


    private void generateBaseIsland(Location center, int size, UUID playerId) {
        int half = size / 2;
        Random random = new Random();

        // Fast generation of the basic island layout (core, bedrock, stone, etc.)
        new BukkitRunnable() {
            @Override
            public void run() {
                // Generate basic terrain for island's core
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

                // After base generation, you can load chunks and then allow island extension
                loadChunks(center, half); // Load the chunks for future expansion
            }
        }.runTask(this); // Run asynchronously for a quick base generation
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
                String worldName = minesConfig.getString("mines." + name + ".world");

                ConfigurationSection pos1Section = minesConfig.getConfigurationSection("mines." + name + ".pos1");
                ConfigurationSection pos2Section = minesConfig.getConfigurationSection("mines." + name + ".pos2");

                Location pos1 = deserializeLocation(pos1Section, worldName);
                Location pos2 = deserializeLocation(pos2Section, worldName);

                List<String> blockNames = config.getStringList("mines." + name + ".blocks");
                List<Material> specialBlocks = blockNames.stream().map(Material::valueOf).collect(Collectors.toList());

                List<Material> fillerBlocks;
                switch (name) {
                    case "ore":
                        fillerBlocks = Arrays.asList(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.CALCITE, Material.TUFF);
                        break;
                    case "wood":
                        fillerBlocks = Arrays.asList(Material.DIRT, Material.MOSS_BLOCK, Material.MOSSY_COBBLESTONE, Material.PALE_MOSS_BLOCK);
                        break;
                    case "nether":
                        fillerBlocks = Arrays.asList(Material.GLOWSTONE, Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.SHROOMLIGHT);
                        break;
                    default:
                        fillerBlocks = Collections.singletonList(Material.STONE);
                }

                World world = Bukkit.getWorld(worldName);
                if (world == null || pos1 == null || pos2 == null) continue;

                int minX = Math.min(pos1.getBlockX(), pos2.getBlockX()) + 2;
                int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX()) - 2;
                int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ()) + 2;
                int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ()) - 2;
                int baseY = Math.min(pos1.getBlockY(), pos2.getBlockY()) + 1;
                int topY = baseY + 58;

                Random rand = new Random();
                for (int x = minX; x < maxX; x++) {
                    for (int z = minZ; z < maxZ; z++) {
                        for (int y = baseY; y < topY; y++) {
                            Material mat = rand.nextDouble() < 0.20
                                    ? specialBlocks.get(rand.nextInt(specialBlocks.size()))
                                    : fillerBlocks.get(rand.nextInt(fillerBlocks.size()));
                            world.getBlockAt(x, y, z).setType(mat);
                        }
                    }
                }
            }
        }, 0L, 20L * 60 * 30); // Every 30 minutes
    }
}
