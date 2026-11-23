package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import com.minecraftcitiesnetwork.vaultstorage.database.OwnerResult;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultCaptureMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.VaultUIContext;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultAction;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@code /vault} and its subcommands. Holds no state. */
@SuppressWarnings("UnstableApiUsage")
public final class VaultCommand {

    public static final String DESCRIPTION = "Vault storage tools";

    private static final String VAULT_ARGUMENT = "vault";
    private static final String PLAYER_ARGUMENT = "player";
    private static final String ACTION_ARGUMENT = "action";

    private VaultCommand() {
    }

    /** What each branch runs. The tree is built from this, so its shape can be tested without a server. */
    interface Handlers {
        /** @param ownerName whose vaults to list, or null for the sender's own */
        int menu(@NotNull CommandContext<CommandSourceStack> context, @Nullable String ownerName);

        int capture(@NotNull CommandContext<CommandSourceStack> context);

        int open(@NotNull CommandContext<CommandSourceStack> context, @NotNull String actionWord);

        int admin(@NotNull CommandContext<CommandSourceStack> context);

        int transfer(@NotNull CommandContext<CommandSourceStack> context);

        int reload(@NotNull CommandContext<CommandSourceStack> context);

        @NotNull CompletableFuture<Suggestions> onlinePlayers(@NotNull SuggestionsBuilder builder);
    }

    /** Takes the plugin and not each service: the menus opened here are built from it. */
    public static @NotNull LiteralCommandNode<CommandSourceStack> create(@NotNull VaultStoragePlugin plugin) {
        return tree(new Handlers() {
            @Override
            public int menu(@NotNull CommandContext<CommandSourceStack> context, @Nullable String ownerName) {
                return VaultCommand.menu(plugin, context, ownerName);
            }

            @Override
            public int capture(@NotNull CommandContext<CommandSourceStack> context) {
                return VaultCommand.capture(plugin, context);
            }

            @Override
            public int open(@NotNull CommandContext<CommandSourceStack> context, @NotNull String actionWord) {
                return VaultCommand.open(plugin, context, actionWord);
            }

            @Override
            public int admin(@NotNull CommandContext<CommandSourceStack> context) {
                return VaultCommand.admin(plugin, context);
            }

            @Override
            public int transfer(@NotNull CommandContext<CommandSourceStack> context) {
                return VaultCommand.transfer(plugin, context);
            }

            @Override
            public int reload(@NotNull CommandContext<CommandSourceStack> context) {
                return VaultCommand.reload(plugin, context);
            }

            @Override
            public @NotNull CompletableFuture<Suggestions> onlinePlayers(@NotNull SuggestionsBuilder builder) {
                return VaultCommand.onlinePlayers(plugin, builder);
            }
        }, ArgumentTypes.uuid());
    }

    /** @param vaultId how a vault's id is read; Paper's own needs a running server */
    static @NotNull LiteralCommandNode<CommandSourceStack> tree(@NotNull Handlers handlers,
                                                                 @NotNull ArgumentType<UUID> vaultId) {
        return Commands.literal("vault")
                .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_VAULT))
                .executes(context -> handlers.menu(context, null))
                .then(Commands.literal("menu")
                        .executes(context -> handlers.menu(context, null))
                        .then(Commands.argument(PLAYER_ARGUMENT, StringArgumentType.word())
                                .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_VAULT_OTHERS))
                                .suggests((context, builder) -> handlers.onlinePlayers(builder))
                                .executes(context -> handlers.menu(context,
                                        StringArgumentType.getString(context, PLAYER_ARGUMENT)))))
                .then(Commands.literal("capture")
                        .requires(source -> source.getSender().hasPermission(Permissions.ACTION_CAPTURE))
                        .executes(handlers::capture))
                .then(Commands.literal("open")
                        .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_OPEN))
                        .then(Commands.argument(VAULT_ARGUMENT, vaultId)
                                .executes(context -> handlers.open(context, "view"))
                                .then(Commands.argument(ACTION_ARGUMENT, StringArgumentType.word())
                                        .suggests((context, builder) -> actions(context.getSource().getSender(), builder))
                                        .executes(context -> handlers.open(context,
                                                StringArgumentType.getString(context, ACTION_ARGUMENT))))))
                .then(Commands.literal("admin")
                        .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_ADMIN))
                        .executes(handlers::admin))
                .then(Commands.literal("transfer")
                        .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_TRANSFER))
                        .then(Commands.argument(VAULT_ARGUMENT, vaultId)
                                .then(Commands.argument(PLAYER_ARGUMENT, StringArgumentType.word())
                                        .suggests((context, builder) -> handlers.onlinePlayers(builder))
                                        .executes(handlers::transfer))))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_RELOAD))
                        .executes(handlers::reload))
                .build();
    }

    private static int menu(@NotNull VaultStoragePlugin plugin, @NotNull CommandContext<CommandSourceStack> context,
                            @Nullable String ownerName) {
        Player player = playerOrTell(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (ownerName == null) {
            new VaultCaptureMenu(plugin, player).open();
            return Command.SINGLE_SUCCESS;
        }
        MessageContainer messages = plugin.messages();
        player.sendMessage(messages.messageFor(MessageKeys.COMMAND_MENU_RESOLVING));
        new UniqueIdentifierResolver(plugin.names()).resolveUuid(ownerName)
                .thenAcceptAsync(owner -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!UniqueIdentifierResolver.isValidUUID(owner)) {
                        player.sendMessage(messages.messageFor(MessageKeys.COMMAND_MENU_PLAYER_NOT_FOUND));
                    } else if (owner.equals(player.getUniqueId())) {
                        new VaultCaptureMenu(plugin, player).open();
                    } else {
                        new VaultCaptureMenu(plugin, player, VaultUIContext.adminFiltered(player.getUniqueId(), owner)).open();
                    }
                }, plugin.mainThread())
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not resolve " + ownerName + ".", failure);
                    player.sendMessage(messages.messageFor(MessageKeys.COMMAND_MENU_PLAYER_NOT_FOUND));
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    private static int capture(@NotNull VaultStoragePlugin plugin, @NotNull CommandContext<CommandSourceStack> context) {
        Player player = playerOrTell(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        plugin.captureService().startCaptureSession(player);
        player.sendMessage(plugin.messages().messageFor(MessageKeys.COMMAND_CAPTURE_STARTED));
        return Command.SINGLE_SUCCESS;
    }

    private static int admin(@NotNull VaultStoragePlugin plugin, @NotNull CommandContext<CommandSourceStack> context) {
        Player player = playerOrTell(plugin, context);
        if (player != null) {
            new VaultCaptureMenu(plugin, player, VaultUIContext.admin(player.getUniqueId())).open();
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int open(@NotNull VaultStoragePlugin plugin, @NotNull CommandContext<CommandSourceStack> context,
                            @NotNull String actionWord) {
        Player player = playerOrTell(plugin, context);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        MessageContainer messages = plugin.messages();
        Optional<VaultAction> action = VaultActionArgument.parse(actionWord);
        if (action.isEmpty()) {
            player.sendMessage(messages.messageFor(MessageKeys.COMMAND_OPEN_UNKNOWN_ACTION));
            return Command.SINGLE_SUCCESS;
        }
        if (!player.hasPermission(action.get().permission())) {
            player.sendMessage(messages.messageFor(MessageKeys.COMMAND_OPEN_NO_PERMISSION));
            return Command.SINGLE_SUCCESS;
        }
        UUID vault = context.getArgument(VAULT_ARGUMENT, UUID.class);
        // Read here, on the main thread: permissions are not safe to read off it.
        boolean mayOpenAny = player.hasPermission(Permissions.COMMAND_OPEN_OTHERS);
        UUID opener = player.getUniqueId();
        CompletableFuture
                .supplyAsync(() -> plugin.repository().find(vault) instanceof FindResult.Found found
                        ? found.vault().vault().ownerUuid() : null, plugin.databaseExecutor())
                .thenApply(owner -> OpenOutcome.decide(owner, opener, mayOpenAny))
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not open vault " + vault + ".", failure);
                    return new OpenOutcome.Failed();
                })
                .thenAcceptAsync(outcome -> {
                    switch (outcome) {
                        case OpenOutcome.Allowed allowed ->
                                plugin.inventoryService().openVirtualInventory(player, vault, action.get());
                        case OpenOutcome.NoSuchVault missing ->
                                player.sendMessage(messages.messageFor(MessageKeys.INVENTORY_NOT_FOUND));
                        case OpenOutcome.NotYours notYours ->
                                player.sendMessage(messages.messageFor(MessageKeys.COMMAND_OPEN_NO_ACCESS));
                        case OpenOutcome.Failed failed ->
                                player.sendMessage(messages.messageFor(MessageKeys.INVENTORY_OPEN_FAILED));
                    }
                }, plugin.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    private static int transfer(@NotNull VaultStoragePlugin plugin, @NotNull CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        MessageContainer messages = plugin.messages();
        UUID vault = context.getArgument(VAULT_ARGUMENT, UUID.class);
        String newOwnerName = StringArgumentType.getString(context, PLAYER_ARGUMENT);
        sender.sendMessage(messages.messageFor(MessageKeys.COMMAND_TRANSFER_RESOLVING));
        new UniqueIdentifierResolver(plugin.names()).resolveUuid(newOwnerName)
                .thenCompose(newOwner -> {
                    if (!UniqueIdentifierResolver.isValidUUID(newOwner)) {
                        return CompletableFuture.<TransferOutcome>completedFuture(
                                new TransferOutcome.UnknownPlayer(newOwnerName));
                    }
                    return CompletableFuture.<TransferOutcome>supplyAsync(
                            () -> switch (plugin.repository().setOwner(vault, newOwner)) {
                                case OwnerResult.NoSuchVault missing -> new TransferOutcome.NoSuchVault(vault);
                                case OwnerResult.InUse held -> new TransferOutcome.InUse(vault);
                                case OwnerResult.Changed changed ->
                                        new TransferOutcome.Transferred(vault, changed.previous(), newOwner);
                            }, plugin.databaseExecutor());
                })
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not transfer vault " + vault + ".", failure);
                    return new TransferOutcome.Failed();
                })
                .thenAcceptAsync(outcome -> sender.sendMessage(transferReply(plugin, sender, outcome)),
                        plugin.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    private static @NotNull Component transferReply(@NotNull VaultStoragePlugin plugin, @NotNull CommandSender sender,
                                                    @NotNull TransferOutcome outcome) {
        MessageContainer messages = plugin.messages();
        return switch (outcome) {
            case TransferOutcome.Transferred done -> {
                plugin.getLogger().info(sender.getName() + " transferred vault " + done.vault() + " from "
                        + done.from() + " to " + done.to());
                yield messages.messageFor(MessageKeys.COMMAND_TRANSFER_DONE,
                        MessageContainer.value("vault", done.vault().toString()),
                        MessageContainer.value("from", done.from().toString()),
                        MessageContainer.value("to", done.to().toString()));
            }
            case TransferOutcome.NoSuchVault missing -> messages.messageFor(MessageKeys.COMMAND_TRANSFER_NOT_FOUND,
                    MessageContainer.value("vault", missing.vault().toString()));
            case TransferOutcome.InUse held -> messages.messageFor(MessageKeys.COMMAND_TRANSFER_IN_USE,
                    MessageContainer.value("vault", held.vault().toString()));
            case TransferOutcome.UnknownPlayer unknown -> messages.messageFor(
                    MessageKeys.COMMAND_TRANSFER_UNKNOWN_PLAYER, MessageContainer.value("player", unknown.name()));
            case TransferOutcome.Failed failed -> messages.messageFor(MessageKeys.COMMAND_TRANSFER_FAILED);
        };
    }

    private static int reload(@NotNull VaultStoragePlugin plugin, @NotNull CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        // The cause of a failure is in the log; the sender is told only which way it went.
        plugin.reload().handleAsync((done, failure) -> {
            sender.sendMessage(plugin.messages().messageFor(failure == null
                    ? MessageKeys.COMMAND_RELOAD_DONE : MessageKeys.COMMAND_RELOAD_FAILED));
            return null;
        }, plugin.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    /** The sender as a player; anyone else is told the command is for players, and null comes back. */
    private static @Nullable Player playerOrTell(@NotNull VaultStoragePlugin plugin,
                                                 @NotNull CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(plugin.messages().messageFor(MessageKeys.COMMAND_PLAYERS_ONLY));
        return null;
    }

    private static @NotNull CompletableFuture<Suggestions> onlinePlayers(@NotNull VaultStoragePlugin plugin,
                                                                         @NotNull SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(online.getName());
            }
        }
        return builder.buildFuture();
    }

    /** Only the actions the sender may use. */
    private static @NotNull CompletableFuture<Suggestions> actions(@NotNull CommandSender sender,
                                                                   @NotNull SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        for (VaultAction action : VaultAction.values()) {
            String word = action.name().toLowerCase(Locale.ROOT);
            if (word.startsWith(typed) && sender.hasPermission(action.permission())) {
                builder.suggest(word);
            }
        }
        return builder.buildFuture();
    }
}
