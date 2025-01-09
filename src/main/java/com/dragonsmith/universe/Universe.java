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
            world.setSpawnFlags(false, false);
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

            islandCenters.put(playerId, center);
            islandSizes.put(playerId, defaultIslandSize); // Use the default size from config
            islandGeneratorLevels.put(playerId, 1); // Default generator level is 1

            generateIsland(center, defaultIslandSize, playerId);
            world.getChunkAt(center).load();  // Ensure the chunk at the center is loaded
            // Teleport player just above the grass block (Y = 57)
            player.teleport(center.clone().add(0, 57, 0));
            islandBiomes.put(playerId, Biome.PLAINS);
            giveStarterChest(center);

            player.sendMessage(ChatColor.GREEN + "Island created at your location!");
            return true;
        }

        // Handle the "/expandisland" command
        if (command.getName().equalsIgnoreCase("expandisland")) {
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island yet!");
                return true;
            }
            if (economy.getBalance(player) < 1000) {
                player.sendMessage(ChatColor.RED + "You need at least 1000 coins to create an island!");
                return true;
            }
            int currentSize = islandSizes.get(playerId);
            int newSize = currentSize * 2;

            if (newSize > maxIslandSize) { // Limit size based on config
                player.sendMessage(ChatColor.RED + "Maximum island size reached!");
                return true;
            }
            economy.withdrawPlayer(player, 1000);
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
        // Command to trust another player
        if (command.getName().equalsIgnoreCase("trust")) {
            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "Usage: /trust <player>");
                return true;
            }

            Player targetPlayer = Bukkit.getPlayer(args[0]);
            if (targetPlayer == null) {
                player.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }

            UUID targetPlayerId = targetPlayer.getUniqueId();
            if (!islandCenters.containsKey(playerId)) {
                player.sendMessage(ChatColor.RED + "You don't have an island to trust players on!");
                return true;
            }

            // Ensure the island is owned by the current player
            if (!islandCenters.get(playerId).equals(islandCenters.get(targetPlayerId))) {
                player.sendMessage(ChatColor.RED + "This player does not have an island.");
                return true;
            }

            // Add the player to the trusted list
            trustedPlayers.computeIfAbsent(playerId, k -> new HashSet<>()).add(targetPlayerId);
            player.sendMessage(ChatColor.GREEN + "You have trusted " + targetPlayer.getName() + " to build on your island!");
            return true;
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
                                }
                                // Generate mixed Stone and Deepslate from Y = -5 to Y = 5
                                else if (y >= -5 && y <= 5) {
                                    Material material = Math.random() < 0.5 ? Material.DEEPSLATE : Material.STONE;
                                    loc.getBlock().setType(material);
                                }
                                // Generate Stone/Dirt from Y = 0 to Y = 55
                                else if (y >= 0 && y <= 52) {
                                    loc.getBlock().setType(Material.STONE);
                                }
                                else if (y >= 53 && y <= 55) {
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
                // Place trees during island generation
                generateTrees(center, size);

                // Save island center and size to the config after generating
                saveIslandData(playerId, center, size);
            }
        }.runTask(this); // Run this task on the main thread
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





    public void generateTrees(Location center, int size) {
        World world = center.getWorld();
        Random random = new Random();

        // Number of trees to generate (between 5 and 25)
        int maxTrees = 5 + random.nextInt(21); // 5 to 25 trees
        int treeCount = maxTrees;

        // Tree types, including small and large trees
        TreeType[] treeTypes = {
                TreeType.TREE, // Standard oak
                TreeType.BIRCH, // Standard birch
                TreeType.BIG_TREE, // Larger oak
                TreeType.TALL_BIRCH // Taller birch
        };

        for (int i = 5; i < maxTrees; i++) {
            // Random offsets within the island size
            int xOffset = random.nextInt(size) - size / 2;
            int zOffset = random.nextInt(size) - size / 2;

            // Calculate tree location
            Location treeLocation = center.clone().add(xOffset, 57, zOffset);

            // Find the highest solid block at X and Z coordinates
            int groundY = (int) treeLocation.getY();
            treeLocation.setY(groundY);

            // Check if the ground is suitable
            Block groundBlock = world.getBlockAt(treeLocation.clone().add(0, 56, 0));
            if (groundBlock.getType() == Material.GRASS_BLOCK || groundBlock.getType() == Material.DIRT || groundBlock.getType() == Material.SAND) {
                // Ensure the area is clear for tree generation
                if (isAreaClear(treeLocation, 5)) {
                    // Randomly select a tree type (small or large, oak or birch)
                    TreeType randomTree = treeTypes[random.nextInt(treeTypes.length)];

                    // Attempt to generate the tree
                    if (world.generateTree(treeLocation, randomTree)) {
                        treeCount++; // Increment tree counter if successful
                    }
                }
            }
        }

        // Notify how many trees were generated
        System.out.println("Generated " + treeCount + " trees.");
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
        // Save the island data (e.g., center, size) to a configuration or database
        getConfig().set("islands." + playerId.toString() + ".center", center);
        getConfig().set("islands." + playerId.toString() + ".size", size);
        saveConfig();
    }

    private void loadIslandData() {
        FileConfiguration config = getConfig();
        if (config.contains("islands")) {
            for (String playerIdString : config.getConfigurationSection("islands").getKeys(false)) {
                UUID playerId = UUID.fromString(playerIdString);
                Location center = (Location) config.get("islands." + playerId + ".center");
                int size = config.getInt("islands." + playerId + ".size", defaultIslandSize);
                islandCenters.put(playerId, center);
                islandSizes.put(playerId, size);
            }
        }
    }
    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        Block block = event.getBlock();

        // Check if the block is water or lava flowing into a location
        if (block.getType() == Material.WATER || block.getType() == Material.LAVA) {
            // Get the block it’s flowing into
            Block toBlock = event.getToBlock();

            // Check if the destination block is cobblestone (generated by water/lava interaction)
            if (toBlock.getType() == Material.COBBLESTONE) {
                // Call the placeOreInCobblestone method to replace the cobblestone with ore
                placeOreInCobblestone(toBlock.getLocation());
            }
        }
    }
    // Block placement event to check if the player is allowed to build
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if the player is trying to build on someone else's island
        if (islandCenters.containsKey(playerId)) {
            Location blockLocation = event.getBlock().getLocation();
            UUID ownerId = getIslandOwner(blockLocation);

            if (ownerId != null && !ownerId.equals(playerId) && !trustedPlayers.getOrDefault(ownerId, new HashSet<>()).contains(playerId)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot build here. You are not trusted on this island.");
            }
        }
    }

    // Block interaction event (for chest opening, etc.)
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (event.getClickedBlock() != null && islandCenters.containsKey(playerId)) {
            Location blockLocation = event.getClickedBlock().getLocation();
            UUID ownerId = getIslandOwner(blockLocation);

            if (ownerId != null && !ownerId.equals(playerId) && !trustedPlayers.getOrDefault(ownerId, new HashSet<>()).contains(playerId)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot interact with this block. You are not trusted on this island.");
            }
        }
    }

    // Get the owner of an island based on a block location
    private UUID getIslandOwner(Location location) {
        for (Map.Entry<UUID, Location> entry : islandCenters.entrySet()) {
            UUID ownerId = entry.getKey();
            Location islandCenter = entry.getValue();
            int islandSize = islandSizes.get(ownerId);
            int halfSize = islandSize / 2;

            if (Math.abs(location.getBlockX() - islandCenter.getBlockX()) <= halfSize &&
                Math.abs(location.getBlockZ() - islandCenter.getBlockZ()) <= halfSize) {
                return ownerId;
            }
        }
        return null; // Return null if no owner is found
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
