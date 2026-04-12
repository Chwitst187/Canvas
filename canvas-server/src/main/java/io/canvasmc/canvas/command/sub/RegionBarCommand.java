package io.canvasmc.canvas.command.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.canvasmc.canvas.Config;
import io.canvasmc.canvas.command.Command;
import io.canvasmc.canvas.world.RegionizedRegionBar;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@NullMarked
public class RegionBarCommand implements Command {

    private static void toggleRegionBar(final CommandSourceStack source, final ServerPlayer player) {
        final RegionizedRegionBar.DisplayManager display = player.canvas$regionBarDisplay;
        final RegionizedRegionBar.Entry current = display.serializeDisplay();
        final RegionizedRegionBar.Entry updated = new RegionizedRegionBar.Entry(!current.enabled(), current.placement());
        display.updateFromEntry(updated);

        final String message = (updated.enabled() ? "Enabled" : "Disabled") +
            " region bar for " + player.getName().getString();
        source.sendSuccess(() -> Component.literal(message), true);
    }

    private static void setRegionBarPlacement(
        final CommandSourceStack source,
        final ServerPlayer player,
        final RegionizedRegionBar.Placement newPlacement,
        final String argName
    ) {
        final RegionizedRegionBar.DisplayManager display = player.canvas$regionBarDisplay;
        final RegionizedRegionBar.Entry current = display.serializeDisplay();
        final RegionizedRegionBar.Entry updated = new RegionizedRegionBar.Entry(current.enabled(), newPlacement);
        display.updateFromEntry(updated);

        final String message = "Set region bar placement for " + player.getName().getString() + " to " + argName;
        source.sendSuccess(() -> Component.literal(message), true);
    }

    @Override
    public String getName() {
        return "regionbar";
    }

    @Override
    public @Nullable String getDescription() {
        return "Toggles or modifies the region stats display bar for one or more players.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base) {
        if (!Config.INSTANCE.enableRegionBar) {
            return base.executes(ctx -> {
                ctx.getSource().sendFailure(Component.literal("Region bar is disabled in the config."));
                return 0;
            });
        }

        return base
            .executes(ctx -> {
                final CommandSourceStack source = ctx.getSource();
                final ServerPlayer player = source.getPlayer();

                if (player == null || !source.isPlayer()) {
                    source.sendFailure(Component.literal("This command must be run by a valid player entity."));
                    return 0;
                }

                toggleRegionBar(source, player);
                return 1;
            })

            .then(literal("toggle")
                .executes(ctx -> {
                    final CommandSourceStack source = ctx.getSource();
                    final ServerPlayer player = source.getPlayer();

                    if (player == null || !source.isPlayer()) {
                        source.sendFailure(Component.literal("This command must be run by a valid player entity."));
                        return 0;
                    }

                    toggleRegionBar(source, player);
                    return 1;
                })
                .then(argument("players", EntityArgument.players())
                    .executes(ctx -> {
                        final Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
                        for (final ServerPlayer player : players) {
                            toggleRegionBar(ctx.getSource(), player);
                        }
                        return 1;
                    })
                )
            )

            .then(argument("players", EntityArgument.players())
                .executes(ctx -> {
                    final Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
                    for (final ServerPlayer player : players) {
                        toggleRegionBar(ctx.getSource(), player);
                    }
                    return 1;
                })

                .then(argument("placement", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("action_bar");
                        builder.suggest("boss_bar");
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        final CommandSourceStack source = ctx.getSource();
                        final Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
                        final String placementArg = StringArgumentType.getString(ctx, "placement").toLowerCase();

                        final RegionizedRegionBar.Placement newPlacement;
                        switch (placementArg) {
                            case "action_bar" -> newPlacement = RegionizedRegionBar.Placement.ACTION_BAR;
                            case "boss_bar" -> newPlacement = RegionizedRegionBar.Placement.BOSS_BAR;
                            default -> {
                                source.sendFailure(Component.literal("Invalid placement: must be 'action_bar' or 'boss_bar'."));
                                return 0;
                            }
                        }

                        for (final ServerPlayer player : players) {
                            setRegionBarPlacement(source, player, newPlacement, placementArg);
                        }

                        return 1;
                    })
                )
            );
    }
}

