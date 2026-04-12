package io.canvasmc.canvas.world;

import ca.spottedleaf.moonrise.common.time.TickData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.canvasmc.canvas.Config;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.text.DecimalFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

public class RegionizedRegionBar {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final String GRADIENT_GOOD = "<gradient:#55ff55:#00aa00><text></gradient>";
    private static final String GRADIENT_MEDIUM = "<gradient:#ffff55:#ffaa00><text></gradient>";
    private static final String GRADIENT_LOW = "<gradient:#ff5555:#aa0000><text></gradient>";
    public static final String DEFAULT_FORMAT = "<gray>Util: <util> Chunks: <chunks> Players: <players> Entities: <entities>";

    private static final int PLAYERS_YELLOW = 100;
    private static final int PLAYERS_RED = 150;
    private static final int CHUNKS_YELLOW = 1500;
    private static final int CHUNKS_RED = 1750;
    private static final double UTIL_YELLOW = 75.0D;
    private static final double UTIL_RED = 90.0D;
    private static final int ENTITIES_YELLOW = 250;
    private static final int ENTITIES_RED = 450;

    private final ThreadLocal<DecimalFormat> oneDecimalPlaces = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));
    private final RegionizedWorldData worldData;
    private final boolean canTick;
    private int ticksSinceLastUpdate = 0;

    public RegionizedRegionBar(final RegionizedWorldData worldData) {
        this.worldData = worldData;
        this.canTick = Config.INSTANCE.enableRegionBar;
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

        final TickData.TickReportData reportData = this.worldData.regionData.getRegionSchedulingHandle().getTickReport5s(System.nanoTime());
        if (reportData == null) {
            return;
        }

        final double utilisation = Math.max(0.0D, reportData.utilisation());
        final double utilisationPercent = utilisation * 100.0D;
        final int chunks = this.worldData.getChunkCount();
        final int players = this.worldData.getPlayerCount();
        final int entities = this.worldData.getEntityCount();

        for (final ServerPlayer localPlayer : this.worldData.getLocalPlayers()) {
            final DisplayManager display = localPlayer.canvas$regionBarDisplay;
            display.setDisplay(this.buildComponent(utilisationPercent, chunks, players, entities));
            display.updateBarColorAndProgress(utilisationPercent);
            display.tick();
        }
    }

    private @NonNull Component buildComponent(final double utilisationPercent, final int chunks, final int players, final int entities) {
        final String configuredFormat = Config.INSTANCE.regionBarFormat;
        final String effectiveFormat = configuredFormat == null || configuredFormat.isBlank()
            ? DEFAULT_FORMAT
            : normalizeFormat(configuredFormat);

        final String formattedUtil = this.oneDecimalPlaces.get().format(utilisationPercent);
        final double ratio = Math.min(1.0D, Math.max(0.0D, utilisationPercent / 100.0D));

        return MINI_MESSAGE.deserialize(
            effectiveFormat,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("util", this.getUtilComponent(utilisationPercent, formattedUtil, ratio)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("chunks", this.getChunkComponent(chunks)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("players", this.getPlayerComponent(players)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("entities", this.getEntityComponent(entities))
        );
    }

    private static @NonNull String normalizeFormat(final @NonNull String input) {
        return input
            .replace("%util%", "<util>")
            .replace("%chunks%", "<chunks>")
            .replace("%players%", "<players>")
            .replace("%entities%", "<entities>");
    }

    private Component gradient(final String tpl, final String value) {
        final String inner = value.replace(",", "<gray>,</gray>");
        return MINI_MESSAGE.deserialize(tpl.replace("<text>", inner));
    }

    private @NotNull Component gradientComponent(final double ratio, final @NotNull String text) {
        final String tpl = ratio <= 0.50D ? GRADIENT_GOOD : (ratio <= 0.70D ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return this.gradient(tpl, text);
    }

    private @NotNull Component getUtilComponent(final double utilPercent, final @NotNull String formattedUtil, final double ratio) {
        if (utilPercent >= UTIL_RED) {
            return this.gradientComponent(0.90D, formattedUtil + "%");
        }
        if (utilPercent >= UTIL_YELLOW) {
            return this.gradientComponent(0.60D, formattedUtil + "%");
        }
        return this.gradientComponent(ratio, formattedUtil + "%");
    }

    private @NotNull Component getPlayerComponent(final int players) {
        final String text = players <= 0 ? "—" : String.valueOf(players);
        if (players >= PLAYERS_RED) {
            return this.gradientComponent(0.90D, text);
        }
        if (players >= PLAYERS_YELLOW) {
            return this.gradientComponent(0.60D, text);
        }
        return this.gradientComponent(0.25D, text);
    }

    private @NotNull Component getChunkComponent(final int chunks) {
        final String text = chunks <= 0 ? "—" : String.valueOf(chunks);
        if (chunks >= CHUNKS_RED) {
            return this.gradientComponent(0.90D, text);
        }
        if (chunks >= CHUNKS_YELLOW) {
            return this.gradientComponent(0.60D, text);
        }
        return this.gradientComponent(0.25D, text);
    }

    private @NotNull Component getEntityComponent(final int entities) {
        final String text = entities <= 0 ? "—" : String.valueOf(entities);
        if (entities >= ENTITIES_RED) {
            return this.gradientComponent(0.90D, text);
        }
        if (entities >= ENTITIES_YELLOW) {
            return this.gradientComponent(0.60D, text);
        }
        return this.gradientComponent(0.25D, text);
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
                public final BossBar regionBar =
                    BossBar.bossBar(
                        this.display,
                        0.0F,
                        BossBar.Color.GREEN,
                        BossBar.Overlay.NOTCHED_20
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
                                this.regionBar.addViewer(bukkitEntity);
                            } else {
                                this.regionBar.removeViewer(bukkitEntity);
                            }
                        } else {
                            this.regionBar.removeViewer(bukkitEntity);
                        }

                        this.dirty = false;
                    }

                    if (!this.enabled) {
                        return;
                    }

                    switch (this.placement) {
                        case BOSS_BAR -> this.regionBar.name(this.display);
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
                public void updateBarColorAndProgress(final double utilPercent) {
                    final double progress = Math.min(1.0D, Math.max(0.0D, utilPercent / 100.0D));
                    this.regionBar.color(barColorFromUtil(utilPercent)).progress((float) progress);
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

        default void updateBarColorAndProgress(final double utilPercent) {
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
            return Config.INSTANCE.enableRegionBar && this.enabled;
        }
    }

    private static BossBar.Color barColorFromUtil(final double utilPercent) {
        if (utilPercent <= UTIL_YELLOW) {
            return BossBar.Color.GREEN;
        }
        if (utilPercent <= UTIL_RED) {
            return BossBar.Color.YELLOW;
        }
        return BossBar.Color.RED;
    }
}

