package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The shape of {@code /vault}: which branch a line reaches, and which permission each needs. */
class VaultCommandTreeTest {

    private static final String VAULT = "00000000-0000-0000-0000-000000000001";

    /** Reads a UUID the way Paper's argument does, without a server. */
    private static final ArgumentType<UUID> UUID_ARGUMENT = new ArgumentType<>() {
        @Override
        public UUID parse(StringReader reader) throws CommandSyntaxException {
            String word = reader.readUnquotedString();
            try {
                return UUID.fromString(word);
            } catch (IllegalArgumentException ex) {
                throw new SimpleCommandExceptionType(() -> "Invalid UUID").createWithContext(reader);
            }
        }
    };

    /** Records which branch ran, and with what. */
    private static final class RecordingHandlers implements VaultCommand.Handlers {
        final List<String> ran = new ArrayList<>();

        @Override
        public int menu(@NotNull CommandContext<CommandSourceStack> context, @Nullable String ownerName) {
            this.ran.add("menu " + ownerName);
            return 1;
        }

        @Override
        public int capture(@NotNull CommandContext<CommandSourceStack> context) {
            this.ran.add("capture");
            return 1;
        }

        @Override
        public int open(@NotNull CommandContext<CommandSourceStack> context, @NotNull String actionWord) {
            this.ran.add("open " + context.getArgument("vault", UUID.class) + " " + actionWord);
            return 1;
        }

        @Override
        public int admin(@NotNull CommandContext<CommandSourceStack> context) {
            this.ran.add("admin");
            return 1;
        }

        @Override
        public int transfer(@NotNull CommandContext<CommandSourceStack> context) {
            this.ran.add("transfer " + context.getArgument("vault", UUID.class) + " "
                    + context.getArgument("player", String.class));
            return 1;
        }

        @Override
        public int reload(@NotNull CommandContext<CommandSourceStack> context) {
            this.ran.add("reload");
            return 1;
        }

        @Override
        public @NotNull CompletableFuture<Suggestions> onlinePlayers(@NotNull SuggestionsBuilder builder) {
            return builder.buildFuture();
        }
    }

    /** A sender who holds exactly these permissions. */
    private static CommandSourceStack senderWith(String... permissions) {
        Set<String> held = Set.of(permissions);
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("hasPermission") && arguments[0] instanceof String node) {
                        return held.contains(node);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(),
                new Class<?>[] {CommandSourceStack.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getSender")) {
                        return sender;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private final RecordingHandlers handlers = new RecordingHandlers();
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();

    VaultCommandTreeTest() {
        this.dispatcher.getRoot().addChild(VaultCommand.tree(this.handlers, UUID_ARGUMENT));
    }

    private List<String> run(String line, String... permissions) throws CommandSyntaxException {
        this.handlers.ran.clear();
        this.dispatcher.execute(line, senderWith(permissions));
        return List.copyOf(this.handlers.ran);
    }

    private void assertRefused(String line, String... permissions) {
        Assertions.assertThrows(CommandSyntaxException.class, () -> run(line, permissions), line);
        Assertions.assertEquals(List.of(), this.handlers.ran, line);
    }

    @Test
    void theBareCommandAndMenuOpenTheSendersOwnVaults() throws CommandSyntaxException {
        Assertions.assertEquals(List.of("menu null"), run("vault", Permissions.COMMAND_VAULT));
        Assertions.assertEquals(List.of("menu null"), run("vault menu", Permissions.COMMAND_VAULT));
    }

    @Test
    void withoutTheCommandPermissionNothingRuns() {
        assertRefused("vault");
        // Not even a branch whose own permission the sender holds.
        assertRefused("vault reload", Permissions.COMMAND_RELOAD);
    }

    @Test
    void anotherPlayersVaultsNeedTheOthersPermission() throws CommandSyntaxException {
        assertRefused("vault menu Steve", Permissions.COMMAND_VAULT);
        Assertions.assertEquals(List.of("menu Steve"),
                run("vault menu Steve", Permissions.COMMAND_VAULT, Permissions.COMMAND_VAULT_OTHERS));
    }

    @Test
    void eachBranchNeedsItsOwnPermissionAndNoOther() throws CommandSyntaxException {
        record Branch(String line, String permission, String ran) {
        }
        List<Branch> branches = List.of(
                new Branch("vault capture", Permissions.ACTION_CAPTURE, "capture"),
                new Branch("vault open " + VAULT, Permissions.COMMAND_OPEN, "open " + VAULT + " view"),
                new Branch("vault admin", Permissions.COMMAND_ADMIN, "admin"),
                new Branch("vault transfer " + VAULT + " Steve", Permissions.COMMAND_TRANSFER,
                        "transfer " + VAULT + " Steve"),
                new Branch("vault reload", Permissions.COMMAND_RELOAD, "reload"));
        for (Branch branch : branches) {
            Assertions.assertEquals(List.of(branch.ran()),
                    run(branch.line(), Permissions.COMMAND_VAULT, branch.permission()), branch.line());
            for (Branch other : branches) {
                if (other != branch) {
                    assertRefused(branch.line(), Permissions.COMMAND_VAULT, other.permission());
                }
            }
        }
    }

    @Test
    void theAdminParentIsNotCheckedByTheTree() {
        // vaultstorage.admin works through its children in paper-plugin.yml, not through code.
        assertRefused("vault reload", Permissions.COMMAND_VAULT, Permissions.ADMIN);
    }

    @Test
    void openViewsUnlessAnActionIsGiven() throws CommandSyntaxException {
        Assertions.assertEquals(List.of("open " + VAULT + " view"),
                run("vault open " + VAULT, Permissions.COMMAND_VAULT, Permissions.COMMAND_OPEN));
        Assertions.assertEquals(List.of("open " + VAULT + " edit"),
                run("vault open " + VAULT + " edit", Permissions.COMMAND_VAULT, Permissions.COMMAND_OPEN));
    }

    @Test
    void anIdThatIsNotAUuidIsRefusedBeforeAnyHandler() {
        assertRefused("vault open not-a-uuid", Permissions.COMMAND_VAULT, Permissions.COMMAND_OPEN);
        assertRefused("vault transfer not-a-uuid Steve", Permissions.COMMAND_VAULT, Permissions.COMMAND_TRANSFER);
    }

    @Test
    void openAndTransferNeedTheirArguments() {
        assertRefused("vault open", Permissions.COMMAND_VAULT, Permissions.COMMAND_OPEN);
        assertRefused("vault transfer " + VAULT, Permissions.COMMAND_VAULT, Permissions.COMMAND_TRANSFER);
    }

    @Test
    void anUnknownBranchIsRefused() {
        assertRefused("vault steal", Permissions.COMMAND_VAULT);
    }
}
