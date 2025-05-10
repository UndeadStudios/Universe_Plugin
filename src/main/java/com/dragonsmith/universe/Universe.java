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
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.Listener;
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

    @Override
    public void onEnable() {
        // Load configuration
        if (!setupEconomy()) {
            getLogger().severe("Vault dependency not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Save default config if it doesn't exist yet
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // Load the configuration values for island settings
        islandSpacing = config.getInt("island.spacing", 1012);
        defaultIslandSize = config.getInt("island.default_size", 32);
        maxIslandSize = config.getInt("island.max_size", 128);
        spawnHeight = 57; // Spawn just above the grass block, which is at Y = 56

        // Initialize the BlockTracker listener
        blockTracker = new BlockTracker();
        getServer().getPluginManager().registerEvents(blockTracker, this);

        getLogger().info("Universe plugin has been enabled!");

        // Generate or load the empty world for islands
        WorldCreator worldCreator = new WorldCreator("universe_world");
        worldCreator.generator(new EmptyWorldGenerator());  // Use a custom world generator if needed
        World world = worldCreator.createWorld();
        if (world != null) {
            world.setMonsterSpawnLimit(70);  // Adjust as needed
            world.setAnimalSpawnLimit(10);   // Adjust as needed
            world.setSpawnFlags(true, true); // Enable mob spawning
        }

        // Load island data for all players (island configuration should be loaded here)
getServer().getPluginManager().registerEvents(new Listener() {
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld().getName().equalsIgnoreCase("universe_world")) {
            getLogger().info("Universe world loaded via event. Now loading island data...");
            loadIslandData();
        }
    }
}, this);

        // Register command tab completers
        getCommand("createisland").setTabCompleter(this);
        getCommand("expandisland").setTabCompleter(this);
        getCommand("balance").setTabCompleter(this);
        this.getCommand("setbiome").setTabCompleter(new SetBiomeTabCompleter());

        // Optionally, you could also add a listener for player events like island creation and expansion
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

    // Calculate the boundaries of the island correctly (adjusted for full size and extra 5 blocks)
    int startX = center.getBlockX() - (size / 2) - 5; // Subtract 5 extra blocks
    int endX = center.getBlockX() + (size / 2) + 5;   // Add 5 extra blocks
    int startZ = center.getBlockZ() - (size / 2) - 5; // Subtract 5 extra blocks
    int endZ = center.getBlockZ() + (size / 2) + 5;   // Add 5 extra blocks

    // Perform block clearing in batches to avoid lag
    Player finalPlayer = player;
    new BukkitRunnable() {
        @Override
        public void run() {
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    // Clear blocks at each y level
                    for (int y = -64; y < 256; y++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR);
                    }
                }
            }

            // Remove player's island data after clearing
            islandCenters.remove(playerId);
            islandSizes.remove(playerId);
            islandGeneratorLevels.remove(playerId);
            islandBiomes.remove(playerId);

            // Notify the player
            finalPlayer.sendMessage(ChatColor.GREEN + "Your island has been successfully deleted!");
            finalPlayer.teleport(world.getSpawnLocation()); // Teleport the player to the world's spawn location
        }
    }.runTask(this);  // Run this task on the main server thread

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
    public boolean isWithinIslandBounds(UUID playerId, Location location) {
        int minX = islandMinX.getOrDefault(playerId, Integer.MIN_VALUE);
        int maxX = islandMaxX.getOrDefault(playerId, Integer.MAX_VALUE);
        int minY = islandMinY.getOrDefault(playerId, Integer.MIN_VALUE);
        int maxY = islandMaxY.getOrDefault(playerId, Integer.MAX_VALUE);
        int minZ = islandMinZ.getOrDefault(playerId, Integer.MIN_VALUE);
        int maxZ = islandMaxZ.getOrDefault(playerId, Integer.MAX_VALUE);

        return location.getBlockX() >= minX && location.getBlockX() <= maxX &&
                location.getBlockY() >= minY && location.getBlockY() <= maxY &&
                location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    private boolean isInIsland(UUID playerId, Location location) {
        Location islandCenter = islandCenters.get(playerId);
        Integer islandSize = islandSizes.get(playerId);

        if (islandCenter == null || islandSize == null) {
            return false; // Player doesn't have an island or the island is uninitialized
        }

        int halfSize = islandSize / 2;
        double distanceX = Math.abs(islandCenter.getX() - location.getX());
        double distanceZ = Math.abs(islandCenter.getZ() - location.getZ());
        double distanceY = Math.abs(islandCenter.getY() - location.getY());

        // Check if the location is within the bounds of the island (on X, Z, and Y axes)
        return distanceX <= halfSize && distanceZ <= halfSize && distanceY <= islandSize;
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

    // Calculate the boundaries
    int halfSize = size / 2;
    int minX = center.getBlockX() - halfSize;
    int maxX = center.getBlockX() + halfSize;
    int minY = 0;  // Adjust if needed
    int maxY = 255; // Adjust if needed
    int minZ = center.getBlockZ() - halfSize;
    int maxZ = center.getBlockZ() + halfSize;

    // Save the data to config
    getConfig().set("islands." + playerId + ".center", centerMap);
    getConfig().set("islands." + playerId + ".size", size);
    getConfig().set("islands." + playerId + ".minX", minX);
    getConfig().set("islands." + playerId + ".maxX", maxX);
    getConfig().set("islands." + playerId + ".minY", minY);
    getConfig().set("islands." + playerId + ".maxY", maxY);
    getConfig().set("islands." + playerId + ".minZ", minZ);
    getConfig().set("islands." + playerId + ".maxZ", maxZ);

    // Save the trusted players list
    Set<UUID> trusted = trustedPlayers.getOrDefault(playerId, new HashSet<>());
    List<String> trustedList = trusted.stream().map(UUID::toString).collect(Collectors.toList());
    getConfig().set("islands." + playerId + ".trusted", trustedList);

    // Save the owner information
    UUID ownerId = islandOwnershipMap.get(center);
    if (ownerId != null) {
        getConfig().set("islands." + playerId + ".owner", ownerId.toString());
    } else {
        getLogger().warning("No owner found for island at " + center + ". This might indicate an issue.");
    }

    // Save the config
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
if (world == null) {
    getLogger().severe("World '" + worldName + "' is not loaded. Island data for player " + playerId + " will not be restored!");
    continue;
}
            double x = (double) centerMap.get("x");
            double y = (double) centerMap.get("y");
            double z = (double) centerMap.get("z");
            float yaw = ((Number) centerMap.get("yaw")).floatValue();
            float pitch = ((Number) centerMap.get("pitch")).floatValue();

            Location center = new Location(world, x, y, z, yaw, pitch);

            // Load the size and boundaries
            int size = config.getInt("islands." + playerId + ".size", defaultIslandSize);
            int minX = config.getInt("islands." + playerId + ".minX", center.getBlockX() - size / 2);
            int maxX = config.getInt("islands." + playerId + ".maxX", center.getBlockX() + size / 2);
            int minY = config.getInt("islands." + playerId + ".minY", 0);
            int maxY = config.getInt("islands." + playerId + ".maxY", 255);
            int minZ = config.getInt("islands." + playerId + ".minZ", center.getBlockZ() - size / 2);
            int maxZ = config.getInt("islands." + playerId + ".maxZ", center.getBlockZ() + size / 2);

            // Store data
            islandCenters.put(playerId, center);
            islandSizes.put(playerId, size);
            islandMinX.put(playerId, minX);
            islandMaxX.put(playerId, maxX);
            islandMinY.put(playerId, minY);
            islandMaxY.put(playerId, maxY);
            islandMinZ.put(playerId, minZ);
            islandMaxZ.put(playerId, maxZ);

            // Load the trusted players list
            List<String> trustedList = config.getStringList("islands." + playerId + ".trusted");
            Set<UUID> trustedSet = trustedList.stream().map(UUID::fromString).collect(Collectors.toSet());
            trustedPlayers.put(playerId, trustedSet);

            // Load the owner information
            String ownerIdString = config.getString("islands." + playerId + ".owner");
            if (ownerIdString != null) {
                UUID ownerId = UUID.fromString(ownerIdString);
                islandOwnershipMap.put(center, ownerId);
            } else {
                getLogger().warning("Owner not found for island at " + center);
            }

            // Debugging: Log the loaded data
            getLogger().info("Loaded island data for player " + playerId);
            getLogger().info("Center: " + center);
            getLogger().info("Size: " + size);
            getLogger().info("Bounds: " + minX + " to " + maxX + ", " + minY + " to " + maxY + ", " + minZ + " to " + maxZ);
            getLogger().info("Trusted Players: " + trustedList);
        }
    } else {
        getLogger().warning("No islands data found in the config.");
    }
}

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        Block block = event.getBlock();
        Block toBlock = event.getToBlock();
        World world = block.getWorld();

        // Ensure we are only dealing with the right blocks (e.g., only modify if it's water or lava)
        if (block.getType() == Material.WATER || block.getType() == Material.LAVA) {
            // Get the player who owns the island at this location
            UUID islandOwnerId = getIslandOwner(block.getLocation());

            // If there's no owner, or the block isn't part of an island, skip processing
            if (islandOwnerId == null) {
                return;
            }

            // Ensure the player is the owner of the island, or has permission to modify
            Player player = Bukkit.getPlayer(islandOwnerId);
            if (player == null || !player.hasPermission("island.modify")) {
                // If the player doesn't have permission, or the island owner isn't online, cancel the event
                event.setCancelled(true);
                return;
            }

            // Prevent water/lava from flowing outside of the island bounds if needed
            if (!isBlockWithinIslandBounds(toBlock.getLocation(), islandOwnerId)) {
                event.setCancelled(true); // Prevent the flow outside the island
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location blockLocation = block.getLocation();

        // Get the island owner for this block location
        UUID islandOwnerId = getIslandOwner(blockLocation);

        // If there's no owner or the block isn't part of an island, skip processing
        if (islandOwnerId == null) {
            return;
        }

// Ensure the player is the owner of the island, a trusted player, or has permission to break blocks
        if (!islandOwnerId.equals(player.getUniqueId()) &&
                !trustedPlayers.getOrDefault(islandOwnerId, Collections.emptySet()).contains(player.getUniqueId()) &&
                !player.hasPermission("island.modify")) {
            // If the player doesn't own the island, isn't trusted, or doesn't have permission, cancel the event
            player.sendMessage(ChatColor.RED + "You cannot break blocks on another player's island.");
            event.setCancelled(true);
            return;
        }

        // Prevent breaking blocks outside the island bounds (if needed)
        if (!isBlockWithinIslandBounds(blockLocation, islandOwnerId)) {
            event.setCancelled(true); // Cancel if the block is outside the island bounds
            player.sendMessage(ChatColor.RED + "You cannot break blocks outside your island.");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location blockLocation = block.getLocation();

        // Get the island owner for this block location
        UUID islandOwnerId = getIslandOwner(blockLocation);

        // If there's no owner or the block isn't part of an island, skip processing
        if (islandOwnerId == null) {
            return;
        }

// Ensure the player is the owner of the island, a trusted player, or has permission to break blocks
        if (!islandOwnerId.equals(player.getUniqueId()) &&
                !trustedPlayers.getOrDefault(islandOwnerId, Collections.emptySet()).contains(player.getUniqueId()) &&
                !player.hasPermission("island.modify")) {
            // If the player doesn't own the island, isn't trusted, or doesn't have permission, cancel the event
            player.sendMessage(ChatColor.RED + "You cannot break blocks on another player's island.");
            event.setCancelled(true);
            return;
        }
        // Prevent placing blocks outside the island bounds (if needed)
        if (!isBlockWithinIslandBounds(blockLocation, islandOwnerId)) {
            event.setCancelled(true); // Cancel if the block is outside the island bounds
            player.sendMessage(ChatColor.RED + "You cannot place blocks outside your island.");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        Block clickedBlock = event.getClickedBlock();

        // If there's no block being interacted with, return
        if (clickedBlock == null) {
            return;
        }

        Location blockLocation = clickedBlock.getLocation();

        // Get the island owner for this block location
        UUID islandOwnerId = getIslandOwner(blockLocation);

        // If there's no owner or the block isn't part of an island, skip processing
        if (islandOwnerId == null) {
            return;
        }

// Ensure the player is the owner of the island, a trusted player, or has permission to break blocks
        if (!islandOwnerId.equals(player.getUniqueId()) &&
                !trustedPlayers.getOrDefault(islandOwnerId, Collections.emptySet()).contains(player.getUniqueId()) &&
                !player.hasPermission("island.modify")) {
            // If the player doesn't own the island, isn't trusted, or doesn't have permission, cancel the event
            player.sendMessage(ChatColor.RED + "You cannot Interact with blocks on another player's island.");
            event.setCancelled(true);
            return;
        }
        // Prevent interactions outside the island bounds (if needed)
        if (!isBlockWithinIslandBounds(blockLocation, islandOwnerId)) {
            event.setCancelled(true); // Cancel if the block is outside the island bounds
            player.sendMessage(ChatColor.RED + "You cannot interact with blocks outside your island.");
        }
    }

    private boolean isBlockWithinIslandBounds(Location location, UUID islandOwnerId) {
        // Get the island center and size
        Location islandCenter = islandCenters.get(islandOwnerId);
        int islandSize = islandSizes.getOrDefault(islandOwnerId, defaultIslandSize);
        int halfSize = islandSize / 2;

        // Get the min/max X, Y, and Z coordinates
        int minX = islandCenter.getBlockX() - halfSize;
        int maxX = islandCenter.getBlockX() + halfSize;
        int minY = islandCenter.getBlockY();
        int maxY = islandCenter.getWorld().getMaxHeight(); // You can change this based on your world's height
        int minZ = islandCenter.getBlockZ() - halfSize;
        int maxZ = islandCenter.getBlockZ() + halfSize;

        // Check if the location is within the island bounds
        return location.getBlockX() >= minX && location.getBlockX() <= maxX &&
                location.getBlockY() >= minY && location.getBlockY() <= maxY &&
                location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
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
                    location.getZ() <= islandCenter.getZ() + halfSize &&
                    location.getY() >= islandCenter.getY() && // Check Y-axis
                    location.getY() <= islandCenter.getWorld().getMaxHeight()) { // Check the world max height for Y
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
