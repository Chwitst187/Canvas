package io.papermc.paper.command;

import com.destroystokyo.paper.util.VersionFetcher;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class PaperVersionCommand {
    public static final String DESCRIPTION = "Gets the version of this server including any plugins in use";

    private static final Component NOT_RUNNING = Component.text()
        .append(Component.text("This server is not running any plugin by that name."))
        .appendNewline()
        .append(Component.text("Use /plugins to get a list of plugins.").clickEvent(ClickEvent.suggestCommand("/plugins")))
        .build();
    private static final JoinConfiguration PLAYER_JOIN_CONFIGURATION = JoinConfiguration.separators(
        Component.text(", ", NamedTextColor.WHITE),
        Component.text(", and ", NamedTextColor.WHITE)
    );
    private static final Component FAILED_TO_FETCH = Component.text("Could not fetch version information!", NamedTextColor.RED);
    private static final Component FETCHING = Component.text("Checking version, please wait...", NamedTextColor.WHITE, TextDecoration.ITALIC);

    private static final Component PREFIX = Component.empty()
        .append(Component.text("C", TextColor.color(0xa907ff)).decorate(TextDecoration.BOLD))
        .append(Component.text("A", TextColor.color(0x9f09f2)).decorate(TextDecoration.BOLD))
        .append(Component.text("N", TextColor.color(0x940ae5)).decorate(TextDecoration.BOLD))
        .append(Component.text("V", TextColor.color(0x8a0cd8)).decorate(TextDecoration.BOLD))
        .append(Component.text("A", TextColor.color(0x800dcb)).decorate(TextDecoration.BOLD))
        .append(Component.text("S", TextColor.color(0x760fbe)).decorate(TextDecoration.BOLD))
        .append(Component.text("M", TextColor.color(0x6b10b1)).decorate(TextDecoration.BOLD))
        .append(Component.text("C", TextColor.color(0x6112a4)).decorate(TextDecoration.BOLD))
        .append(Component.text(" "))
        .append(Component.text("»", NamedTextColor.GRAY))
        .append(Component.text(" "));

    private final VersionFetcher versionFetcher = CraftMagicNumbers.INSTANCE.getVersionFetcher();
    private CompletableFuture<ComputedVersion> computedVersion = CompletableFuture.completedFuture(new ComputedVersion(Component.empty(), -1)); // Precompute-- someday move that stuff out of bukkit

    public static LiteralCommandNode<CommandSourceStack> create() {
        final PaperVersionCommand command = new PaperVersionCommand();

        return Commands.literal("version")
            .requires(source -> source.getSender().hasPermission("bukkit.command.version"))
            .then(Commands.argument("plugin", StringArgumentType.word())
                .suggests(command::suggestPlugins)
                .executes(command::pluginVersion))
            .executes(command::serverVersion)
            .build();
    }

    private int pluginVersion(final CommandContext<CommandSourceStack> context) {
        final CommandSender sender = context.getSource().getSender();
        final String pluginName = context.getArgument("plugin", String.class).toLowerCase(Locale.ROOT);

        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null) {
            plugin = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(checkPlugin -> checkPlugin.getName().toLowerCase(Locale.ROOT).contains(pluginName))
                .findAny()
                .orElse(null);
        }

        if (plugin != null) {
            this.sendPluginInfo(plugin, sender);
        } else {
            sender.sendMessage(withPrefixPerLine(NOT_RUNNING));
        }

        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestPlugins(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            final String name = plugin.getName();
            if (StringUtil.startsWithIgnoreCase(name, builder.getRemainingLowerCase())) {
                builder.suggest(name);
            }
        }

        return CompletableFuture.completedFuture(builder.build());
    }

    private void sendPluginInfo(final Plugin plugin, final CommandSender sender) {
        final PluginMeta meta = plugin.getPluginMeta();

        final TextComponent.Builder builder = Component.text()
            .append(Component.text(meta.getName()))
            .append(Component.text(" version "))
            .append(Component.text(meta.getVersion(), NamedTextColor.GREEN)
                .hoverEvent(Component.translatable("chat.copy.click"))
                .clickEvent(ClickEvent.copyToClipboard(meta.getVersion()))
            );

        if (meta.getDescription() != null) {
            builder
                .appendNewline()
                .append(Component.text(meta.getDescription()));
        }

        if (meta.getWebsite() != null) {
            Component websiteComponent = Component.text(meta.getWebsite(), NamedTextColor.GREEN).clickEvent(ClickEvent.openUrl(meta.getWebsite()));
            builder.appendNewline().append(Component.text("Website: ").append(websiteComponent));
        }

        if (!meta.getAuthors().isEmpty()) {
            String prefix = meta.getAuthors().size() == 1 ? "Author: " : "Authors: ";
            builder.appendNewline().append(Component.text(prefix).append(formatNameList(meta.getAuthors())));
        }

        if (!meta.getContributors().isEmpty()) {
            builder.appendNewline().append(Component.text("Contributors: ").append(formatNameList(meta.getContributors())));
        }
        sender.sendMessage(withPrefixPerLine(builder.build()));
    }

    private static Component formatNameList(final List<String> names) {
        return Component.join(PLAYER_JOIN_CONFIGURATION, names.stream().map(Component::text).toList()).color(NamedTextColor.GREEN);
    }

    private int serverVersion(CommandContext<CommandSourceStack> context) {
        sendVersion(context.getSource().getSender());
        return Command.SINGLE_SUCCESS;
    }

    private void sendVersion(final CommandSender sender) {
        final CompletableFuture<ComputedVersion> version = getVersionOrFetch();
        if (!version.isDone()) {
            sender.sendMessage(withPrefixPerLine(FETCHING));
        }

        version.whenComplete((computedVersion, throwable) -> {
            if (computedVersion != null) {
                sender.sendMessage(withPrefixPerLine(computedVersion.message));
            } else if (throwable != null) {
                sender.sendMessage(withPrefixPerLine(FAILED_TO_FETCH));
                MinecraftServer.LOGGER.warn("Could not fetch version information!", throwable);
            }
        });
    }

    private CompletableFuture<ComputedVersion> getVersionOrFetch() {
        if (!this.computedVersion.isDone()) {
            return this.computedVersion;
        }

        if (this.computedVersion.isCompletedExceptionally() || System.currentTimeMillis() - this.computedVersion.resultNow().computedTime() > this.versionFetcher.getCacheTime()) {
            this.computedVersion = this.fetchVersionMessage();
        }

        return this.computedVersion;
    }

    private CompletableFuture<ComputedVersion> fetchVersionMessage() {
       return CompletableFuture.supplyAsync(() -> {
           final Component message = Component.textOfChildren(
               colorizeVersionMessage(Bukkit.getVersionMessage()),
               Component.newline(),
               this.versionFetcher.getVersionMessage()
           );

           return new ComputedVersion(
               message.hoverEvent(Component.translatable("chat.copy.click", NamedTextColor.WHITE))
                   .clickEvent(ClickEvent.copyToClipboard(PlainTextComponentSerializer.plainText().serialize(message))),
               System.currentTimeMillis()
           );
       });
    }

    private static Component colorizeVersionMessage(final String text) {
        final int canvasMcIndex = text.indexOf("CanvasMC");
        if (canvasMcIndex != -1) {
            final String before = text.substring(0, canvasMcIndex);
            final String after = text.substring(canvasMcIndex + 8);

            return Component.textOfChildren(
                Component.text(before, NamedTextColor.WHITE),
                Component.text("C", TextColor.color(0xa907ff)).decorate(TextDecoration.BOLD),
                Component.text("A", TextColor.color(0x9f09f2)).decorate(TextDecoration.BOLD),
                Component.text("N", TextColor.color(0x940ae5)).decorate(TextDecoration.BOLD),
                Component.text("V", TextColor.color(0x8a0cd8)).decorate(TextDecoration.BOLD),
                Component.text("A", TextColor.color(0x800dcb)).decorate(TextDecoration.BOLD),
                Component.text("S", TextColor.color(0x760fbe)).decorate(TextDecoration.BOLD),
                Component.text("M", TextColor.color(0x6b10b1)).decorate(TextDecoration.BOLD),
                Component.text("C", TextColor.color(0x6112a4)).decorate(TextDecoration.BOLD),
                Component.text(after, NamedTextColor.WHITE)
            );
        }

        final int canvasIndex = text.indexOf("Canvas");
        if (canvasIndex != -1) {
            final String before = text.substring(0, canvasIndex);
            final String after = text.substring(canvasIndex + 6);

            return Component.textOfChildren(
                Component.text(before, NamedTextColor.WHITE),
                Component.text("C", TextColor.color(0xa907ff)).decorate(TextDecoration.BOLD),
                Component.text("A", TextColor.color(0x9f09f2)).decorate(TextDecoration.BOLD),
                Component.text("N", TextColor.color(0x940ae5)).decorate(TextDecoration.BOLD),
                Component.text("V", TextColor.color(0x8a0cd8)).decorate(TextDecoration.BOLD),
                Component.text("A", TextColor.color(0x800dcb)).decorate(TextDecoration.BOLD),
                Component.text("S", TextColor.color(0x760fbe)).decorate(TextDecoration.BOLD),
                Component.text(after, NamedTextColor.WHITE)
            );
        }

        return Component.text(text, NamedTextColor.WHITE);
    }

    private static Component withPrefixPerLine(final Component message) {
        final String plain = PlainTextComponentSerializer.plainText().serialize(message);
        final String[] lines = plain.split("\\R", -1);
        Component result = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            result = result.append(Component.textOfChildren(PREFIX, colorizeVersionMessage(lines[i])));
            if (i < lines.length - 1) {
                result = result.append(Component.newline());
            }
        }
        return result;
    }

    record ComputedVersion(Component message, long computedTime) {

    }
}
