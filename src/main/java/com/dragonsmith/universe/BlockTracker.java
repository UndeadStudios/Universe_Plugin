package com.dragonsmith.universe;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BlockTracker implements Listener {

    // A map to track broken blocks for each player (using UUID as the key)
    private final HashMap<UUID, Set<Location>> brokenBlocks = new HashMap<>();

    // Handle block break event
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Location blockLocation = event.getBlock().getLocation();

        // Initialize the set for the player if it doesn't exist
        brokenBlocks.putIfAbsent(playerId, new HashSet<>());

        // Add the block location to the set of broken blocks
        brokenBlocks.get(playerId).add(blockLocation);
    }

    // Method to check if a block has been broken by the player
    public boolean isBlockBroken(UUID playerId, Location location) {
        return brokenBlocks.getOrDefault(playerId, new HashSet<>()).contains(location);
    }
}
