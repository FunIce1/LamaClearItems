package dev.lama.lamaclearitems;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public final class LamaClearItems extends JavaPlugin {

    private BukkitTask clearLoopTask;
    private BukkitTask antiLagTask;
    private int secondsLeft;
    private int intervalSeconds;
    private int prewarnSeconds;
    private int prewarnStep;
    private int finalCountdownSeconds;
    private boolean broadcast;
    private boolean removeInvisibleOnly;
    private final Set<Integer> warnTimes = new HashSet<>();
    private boolean antiLagEnabled;
    private int antiLagCheckEverySeconds;
    private double antiLagTpsTriggerBelow;
    private int antiLagScanMaxChunks;
    private int thrHoppers;
    private int thrHopperMinecarts;
    private int thrEntities;
    private boolean notifyConsole;
    private boolean notifyAdminsChat;
    private String notifyPermission;
    private boolean actionsEnabled;
    private boolean actionRemoveHopperMinecarts;
    private boolean actionRemoveItems;
    private boolean actionRemoveInvisibleArmorStands;
    private boolean actionBreakHopperBlocks;

    private Method getTpsMethod;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        initTpsSupport();
        startClearLoop();
        startAntiLagLoop();
    }

    @Override
    public void onDisable() {
        if (clearLoopTask != null) clearLoopTask.cancel();
        if (antiLagTask != null) antiLagTask.cancel();
    }

    private void initTpsSupport() {
        try {
            getTpsMethod = Bukkit.getServer().getClass().getMethod("getTPS");
        } catch (Throwable ignored) {
            getTpsMethod = null;
        }
    }

    private double[] getServerTpsOrNull() {
        if (getTpsMethod == null) return null;
        try {
            Object res = getTpsMethod.invoke(Bukkit.getServer());
            if (res instanceof double[] arr && arr.length >= 3) return arr;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void loadSettings() {
        reloadConfig();

        intervalSeconds = Math.max(10, getConfig().getInt("interval-seconds", 300));
        prewarnSeconds = Math.max(0, getConfig().getInt("prewarn-seconds", 60));
        prewarnStep = Math.max(1, getConfig().getInt("prewarn-step", 10));
        finalCountdownSeconds = Math.max(0, getConfig().getInt("final-countdown-seconds", 5));
        broadcast = getConfig().getBoolean("broadcast", true);
        removeInvisibleOnly = getConfig().getBoolean("armor-stands.remove-invisible-only", true);

        warnTimes.clear();
        if (prewarnSeconds > 0) {
            for (int t = prewarnSeconds; t >= 10; t -= prewarnStep) warnTimes.add(t);
        }
        for (int t = finalCountdownSeconds; t >= 1; t--) warnTimes.add(t);

        antiLagEnabled = getConfig().getBoolean("anti-lag.enabled", true);
        antiLagCheckEverySeconds = Math.max(5, getConfig().getInt("anti-lag.check-every-seconds", 10));
        antiLagTpsTriggerBelow = getConfig().getDouble("anti-lag.tps-trigger-below", 18.0);
        antiLagScanMaxChunks = Math.max(10, getConfig().getInt("anti-lag.scan-max-chunks-per-check", 120));

        thrHoppers = Math.max(0, getConfig().getInt("anti-lag.thresholds.hoppers", 64));
        thrHopperMinecarts = Math.max(0, getConfig().getInt("anti-lag.thresholds.hopper-minecarts", 12));
        thrEntities = Math.max(0, getConfig().getInt("anti-lag.thresholds.entities", 180));

        notifyConsole = getConfig().getBoolean("anti-lag.notify.console", true);
        notifyAdminsChat = getConfig().getBoolean("anti-lag.notify.admins-in-chat", true);
        notifyPermission = getConfig().getString("anti-lag.notify.admin-permission", "lamaclearitems.alert");

        actionsEnabled = getConfig().getBoolean("anti-lag.actions.enabled", true);
        actionRemoveHopperMinecarts = getConfig().getBoolean("anti-lag.actions.remove-hopper-minecarts", true);
        actionRemoveItems = getConfig().getBoolean("anti-lag.actions.remove-items", true);
        actionRemoveInvisibleArmorStands = getConfig().getBoolean("anti-lag.actions.remove-invisible-armor-stands", true);
        actionBreakHopperBlocks = getConfig().getBoolean("anti-lag.actions.break-hopper-blocks", false);
    }

    private void startClearLoop() {
        if (clearLoopTask != null) clearLoopTask.cancel();
        secondsLeft = intervalSeconds;

        clearLoopTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (broadcast && warnTimes.contains(secondsLeft)) {
                broadcastMsg("messages.warn", vars("time", String.valueOf(secondsLeft)));
            }

            if (secondsLeft <= 0) {
                ClearResult result = clearItemsAndStandsAllWorlds();
                if (broadcast) {
                    Map<String, String> v = new HashMap<>();
                    v.put("items", String.valueOf(result.itemsRemoved));
                    v.put("stands", String.valueOf(result.invisibleStandsRemoved));
                    v.put("total", String.valueOf(result.totalRemoved));
                    broadcastMsg("messages.done", v);
                }
                secondsLeft = intervalSeconds;
            } else {
                secondsLeft--;
            }
        }, 20L, 20L);
    }

    private ClearResult clearItemsAndStandsAllWorlds() {
        List<String> enabledWorlds = getConfig().getStringList("filters.enabled-worlds");
        Set<String> enabled = enabledWorlds.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean filterWorlds = !enabled.isEmpty();

        boolean ignoreNamed = getConfig().getBoolean("filters.ignore-items-with-custom-name", false);
        boolean ignoreGlow = getConfig().getBoolean("filters.ignore-items-with-glow", false);

        int itemsRemoved = 0;
        int standsRemoved = 0;

        for (World world : Bukkit.getWorlds()) {
            if (filterWorlds && !enabled.contains(world.getName().toLowerCase(Locale.ROOT))) continue;

            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (!item.isValid()) continue;
                var stack = item.getItemStack();
                var meta = stack.getItemMeta();
                if (ignoreNamed && meta != null && meta.hasDisplayName()) continue;
                if (ignoreGlow && item.isGlowing()) continue;
                item.remove();
                itemsRemoved++;
            }

            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (!stand.isValid()) continue;
                if (removeInvisibleOnly && !stand.isInvisible()) continue;
                stand.remove();
                standsRemoved++;
            }
        }

        return new ClearResult(itemsRemoved, standsRemoved);
    }

    private void startAntiLagLoop() {
        if (antiLagTask != null) antiLagTask.cancel();
        if (!antiLagEnabled) return;

        antiLagTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            double[] tps = getServerTpsOrNull();
            if (tps == null || tps.length == 0) return;

            double tps1m = tps[0];
            if (tps1m >= antiLagTpsTriggerBelow) return;
            scanAndMitigateLagChunks(tps1m);
        }, 20L * antiLagCheckEverySeconds, 20L * antiLagCheckEverySeconds);
    }

    private void scanAndMitigateLagChunks(double currentTps1m) {
        List<String> enabledWorlds = getConfig().getStringList("filters.enabled-worlds");
        Set<String> enabled = enabledWorlds.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean filterWorlds = !enabled.isEmpty();

        int scanned = 0;

        for (World world : Bukkit.getWorlds()) {
            if (filterWorlds && !enabled.contains(world.getName().toLowerCase(Locale.ROOT))) continue;

            Chunk[] chunks = world.getLoadedChunks();
            for (Chunk chunk : chunks) {
                if (scanned >= antiLagScanMaxChunks) return;
                scanned++;

                ChunkStats stats = analyzeChunk(chunk);

                boolean suspicious =
                        (thrHoppers > 0 && stats.hoppers >= thrHoppers) ||
                                (thrHopperMinecarts > 0 && stats.hopperMinecarts >= thrHopperMinecarts) ||
                                (thrEntities > 0 && stats.entities >= thrEntities);

                if (!suspicious) continue;

                notifyLagChunk(world, chunk, stats, currentTps1m);

                if (actionsEnabled) {
                    String action = applyActionsOnChunk(chunk);
                    if (!action.isEmpty()) notifyAction(world, chunk, action);
                }
            }
        }
    }

    private ChunkStats analyzeChunk(Chunk chunk) {
        int hoppers = 0;

        for (BlockState st : chunk.getTileEntities()) {
            if (st instanceof Hopper) hoppers++;
        }

        int hopperMinecarts = 0;
        int entities = 0;

        for (Entity e : chunk.getEntities()) {
            entities++;
            if (e instanceof HopperMinecart) hopperMinecarts++;
        }

        return new ChunkStats(hoppers, hopperMinecarts, entities);
    }

    private void notifyLagChunk(World world, Chunk chunk, ChunkStats stats, double tps1m) {
        Map<String, String> v = new HashMap<>();
        v.put("world", world.getName());
        v.put("x", String.valueOf(chunk.getX()));
        v.put("z", String.valueOf(chunk.getZ()));
        v.put("hoppers", String.valueOf(stats.hoppers));
        v.put("hoppercarts", String.valueOf(stats.hopperMinecarts));
        v.put("entities", String.valueOf(stats.entities));
        v.put("tps", String.format(Locale.US, "%.2f", Math.min(20.0, Math.max(0.0, tps1m))));

        String msg = getMsg("messages.lag_found", v);

        if (notifyConsole) getLogger().warning(stripColor(msg));
        if (notifyAdminsChat) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(notifyPermission)) p.sendMessage(msg);
            }
        }
    }

    private void notifyAction(World world, Chunk chunk, String action) {
        Map<String, String> v = new HashMap<>();
        v.put("action", action);
        v.put("world", world.getName());
        v.put("x", String.valueOf(chunk.getX()));
        v.put("z", String.valueOf(chunk.getZ()));

        String msg = getMsg("messages.lag_action", v);

        if (notifyConsole) getLogger().warning(stripColor(msg));
        if (notifyAdminsChat) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(notifyPermission)) p.sendMessage(msg);
            }
        }
    }

    private String applyActionsOnChunk(Chunk chunk) {
        int removed = 0;
        int broke = 0;

        if (actionRemoveHopperMinecarts || actionRemoveItems || actionRemoveInvisibleArmorStands) {
            for (Entity e : chunk.getEntities()) {
                if (!e.isValid()) continue;

                if (actionRemoveHopperMinecarts && e instanceof HopperMinecart) {
                    e.remove();
                    removed++;
                    continue;
                }
                if (actionRemoveItems && e instanceof Item) {
                    e.remove();
                    removed++;
                    continue;
                }
                if (actionRemoveInvisibleArmorStands && e instanceof ArmorStand stand) {
                    if (!removeInvisibleOnly || stand.isInvisible()) {
                        stand.remove();
                        removed++;
                    }
                }
            }
        }

        if (actionBreakHopperBlocks) {
            int minY = chunk.getWorld().getMinHeight();
            int maxY = chunk.getWorld().getMaxHeight() - 1;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        Block b = chunk.getBlock(x, y, z);
                        if (b.getType() == Material.HOPPER) {
                            b.setType(Material.AIR, false);
                            broke++;
                        }
                    }
                }
            }
        }

        if (removed == 0 && broke == 0) return "";
        if (broke > 0) return "removedEntities=" + removed + ", brokeHoppers=" + broke;
        return "removedEntities=" + removed;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("lamareload") || cmd.equals("reload")) {
            if (!sender.hasPermission("lamaclearitems.reload")) {
                sender.sendMessage(getMsg("messages.no-perms", Collections.emptyMap()));
                return true;
            }
            loadSettings();
            startClearLoop();
            startAntiLagLoop();
            sender.sendMessage(getMsg("messages.reloaded", Collections.emptyMap()));
            return true;
        }

        if (cmd.equals("lamatps")) {
            if (!sender.hasPermission("lamaclearitems.tps")) {
                sender.sendMessage(getMsg("messages.no-perms", Collections.emptyMap()));
                return true;
            }
            showTps(sender);
            return true;
        }

        if (cmd.equals("lamaclearitems")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("lamaclearitems.reload")) {
                    sender.sendMessage(getMsg("messages.no-perms", Collections.emptyMap()));
                    return true;
                }
                loadSettings();
                startClearLoop();
                startAntiLagLoop();
                sender.sendMessage(getMsg("messages.reloaded", Collections.emptyMap()));
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("tps")) {
                if (!sender.hasPermission("lamaclearitems.tps")) {
                    sender.sendMessage(getMsg("messages.no-perms", Collections.emptyMap()));
                    return true;
                }
                showTps(sender);
                return true;
            }

            if (!sender.hasPermission("lamaclearitems.use")) {
                sender.sendMessage(getMsg("messages.no-perms", Collections.emptyMap()));
                return true;
            }

            ClearResult result = clearItemsAndStandsAllWorlds();
            sender.sendMessage(getMsg("messages.manual", vars("total", String.valueOf(result.totalRemoved))));
            return true;
        }

        return false;
    }

    private void showTps(CommandSender sender) {
        double[] tps = getServerTpsOrNull();
        Map<String, String> v = new HashMap<>();
        if (tps == null) {
            v.put("tps_1m", "N/A");
            v.put("tps_5m", "N/A");
            v.put("tps_15m", "N/A");
        } else {
            v.put("tps_1m", formatTps(tps[0]));
            v.put("tps_5m", formatTps(tps[1]));
            v.put("tps_15m", formatTps(tps[2]));
        }
        sender.sendMessage(getMsg("messages.tps", v));
    }

    private String formatTps(double value) {
        double capped = Math.min(20.0, Math.max(0.0, value));
        return String.format(Locale.US, "%.2f", capped);
    }

    private void broadcastMsg(String path, Map<String, String> vars) {
        Bukkit.broadcastMessage(getMsg(path, vars));
    }

    private String getMsg(String path, Map<String, String> vars) {
        String prefix = color(getConfig().getString("messages.prefix", ""));
        String raw = getConfig().getString(path, "");
        if (raw == null) raw = "";

        raw = raw.replace("%prefix%", prefix);

        for (Map.Entry<String, String> e : vars.entrySet()) {
            raw = raw.replace("%" + e.getKey() + "%", e.getValue());
        }

        return color(raw);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String stripColor(String s) {
        return ChatColor.stripColor(s);
    }

    private Map<String, String> vars(String k, String v) {
        Map<String, String> map = new HashMap<>();
        map.put(k, v);
        return map;
    }

    private static final class ClearResult {
        final int itemsRemoved;
        final int invisibleStandsRemoved;
        final int totalRemoved;

        ClearResult(int itemsRemoved, int invisibleStandsRemoved) {
            this.itemsRemoved = itemsRemoved;
            this.invisibleStandsRemoved = invisibleStandsRemoved;
            this.totalRemoved = itemsRemoved + invisibleStandsRemoved;
        }
    }

    private static final class ChunkStats {
        final int hoppers;
        final int hopperMinecarts;
        final int entities;

        ChunkStats(int hoppers, int hopperMinecarts, int entities) {
            this.hoppers = hoppers;
            this.hopperMinecarts = hopperMinecarts;
            this.entities = entities;
        }
    }
}
