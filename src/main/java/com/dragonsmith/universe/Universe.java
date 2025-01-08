package com.dragonsmith.universe;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.Chest;

import java.util.*;

public class Universe extends JavaPlugin {

    private final HashMap<UUID, Location> islandCenters = new HashMap<>();
    private final HashMap<UUID, Integer> islandSizes = new HashMap<>();
    private final HashMap<UUID, Integer> islandGeneratorLevels = new HashMap<>(); // Track the level of ore generator
    // Initialize the islandBiomes map if not already initialized
    private Map<UUID, Biome> islandBiomes = new HashMap<>();
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

        islandSpacing = config.getInt("island.spacing", 600);
        defaultIslandSize = config.getInt("island.default_size", 32);
        maxIslandSize = config.getInt("island.max_size", 512);
        spawnHeight = 56; // Spawn just above the grass block, which is at Y = 56

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


        return false;
    }
    private void setBiome(Location center, int size, Biome biome) {
        int half = size / 2;  // Calculate the half of the island size
        World world = center.getWorld();

        // Ensure the world isn't null before proceeding
        if (world == null) {
            return;
        }

        // Loop through the area around the center and set the biome
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                // Adjust the X and Z coordinates for the biome setting
                int blockX = center.getBlockX() + x;
                int blockZ = center.getBlockZ() + z;

                // Set the biome for the corresponding chunk
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
                                // Set air for any other blocks (this would be redundant as we're checking for air above)
                                else {
                                    loc.getBlock().setType(Material.AIR);
                                }
                            }
                        }
                    }
                }


                // Place trees during island generation
                generateTrees(center, size);


                // Save island center and size to the config after generating
                saveIslandData(playerId, center, size);

                // Set the world border
                World world = Bukkit.getWorld("universe_world");
                if (world != null) {
                    // Set the world border's center and size to match the island's size
                    WorldBorder border = world.getWorldBorder();
                    border.setCenter(center);
                    border.setSize(size); // Set the island's size as the border size
                }
            }
        }.runTask(this); // Run this task on the main thread
    }


    private void generateOres(Location center, int size, UUID playerId) {
        int generatorLevel = islandGeneratorLevels.get(playerId); // Get the current generator level
        Material oreType = Material.COBBLESTONE; // Default ore is cobblestone

        // Set ore type based on generator level
        switch (generatorLevel) {
            case 1:
                oreType = Material.COAL_ORE;
                break;
            case 2:
                oreType = Material.IRON_ORE;
                break;
            case 3:
                oreType = Material.GOLD_ORE;
                break;
            case 4:
                oreType = Material.DIAMOND_ORE;
                break;
            case 5:
                oreType = Material.EMERALD_ORE;
                break;
        }

        int half = size / 2;

        // Randomly place ores within the island bounds
        for (int i = 0; i < size * 2; i++) {
            int x = center.getBlockX() + (int) (Math.random() * size - half);
            int z = center.getBlockZ() + (int) (Math.random() * size - half);
            Location loc = new Location(center.getWorld(), x, 55, z); // Place ores around Y = 55

            if (loc.getBlock().getType() == Material.STONE) {
                loc.getBlock().setType(oreType);
            }
        }
    }

    public void generateTrees(Location center, int size) {
        World world = center.getWorld();
        // Loop through the area around the center point
        for (int x = -size / 2; x < size / 2; x++) {
            for (int z = -size / 2; z < size / 2; z++) {
                // Calculate the location for the potential tree position
                Location treeLocation = center.clone().add(x, 0, z);

                // Find the ground level (solid block below height 57)
                int groundY = world.getHighestBlockYAt(treeLocation.getBlockX(), treeLocation.getBlockZ());

                // Ensure the location is at Y=57 and there is solid ground below it
                if (groundY == 57) {
                    Block block = world.getBlockAt(treeLocation);
                    if (block.getType() == Material.AIR) {
                        // Check if the block below is solid (this ensures a tree doesn't spawn mid-air)
                        Block blockBelow = world.getBlockAt(treeLocation.clone().add(0, -1, 0));
                        if (blockBelow.getType() == Material.GRASS_BLOCK || blockBelow.getType() == Material.DIRT || blockBelow.getType() == Material.SAND) {
                            // Random chance to place a tree
                            if (Math.random() < 0.1) {
                                // Generate the tree
                                world.generateTree(treeLocation, TreeType.TREE); // You can change TreeType if needed
                            }
                        }
                    }
                }
            }
        }
    }

    private void giveStarterChest(Location center) {
        // Create a chest and populate it with basic starter items
        Location chestLocation = center.clone().add(0, 57, 0);
        chestLocation.getBlock().setType(Material.CHEST);

        Chest chest = (Chest) chestLocation.getBlock().getState();
        Inventory chestInventory = chest.getInventory();

        chestInventory.addItem(new ItemStack(Material.OAK_SAPLING, 3));
        chestInventory.addItem(new ItemStack(Material.CRAFTING_TABLE));
        chestInventory.addItem(new ItemStack(Material.ICE, 2));
        chestInventory.addItem(new ItemStack(Material.LAVA_BUCKET, 1));
        chestInventory.addItem(new ItemStack(Material.LEATHER_HELMET));
        chestInventory.addItem(new ItemStack(Material.LEATHER_CHESTPLATE));
        chestInventory.addItem(new ItemStack(Material.LEATHER_LEGGINGS));
        chestInventory.addItem(new ItemStack(Material.LEATHER_BOOTS));
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
    public void onCobblestoneGen(BlockFormEvent event) {
        Block block = event.getBlock();
        // Check if the cobblestone is being formed
        if (block.getType() == Material.COBBLESTONE) {
            // Random chance to place ores within the cobblestone
            if (Math.random() < 0.2) { // 20% chance, adjust as necessary
                Location cobbleLocation = block.getLocation();
                placeOreInCobblestone(cobbleLocation);
            }
        }
    }

    private void placeOreInCobblestone(Location location) {
        Random rand = new Random();
        // Choose a random ore to generate
        Material ore = getRandomOre();

        // Get a random offset from the cobblestone's location (nearby area)
        int offsetX = rand.nextInt(3) - 1;  // Random -1, 0, or 1
        int offsetY = rand.nextInt(3) - 1;  // Random -1, 0, or 1
        int offsetZ = rand.nextInt(3) - 1;  // Random -1, 0, or 1

        Location oreLocation = location.clone().add(offsetX, offsetY, offsetZ);

        // Only place ore if it's not a solid block (ensures it doesn't overwrite other blocks)
        if (oreLocation.getBlock().getType() == Material.AIR) {
            oreLocation.getBlock().setType(ore);
        }
    }

    private Material getRandomOre() {
        // Return a random ore (can be expanded with more ores)
        Material[] ores = {
                Material.COAL_ORE, Material.IRON_ORE, Material.GOLD_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE
        };
        return ores[new Random().nextInt(ores.length)];
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("createisland") || command.getName().equalsIgnoreCase("expandisland")) {
            return Collections.emptyList();
        }
        return null;
    }
}
