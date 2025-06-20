package com.dragonsmith.universe;

import org.bukkit.Location;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Island {
    private final UUID owner;
    private Location center;
    private int size;
    private int generatorLevel;

    private int minX, maxX, minZ, maxZ;
    private final Set<UUID> trustedPlayers = new HashSet<>();

    public Island(UUID owner, Location center, int size) {
        this.owner = owner;
        this.center = center;
        this.size = size;
        calculateBounds();
        this.generatorLevel = 1;
    }

    private void calculateBounds() {
        int half = size / 2;
        minX = center.getBlockX() - half;
        maxX = center.getBlockX() + half;
        minZ = center.getBlockZ() - half;
        maxZ = center.getBlockZ() + half;
    }

    public boolean isWithin(Location loc) {
        if (!loc.getWorld().equals(center.getWorld())) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean isTrusted(UUID playerId) {
        return owner.equals(playerId) || trustedPlayers.contains(playerId);
    }

    public void trust(UUID playerId) {
        if (!owner.equals(playerId)) trustedPlayers.add(playerId);
    }

    public void untrust(UUID playerId) {
        trustedPlayers.remove(playerId);
    }

    public UUID getOwner() {
        return owner;
    }

    public Location getCenter() {
        return center;
    }

    public int getSize() {
        return size;
    }

    public int getGeneratorLevel() {
        return generatorLevel;
    }

    public void setGeneratorLevel(int generatorLevel) {
        this.generatorLevel = generatorLevel;
    }

    public Set<UUID> getTrustedPlayers() {
        return trustedPlayers;
    }
}
