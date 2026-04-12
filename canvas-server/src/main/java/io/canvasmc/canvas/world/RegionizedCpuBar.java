package io.canvasmc.canvas.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.canvasmc.canvas.Config;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

public class RegionizedCpuBar {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final int UPDATE_INTERVAL_TICKS = 20;
    public static final String DEFAULT_FORMAT = "<gray>CPU: <used>/<max> (<util>) <temp>";

    private static final String GRADIENT_GOOD = "<gradient:#55ff55:#00aa00><text></gradient>";
    private static final String GRADIENT_MEDIUM = "<gradient:#ffff55:#ffaa00><text></gradient>";
    private static final String GRADIENT_LOW = "<gradient:#ff5555:#aa0000><text></gradient>";

    private static final boolean WINDOWS_OS = isWindowsOs();
    private static final double EMA_ALPHA = 0.3D;

    private final RegionizedWorldData worldData;
    private final boolean canTick;

    private int ticksSinceLastUpdate = 0;
    private volatile double cachedCpuPercent = 0.0D;
    private volatile int cachedProcs = Runtime.getRuntime().availableProcessors();
    private volatile double cachedCpuTempC = Double.NaN;

    private volatile Object oshiSensors = null;
    private volatile boolean oshiSensorsInitAttempted = false;
    private volatile boolean cpuTempPollingDisabled = false;

    private long lastCpuTime = 0L;
    private long lastSystemTime = 0L;
    private volatile boolean firstMeasurement = true;

    private long lastProcCpuTime = -1L;
    private long lastWallClockTime = -1L;

    private volatile boolean hasSmoothed = false;
    private volatile double smoothedCpuPercent = 0.0D;

    public RegionizedCpuBar(final RegionizedWorldData worldData) {
        this.worldData = worldData;
        this.canTick = Config.INSTANCE.enableCpuBar;
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

        this.poll();

        final double cpuPercent = this.cachedCpuPercent;
        final int procs = this.cachedProcs;
        final double cpuTempC = this.cachedCpuTempC;
        final double maxPercent = Math.max(1.0D, procs * 100.0D);
        final double ratio = Math.min(1.0D, Math.max(0.0D, cpuPercent / maxPercent));
        final Component display = this.buildComponent(cpuPercent, procs, cpuTempC, ratio);

        for (final ServerPlayer localPlayer : this.worldData.getLocalPlayers()) {
            final DisplayManager manager = localPlayer.canvas$cpuBarDisplay;
            manager.setDisplay(display);
            manager.updateBarColorAndProgress(ratio);
            manager.tick();
        }
    }

    private void poll() {
        double raw = this.computeCpuPercentRaw();
        final int procs = this.getAvailableProcessors();
        final double maxPercent = Math.max(100.0D, procs * 100.0D);

        if (raw < 0.0D) {
            raw = 0.0D;
        }
        if (raw > maxPercent) {
            raw = maxPercent;
        }

        if (this.hasSmoothed) {
            final double maxDelta = 0.50D * maxPercent;
            final double lower = this.smoothedCpuPercent - maxDelta;
            final double upper = this.smoothedCpuPercent + maxDelta;
            if (raw < lower) {
                raw = lower;
            } else if (raw > upper) {
                raw = upper;
            }
        }

        final double smoothed;
        if (!this.hasSmoothed) {
            smoothed = raw;
            this.hasSmoothed = true;
        } else {
            smoothed = (EMA_ALPHA * raw) + ((1.0D - EMA_ALPHA) * this.smoothedCpuPercent);
        }

        this.smoothedCpuPercent = smoothed;
        this.cachedCpuPercent = smoothed;
        this.cachedProcs = procs;
        this.cachedCpuTempC = this.readCpuTemperatureCelsius();
    }

    private @NonNull Component buildComponent(
        final double cpuPercent,
        final int procs,
        final double cpuTempC,
        final double ratio
    ) {
        final String configuredFormat = Config.INSTANCE.cpuBarFormat;
        final String effectiveFormat = configuredFormat == null || configuredFormat.isBlank()
            ? DEFAULT_FORMAT
            : normalizeFormat(configuredFormat);
        final double maxPercent = Math.max(1.0D, procs * 100.0D);

        return MINI_MESSAGE.deserialize(
            effectiveFormat,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("used", this.getUsedComponent(cpuPercent, maxPercent)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("max", this.getMaxComponent(maxPercent, cpuPercent)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("util", this.getUtilComponent(ratio)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("temp", this.getTempComponent(cpuTempC))
        );
    }

    private static @NonNull String normalizeFormat(final @NonNull String input) {
        return input
            .replace("%used%", "<used>")
            .replace("%max%", "<max>")
            .replace("%util%", "<util>")
            .replace("%temp%", "<temp>");
    }

    private Component gradient(final String tpl, final String value) {
        final String inner = value.replace(",", "<gray>,</gray>");
        return MINI_MESSAGE.deserialize(tpl.replace("<text>", inner));
    }

    private Component gradientForUtil(final double ratio, final String value) {
        final String tpl = ratio <= 0.50D ? GRADIENT_GOOD : (ratio <= 0.70D ? GRADIENT_MEDIUM : GRADIENT_LOW);
        return this.gradient(tpl, value);
    }

    private Component getUsedComponent(final double cpuPercent, final double maxPercent) {
        if (cpuPercent <= 0.0D) {
            return MINI_MESSAGE.deserialize("<gray>-");
        }
        final double ratio = Math.min(1.0D, Math.max(0.0D, cpuPercent / maxPercent));
        return this.gradientForUtil(ratio, String.format(Locale.ROOT, "%.2f", cpuPercent));
    }

    private Component getMaxComponent(final double maxPercent, final double cpuPercent) {
        if (maxPercent <= 0.0D) {
            return MINI_MESSAGE.deserialize("<gray>-");
        }
        final double ratio = Math.min(1.0D, Math.max(0.0D, cpuPercent / maxPercent));
        return this.gradientForUtil(ratio, String.format(Locale.ROOT, "%.2f", maxPercent));
    }

    private Component getUtilComponent(final double ratio) {
        if (ratio <= 0.0D) {
            return MINI_MESSAGE.deserialize("<gray>-");
        }
        final String text = String.format(Locale.ROOT, "%.0f%%", ratio * 100.0D);
        return this.gradientForUtil(ratio, text);
    }

    private Component getTempComponent(final double cpuTempC) {
        if (!Double.isFinite(cpuTempC) || cpuTempC <= 0.0D) {
            return MINI_MESSAGE.deserialize("<gray>(n/a)");
        }

        final String text = String.format(Locale.ROOT, "%.0f°", cpuTempC);
        final String tpl = cpuTempC < 70.0D ? GRADIENT_GOOD : (cpuTempC >= 85.0D ? GRADIENT_LOW : GRADIENT_MEDIUM);
        return Component.text()
            .append(MINI_MESSAGE.deserialize("<gray>(</gray>"))
            .append(this.gradient(tpl, text))
            .append(MINI_MESSAGE.deserialize("<gray>)</gray>"))
            .build();
    }

    private synchronized double computeCpuPercentRaw() {
        try {
            final java.lang.management.OperatingSystemMXBean baseOs = ManagementFactory.getOperatingSystemMXBean();
            final int procs = baseOs.getAvailableProcessors();

            if (baseOs instanceof com.sun.management.OperatingSystemMXBean os) {
                final long procCpuTime = os.getProcessCpuTime();
                final long now = System.nanoTime();
                if (procCpuTime >= 0L) {
                    if (this.lastProcCpuTime < 0L || this.lastWallClockTime < 0L) {
                        this.lastProcCpuTime = procCpuTime;
                        this.lastWallClockTime = now;
                        return this.cachedCpuPercent;
                    }

                    final long cpuDiff = procCpuTime - this.lastProcCpuTime;
                    final long wallDiff = now - this.lastWallClockTime;
                    this.lastProcCpuTime = procCpuTime;
                    this.lastWallClockTime = now;

                    if (wallDiff > 0L && cpuDiff >= 0L) {
                        final double coresUsed = (double) cpuDiff / (double) wallDiff;
                        return coresUsed * 100.0D;
                    }
                }

                final double load = os.getProcessCpuLoad();
                if (Double.isFinite(load) && load >= 0.0D) {
                    return load * procs * 100.0D;
                }
            }

            final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            if (!threadBean.isThreadCpuTimeSupported()) {
                return this.cachedCpuPercent;
            }
            if (!threadBean.isThreadCpuTimeEnabled()) {
                try {
                    threadBean.setThreadCpuTimeEnabled(true);
                } catch (final SecurityException ignored) {
                }
            }

            long currentCpuTime = 0L;
            final long[] threadIds = threadBean.getAllThreadIds();
            for (final long id : threadIds) {
                final long time = threadBean.getThreadCpuTime(id);
                if (time != -1L) {
                    currentCpuTime += time;
                }
            }

            final long currentSystemTime = System.nanoTime();

            if (this.firstMeasurement) {
                this.lastCpuTime = currentCpuTime;
                this.lastSystemTime = currentSystemTime;
                this.firstMeasurement = false;
                return this.cachedCpuPercent;
            }

            final long cpuTimeDiff = currentCpuTime - this.lastCpuTime;
            final long systemTimeDiff = currentSystemTime - this.lastSystemTime;
            if (systemTimeDiff <= 0L) {
                return this.cachedCpuPercent;
            }

            final double cpuUsage = (double) cpuTimeDiff / (double) systemTimeDiff;
            final double percent = cpuUsage * 100.0D;

            this.lastCpuTime = currentCpuTime;
            this.lastSystemTime = currentSystemTime;
            return percent;
        } catch (final Throwable ignored) {
            return this.cachedCpuPercent;
        }
    }

    private int getAvailableProcessors() {
        try {
            return ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();
        } catch (final Throwable ignored) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    private double readCpuTemperatureCelsius() {
        if (this.cpuTempPollingDisabled) {
            return Double.NaN;
        }

        try {
            Object sensors = this.oshiSensors;
            if (!this.oshiSensorsInitAttempted) {
                synchronized (this) {
                    if (!this.oshiSensorsInitAttempted) {
                        this.oshiSensors = this.initOshiSensorsReflective();
                        this.oshiSensorsInitAttempted = true;
                    }
                }
                sensors = this.oshiSensors;
            }

            if (sensors == null) {
                return Double.NaN;
            }

            final Object value = sensors.getClass().getMethod("getCpuTemperature").invoke(sensors);
            if (value instanceof Number number) {
                final double temp = number.doubleValue();
                if (Double.isFinite(temp) && temp > 0.0D) {
                    return temp;
                }

                if (WINDOWS_OS) {
                    this.cpuTempPollingDisabled = true;
                }
            }
        } catch (final Throwable ignored) {
            this.cpuTempPollingDisabled = true;
        }

        return Double.NaN;
    }

    private static boolean isWindowsOs() {
        final String os = System.getProperty("os.name", "");
        return os.regionMatches(true, 0, "Windows", 0, "Windows".length());
    }

    private Object initOshiSensorsReflective() {
        try {
            final Class<?> systemInfoClass = Class.forName("oshi.SystemInfo");
            final Object systemInfo = systemInfoClass.getDeclaredConstructor().newInstance();
            final Object hardware = systemInfoClass.getMethod("getHardware").invoke(systemInfo);
            return hardware.getClass().getMethod("getSensors").invoke(hardware);
        } catch (final Throwable ignored) {
            return null;
        }
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
                public final BossBar cpuBar =
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
                                this.cpuBar.addViewer(bukkitEntity);
                            } else {
                                this.cpuBar.removeViewer(bukkitEntity);
                            }
                        } else {
                            this.cpuBar.removeViewer(bukkitEntity);
                        }

                        this.dirty = false;
                    }

                    if (!this.enabled) {
                        return;
                    }

                    switch (this.placement) {
                        case BOSS_BAR -> this.cpuBar.name(this.display);
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
                public void updateBarColorAndProgress(final double ratio) {
                    final double progress = Math.min(1.0D, Math.max(0.0D, ratio));
                    this.cpuBar.color(barColorFromRatio(progress)).progress((float) progress);
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

        default void updateBarColorAndProgress(final double ratio) {
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
            return Config.INSTANCE.enableCpuBar && this.enabled;
        }
    }

    private static BossBar.Color barColorFromRatio(final double ratio) {
        if (ratio <= 0.50D) {
            return BossBar.Color.GREEN;
        }
        if (ratio <= 0.70D) {
            return BossBar.Color.YELLOW;
        }
        return BossBar.Color.RED;
    }
}

