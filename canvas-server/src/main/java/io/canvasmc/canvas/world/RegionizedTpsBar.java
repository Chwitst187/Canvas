package io.canvasmc.canvas.world;

import ca.spottedleaf.moonrise.common.time.TickData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.canvasmc.canvas.Config;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static net.kyori.adventure.text.Component.text;

public class RegionizedTpsBar {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String GRADIENT_GOOD = "<gradient:#55ff55:#00aa00><text></gradient>";
    private static final String GRADIENT_MEDIUM = "<gradient:#ffff55:#ffaa00><text></gradient>";
    private static final String GRADIENT_LOW = "<gradient:#ff5555:#aa0000><text></gradient>";
    private static final int TPS_PRECISION = 2;
    private static final int MSPT_PRECISION = 2;
    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final long GLOBAL_STATS_CACHE_NANOS = 1_000_000_000L;
    public static final String DEFAULT_FORMAT =
        "<gray>TPS: <tps> MSPT: <mspt> Ping: <ping> ChunkHot: <chunkhot>";
    private static volatile long cachedGlobalStatsAt = Long.MIN_VALUE;
    private static volatile GlobalStats cachedGlobalStats = GlobalStats.empty();
    private final RegionizedWorldData worldData;
    private final boolean canTick;
    private int ticksSinceLastUpdate = 0;

    public RegionizedTpsBar(RegionizedWorldData worldData) {
        this.worldData = worldData;
        this.canTick = Config.INSTANCE.enableTpsBar;
    }

    public static @NonNull Component gradient(final String textContent, final @Nullable Consumer<Style.Builder> style, final TextColor... colors) {
        final Gradient gradient = new Gradient(colors);
        final TextComponent.Builder builder = text();
        if (style != null) {
            builder.style(style);
        }
        final char[] content = textContent.toCharArray();
        gradient.length(content.length);
        for (final char c : content) {
            builder.append(text(c, gradient.nextColor()));
        }
        return builder.build();
    }

    public RegionizedWorldData getWorldData() {
        return worldData;
    }

    public void tick() {
        if (!this.canTick) return;

        this.ticksSinceLastUpdate++;
        if (this.ticksSinceLastUpdate >= UPDATE_INTERVAL_TICKS) {
            this.ticksSinceLastUpdate = 0;
            final GlobalStats stats = getGlobalStats();
            // update players
            for (final ServerPlayer localPlayer : this.worldData.getLocalPlayers()) {
                final Component textComponent = buildComponent(stats, localPlayer);
                localPlayer.canvas$tpsBarDisplay.setDisplay(textComponent);
                localPlayer.canvas$tpsBarDisplay.updateBarColorAndProgress(stats.mspt());
                localPlayer.canvas$tpsBarDisplay.tick();
            }
        }
    }

    private Component gradient(String tpl, String value) {
        String inner = value.replace(",", "<gray>,</gray>");
        String miniMessage = tpl.replace("<text>", inner);
        return MINI_MESSAGE.deserialize(miniMessage);
    }

    private Component gradientForTps(double tps, String value) {
        String tpl = tps >= 18 ? GRADIENT_GOOD : (tps >= 15 ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return gradient(tpl, value);
    }

    private Component gradientForMspt(double mspt, String value) {
        String tpl = mspt <= 35 ? GRADIENT_GOOD : (mspt <= 50 ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return gradient(tpl, value);
    }

    private Component gradientForPing(int ping, String value) {
        String tpl = ping <= 80 ? GRADIENT_GOOD : (ping <= 160 ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return gradient(tpl, value);
    }

    private Component gradientForChunkHot(long chunkHot, String value) {
        String tpl = chunkHot < 20_000 ? GRADIENT_GOOD : (chunkHot < 30_000 ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return gradient(tpl, value);
    }

    private @NonNull Component buildComponent(final @NonNull GlobalStats stats, final ServerPlayer localPlayer) {
        final int pingVal = localPlayer != null ? localPlayer.connection.latency() : 0;
        final String configuredFormat = Config.INSTANCE.tpsBarFormat;
        final String effectiveFormat = configuredFormat == null || configuredFormat.isBlank()
            ? DEFAULT_FORMAT
            : normalizeFormat(configuredFormat);

        return MINI_MESSAGE.deserialize(
            effectiveFormat,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("tps", getTpsComponent(stats.tps())),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("mspt", getMsptComponent(stats.mspt())),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("ping", getPingComponent(pingVal)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("chunkhot", getChunkHotComponent(stats.chunkHot())),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("util", getUtilComponent(stats.utilisationPercent())),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("players", getPlayersComponent(stats.players()))
        );
    }

    private static @NonNull String normalizeFormat(final @NonNull String input) {
        return input
            .replace("%tps%", "<tps>")
            .replace("%mspt%", "<mspt>")
            .replace("%ping%", "<ping>")
            .replace("%chunkhot%", "<chunkhot>")
            .replace("%util%", "<util>")
            .replace("%players%", "<players>");
    }

    private static long getGlobalFullChunksCount() {
        long chunkHot = 0L;
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            chunkHot += level.getChunkSource().getFullChunksCount();
        }
        return chunkHot;
    }

    private @NotNull Component getTpsComponent(double tps) {
        return gradientForTps(tps, tps <= 0.0 ? "—" : String.format("%." + TPS_PRECISION + "f", tps));
    }

    private @NotNull Component getMsptComponent(double mspt) {
        return gradientForMspt(mspt, mspt <= 0.0 ? "—" : String.format("%." + MSPT_PRECISION + "f", mspt));
    }

    private @NotNull Component getPingComponent(int ping) {
        if (ping <= 0) {
            return MINI_MESSAGE.deserialize("<gray>—");
        }
        return gradientForPing(ping, String.valueOf(ping)).append(MINI_MESSAGE.deserialize("<gray>ms"));
    }

    private @NotNull Component getChunkHotComponent(long chunkHot) {
        if (chunkHot <= 0L) {
            return MINI_MESSAGE.deserialize("<gray>—");
        }
        return gradientForChunkHot(chunkHot, String.valueOf(chunkHot));
    }

    private @NotNull Component getUtilComponent(double utilisationPercent) {
        if (utilisationPercent <= 0.0D) {
            return MINI_MESSAGE.deserialize("<gray>—");
        }

        final double ratio = Math.min(1.0D, Math.max(0.0D, utilisationPercent / 100.0D));
        final String tpl = ratio <= 0.50D ? GRADIENT_GOOD : (ratio <= 0.70D ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return gradient(tpl, String.format(Locale.ROOT, "%.1f%%", utilisationPercent));
    }

    private @NotNull Component getPlayersComponent(int players) {
        if (players <= 0) {
            return MINI_MESSAGE.deserialize("<gray>—");
        }
        return gradient(GRADIENT_GOOD, String.valueOf(players));
    }

    private static @NonNull GlobalStats getGlobalStats() {
        final long now = System.nanoTime();
        final long cachedAt = cachedGlobalStatsAt;
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < GLOBAL_STATS_CACHE_NANOS) {
            return cachedGlobalStats;
        }

        synchronized (RegionizedTpsBar.class) {
            final long refreshedCachedAt = cachedGlobalStatsAt;
            if (refreshedCachedAt != Long.MIN_VALUE && now - refreshedCachedAt < GLOBAL_STATS_CACHE_NANOS) {
                return cachedGlobalStats;
            }

            final GlobalStats stats = computeGlobalStats(now);
            cachedGlobalStats = stats;
            cachedGlobalStatsAt = now;
            return stats;
        }
    }

    private static @NonNull GlobalStats computeGlobalStats(final long now) {
        final GlobalStatsAccumulator accumulator = new GlobalStatsAccumulator(now);
        accumulator.add(RegionizedServer.getGlobalTickData());
        for (final ServerLevel world : RegionizedServer.getInstance().worlds) {
            world.regioniser.computeForAllRegionsUnsynchronised(region -> accumulator.add(region.getData().getRegionSchedulingHandle()));
        }

        final int players = MinecraftServer.getServer().getPlayerList().getPlayerCount();
        return accumulator.toStats(players, getGlobalFullChunksCount());
    }

    private record GlobalStats(double tps, double mspt, double utilisationPercent, int players, long chunkHot) {
        private static @NonNull GlobalStats empty() {
            return new GlobalStats(20.0D, 0.0D, 0.0D, 0, 0L);
        }
    }

    private static final class GlobalStatsAccumulator {
        private final long now;
        private double tpsTotal;
        private double msptTotal;
        private double utilisationTotal;
        private int samples;

        private GlobalStatsAccumulator(final long now) {
            this.now = now;
        }

        private void add(final TickRegionScheduler.RegionScheduleHandle scheduleHandle) {
            final TickData.TickReportData reportData = scheduleHandle.getTickReport5s(this.now);
            if (reportData == null) {
                return;
            }

            this.tpsTotal += reportData.tpsData().segmentAll().average();
            this.msptTotal += reportData.timePerTickData().segmentAll().average() / 1.0E6;
            this.utilisationTotal += Math.max(0.0D, reportData.utilisation()) * 100.0D;
            this.samples++;
        }

        private @NonNull GlobalStats toStats(final int players, final long chunkHot) {
            if (this.samples <= 0) {
                return new GlobalStats(TickRegionScheduler.getTickRate(), 0.0D, 0.0D, players, chunkHot);
            }

            return new GlobalStats(
                this.tpsTotal / this.samples,
                this.msptTotal / this.samples,
                this.utilisationTotal / this.samples,
                players,
                chunkHot
            );
        }
    }

    public enum Placement {
        ACTION_BAR, BOSS_BAR;
        public static final Codec<Placement> CODEC = Codec.STRING.comapFlatMap((string) -> DataResult.success(Placement.valueOf(string)), Enum::name);
    }

    public interface DisplayManager {
        @Contract(value = "_ -> new", pure = true)
        static @NonNull DisplayManager createNew(ServerPlayer entityPlayer) {
            return new DisplayManager() {
                private Component display = Component.text("Waiting for region update...");
                public final BossBar tpsBar =
                    BossBar.bossBar(
                        this.display,
                        0.0F,
                        BossBar.Color.PURPLE,
                        BossBar.Overlay.PROGRESS
                    );

                private volatile boolean enabled = false;
                private Placement placement = Placement.BOSS_BAR;
                private boolean dirty = true; // force initial sync

                @Override
                public void tick() {
                    // handle state changes if marked dirty
                    if (dirty) {
                        final CraftPlayer bukkitEntity = entityPlayer.getBukkitEntity();

                        if (placement == Placement.BOSS_BAR) {
                            if (enabled) {
                                tpsBar.addViewer(bukkitEntity);
                            }
                            else {
                                tpsBar.removeViewer(bukkitEntity);
                            }
                        }
                        else {
                            tpsBar.removeViewer(bukkitEntity);
                        }

                        dirty = false;
                    }

                    if (!enabled) {
                        return;
                    }

                    switch (placement) {
                        case BOSS_BAR -> tpsBar.name(display);
                        case ACTION_BAR -> entityPlayer.connection.send(
                            new ClientboundSetActionBarTextPacket(
                                PaperAdventure.asVanillaNullToEmpty(display)
                            )
                        );
                    }
                }

                @Override
                public void setDisplay(final Component component) {
                    this.display = component;
                }

                @Override
                public void updateBarColorAndProgress(final double mspt) {
                    final double ratio = Math.min(1.0D, Math.max(0.0D, mspt / 50.0D));
                    final BossBar.Color bossBarColor = mspt <= 35.0D
                        ? BossBar.Color.GREEN
                        : (mspt <= 50.0D ? BossBar.Color.YELLOW : BossBar.Color.RED);
                    this.tpsBar.color(bossBarColor).progress((float) ratio);
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
                    return new Entry(
                        this.enabled, this.placement
                    );
                }
            };
        }

        void tick();

        void setDisplay(Component component);

        default void updateBarColorAndProgress(double mspt) {}

        void enable();

        void disable();

        void updateFromEntry(Entry entry);

        Entry serializeDisplay();
    }

    record FormatEntry(String raw, List<Segment> segments) {
        sealed interface Segment permits Segment.Static, Segment.Dynamic {
            record Static(Component component) implements Segment {}
            record Dynamic(String key) implements Segment {}
        }

        static @NonNull FormatEntry compile(final @NonNull String effectiveRaw) {
            final String normalized = effectiveRaw.isEmpty() ? DEFAULT_FORMAT : normalize(effectiveRaw);
            return new FormatEntry(effectiveRaw, buildSegments(normalized));
        }

        private static @NonNull String normalize(final @NonNull String input) {
            return normalizeFormat(input);
        }

        private static @NonNull List<Segment> buildSegments(final String normalized) {
            final List<Segment> result = new ArrayList<>();
            final String[] keys = {"tps", "mspt", "util", "players", "ping", "chunkhot"};
            String remaining = normalized;

            while (!remaining.isEmpty()) {
                int earliestIdx = Integer.MAX_VALUE;
                String earliestKey = null;
                for (final String key : keys) {
                    final int idx = remaining.indexOf("<" + key + ">");
                    if (idx >= 0 && idx < earliestIdx) {
                        earliestIdx = idx;
                        earliestKey = key;
                    }
                }

                if (earliestKey == null) {
                    result.add(parseStatic(remaining));
                    break;
                }

                if (earliestIdx > 0) {
                    result.add(parseStatic(remaining.substring(0, earliestIdx)));
                }
                result.add(new Segment.Dynamic(earliestKey));
                remaining = remaining.substring(earliestIdx + earliestKey.length() + 2); // +2 for '<' and '>'
            }

            return result;
        }

        @Contract("_ -> new")
        private static Segment.@NonNull Static parseStatic(final String text) {
            try {
                return new Segment.Static(MINI_MESSAGE.deserialize(text));
            } catch (final Exception e) {
                return new Segment.Static(Component.text(text));
            }
        }
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
            return Config.INSTANCE.enableTpsBar && enabled;
        }
    }

    public static final class Gradient {
        private final boolean negativePhase;
        private final TextColor[] colors;
        private int index = 0;
        private int colorIndex = 0;
        private float factorStep = 0;
        private float phase;

        public Gradient(final @NonNull TextColor... colors) {
            this(0, colors);
        }

        public Gradient(final float phase, final @NonNull TextColor @NonNull ... colors) {
            if (colors.length < 2) {
                throw new IllegalArgumentException("Gradients must have at least two colors! colors=" + Arrays.toString(colors));
            }
            if (phase > 1.0 || phase < -1.0) {
                throw new IllegalArgumentException(String.format("Phase must be in range [-1, 1]. '%s' is not valid.", phase));
            }
            this.colors = colors;
            if (phase < 0) {
                this.negativePhase = true;
                this.phase = 1 + phase;
                Collections.reverse(Arrays.asList(this.colors));
            }
            else {
                this.negativePhase = false;
                this.phase = phase;
            }
        }

        public void length(final int size) {
            this.colorIndex = 0;
            this.index = 0;
            final int sectorLength = size / (this.colors.length - 1);
            this.factorStep = 1.0f / sectorLength;
            this.phase = this.phase * sectorLength;
        }

        public @NonNull TextColor nextColor() {
            if (this.factorStep * this.index > 1) {
                this.colorIndex++;
                this.index = 0;
            }

            float factor = this.factorStep * (this.index++ + this.phase);
            // loop around if needed
            if (factor > 1) {
                factor = 1 - (factor - 1);
            }
            if (this.negativePhase && this.colors.length % 2 != 0) {
                // flip the gradient segment for to allow for looping phase -1 through 1
                return this.interpolate(this.colors[this.colorIndex + 1], this.colors[this.colorIndex], factor);
            }
            else {
                return this.interpolate(this.colors[this.colorIndex], this.colors[this.colorIndex + 1], factor);
            }
        }

        private @NonNull TextColor interpolate(final @NonNull TextColor color1, final @NonNull TextColor color2, final float factor) {
            return TextColor.color(
                Math.round(color1.red() + factor * (color2.red() - color1.red())),
                Math.round(color1.green() + factor * (color2.green() - color1.green())),
                Math.round(color1.blue() + factor * (color2.blue() - color1.blue()))
            );
        }
    }
}
