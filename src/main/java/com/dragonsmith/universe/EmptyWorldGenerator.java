package com.dragonsmith.universe;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

public class EmptyWorldGenerator extends ChunkGenerator {

    @Override
    public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
        // Create a new chunk data object
        ChunkData chunkData = createChunkData(world);

        // The chunk is empty, so no terrain generation here.
        // You could leave it as is or add special logic if you want to create custom "empty" features.

        // Return the empty chunk data
        return chunkData;
    }
}
