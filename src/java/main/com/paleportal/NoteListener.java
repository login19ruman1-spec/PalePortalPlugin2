package com.paleportal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Отслеживает проигрывание мелодии на музыкальных блоках перед порталом.
 *
 * Игрок меняет ноту кликом по нодблоку (PlayerInteractEvent), это вызывает
 * NotePlayEvent без информации об игроке - поэтому мы кратко запоминаем,
 * кто последним взаимодействовал с конкретным нодблоком, и используем эту
 * информацию, чтобы связать NotePlayEvent#getNote()#getId() с игроком.
 */
public class NoteListener implements Listener {

    private static final long INTERACT_CORRELATION_WINDOW_MS = 2000L;

    private final PalePortalPlugin plugin;
    private final ConfigManager config;
    private final DataManager data;
    private final PortalManager portalManager;

    /** Позиция нодблока (упакованная) -> (игрок, время клика). */
    private final Map<Long, PendingClick> pendingClicks = new HashMap<>();
    /** Прогресс каждого игрока в разгадывании мелодии. */
    private final Map<UUID, Progress> progress = new HashMap<>();

    public NoteListener(PalePortalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.data = plugin.getDataManager();
        this.portalManager = plugin.getPortalManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.NOTE_BLOCK) return;

        int index = findMusicBlockIndex(event.getClickedBlock().getLocation());
        if (index < 0) return;

        pendingClicks.put(packLocation(event.getClickedBlock().getLocation()),
                new PendingClick(event.getPlayer().getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNotePlay(NotePlayEvent event) {
        if (data.isTeleportUnlocked()) return; // мелодию повторно играть не нужно
        if (!data.isPortalActive()) return;

        int blockIndex = findMusicBlockIndex(event.getBlock().getLocation());
        if (blockIndex < 0) return;

        long key = packLocation(event.getBlock().getLocation());
        PendingClick click = pendingClicks.remove(key);
        if (click == null) return;
        long now = System.currentTimeMillis();
        if (now - click.timestamp > INTERACT_CORRELATION_WINDOW_MS) return;

        Player player = plugin.getServer().getPlayer(click.playerId);
        if (player == null) return;

        int noteId = event.getNote().getId();
        handleNote(player, blockIndex, noteId, now);
    }

    private void handleNote(Player player, int blockIndex, int noteId, long now) {
        List<Integer> melody = config.getMelody();
        if (melody.isEmpty()) return;

        Progress p = progress.get(player.getUniqueId());

        // Истёк тайм-аут - считаем прогресс несуществующим.
        if (p != null && now - p.startedAt > config.getMelodyTimeLimitSeconds() * 1000L) {
            progress.remove(player.getUniqueId());
            p = null;
        }

        int expectedIndex = (p == null) ? 0 : p.nextIndex;
        int expectedNote = melody.get(expectedIndex);

        boolean correct = blockIndex == expectedIndex && noteId == expectedNote;

        if (correct) {
            int nextIndex = expectedIndex + 1;
            if (nextIndex >= melody.size()) {
                // Мелодия сыграна полностью и верно.
                progress.remove(player.getUniqueId());
                portalManager.onMelodySuccess(player);
            } else {
                Progress newProgress = new Progress(nextIndex, (p == null) ? now : p.startedAt);
                progress.put(player.getUniqueId(), newProgress);
            }
        } else {
            progress.remove(player.getUniqueId());
            portalManager.onMelodyFail(player);

            // Разрешаем сразу начать заново, если текущая нота - это первая нота мелодии.
            if (blockIndex == 0 && noteId == melody.get(0)) {
                progress.put(player.getUniqueId(), new Progress(1, now));
            }
        }
    }

    private int findMusicBlockIndex(Location loc) {
        List<Location> blocks = config.getMusicBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            Location music = blocks.get(i);
            if (music.getWorld() == null || loc.getWorld() == null) continue;
            if (!music.getWorld().equals(loc.getWorld())) continue;
            if (music.getBlockX() == loc.getBlockX()
                    && music.getBlockY() == loc.getBlockY()
                    && music.getBlockZ() == loc.getBlockZ()) {
                return i;
            }
        }
        return -1;
    }

    private long packLocation(Location loc) {
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private record PendingClick(UUID playerId, long timestamp) {
    }

    private record Progress(int nextIndex, long startedAt) {
    }
}
