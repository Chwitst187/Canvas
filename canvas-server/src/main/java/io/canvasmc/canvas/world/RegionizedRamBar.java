package io.canvasmc.canvas.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.canvasmc.canvas.Config;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

@org.jspecify.annotations.NullMarked
public class RegionizedRamBar {
    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String GRADIENT_GOOD = "<gradient:#55ff55:#00aa00><text></gradient>";
    private static final String GRADIENT_MEDIUM = "<gradient:#ffff55:#ffaa00><text></gradient>";
    private static final String GRADIENT_LOW = "<gradient:#ff5555:#aa0000><text></gradient>";
    public static final String DEFAULT_FORMAT = "<gray>Mem: <used>/<xmx> (<percent>)";
    private final RegionizedWorldData worldData;
    private final boolean canTick;
    private int ticksSinceLastUpdate = 0;
    private static final ConcurrentMap<UUID, DisplayManager> DISPLAY_MANAGERS = new ConcurrentHashMap<>();
    private static final @org.jspecify.annotations.Nullable Field RAM_BAR_FIELD = resolveRamBarField();


    public RegionizedRamBar(final RegionizedWorldData worldData) {
        this.worldData = worldData;
        this.canTick = Config.INSTANCE.enableRamBar;
    }

    public void tick() {
        if (!this.canTick) return;

        this.ticksSinceLastUpdate++;
        if (this.ticksSinceLastUpdate >= UPDATE_INTERVAL_TICKS) {
            this.ticksSinceLastUpdate = 0;
            final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            final long used = heap.getUsed();
            final long xmx = heap.getMax();
            final double percent = safePercent(used, xmx);
            final Component display = buildComponent(used, xmx, percent);
            for (final ServerPlayer localPlayer : this.worldData.getLocalPlayers()) {
                final DisplayManager manager = managerFor(localPlayer);
                manager.setDisplay(display);
                manager.updateBarColorAndProgress(percent);
                manager.tick();
            }
        }
    }

    private static Component buildComponent(final long used, final long xmx, final double percent) {
        return MINI_MESSAGE.deserialize(
            normalizeFormat(Config.INSTANCE.ramBarFormat),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("used", getUsedComponent(used, percent)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("xmx", getMaxMemComponent(xmx, percent)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("percent", getPercentComponent(percent))
        );
    }

    public static void renderNow(final @NonNull ServerPlayer player) {
        final DisplayManager manager = managerFor(player);
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long used = heap.getUsed();
        final long xmx = heap.getMax();
        final double percent = xmx <= 0L ? 0.0D : Math.clamp((double) used / (double) xmx, 0.0D, 1.0D);

        manager.setDisplay(buildComponent(used, xmx, percent));
        manager.updateBarColorAndProgress(percent);
        manager.tick();
    }


    public static @NonNull DisplayManager managerFor(final @NonNull ServerPlayer player) {
        if (RAM_BAR_FIELD != null) {
            try {
                final Object value = RAM_BAR_FIELD.get(player);
                if (value instanceof DisplayManager manager) {
                    return manager;
                }
            } catch (final IllegalAccessException ignored) {
            }
        }

        return DISPLAY_MANAGERS.computeIfAbsent(player.getUUID(), ignored -> DisplayManager.createNew(player));
    }

    private static @org.jspecify.annotations.Nullable Field resolveRamBarField() {
        try {
            final Field field = ServerPlayer.class.getField("canvas$ramBarDisplay");
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException ignored) {
            return null;
        }
    }


    private static @NonNull String normalizeFormat(final @NonNull String input) {
        return input
            .replace("%used%", "<used>")
            .replace("%xmx%", "<xmx>")
            .replace("%percent%", "<percent>");
    }

    private static double safePercent(final long used, final long max) {
        if (max <= 0L) return 0.0D;
        final double p = (double) used / (double) max;
        return Math.clamp(p, 0.0D, 1.0D);
    }

    private static @NotNull Component getUsedComponent(final long usedBytes, final double percent) {
        if (usedBytes <= 0L) return MINI_MESSAGE.deserialize("<gray>—").append(Component.text("GB"));
        final double usedGb = usedBytes / (1024.0D * 1024.0D * 1024.0D);
        return gradientComponent(percent, String.format("%.2f", usedGb)).append(MINI_MESSAGE.deserialize("<gray>GB"));
    }

    private static @NotNull Component getMaxMemComponent(final long maxBytes, final double percent) {
        if (maxBytes <= 0L) return MINI_MESSAGE.deserialize("<gray>—").append(Component.text("GB"));
        final double maxGb = maxBytes / (1024.0D * 1024.0D * 1024.0D);
        return gradientComponent(percent, String.format("%.2f", maxGb)).append(MINI_MESSAGE.deserialize("<gray>GB"));
    }

    private static @NotNull Component getPercentComponent(final double percent) {
        if (percent <= 0.0D) return MINI_MESSAGE.deserialize("<gray>—");
        return gradientComponent(percent, String.format("%.0f%%", percent * 100.0D));
    }

    private static @NotNull Component gradientComponent(final double percent, final @NotNull String text) {
        final String tpl = percent <= 0.50D ? GRADIENT_GOOD : (percent <= 0.70D ? GRADIENT_MEDIUM : GRADIENT_LOW);
        final String inner = text.replace(",", "<gray>,</gray>");
        return MINI_MESSAGE.deserialize(tpl.replace("<text>", inner));
    }

    public enum Placement {
        ACTION_BAR, BOSS_BAR;
        public static final Codec<Placement> CODEC = Codec.STRING.comapFlatMap((string) -> DataResult.success(Placement.valueOf(string)), Enum::name);
    }

    public interface DisplayManager {
        @Contract(value = "_ -> new", pure = true)
        static @NonNull DisplayManager createNew(final ServerPlayer entityPlayer) {
            return new DisplayManager() {
                private Component display = Component.text("Waiting for region update...");
                public final BossBar ramBar = BossBar.bossBar(this.display, 0.0F, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
                private volatile boolean enabled = false;
                private Placement placement = Placement.BOSS_BAR;
                private boolean dirty = true;

                @Override
                public void tick() {
                    if (dirty) {
                        final CraftPlayer bukkitEntity = entityPlayer.getBukkitEntity();

                        if (placement == Placement.BOSS_BAR) {
                            // Ensure stale action-bar text is cleared when switching to boss-bar mode.
                            entityPlayer.connection.send(new ClientboundSetActionBarTextPacket(PaperAdventure.asVanillaNullToEmpty(Component.empty())));
                            if (enabled) {
                                ramBar.addViewer(bukkitEntity);
                            } else {
                                ramBar.removeViewer(bukkitEntity);
                            }
                        } else {
                            ramBar.removeViewer(bukkitEntity);
                            if (!enabled) {
                                // ACTION_BAR is client-side text; send an empty packet to clear immediately on disable.
                                entityPlayer.connection.send(new ClientboundSetActionBarTextPacket(PaperAdventure.asVanillaNullToEmpty(Component.empty())));
                            }
                        }

                        dirty = false;
                    }

                    if (!enabled) return;

                    switch (placement) {
                        case BOSS_BAR -> ramBar.name(display);
                        case ACTION_BAR -> entityPlayer.connection.send(new ClientboundSetActionBarTextPacket(PaperAdventure.asVanillaNullToEmpty(display)));
                    }
                }

                @Override
                public void setDisplay(final Component component) {
                    this.display = component;
                }

                @Override
                public void updateBarColorAndProgress(final double percent) {
                    final BossBar.Color color = percent <= 0.50D ? BossBar.Color.GREEN : (percent <= 0.70D ? BossBar.Color.YELLOW : BossBar.Color.RED);
                    this.ramBar.color(color).progress((float) percent);
                }

                @Override
                public void enable() {
                    this.enabled = true;
                    this.dirty = true;
                }

                @Override
                public void disable() {
                    this.enabled = false;
                    this.dirty = true;
                }

                @Override
                public void updateFromEntry(final Entry entry) {
                    this.enabled = entry.enabled();
                    this.placement = entry.placement();
                    this.dirty = true;
                }

                @Override
                public Entry serializeDisplay() {
                    return new Entry(this.enabled, this.placement);
                }
            };
        }

        void tick();


        void setDisplay(Component component);

        default void updateBarColorAndProgress(double percent) {}

        void enable();

        void disable();

        void updateFromEntry(Entry entry);

        Entry serializeDisplay();
    }


    public record Entry(boolean enabled, Placement placement) {
        public static final Entry FALLBACK = new Entry(false, Placement.BOSS_BAR);
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("enabled", false).forGetter(Entry::enabled),
                    Placement.CODEC.optionalFieldOf("placement", Placement.BOSS_BAR).forGetter(Entry::placement)
                )
                .apply(instance, Entry::new)
        );

        @Override
        public boolean enabled() {
            return Config.INSTANCE.enableRamBar && enabled;
        }
    }
}
