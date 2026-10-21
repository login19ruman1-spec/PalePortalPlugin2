package com.paleportal;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldInfo;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;

import java.util.List;
import java.util.Random;

/**
 * Генератор мира "Бледный лес" (pale_forest).
 * Простой, но детерминированный ландшафт: только бледные дубы, бледный мох,
 * (серая) трава окрашенная биомом Pale Garden, и редкие водоёмы.
 * Никаких пещер, структур и ванильного спавна мобов - всё это
 * контролируется плагином вручную (см. CreakingManager).
 */
public class PaleWorldGenerator extends ChunkGenerator {

    private static final int BASE_HEIGHT = 68;
    private static final int AMPLITUDE = 6;

    private final double treeChance;
    private final double pondThreshold; // чем выше - тем реже пруды

    public PaleWorldGenerator(double treeChance, double pondChance) {
        this.treeChance = treeChance;
        // pondChance ~ доля площади под прудами; переводим в порог для низкочастотного шума [-1;1]
        this.pondThreshold = 1.0 - Math.max(0.0001, Math.min(0.5, pondChance)) * 2.0;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        long seed = worldInfo.getSeed();
        int minY = chunkData.getMinHeight();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (chunkX << 4) + lx;
                int wz = (chunkZ << 4) + lz;

                int height = BASE_HEIGHT + (int) Math.round(noise2D(wx, wz, seed) * AMPLITUDE);
                boolean pond = noise2D(wx, wz, seed ^ 0x9E3779B97F4A7C15L) * 0.6 + 0.4 > pondThreshold
                        && noise2D(wx, wz, seed ^ 0x9E3779B97F4A7C15L, 0.3) > pondThreshold;

                int stoneTop = Math.max(minY + 1, height - 5);
                for (int y = minY; y < stoneTop; y++) {
                    chunkData.setBlock(lx, y, lz, Material.STONE);
                }
                for (int y = stoneTop; y < height - 1; y++) {
                    chunkData.setBlock(lx, y, lz, Material.DIRT);
                }

                if (pond) {
                    chunkData.setBlock(lx, height - 1, lz, Material.DIRT);
                    chunkData.setBlock(lx, height, lz, Material.WATER);
                } else {
                    boolean mossy = (hash(wx, wz, seed) & 3L) == 0L;
                    Material top = mossy ? Material.PALE_MOSS_BLOCK : Material.GRASS_BLOCK;
                    chunkData.setBlock(lx, height - 1, lz, top);
                    if (!mossy) {
                        chunkData.setBlock(lx, height - 2, lz, Material.DIRT);
                    }
                }
            }
        }
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new PaleTreePopulator(treeChance));
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
                return Biome.PALE_GARDEN;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo worldInfo) {
                return List.of(Biome.PALE_GARDEN);
            }
        };
    }

    // ------------------------------------------------------------------
    //  Детерминированный шум (без внешних библиотек): несколько октав sin/cos.
    // ------------------------------------------------------------------

    private static double noise2D(double x, double z, long seed) {
        return noise2D(x, z, seed, 1.0);
    }

    private static double noise2D(double x, double z, long seed, double scale) {
        double sx = (seed % 1000) * 0.001;
        double sz = ((seed / 1000) % 1000) * 0.001;
        double n = Math.sin((x * 0.045 * scale) + sx) * Math.cos((z * 0.045 * scale) + sz)
                + 0.5 * Math.sin((x * 0.09 * scale) + sz + 1.7) * Math.cos((z * 0.09 * scale) + sx + 0.4)
                + 0.25 * Math.sin((x * 0.18 * scale) - sx) * Math.cos((z * 0.18 * scale) - sz);
        return Math.max(-1.0, Math.min(1.0, n / 1.75));
    }

    private static long hash(long x, long z, long seed) {
        long h = seed;
        h = h * 31 + x;
        h = h * 31 + z;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h & Long.MAX_VALUE;
    }

    /**
     * Расставляет бледные дубы (pale_oak_log/pale_oak_leaves) на подходящей
     * поверхности (бледный мох или трава).
     */
    private static class PaleTreePopulator extends BlockPopulator {

        private final double chance;

        PaleTreePopulator(double chance) {
            this.chance = chance;
        }

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
            long seed = worldInfo.getSeed();
            for (int lx = 2; lx < 14; lx++) {
                for (int lz = 2; lz < 14; lz++) {
                    int wx = (chunkX << 4) + lx;
                    int wz = (chunkZ << 4) + lz;

                    double roll = (hash(wx, wz, seed ^ 0x51ED270B6F9AA111L) & 0xFFFFFF) / (double) 0xFFFFFF;
                    if (roll > chance) continue;

                    int topY = limitedRegion.getHighestBlockYAt(wx, wz);
                    Material surface = limitedRegion.getType(wx, topY, wz);
                    if (surface != Material.PALE_MOSS_BLOCK && surface != Material.GRASS_BLOCK) continue;

                    int trunkHeight = 4 + (int) (hash(wx, wz, seed) % 2);
                    plantTree(limitedRegion, wx, topY + 1, wz, trunkHeight);
                }
            }
        }

        private void plantTree(LimitedRegion region, int x, int baseY, int z, int trunkHeight) {
            try {
                for (int i = 0; i < trunkHeight; i++) {
                    safeSet(region, x, baseY + i, z, Material.PALE_OAK_LOG);
                }
                int canopyY = baseY + trunkHeight;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // срезаем углы кроны
                            if (dx == 0 && dz == 0 && dy <= 0) continue; // не перекрываем ствол
                            safeSet(region, x + dx, canopyY + dy, z + dz, Material.PALE_OAK_LEAVES);
                        }
                    }
                }
                safeSet(region, x, canopyY + 2, z, Material.PALE_OAK_LEAVES);
            } catch (Exception ignored) {
                // Часть кроны может выйти за пределы LimitedRegion у границы чанка - это нормально.
            }
        }

        private void safeSet(LimitedRegion region, int x, int y, int z, Material material) {
            try {
                if (region.isInRegion(x, y, z)) {
                    BlockData current = region.getBlockData(x, y, z);
                    if (current.getMaterial() == Material.AIR || material == Material.PALE_OAK_LOG) {
                        region.setType(x, y, z, material);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
