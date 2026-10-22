package com.paleportal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Загружает и хранит всю конфигурацию плагина (config.yml).
 * Пересоздаётся/перезаполняется при /paleportal reload.
 */
public class ConfigManager {

    private final PalePortalPlugin plugin;
    private final Logger log;

    // --- Рамка портала ---
    private String portalWorldName;
    private int fx1, fy1, fz1, fx2, fy2, fz2;
    private Material portalGlassMaterial;
    private Material portalFrameMaterial;
    private boolean particlesEnabled;
    private int particlePeriodTicks;

    // --- Музыкальные блоки и мелодия ---
    private double musicRadius;
    private final List<Location> musicBlocks = new ArrayList<>();
    private final List<Integer> melody = new ArrayList<>();
    private int melodyTimeLimitSeconds;

    // --- Pale Forest ---
    private String paleForestWorldName;
    private double entryX, entryY, entryZ;
    private float entryYaw, entryPitch;
    private boolean nightVisionEnabled;
    private boolean darknessEnabled;
    private int darknessAmplifier;
    private double pondChance;
    private double treeChance;

    // --- Creakings ---
    private boolean creakingAutoSpawn;
    private int creakingSpawnPeriodMinutes;
    private int creakingSpawnRadius;
    private int creakingMinDistanceToPlayer;
    private int creakingMaxAlive;

    private double normalHealth, normalDamage;
    private double smartHealth, smartDamage;
    private double kindHealth, kindDamage;
    private double ancientHealth, ancientDamage, ancientSpeed, ancientAggroRadius;

    private boolean logActivations;

    public ConfigManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /** Полная перезагрузка config.yml с диска. */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        portalWorldName = cfg.getString("portal-world", "world");

        fx1 = cfg.getInt("portal-frame.corner1.x");
        fy1 = cfg.getInt("portal-frame.corner1.y");
        fz1 = cfg.getInt("portal-frame.corner1.z");
        fx2 = cfg.getInt("portal-frame.corner2.x");
        fy2 = cfg.getInt("portal-frame.corner2.y");
        fz2 = cfg.getInt("portal-frame.corner2.z");

        portalGlassMaterial = parseMaterial(cfg.getString("portal-glass-material", "LIGHT_BLUE_STAINED_GLASS"),
                Material.LIGHT_BLUE_STAINED_GLASS);
        portalFrameMaterial = parseMaterial(cfg.getString("portal-frame-material", "REINFORCED_DEEPSLATE"),
                Material.REINFORCED_DEEPSLATE);

        particlesEnabled = cfg.getBoolean("portal-particles.enabled", true);
        particlePeriodTicks = Math.max(1, cfg.getInt("portal-particles.period-ticks", 5));

        musicRadius = cfg.getDouble("music-radius", 10.0);
        melodyTimeLimitSeconds = cfg.getInt("melody-time-limit-seconds", 30);

        musicBlocks.clear();
        List<?> rawBlocks = cfg.getList("music-blocks");
        if (rawBlocks != null) {
            for (Object o : rawBlocks) {
                if (o instanceof java.util.Map<?, ?> map) {
                    Object rawWorld = map.get("world");
                    String worldName = rawWorld != null ? String.valueOf(rawWorld) : portalWorldName;
                    World w = plugin.getServer().getWorld(worldName);
                    double x = toDouble(map.get("x"));
                    double y = toDouble(map.get("y"));
                    double z = toDouble(map.get("z"));
                    // Мир может быть ещё не загружен на момент чтения конфига -
                    // сохраняем локацию "отвязанной", мир будет подставлен позже при использовании.
                    musicBlocks.add(new Location(w, x, y, z));
                }
            }
        }

        melody.clear();
        List<Integer> rawMelody = cfg.getIntegerList("melody");
        if (rawMelody.isEmpty()) {
            melody.addAll(List.of(6, 10, 13, 10, 6));
        } else {
            melody.addAll(rawMelody);
        }

        paleForestWorldName = cfg.getString("pale-forest.world-name", "pale_forest");
        entryX = cfg.getDouble("pale-forest.entry.x", 0.5);
        entryY = cfg.getDouble("pale-forest.entry.y", 65);
        entryZ = cfg.getDouble("pale-forest.entry.z", 0.5);
        entryYaw = (float) cfg.getDouble("pale-forest.entry.yaw", 0);
        entryPitch = (float) cfg.getDouble("pale-forest.entry.pitch", 0);

        nightVisionEnabled = cfg.getBoolean("pale-forest.effects.night-vision", true);
        darknessEnabled = cfg.getBoolean("pale-forest.effects.darkness-enabled", true);
        darknessAmplifier = cfg.getInt("pale-forest.effects.darkness-amplifier", 0);

        pondChance = cfg.getDouble("pale-forest.pond-chance", 0.03);
        treeChance = cfg.getDouble("pale-forest.tree-chance", 0.015);

        creakingAutoSpawn = cfg.getBoolean("creakings.auto-spawn-enabled", true);
        creakingSpawnPeriodMinutes = cfg.getInt("creakings.auto-spawn-period-minutes", 5);
        creakingSpawnRadius = cfg.getInt("creakings.spawn-radius", 50);
        creakingMinDistanceToPlayer = cfg.getInt("creakings.min-distance-to-player", 10);
        creakingMaxAlive = cfg.getInt("creakings.max-alive", 12);

        normalHealth = cfg.getDouble("creakings.normal.health", 20.0);
        normalDamage = cfg.getDouble("creakings.normal.damage", 4.0);
        smartHealth = cfg.getDouble("creakings.smart.health", 30.0);
        smartDamage = cfg.getDouble("creakings.smart.damage", 8.0);
        kindHealth = cfg.getDouble("creakings.kind.health", 15.0);
        kindDamage = cfg.getDouble("creakings.kind.damage", 0.0);
        ancientHealth = cfg.getDouble("creakings.ancient.health", 50.0);
        ancientDamage = cfg.getDouble("creakings.ancient.damage", 6.0);
        ancientSpeed = cfg.getDouble("creakings.ancient.speed", 0.15);
        ancientAggroRadius = cfg.getDouble("creakings.ancient.aggro-radius", 10.0);

        logActivations = cfg.getBoolean("logging.log-activations", true);

        validateMusicBlocks();
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null) return fallback;
        try {
            Material m = Material.matchMaterial(name.trim());
            return m != null ? m : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Проверяет, что каждый музыкальный блок находится в радиусе {@link #musicRadius}
     * от центра рамки (евклидово расстояние). Невалидные блоки не исключаются из
     * списка (чтобы не ломать индексацию мелодии), но предупреждение пишется в консоль.
     */
    public void validateMusicBlocks() {
        Location center = getFrameCenter();
        if (center == null) {
            log.warning("[PalePortal] Не удалось вычислить центр рамки портала - проверка радиуса музыкальных блоков пропущена.");
            return;
        }
        for (int i = 0; i < musicBlocks.size(); i++) {
            Location loc = musicBlocks.get(i);
            if (loc.getWorld() == null || center.getWorld() == null
                    || !loc.getWorld().equals(center.getWorld())) {
                // Сравниваем только по координатам, если миры ещё не загружены при старте.
            }
            double dx = loc.getX() - center.getX();
            double dy = loc.getY() - center.getY();
            double dz = loc.getZ() - center.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > musicRadius) {
                log.warning(String.format(
                        "[PalePortal] Музыкальный блок #%d (%.1f, %.1f, %.1f) находится в %.1f блоках от центра рамки - "
                                + "это больше допустимого радиуса (%.1f). Активация мелодии может работать некорректно.",
                        i + 1, loc.getX(), loc.getY(), loc.getZ(), distance, musicRadius));
            }
        }
    }

    public Location getFrameCenter() {
        World world = plugin.getServer().getWorld(portalWorldName);
        double cx = (fx1 + fx2) / 2.0;
        double cy = (fy1 + fy2) / 2.0;
        double cz = (fz1 + fz2) / 2.0;
        return new Location(world, cx, cy, cz);
    }

    public void setFrameCorner1(Location loc) {
        fx1 = loc.getBlockX();
        fy1 = loc.getBlockY();
        fz1 = loc.getBlockZ();
        portalWorldName = loc.getWorld().getName();
        persistFrame();
    }

    public void setFrameCorner2(Location loc) {
        fx2 = loc.getBlockX();
        fy2 = loc.getBlockY();
        fz2 = loc.getBlockZ();
        portalWorldName = loc.getWorld().getName();
        persistFrame();
    }

    private void persistFrame() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("portal-world", portalWorldName);
        cfg.set("portal-frame.corner1.x", fx1);
        cfg.set("portal-frame.corner1.y", fy1);
        cfg.set("portal-frame.corner1.z", fz1);
        cfg.set("portal-frame.corner2.x", fx2);
        cfg.set("portal-frame.corner2.y", fy2);
        cfg.set("portal-frame.corner2.z", fz2);
        plugin.saveConfig();
    }

    public void setMusicBlock(int index, Location loc) {
        while (musicBlocks.size() <= index) {
            musicBlocks.add(loc.clone());
        }
        musicBlocks.set(index, loc.clone());
        FileConfiguration cfg = plugin.getConfig();
        String path = "music-blocks." + index;
        cfg.set(path + ".world", loc.getWorld().getName());
        cfg.set(path + ".x", loc.getX());
        cfg.set(path + ".y", loc.getY());
        cfg.set(path + ".z", loc.getZ());
        plugin.saveConfig();
        validateMusicBlocks();
    }

    // ----------------- Геттеры -----------------

    public String getPortalWorldName() {
        return portalWorldName;
    }

    public int getFx1() { return fx1; }
    public int getFy1() { return fy1; }
    public int getFz1() { return fz1; }
    public int getFx2() { return fx2; }
    public int getFy2() { return fy2; }
    public int getFz2() { return fz2; }

    public Material getPortalGlassMaterial() {
        return portalGlassMaterial;
    }

    public Material getPortalFrameMaterial() {
        return portalFrameMaterial;
    }

    public boolean isParticlesEnabled() {
        return particlesEnabled;
    }

    public int getParticlePeriodTicks() {
        return particlePeriodTicks;
    }

    public double getMusicRadius() {
        return musicRadius;
    }

    public List<Location> getMusicBlocks() {
        return musicBlocks;
    }

    public List<Integer> getMelody() {
        return melody;
    }

    public int getMelodyTimeLimitSeconds() {
        return melodyTimeLimitSeconds;
    }

    public String getPaleForestWorldName() {
        return paleForestWorldName;
    }

    public Location getEntryLocation() {
        World w = plugin.getServer().getWorld(paleForestWorldName);
        return new Location(w, entryX, entryY, entryZ, entryYaw, entryPitch);
    }

    public boolean isNightVisionEnabled() {
        return nightVisionEnabled;
    }

    public boolean isDarknessEnabled() {
        return darknessEnabled;
    }

    public int getDarknessAmplifier() {
        return darknessAmplifier;
    }

    public double getPondChance() {
        return pondChance;
    }

    public double getTreeChance() {
        return treeChance;
    }

    public boolean isCreakingAutoSpawn() {
        return creakingAutoSpawn;
    }

    public int getCreakingSpawnPeriodMinutes() {
        return creakingSpawnPeriodMinutes;
    }

    public int getCreakingSpawnRadius() {
        return creakingSpawnRadius;
    }

    public int getCreakingMinDistanceToPlayer() {
        return creakingMinDistanceToPlayer;
    }

    public int getCreakingMaxAlive() {
        return creakingMaxAlive;
    }

    public double getNormalHealth() { return normalHealth; }
    public double getNormalDamage() { return normalDamage; }
    public double getSmartHealth() { return smartHealth; }
    public double getSmartDamage() { return smartDamage; }
    public double getKindHealth() { return kindHealth; }
    public double getKindDamage() { return kindDamage; }
    public double getAncientHealth() { return ancientHealth; }
    public double getAncientDamage() { return ancientDamage; }
    public double getAncientSpeed() { return ancientSpeed; }
    public double getAncientAggroRadius() { return ancientAggroRadius; }

    public boolean isLogActivations() {
        return logActivations;
    }
}
