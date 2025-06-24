package com.dragonsmith.universe;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SellBoostManager {
    private final Map<UUID, BoostData> boosts = new HashMap<>();

    public void giveBoost(UUID uuid, double multiplier, long durationMillis) {
        boosts.put(uuid, new BoostData(multiplier, System.currentTimeMillis() + durationMillis));
    }

    public double getMultiplier(UUID uuid) {
        BoostData data = boosts.get(uuid);
        if (data == null || System.currentTimeMillis() > data.endTime) {
            boosts.remove(uuid);
            return 1.0;
        }
        return data.multiplier;
    }

    public void clearExpired() {
        long now = System.currentTimeMillis();
        boosts.entrySet().removeIf(e -> e.getValue().endTime < now);
    }

    private static class BoostData {
        double multiplier;
        long endTime;
        BoostData(double m, long e) {
            this.multiplier = m;
            this.endTime = e;
        }
    }
}
