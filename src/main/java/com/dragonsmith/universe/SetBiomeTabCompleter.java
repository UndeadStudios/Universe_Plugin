package com.dragonsmith.universe;

import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

public class SetBiomeTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> suggestions = new ArrayList<>();

        // Ensure the sender is a player and that the correct command is being used
        if (sender instanceof Player && command.getName().equalsIgnoreCase("setbiome")) {
            // Only allow completion if the player is entering a biome name (args[0])
            if (args.length == 1) {
                // Add all possible biome names to the suggestions list
                for (Biome biome : Biome.values()) {
                    if (biome.name().toLowerCase().startsWith(args[0].toLowerCase())) {
                        suggestions.add(biome.name().toLowerCase());
                    }
                }
            }
        }
        return suggestions;
    }
}
