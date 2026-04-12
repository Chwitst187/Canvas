package io.canvasmc.canvas.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.canvasmc.canvas.Config;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

public class RegionizedRamBar {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final String GRADIENT_GOOD = "<gradient:#55ff55:#00aa00><text></gradient>";
    private static final String GRADIENT_MEDIUM = "<gradient:#ffff55:#ffaa00><text></gradient>";
    private static final String GRADIENT_LOW = "<gradient:#ff5555:#aa0000><text></gradient>";
    public static final String DEFAULT_FORMAT = "<gray>Mem: <used>/<xmx> (<percent>)";

    private final RegionizedWorldData worldData;
    private final boolean canTick;
    private int ticksSinceLastUpdate = 0;

    public RegionizedRamBar(final RegionizedWorldData worldData) {
        this.worldData = worldData;
        this.canTick = Config.INSTANCE.enableRamBar;
    }

    public void tick() {
        if (!this.canTick) {
            return;
        }

        this.ticksSinceLastUpdate++;
        if (this.ticksSinceLastUpdate < UPDATE_INTERVAL_TICKS) {
            return;
        }
        this.ticksSinceLastUpdate = 0;

        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        final long used = heap.getUsed();
        final long xmx = heap.getMax();
        final double percent = safePercent(used, xmx);

        for (final ServerPlayer localPlayer : this.worldData.getLocalPlayers()) {
            final Component textComponent = this.buildComponent(used, xmx, percent);
            final DisplayManager display = localPlayer.canvas$ramBarDisplay;
            display.setDisplay(textComponent);
            display.updateBarColorAndProgress(percent);
            display.tick();
        }
    }

    private @NonNull Component buildComponent(final long used, final long xmx, final double percent) {
        final String configuredFormat = Config.INSTANCE.ramBarFormat;
        final String effectiveFormat = configuredFormat == null || configuredFormat.isBlank()
            ? DEFAULT_FORMAT
            : normalizeFormat(configuredFormat);
        return MINI_MESSAGE.deserialize(
            effectiveFormat,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("used", getUsedComponent(used, percent)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("xmx", getMaxMemComponent(xmx, percent)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("percent", getPercentComponent(percent))
        );
    }

    private static @NonNull String normalizeFormat(final @NonNull String input) {
        return input
            .replace("%used%", "<used>")
            .replace("%xmx%", "<xmx>")
            .replace("%percent%", "<percent>");
    }

    private Component gradient(final String tpl, final String value) {
        final String inner = value.replace(",", "<gray>,</gray>");
        return MINI_MESSAGE.deserialize(tpl.replace("<text>", inner));
    }

    private @NotNull Component gradientComponent(final double percent, final @NotNull String text) {
        final String tpl = percent <= 0.50D ? GRADIENT_GOOD : (percent <= 0.70D ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return gradient(tpl, text);
    }

    private double safePercent(final long used, final long max) {
        if (max <= 0L) {
            return 0.0D;
        }
        final double raw = (double) used / (double) max;
        return Math.min(1.0D, Math.max(0.0D, raw));
    }

    private @NotNull Component getUsedComponent(final long usedBytes, final double percent) {
        if (usedBytes <= 0L) {
            return MINI_MESSAGE.deserialize("<gray>—").append(MINI_MESSAGE.deserialize("<gray>GB"));
        }
        final double usedGb = usedBytes / (1024.0D * 1024.0D * 1024.0D);
        final Component value = gradientComponent(percent, String.format("%.2f", usedGb));
        return value.append(MINI_MESSAGE.deserialize("<gray>GB"));
    }

    private @NotNull Component getMaxMemComponent(final long maxBytes, final double percent) {
        if (maxBytes <= 0L) {
            return MINI_MESSAGE.deserialize("<gray>—").append(MINI_MESSAGE.deserialize("<gray>GB"));
        }
        final double maxGb = maxBytes / (1024.0D * 1024.0D * 1024.0D);
        final Component value = gradientComponent(percent, String.format("%.2f", maxGb));
        return value.append(MINI_MESSAGE.deserialize("<gray>GB"));
    }

    private @NotNull Component getPercentComponent(final double percent) {
        if (percent <= 0.0D) {
            return MINI_MESSAGE.deserialize("<gray>—");
        }
        return gradientComponent(percent, String.format("%.0f%%", percent * 100.0D));
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
                public final BossBar ramBar =
                    BossBar.bossBar(
                        this.display,
                        0.0F,
                        BossBar.Color.PURPLE,
                        BossBar.Overlay.PROGRESS
                    );

                private volatile boolean enabled = false;
                private Placement placement = Placement.BOSS_BAR;
                private boolean dirty = true;

                @Override
                public void tick() {
                    if (this.dirty) {
                        final CraftPlayer bukkitEntity = entityPlayer.getBukkitEntity();

                        if (this.placement == Placement.BOSS_BAR) {
                            if (this.enabled) {
                                this.ramBar.addViewer(bukkitEntity);
                            } else {
                                this.ramBar.removeViewer(bukkitEntity);
                            }
                        } else {
                            this.ramBar.removeViewer(bukkitEntity);
                        }

                        this.dirty = false;
                    }

                    if (!this.enabled) {
                        return;
                    }

                    switch (this.placement) {
                        case BOSS_BAR -> this.ramBar.name(this.display);
                        case ACTION_BAR -> entityPlayer.connection.send(
                            new ClientboundSetActionBarTextPacket(PaperAdventure.asVanillaNullToEmpty(this.display))
                        );
                    }
                }

                @Override
                public void setDisplay(final Component component) {
                    this.display = component;
                }

                @Override
                public void updateBarColorAndProgress(final double percent) {
                    this.ramBar.color(barColorFromMemory(percent)).progress((float) percent);
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

        default void updateBarColorAndProgress(final double percent) {
        }

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
            return Config.INSTANCE.enableRamBar && this.enabled;
        }
    }

    private static BossBar.Color barColorFromMemory(final double memPercent) {
        if (memPercent <= 0.50D) {
            return BossBar.Color.GREEN;
        }
        if (memPercent <= 0.70D) {
            return BossBar.Color.YELLOW;
        }
        return BossBar.Color.RED;
    }
}

