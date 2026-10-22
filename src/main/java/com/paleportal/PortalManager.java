
package com.paleportal;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Управляет рамкой портала древнего города:
 *  - вычисляет внутреннюю зону (2x3) из конфига;
 *  - следит, что рамка (reinforced_deepslate) цела, иначе убирает блоки портала;
 *  - спавнит частицы внутри портала;
 *  - телепортирует игрока в pale_forest, когда зона активна и телепортация разблокирована.
 */
public class PortalManager implements Listener {

    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long MOVE_TELEPORT_COOLDOWN_MS = 3000L;

    private final PalePortalPlugin plugin;
    private final ConfigManager config;
    private final DataManager data;

    /** Координаты блоков (2x3) внутри рамки, где стоят блоки портала. */
    private final List<int[]> interiorBlocks = new ArrayList<>();
    /** Координаты блоков самой рамки (reinforced_deepslate) по периметру. */
    private final Set<Long> borderPositions = new HashSet<>();
    private final List<int[]> borderBlocks = new ArrayList<>();

    private World portalWorld;
    private BukkitTask particleTask;

    private final Map<java.util.UUID, Long> lastTeleport = new HashMap<>();

    public PortalManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.data = plugin.getDataManager();
    }

    /** Пересчитывает геометрию рамки из текущего конфига и синхронизирует состояние с реальными блоками. */
    public void reloadGeometry() {
        interiorBlocks.clear();
        borderBlocks.clear();
        borderPositions.clear();

        portalWorld = plugin.getServer().getWorld(config.getPortalWorldName());
        if (portalWorld == null) {
            plugin.getLogger().warning("[PalePortal] Мир рамки портала '" + config.getPortalWorldName()
                    + "' не найден. Геометрия портала не вычислена.");
            return;
        }

        int minX = Math.min(config.getFx1(), config.getFx2());
        int maxX = Math.max(config.getFx1(), config.getFx2());
        int minY = Math.min(config.getFy1(), config.getFy2());
        int maxY = Math.max(config.getFy1(), config.getFy2());
        int minZ = Math.min(config.getFz1(), config.getFz2());
        int maxZ = Math.max(config.getFz1(), config.getFz2());

        int rangeX = maxX - minX;
        int rangeY = maxY - minY;
        int rangeZ = maxZ - minZ;

        // Ось с наименьшим диапазоном считаем "толщиной" рамки (плоскость портала).
        int smallest = Math.min(rangeX, Math.min(rangeY, rangeZ));

        int inMinX = minX, inMaxX = maxX;
        int inMinY = minY, inMaxY = maxY;
        int inMinZ = minZ, inMaxZ = maxZ;

        if (rangeX > smallest) { inMinX = minX + 1; inMaxX = maxX - 1; }
        if (rangeY > smallest) { inMinY = minY + 1; inMaxY = maxY - 1; }
        if (rangeZ > smallest) { inMinZ = minZ + 1; inMaxZ = maxZ - 1; }

        if (inMinX > inMaxX || inMinY > inMaxY || inMinZ > inMaxZ) {
            plugin.getLogger().warning("[PalePortal] Рамка портала слишком мала, внутренняя зона не определена.");
            return;
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean interior = x >= inMinX && x <= inMaxX
                            && y >= inMinY && y <= inMaxY
                            && z >= inMinZ && z <= inMaxZ;
                    if (interior) {
                        interiorBlocks.add(new int[]{x, y, z});
                    } else {
                        borderBlocks.add(new int[]{x, y, z});
                        borderPositions.add(packPos(x, y, z));
                    }
                }
            }
        }

        // Синхронизируем видимое состояние блоков с реальностью при (пере)загрузке.
        boolean intact = isFrameIntact();
        if (intact) {
            placePortalBlocks();
            data.setPortalActive(true);
        } else {
            clearPortalBlocks();
            data.setPortalActive(false);
        }
    }

    private long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /** Проверяет, что все блоки периметра рамки - это портал-материал (reinforced_deepslate). */
    public boolean isFrameIntact() {
        if (portalWorld == null || borderBlocks.isEmpty()) {
            return false;
        }
        for (int[] pos : borderBlocks) {
            Block block = portalWorld.getBlockAt(pos[0], pos[1], pos[2]);
            if (block.getType() != config.getPortalFrameMaterial()) {
                return false;
            }
        }
        return true;
    }

    public void placePortalBlocks() {
        if (portalWorld == null) return;
        for (int[] pos : interiorBlocks) {
            Block block = portalWorld.getBlockAt(pos[0], pos[1], pos[2]);
            if (block.getType() != config.getPortalGlassMaterial()) {
                block.setType(config.getPortalGlassMaterial(), false);
            }
        }
    }

    public void clearPortalBlocks() {
        if (portalWorld == null) return;
        for (int[] pos : interiorBlocks) {
            Block block = portalWorld.getBlockAt(pos[0], pos[1], pos[2]);
            if (block.getType() == config.getPortalGlassMaterial()) {
                block.setType(org.bukkit.Material.AIR, false);
            }
        }
    }

    public void startParticleTask() {
        stopParticleTask();
        if (!config.isParticlesEnabled()) return;
        particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::spawnParticles,
                config.getParticlePeriodTicks(), config.getParticlePeriodTicks());
    }

    public void stopParticleTask() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    private void spawnParticles() {
        if (portalWorld == null || !data.isPortalActive() || interiorBlocks.isEmpty()) return;
        DustOptions dust = new DustOptions(Color.fromRGB(0, 150, 255), 1.0f);
        for (int[] pos : interiorBlocks) {
            Location loc = new Location(portalWorld, pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5);
            portalWorld.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 2, 0.2, 0.3, 0.2, 0.0);
            portalWorld.spawnParticle(Particle.DUST, loc, 2, 0.2, 0.3, 0.2, 0.0, dust);
        }
    }

    // ------------------------------------------------------------------
    //  Слом / восстановление рамки
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!isBorderPosition(b)) return;
        if (b.getType() != config.getPortalFrameMaterial()) return;

        // Блок рамки сломан этим игроком -> деактивируем портал.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean wasActive = data.isPortalActive();
            if (wasActive) {
                clearPortalBlocks();
                data.setPortalActive(false);
                plugin.getLogger().info("[PalePortal] Рамка портала повреждена игроком "
                        + event.getPlayer().getName() + ", портал деактивирован.");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block b = event.getBlock();
        if (!isBorderPosition(b)) return;
        if (event.getBlock().getType() != config.getPortalFrameMaterial()) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!data.isPortalActive() && isFrameIntact()) {
                placePortalBlocks();
                data.setPortalActive(true);
                plugin.getLogger().info("[PalePortal] Рамка портала восстановлена игроком "
                        + event.getPlayer().getName() + ", портал снова активен.");
            }
        });
    }

    private boolean isBorderPosition(Block block) {
        if (portalWorld == null || !block.getWorld().equals(portalWorld)) return false;
        return borderPositions.contains(packPos(block.getX(), block.getY(), block.getZ()));
    }

    // ------------------------------------------------------------------
    //  Телепортация
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (portalWorld == null || interiorBlocks.isEmpty()) return;
        if (!data.isPortalActive() || !data.isTeleportUnlocked()) return;

        Location to = event.getTo();
        if (to == null || !to.getWorld().equals(portalWorld)) return;

        if (!isInsideInterior(to)) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastTeleport.get(player.getUniqueId());
        if (last != null && now - last < MOVE_TELEPORT_COOLDOWN_MS) return;
        lastTeleport.put(player.getUniqueId(), now);

        teleportToPaleForest(player);
    }

    private boolean isInsideInterior(Location loc) {
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (int[] pos : interiorBlocks) {
            if (pos[0] == x && pos[1] == y && pos[2] == z) return true;
        }
        return false;
    }

    public void teleportToPaleForest(Player player) {
        Location entry = config.getEntryLocation();
        if (entry.getWorld() == null) {
            player.sendMessage("§cМир «Бледный лес» ещё не готов. Попробуйте позже.");
            return;
        }
        player.teleport(entry);
        player.sendMessage("§bВы перенесены в Бледный лес...");
    }

    // ------------------------------------------------------------------
    //  Активация музыкой (вызывается из NoteListener)
    // ------------------------------------------------------------------

    public void onMelodySuccess(Player player) {
        data.setTeleportUnlocked(true);

        Location center = config.getFrameCenter();
        if (center.getWorld() != null) {
            center.getWorld().playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 1.0f, 0.8f);
            center.getWorld().playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);
            center.getWorld().spawnParticle(Particle.PORTAL, center, 50, 0.5, 1.0, 0.5, 0.05);
            center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, center, 50, 0.5, 1.0, 0.5, 0.05);
        }

        player.sendMessage("§d§lПортал в Бледный лес открыт!");

        if (config.isLogActivations()) {
            String time = LocalDateTime.now().format(LOG_TIME_FORMAT);
            plugin.getLogger().info("[PalePortal] Портал активирован игроком " + player.getName()
                    + " (" + player.getUniqueId() + ") в " + time);
        }
    }

    public void onMelodyFail(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
    }

    public ConfigManager getConfigManager() {
        return config;
    }
}
