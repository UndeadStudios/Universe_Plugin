package com.dragonsmith.universe;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MoneyManager {

    private final File file;
    private final YamlConfiguration config;
    private final Map<UUID, Double> balances = new HashMap<>();

    public MoneyManager(File dataFolder) {
        this.file = new File(dataFolder, "balances.yml");
        this.config = YamlConfiguration.loadConfiguration(file);
        loadBalances();
    }

    private void loadBalances() {
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                double balance = config.getDouble(key);
                balances.put(uuid, balance);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void saveBalances() {
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue());
        }

        try {
            config.save(file);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to save balances: " + e.getMessage());
        }
    }

    public double getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, 0.0);
    }

    public void deposit(UUID uuid, double amount) {
        balances.put(uuid, getBalance(uuid) + amount);
    }

    public boolean withdraw(UUID uuid, double amount) {
        double current = getBalance(uuid);
        if (current < amount) return false;
        balances.put(uuid, current - amount);
        return true;
    }

    public void setBalance(UUID uuid, double amount) {
        balances.put(uuid, amount);
    }
}