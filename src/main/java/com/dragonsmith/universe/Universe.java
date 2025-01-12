package com.dragonsmith.universe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.Chest;

import java.util.*;
import java.util.stream.Collectors;

public class Universe extends JavaPlugin implements Listener {

    private final HashMap<UUID, Location> islandCenters = new HashMap<>();
    private final HashMap<UUID, Integer> islandSizes = new HashMap<>();
    private final HashMap<UUID, Integer> islandGeneratorLevels = new HashMap<>(); // Track the level of ore generator
    // Initialize the islandBiomes map if not already initialized
    private Map<UUID, Biome> islandBiomes = new HashMap<>();
       // Add a map to track which players are trusted on an island
    private final HashMap<UUID, Set<UUID>> trustedPlayers = new HashMap<>();
    private BlockTracker blockTracker;
    private Economy economy;

    private int nextIslandX = 0;
    private int nextIslandZ = 0;

    private int islandSpacing; // Loaded from config
    private int defaultIslandSize; // Loaded from config
    private int maxIslandSize; // Loaded from config
    private int spawnHeight; // Loaded from config

    @Override
    public void onEnable() {
        // Load configuration
        if (!setupEconomy()) {
            getLogger().severe("Vault dependency not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        islandSpacing = config.getInt("island.spacing", 1012);
        defaultIslandSize = config.getInt("island.default_size", 32);
        maxIslandSize = config.getInt("island.max_size", 128);
        spawnHeight = 57; // Spawn just above the grass block, which is at Y = 56

        // Initialize the BlockTracker listener
        blockTracker = new BlockTracker();
        getServer().getPluginManager().registerEvents(blockTracker, this);

        getLogger().info("universe plugin has been enabled!");

        // Generate or load the empty world
        WorldCreator worldCreator = new WorldCreator("universe_world");
        worldCreator.generator(new EmptyWorldGenerator());
        World world = worldCreator.createWorld();
        if (world != null) {
         world.setMonsterSpawnLimit(70); // Adjust as needed
        world.setAnimalSpawnLimit(10); // Adjust as needed
        world.setSpawnFlags(true, true); // Enable mob spawning
        }

        // Load island data for all players
        loadIslandData();

        getCommand("createisland").setTabCompleter(this);
        getCommand("expandisland").setTabCompleter(this);
        getCommand("balance").setTabCompleter(this);
        this.getCommand("setbiome").setTabCompleter(new SetBiomeTabCompleter());

    }

    @Override
    public void onDisable() {
        getLogger().info("universe plugin has been disabled!");
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
        if (command.getName().equalsIgnoreCase("deleteisland")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island to delete!");
                return true;
            }

            World world = Bukkit.getWorld("universe_world");
            if (world == null) {
                player.sendMessage(ChatColor.RED + "The world is not available!");
                return true;
            }

            // Get island details
            Location center = islandCenters.get(playerId);
            int size = islandSizes.get(playerId);

            // Clear the island area
            int startX = center.getBlockX() - size / 2;
            int endX = center.getBlockX() + size / 2;
            int startZ = center.getBlockZ() - size / 2;
            int endZ = center.getBlockZ() + size / 2;

            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    for (int y = 0; y < 256; y++) { // Clear from bottom to top of the world
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                }
            }

            // Remove player's island data
            islandCenters.remove(playerId);
            islandSizes.remove(playerId);
            islandGeneratorLevels.remove(playerId);
            islandBiomes.remove(playerId);

            // Notify the player
            player.sendMessage(ChatColor.GREEN + "Your island has been successfully deleted!");
            player.teleport(world.getSpawnLocation()); // Teleport the player to the world's spawn location
            return true;
        }

        // Handle the "/expandisland" command
        if (command.getName().equalsIgnoreCase("expandisland")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }
            if (economy.getBalance(player) < 50000 * islandSizes.get(playerId)) {
                player.sendMessage(ChatColor.RED + "You need at least "+ 50000 * islandSizes.get(playerId)+" coins to expand the realm!");
                return true;
            }
            int currentSize = islandSizes.get(playerId);
            int newSize = currentSize * 2;

            if (newSize > maxIslandSize) { // Limit size based on config
                player.sendMessage(ChatColor.RED + "Maximum island size reached!");
                return true;
            }
            economy.withdrawPlayer(player, 50000 * islandSizes.get(playerId));
            Location center = islandCenters.get(playerId);
            generateIsland(center, newSize, playerId);
            islandSizes.put(playerId, newSize);

            player.sendMessage(ChatColor.GREEN + "Island expanded to " + newSize + "x" + newSize + "!");
            return true;
        }

        // Handle the "/upgradegenerator" command
        if (command.getName().equalsIgnoreCase("upgradegenerator")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }

            int currentLevel = islandGeneratorLevels.get(playerId);
            if (currentLevel < 5) { // Limit the maximum level
                int newLevel = currentLevel + 1;
                islandGeneratorLevels.put(playerId, newLevel);
                player.sendMessage(ChatColor.GREEN + "Your island generator has been upgraded to level " + newLevel + "!");
            } else {
                player.sendMessage(ChatColor.RED + "Maximum generator level reached!");
            }
            return true;
        }

        // Handle the "/home" command to teleport to the island
        if (command.getName().equalsIgnoreCase("home")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }

            Location center = islandCenters.get(playerId);
            player.teleport(center.clone().add(0, 57, 0));
            player.sendMessage(ChatColor.GREEN + "Teleported to your island!");
            return true;
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

                    trustedPlayers.computeIfAbsent(ownerId, k -> new HashSet<>()).add(targetId);
                    player.sendMessage(ChatColor.GREEN + target.getName() + " has been trusted on your island.");
                    saveIslandData(ownerId, islandCenters.get(ownerId), islandSizes.get(ownerId));
                } else {
                    player.sendMessage(ChatColor.RED + "Player not found.");
                }
                return true;
            }
        }

if (command.getName().equalsIgnoreCase("untrust") && sender instanceof Player) {
     player = (Player) sender;
    UUID ownerId = player.getUniqueId();

    if (args.length == 1) {
        Player target = Bukkit.getPlayer(args[0]);
        if (target != null) {
            UUID targetId = target.getUniqueId();
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

private boolean isBlockWithinIslandBounds(Location blockLocation, UUID playerId) {
    // Get the island's center and size
    Location islandCenter = islandCenters.get(playerId);
    int size = islandSizes.get(playerId);
    
    // If the player doesn't have an island, return false
    if (islandCenter == null) return false;
    
    int halfSize = size / 2;
    
    // Get the island bounds and check if the block is inside
    int minX = islandCenter.getBlockX() - halfSize;
    int maxX = islandCenter.getBlockX() + halfSize;
    int minZ = islandCenter.getBlockZ() - halfSize;
    int maxZ = islandCenter.getBlockZ() + halfSize;
    int minY = islandCenter.getBlockY();
    int maxY = minY + size;  // Adjust this based on your island height
    
    return blockLocation.getBlockX() >= minX && blockLocation.getBlockX() <= maxX &&
           blockLocation.getBlockZ() >= minZ && blockLocation.getBlockZ() <= maxZ &&
           blockLocation.getBlockY() >= minY && blockLocation.getBlockY() <= maxY;
}


private void generateIsland(Location center, int size, UUID playerId) {
    int half = size / 2;
    Random random = new Random();

    // Perform the island generation on the main thread to ensure block updates
    new BukkitRunnable() {
        @Override
        public void run() {
            // Generate terrain
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    for (int y = -64; y <= 64; y++) { // Loop through Y from -64 to 64
                        Location loc = center.clone().add(x, y, z);

                        // Skip blocks that have been broken by the player
                        if (blockTracker.isBlockBroken(playerId, loc)) {
                            continue; // Don't modify this block
                        }

                        // Only modify air blocks (or bedrock for the bottom-most layer)
                        if (loc.getBlock().getType() == Material.AIR || loc.getBlock().getType() == Material.BEDROCK) {
                            if (y == -64) {
                                loc.getBlock().setType(Material.BEDROCK);
                            }
                            // Generate Deepslate from Y = -63 to Y = -6
                            else if (y >= -63 && y <= -6) {
                                loc.getBlock().setType(Material.DEEPSLATE);
                                generateOres(loc, random, y);
                            }
                            // Generate mixed Stone and Deepslate from Y = -5 to Y = 5
                            else if (y >= -5 && y <= 5) {
                                Material material = Math.random() < 0.5 ? Material.DEEPSLATE : Material.STONE;
                                loc.getBlock().setType(material);
                                generateOres(loc, random, y);
                            }
                            // Generate Stone/Dirt from Y = 0 to Y = 55
                            else if (y >= 0 && y <= 52) {
                                loc.getBlock().setType(Material.STONE);
                                generateOres(loc, random, y);
                            } else if (y >= 53 && y <= 55) {
                                loc.getBlock().setType(Material.DIRT);
                            }
                            // Generate Grass at Y = 56
                            else if (y == 56) {
                                loc.getBlock().setType(Material.GRASS_BLOCK);
                            }
                        }
                    }
                }
            }

            // Now generate trees on the island
            generateTrees(center, size, random);

            // Save island center and size to the config after generating
            saveIslandData(playerId, center, size);
        }
    }.runTask(this); // Run this task on the main thread
}
    private void generateOres(Location loc, Random random, int y) {
        Material ore = null;

        if (y >= -64 && y <= -8) { // Deepslate layer (Deep underground)
            if (random.nextDouble() < 0.001) ore = Material.DEEPSLATE_DIAMOND_ORE; // Extremely rare (0.1%)
            else if (random.nextDouble() < 0.0015) ore = Material.DEEPSLATE_EMERALD_ORE; // Extremely rare (0.15%)
            else if (random.nextDouble() < 0.005) ore = Material.DEEPSLATE_REDSTONE_ORE; // Rare (0.5%)
            else if (random.nextDouble() < 0.01) ore = Material.DEEPSLATE_GOLD_ORE; // Rare (1%)
            else if (random.nextDouble() < 0.015) ore = Material.DEEPSLATE_LAPIS_ORE; // Less common (1.5%)
            else if (random.nextDouble() < 0.02) ore = Material.DEEPSLATE_COPPER_ORE; // Less common (2%)
            else if (random.nextDouble() < 0.03) ore = Material.DEEPSLATE_IRON_ORE; // Common (3%)
        } else if (y >= -7 && y <= 15) { // Stone + transitional layer
            if (random.nextDouble() < 0.0005) ore = Material.DIAMOND_ORE; // Extremely rare (0.05%)
            else if (random.nextDouble() < 0.001) ore = Material.EMERALD_ORE; // Extremely rare (0.1%)
            else if (random.nextDouble() < 0.002) ore = Material.REDSTONE_ORE; // Rare (0.2%)
            else if (random.nextDouble() < 0.005) ore = Material.GOLD_ORE; // Rare (0.5%)
            else if (random.nextDouble() < 0.01) ore = Material.LAPIS_ORE; // Less common (1%)
            else if (random.nextDouble() < 0.015) ore = Material.COPPER_ORE; // Common (1.5%)
            else if (random.nextDouble() < 0.02) ore = Material.IRON_ORE; // Common (2%)
        } else if (y >= 16 && y <= 52) { // Surface + near-surface layer
            if (random.nextDouble() < 0.0003) ore = Material.EMERALD_ORE; // Extremely rare (0.03%)
            else if (random.nextDouble() < 0.005) ore = Material.COAL_ORE; // Common (0.5%)
            else if (random.nextDouble() < 0.01) ore = Material.IRON_ORE; // Less common (1%)
            else if (random.nextDouble() < 0.015) ore = Material.COPPER_ORE; // Common (1.5%)
        }

        if (ore != null) {
            loc.getBlock().setType(ore);
        }
    }



            public void generateTerrain(Location center, int size) {
        // Calculate the half size of the island (radius from center)
        int half = size / 2;
        World world = center.getWorld();

        // Ensure the world is not null
        if (world == null) {
            return;
        }

        // Random object to decide where to place flowers and grass
        Random random = new Random();

        // Loop through the area around the center and generate terrain
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                // Calculate the X and Z coordinates based on the center
                int blockX = center.getBlockX() + x;
                int blockZ = center.getBlockZ() + z;

                // Apply a fixed Y-offset to the Y coordinate (height of the island)
                int blockY = center.getBlockY() + 56;

                // Get the block at the calculated X, Y, Z location (for the ground)
                Block block = world.getBlockAt(blockX, blockY + 1, blockZ);

                // Skip the block if it is a chest or non-ground block
                if (block.getType() == Material.CHEST || block.getType() == Material.AIR) {
                    continue;  // Skip this block and move to the next one
                }

                // Only modify blocks that are grass blocks or suitable ground
                if (block.getType() == Material.GRASS_BLOCK || block.getType() == Material.DIRT) {
                    // Check the block above (one block higher than the ground)
                    Block blockAbove = world.getBlockAt(blockX, blockY + 1, blockZ);

                    // Make sure the block above is empty (not solid or already occupied)
                    if (blockAbove.getType() == Material.AIR) {
                        // Randomly decide whether to place grass or a flower
                        if (random.nextInt(10) < 8) {  // 80% chance to add grass
                            blockAbove.setType(Material.TALL_GRASS);
                        } else {
                            // Randomly choose between a flower (poppy or dandelion)
                            if (random.nextBoolean()) {
                                blockAbove.setType(Material.POPPY);  // 50% chance for poppy
                            } else {
                                blockAbove.setType(Material.DANDELION);  // 50% chance for dandelion
                            }
                        }
                    }
                }
            }
        }
    }





public void generateTrees(Location center, int size, Random random) {
    World world = center.getWorld();
    
    // Number of trees to generate (between 5 and 25)
    int maxTrees = 5 + random.nextInt(21); // Random tree count between 5 and 25

    // Tree types: oak, birch, jungle, etc.
    TreeType[] treeTypes = {
            TreeType.TREE,        // Standard oak
            TreeType.BIRCH,       // Birch tree
            TreeType.JUNGLE,      // Jungle tree
            TreeType.REDWOOD,     // Redwood tree (very large)
            TreeType.SMALL_JUNGLE // Smaller jungle tree
    };

    // Loop to generate trees
    for (int i = 0; i < maxTrees; i++) {
        // Random offsets within the island size (to avoid trees growing too close together)
        int xOffset = random.nextInt(size) - size / 2;
        int zOffset = random.nextInt(size) - size / 2;

        // Calculate the tree location (Y coordinate will be at 57 for the island surface)
        Location treeLocation = center.clone().add(xOffset, 57, zOffset);
        
        // Make sure the location is a valid place to grow a tree (ground block is dirt or grass)
        Block groundBlock = world.getBlockAt(treeLocation.clone().add(0, -1, 0));  // Ground block just beneath the location
        if (groundBlock.getType() == Material.GRASS_BLOCK || groundBlock.getType() == Material.DIRT) {
            // Ensure there's space for tree generation (no other blocks or terrain issues)
            if (isAreaClearForTree(world, treeLocation, 2)) {  // Check space for tree
                // Randomly select a tree type (oak, birch, etc.)
                TreeType selectedTree = treeTypes[random.nextInt(treeTypes.length)];

                // Try to generate the tree
                world.generateTree(treeLocation, selectedTree);
            }
        }
    }
}

private boolean isAreaClearForTree(World world, Location location, int radius) {
    // Check if there are no solid blocks in the area where the tree would be placed
    for (int x = -radius; x <= radius; x++) {
        for (int z = -radius; z <= radius; z++) {
            for (int y = -2; y <= 3; y++) {  // Check a 5x5x5 block area for block interference
                Location checkLoc = location.clone().add(x, y, z);
                if (!checkLoc.getBlock().getType().isAir()) {
                    return false;  // Block is not air, so space is not clear
                }
            }
        }
    }
    return true;
}


    /**
     * Checks if the area around the location is clear for tree generation.
     *
     * @param location The center of the area to check.
     * @param radius   The radius around the location to check.
     * @return True if the area is clear, false otherwise.
     */
    private boolean isAreaClear(Location location, int radius) {
        World world = location.getWorld();
        int startX = location.getBlockX() - radius;
        int endX = location.getBlockX() + radius;
        int startZ = location.getBlockZ() - radius;
        int endZ = location.getBlockZ() + radius;
        int y = location.getBlockY();

        // Check each block within the radius
        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.isEmpty()) {
                    return false; // Found an obstruction
                }
            }
        }
        return true;
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

    private void saveIslandData(UUID playerId, Location center, int size) {
        // Serialize the Location to a map
        Map<String, Object> centerMap = new HashMap<>();
        centerMap.put("world", center.getWorld().getName());
        centerMap.put("x", center.getX());
        centerMap.put("y", center.getY());
        centerMap.put("z", center.getZ());
        centerMap.put("yaw", center.getYaw());
        centerMap.put("pitch", center.getPitch());

        // Save center and size
        getConfig().set("islands." + playerId + ".center", centerMap);
        getConfig().set("islands." + playerId + ".size", size);

        // Save the trusted players list
        Set<UUID> trusted = trustedPlayers.getOrDefault(playerId, new HashSet<>());
        List<String> trustedList = trusted.stream().map(UUID::toString).toList();
        getConfig().set("islands." + playerId + ".trusted", trustedList);

        saveConfig();
    }

    private void loadIslandData() {
        FileConfiguration config = getConfig();
        if (config.contains("islands")) {
            for (String playerIdString : config.getConfigurationSection("islands").getKeys(false)) {
                UUID playerId = UUID.fromString(playerIdString);

                // Deserialize the Location
                Map<String, Object> centerMap = config.getConfigurationSection("islands." + playerId + ".center").getValues(false);
                String worldName = (String) centerMap.get("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) continue; // Handle missing world gracefully
                double x = (double) centerMap.get("x");
                double y = (double) centerMap.get("y");
                double z = (double) centerMap.get("z");
                float yaw = ((Number) centerMap.get("yaw")).floatValue();
                float pitch = ((Number) centerMap.get("pitch")).floatValue();

                Location center = new Location(world, x, y, z, yaw, pitch);

                // Load size
                int size = config.getInt("islands." + playerId + ".size", defaultIslandSize);

                // Store data
                islandCenters.put(playerId, center);
                islandSizes.put(playerId, size);

                // Load the trusted players list
                List<String> trustedList = config.getStringList("islands." + playerId + ".trusted");
                Set<UUID> trustedSet = trustedList.stream().map(UUID::fromString).collect(Collectors.toSet());
                trustedPlayers.put(playerId, trustedSet);
            }
        }
    }

@EventHandler
public void onBlockFromTo(BlockFromToEvent event) {
    Block block = event.getBlock();
    Location from = event.getBlock().getLocation();
    Location to = event.getToBlock().getLocation();

    // Check if either the source or the destination block is outside the player's island
    if (isInIsland(playerId, to) && !isInIsland(playerId, from)) {
        event.setCancelled(true);
        block.getWorld().getBlockAt(to).setType(Material.AIR);  // Remove flowing block if outside bounds
        // Optionally, send a message to the player that the block cannot flow out of bounds
        player.sendMessage("You cannot let water or lava flow outside your island.");
    }
}

@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    Location blockLocation = event.getBlock().getLocation();

    // Check if the block is within the player's island and if the player is allowed to break blocks
    if (isBlockWithinIslandBounds(blockLocation, playerId)) {
        Set<UUID> trusted = trustedPlayers.get(playerId);
        
        // Check if the player is the island owner or trusted
        if (playerId.equals(islandOwnerId) || trusted.contains(playerId)) {
            return; // Allow block break
        } else {
            event.setCancelled(true); // Prevent block break if not trusted
            player.sendMessage("You are not allowed to modify this island.");
        }
    } else {
        // If the block is outside the island bounds, you can allow breaking or modify as needed.
        // For example, cancel the event if the block is outside the island
        event.setCancelled(true);
        player.sendMessage("You are outside of your island.");
    }
}


@EventHandler
public void onBlockPlace(BlockPlaceEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    Location blockLocation = event.getBlock().getLocation();

    // Check if the block is within the player's island bounds
    if (isInIsland(playerId, blockLocation)) {
        // If the player is not trusted, cancel the event
        if (!isPlayerTrusted(playerId, playerId)) {
            event.setCancelled(true);
            player.sendMessage("You do not have permission to place blocks here.");
        }
    } else {
        // If the block is outside the island, cancel the event
        event.setCancelled(true);
        player.sendMessage("You cannot place blocks outside your island.");
    }
}



@EventHandler
public void onPlayerInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    Block clickedBlock = event.getClickedBlock();

    if (clickedBlock == null) {
        return;
    }

    Location blockLocation = clickedBlock.getLocation();

    // Check if the interaction is within the player's island
    if (isInIsland(playerId, blockLocation)) {
        // Allow interactions within the island, but check trust status for specific blocks (e.g., chests, doors, etc.)
        if (clickedBlock.getType() == Material.CHEST) {
            // If the player isn't trusted, cancel the interaction
            if (!isPlayerTrusted(playerId, playerId)) {
                event.setCancelled(true);
                player.sendMessage("You do not have permission to interact with this chest.");
            }
        }
    } else {
        // If the interaction is outside the island, cancel the event
        event.setCancelled(true);
        player.sendMessage("You cannot interact with blocks outside your island.");
    }
}



    private UUID getIslandOwner(Location location) {
        for (Map.Entry<UUID, Location> entry : islandCenters.entrySet()) {
            UUID ownerId = entry.getKey();
            Location islandCenter = entry.getValue();
            int islandSize = islandSizes.getOrDefault(ownerId, defaultIslandSize);
            int halfSize = islandSize / 2;

            // Check if the location is within the bounds of the island
            if (location.getX() >= islandCenter.getX() - halfSize &&
                    location.getX() <= islandCenter.getX() + halfSize &&
                    location.getZ() >= islandCenter.getZ() - halfSize &&
                    location.getZ() <= islandCenter.getZ() + halfSize) {
                return ownerId;
            }
        }
        return null; // No owner found
    }


    private void placeOreInCobblestone(Location location) {
        Random rand = new Random();

        // Choose a random ore to generate
        Material ore = getRandomOre(rand);

        // Only replace the cobblestone with an ore if it's indeed cobblestone
        if (location.getBlock().getType() == Material.COBBLESTONE) {
            location.getBlock().setType(ore);  // Replace cobblestone with ore
        }
    }

    // Helper method to return a random ore type
    private Material getRandomOre(Random rand) {
        switch (rand.nextInt(5)) {
            case 0:
                return Material.COAL_ORE;
            case 1:
                return Material.IRON_ORE;
            case 2:
                return Material.GOLD_ORE;
            case 3:
                return Material.DIAMOND_ORE;
            case 4:
                return Material.REDSTONE_ORE;  // You can add more ores here as needed
            default:
                return Material.COBBLESTONE;  // Shouldn't really happen, but a fallback
        }
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("createisland") || command.getName().equalsIgnoreCase("expandisland")) {
            return Collections.emptyList();
        }
        return null;
    }
}
