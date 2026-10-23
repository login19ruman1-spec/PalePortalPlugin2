package com.paleportal;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PortalManager implements Listener {

    private static final DateTimeFormatter LOG_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long TELEPORT_COOLDOWN_MS = 3000L;

    private final PalePortalPlugin plugin;
    private final ConfigManager config;
    private final DataManager data;

    private final List<int[]> interiorBlocks = new ArrayList<>();
    private final List<int[]> borderBlocks = new ArrayList<>();

    private final Set<Long> interiorPositions = new HashSet<>();
    private final Set<Long> borderPositions = new HashSet<>();

    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private final Set<UUID> pendingTeleports = new HashSet<>();

    private World portalWorld;
    private BukkitTask particleTask;

    public PortalManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.data = plugin.getDataManager();
    }

    /**
     * Пересчитывает рамку.
     * Выбор углов и перезагрузка не открывают портал.
     */
    public void reloadGeometry() {
        stopParticleTask();
        deactivatePortal();

        interiorBlocks.clear();
        borderBlocks.clear();
        interiorPositions.clear();
        borderPositions.clear();
        lastTeleport.clear();
        pendingTeleports.clear();

        portalWorld = plugin.getServer().getWorld(
            config.getPortalWorldName()
        );

        if (portalWorld == null) {
            plugin.getLogger().warning(
                "[PalePortal] Мир рамки не найден: "
                + config.getPortalWorldName()
            );
            return;
        }

        int minX = Math.min(config.getFx1(), config.getFx2());
        int maxX = Math.max(config.getFx1(), config.getFx2());

        int minY = Math.min(config.getFy1(), config.getFy2());
        int maxY = Math.max(config.getFy1(), config.getFy2());

        int minZ = Math.min(config.getFz1(), config.getFz2());
        int maxZ = Math.max(config.getFz1(), config.getFz2());

        boolean fixedX = minX == maxX;
        boolean fixedZ = minZ == maxZ;

        if (fixedX == fixedZ) {
            plugin.getLogger().warning(
                "[PalePortal] Рамка должна находиться "
                + "в одной вертикальной плоскости."
            );
            return;
        }

        long height = (long) maxY - minY;
        long width = fixedX
            ? (long) maxZ - minZ
            : (long) maxX - minX;

        if (width < 2 || height < 2) {
            plugin.getLogger().warning(
                "[PalePortal] Рамка слишком мала: "
                + "внутри должен оставаться свободный проём."
            );
            return;
        }

        if (minY < portalWorld.getMinHeight()
                || maxY >= portalWorld.getMaxHeight()) {
            plugin.getLogger().warning(
                "[PalePortal] Рамка выходит за высоту мира."
            );
            return;
        }

        // Защита от случайного выбора огромной области.
        if (width > 512 || height > 512) {
            plugin.getLogger().warning(
                "[PalePortal] Слишком большая рамка. "
                + "Проверьте выбранные углы."
            );
            return;
        }

        for (int horizontal = 0;
                horizontal <= (int) width;
                horizontal++) {

            for (int vertical = 0;
                    vertical <= (int) height;
                    vertical++) {

                int x = fixedX ? minX : minX + horizontal;
                int y = minY + vertical;
                int z = fixedX ? minZ + horizontal : minZ;

                boolean border =
                    horizontal == 0
                    || horizontal == (int) width
                    || vertical == 0
                    || vertical == (int) height;

                int[] position = new int[]{x, y, z};

                if (border) {
                    borderBlocks.add(position);
                    borderPositions.add(packPos(x, y, z));
                } else {
                    interiorBlocks.add(position);
                    interiorPositions.add(packPos(x, y, z));
                }
            }
        }

        // Удаляем старое стеклянное заполнение,
        // но не открываем портал.
        clearPortalBlocks();
    }

    private long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
            | ((long) (y & 0xFFF) << 26)
            | (z & 0x3FFFFFF);
    }

    public boolean isFrameIntact() {
        if (portalWorld == null || borderBlocks.isEmpty()) {
            return false;
        }

        for (int[] position : borderBlocks) {
            Block block = portalWorld.getBlockAt(
                position[0],
                position[1],
                position[2]
            );

            if (block.getType()
                    != config.getPortalFrameMaterial()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Совместимость с существующими вызовами.
     * Сплошное стекло не ставим: игрок должен
     * свободно заходить внутрь портала.
     */
    public void placePortalBlocks() {
        clearPortalBlocks();
    }

    public void clearPortalBlocks() {
        if (portalWorld == null) return;

        Material portalMaterial = config.getPortalGlassMaterial();

        // Не допускаем удаления рамки при ошибочной настройке.
        if (portalMaterial == null
                || portalMaterial == config.getPortalFrameMaterial()) {
            return;
        }

        for (int[] position : interiorBlocks) {
            Block block = portalWorld.getBlockAt(
                position[0],
                position[1],
                position[2]
            );

            if (block.getType() == portalMaterial) {
                block.setType(Material.AIR, false);
            }
        }
    }

    private boolean isInteriorClear() {
        if (portalWorld == null || interiorBlocks.isEmpty()) {
            return false;
        }

        for (int[] position : interiorBlocks) {
            Block block = portalWorld.getBlockAt(
                position[0],
                position[1],
                position[2]
            );

            if (!block.isPassable() || block.isLiquid()) {
                return false;
            }
        }

        return true;
    }

    private void deactivatePortal() {
        data.setPortalActive(false);
        data.setTeleportUnlocked(false);
        clearPortalBlocks();
    }

    public void startParticleTask() {
        stopParticleTask();

        if (!config.isParticlesEnabled()) return;

        long period = Math.max(
            1L,
            config.getParticlePeriodTicks()
        );

        particleTask = plugin.getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                this::spawnParticles,
                period,
                period
            );
    }

    public void stopParticleTask() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    private void spawnParticles() {
        if (portalWorld == null
                || !data.isPortalActive()
                || !data.isTeleportUnlocked()
                || interiorBlocks.isEmpty()) {
            return;
        }

        if (!isFrameIntact()) {
            deactivatePortal();
            return;
        }

        Particle.DustOptions dust = new Particle.DustOptions(
            Color.fromRGB(0, 150, 255),
            1.0f
        );

        for (int[] position : interiorBlocks) {
            Location location = new Location(
                portalWorld,
                position[0] + 0.5,
                position[1] + 0.5,
                position[2] + 0.5
            );

            portalWorld.spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                location,
                2,
                0.2, 0.3, 0.2,
                0.0
            );

            portalWorld.spawnParticle(
                Particle.DUST,
                location,
                2,
                0.2, 0.3, 0.2,
                0.0,
                dust
            );
        }
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        if (!isBorderPosition(block)) return;

        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> {
                if (!isFrameIntact()) {
                    deactivatePortal();
                }
            }
        );
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        if (!isBorderPosition(block)) return;

        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> {
                if (!isFrameIntact()) {
                    deactivatePortal();
                }

                // Целая или восстановленная рамка
                // сама по себе портал не открывает.
            }
        );
    }

    private boolean isBorderPosition(Block block) {
        return portalWorld != null
            && block.getWorld().equals(portalWorld)
            && borderPositions.contains(
                packPos(
                    block.getX(),
                    block.getY(),
                    block.getZ()
                )
            );
    }

    private boolean isInsideInterior(Location location) {
        return location != null
            && portalWorld != null
            && portalWorld.equals(location.getWorld())
            && interiorPositions.contains(
                packPos(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
                )
            );
    }

    private boolean touchesInterior(Location feet) {
        return isInsideInterior(feet)
            || isInsideInterior(
                feet.clone().add(0, 1, 0)
            );
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!data.isPortalActive()
                || !data.isTeleportUnlocked()) {
            return;
        }

        Location to = event.getTo();

        if (to == null || !touchesInterior(to)) return;

        if (!isFrameIntact()) {
            deactivatePortal();
            return;
        }

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (pendingTeleports.contains(id)) return;

        long now = System.currentTimeMillis();
        Long previous = lastTeleport.get(id);

        if (previous != null
                && now - previous < TELEPORT_COOLDOWN_MS) {
            return;
        }

        pendingTeleports.add(id);

        // Выполняем после завершения события движения.
        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> {
                try {
                    if (!player.isOnline()
                            || !data.isPortalActive()
                            || !data.isTeleportUnlocked()
                            || !touchesInterior(
                                player.getLocation()
                            )) {
                        return;
                    }

                    if (!isFrameIntact()) {
                        deactivatePortal();
                        return;
                    }

                    lastTeleport.put(
                        id,
                        System.currentTimeMillis()
                    );

                    teleportToPaleForest(player);
                } finally {
                    pendingTeleports.remove(id);
                }
            }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();

        lastTeleport.remove(id);
        pendingTeleports.remove(id);
    }

    public void teleportToPaleForest(Player player) {
        Location entry = config.getEntryLocation();

        if (entry == null || entry.getWorld() == null) {
            player.sendMessage(
                "§cМир Бледного леса не загружен "
                + "или точка входа настроена неверно."
            );

            plugin.getLogger().warning(
                "[PalePortal] Не найдены мир "
                + "или точка входа в Бледный лес."
            );
            return;
        }

        entry = entry.clone();

        if (!Double.isFinite(entry.getX())
                || !Double.isFinite(entry.getY())
                || !Double.isFinite(entry.getZ())
                || entry.getY()
                    < entry.getWorld().getMinHeight()
                || entry.getY() + 1.8
                    >= entry.getWorld().getMaxHeight()) {

            player.sendMessage(
                "§cНекорректные координаты входа "
                + "в Бледный лес."
            );
            return;
        }

        if (entry.getWorld().equals(portalWorld)) {
            player.sendMessage(
                "§cТочка входа указывает на мир рамки. "
                + "Проверьте настройки Бледного леса."
            );
            return;
        }

        if (!entry.getBlock().isPassable()
                || !entry.clone().add(0, 1, 0)
                    .getBlock().isPassable()) {

            player.sendMessage(
                "§cТочка входа в Бледный лес перекрыта. "
                + "Освободите место или измените координаты."
            );
            return;
        }

        boolean success = player.teleport(
            entry,
            PlayerTeleportEvent.TeleportCause.PLUGIN
        );

        if (success) {
            player.setFallDistance(0);

            player.sendMessage(
                "§bВы перенесены в Бледный лес..."
            );
        } else {
            player.sendMessage(
                "§cТелепортация не выполнена. "
                + "Возможно, её отменил другой плагин."
            );

            plugin.getLogger().warning(
                "[PalePortal] Телепортация игрока "
                + player.getName()
                + " не выполнена."
            );
        }
    }

    /**
     * Вызывается обработчиком музыкальных блоков
     * после проверки правильной мелодии.
     */
    public void onMelodySuccess(Player player) {
        if (portalWorld == null || interiorBlocks.isEmpty()) {
            player.sendMessage(
                "§cСначала настройте рамку портала."
            );
            return;
        }

        if (!isFrameIntact()) {
            deactivatePortal();

            player.sendMessage(
                "§cРамка повреждена. Восстановите её "
                + "и сыграйте мелодию снова."
            );
            return;
        }

        placePortalBlocks();

        if (!isInteriorClear()) {
            deactivatePortal();

            player.sendMessage(
                "§cОсвободите внутренний проём рамки "
                + "и сыграйте мелодию снова."
            );
            return;
        }

        data.setTeleportUnlocked(true);
        data.setPortalActive(true);

        startParticleTask();

        Location center = config.getFrameCenter();

        if (center != null && center.getWorld() != null) {
            World world = center.getWorld();

            world.playSound(
                center,
                Sound.BLOCK_PORTAL_TRIGGER,
                1.0f,
                0.8f
            );

            world.playSound(
                center,
                Sound.ENTITY_ENDER_DRAGON_GROWL,
                1.0f,
                0.8f
            );

            world.spawnParticle(
                Particle.PORTAL,
                center,
                50,
                0.5, 1.0, 0.5,
                0.05
            );

            world.spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                center,
                50,
                0.5, 1.0, 0.5,
                0.05
            );
        }

        player.sendMessage(
            "§d§lПортал в Бледный лес открыт!"
        );

        if (config.isLogActivations()) {
            String time = LocalDateTime.now()
                .format(LOG_TIME_FORMAT);

            plugin.getLogger().info(
                "[PalePortal] Портал активирован игроком "
                + player.getName()
                + " в "
                + time
            );
        }
    }

    public void onMelodyFail(Player player) {
        player.playSound(
            player.getLocation(),
            Sound.BLOCK_NOTE_BLOCK_BASS,
            1.0f,
            1.0f
        );
    }

    public ConfigManager getConfigManager() {
        return config;
    }
}
