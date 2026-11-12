package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.capture.PlayerVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultDtoImp;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultImp;
import com.minecraftcitiesnetwork.vaultstorage.vault.ItemSerialization;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.data.type.WallSign;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.database.AppendResult;
import com.minecraftcitiesnetwork.vaultstorage.database.NewVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import org.bukkit.Chunk;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;
import com.minecraftcitiesnetwork.vaultstorage.capture.HangingVaultSupport;
import org.jspecify.annotations.NonNull;

/**
 * Service responsible for capturing a block into a Vault model.
 * <p>Behavior expansion: non-container blocks can now be "vaulted". They behave identically to an empty container capture:
 * Bolt protection is removed, no vault is created, the block remains in place.</p>
 * Threading policy:
 * - Must run on the main server thread because it interacts with the world (block state and inventories).
 *
 */
public class VaultCaptureService {

    /**
     * Validates that a UUID is not null and not all-zeros.
     * If invalid, attempts to resolve using the player's name via UniqueIdentifierResolver.
     * Returns a CompletableFuture with the valid UUID or null if resolution fails.
     */
    private CompletableFuture<UUID> ensureValidOwnerUUID(UUID candidateUUID, Player actor) {
        // If already valid, return immediately
        if (UniqueIdentifierResolver.isValidUUID(candidateUUID)) {
            VaultStoragePlugin.getInstance().getLogger().info(
                "[VaultCaptureService] Owner UUID is valid for " + actor.getName() + ": " + candidateUUID
            );
            return CompletableFuture.completedFuture(candidateUUID);
        }

        var plugin = VaultStoragePlugin.getInstance();
        if (VaultOwners.choose(candidateUUID, actor.getUniqueId()) == VaultOwners.Choice.ABORT) {
            plugin.getLogger().warning("[VaultCaptureService] The lock owner's id (" + candidateUUID
                    + ") is not usable. Nothing was captured for " + actor.getName()
                    + "; the vault will not be given to whoever clicked.");
            return CompletableFuture.completedFuture(null);
        }

        // The actor's own id is unusable (a Bedrock conversion error): look it up by their name.
        var resolver = new UniqueIdentifierResolver(plugin.names());

        VaultStoragePlugin.getInstance().getLogger().warning(
            "[VaultCaptureService] INVALID owner UUID detected for actor " + actor.getName() +
            " (" + candidateUUID + "). Attempting to resolve by player name..."
        );

        return resolver.resolveUuid(actor.getName()).thenApply(resolvedUUID -> {
            if (UniqueIdentifierResolver.isValidUUID(resolvedUUID)) {
                VaultStoragePlugin.getInstance().getLogger().info(
                    "[VaultCaptureService]  Successfully resolved UUID for " + actor.getName() +
                    ": " + resolvedUUID + " (Bedrock player fallback)"
                );
                return resolvedUUID;
            } else {
                VaultStoragePlugin.getInstance().getLogger().warning(
                    "[VaultCaptureService] failed to resolve valid UUID for " + actor.getName() +
                    ". Vault capture aborted. (Invalid fallback UUID: " + resolvedUUID + ")"
                );
                return null;
            }
        });
    }

    /**
     * Determines if the given block is a container and if its effective inventory is empty.
     * For non-container blocks returns true (treated as empty capture behavior).
     * <p>For chests, uses the combined {@link Chest#getBlockInventory()} ensuring double chests are evaluated as a whole.</p>
     * <p>Empty definition: no non-null ItemStack whose type != AIR and amount > 0.</p>
     * @param block target block
     * @return true if empty (or not a container), false otherwise
     */
    public boolean isContainerEmpty(Block block) {
        if (!(block.getState() instanceof Container c)) {
            // Non-container: treat as empty so capture logic will produce no vault
            return true;
        }
        // Note: checking getBlockInventory() on a Double Chest returns ONLY the half belonging to this block,
        // which is exactly what we need for split-logic.
        Inventory inv = (c instanceof Chest chest) ? chest.getBlockInventory() : c.getInventory();
        for (ItemStack stack : inv.getContents()) {
            if (stack == null) continue;
            if (stack.getType() == Material.AIR) continue;
            if (stack.getAmount() <= 0) continue;
            return false; // Found meaningful content
        }
        return true;
    }

    /**
     * Describes the container as the vault it would become: its items, material, location and
     * block data. Reads only; the block is removed by {@link #storeThenRemove} once the vault is
     * stored. Must be called on the main thread.
     */
    public VaultImp describeAsVault(Player actor, Block block) {
        return describeAsVault(actor.getUniqueId(), block);
    }

    /** Player-free variant of {@link #describeAsVault(Player, Block)}. */
    public VaultImp describeAsVault(UUID vaultOwnerUuid, Block block) {
        if (!(block.getState() instanceof Container container)) {
            throw new IllegalArgumentException("Target block is not a container.");
        }
        Inventory captureInv = (container instanceof Chest chest) ? chest.getBlockInventory() : container.getInventory();
        List<ItemStack> stacks = Arrays.stream(captureInv.getContents())
                .filter(Objects::nonNull)
                .filter(is -> is.getType() != Material.AIR && is.getAmount() > 0)
                .collect(Collectors.toList());

        // Reads only. The block is removed by the capture pipeline, after the vault is stored.
        Material material = block.getType();
        var location = block.getLocation();
        Instant when = Instant.now();
        String blockDataString = block.getBlockData().getAsString();

        UUID vaultId = UUID.randomUUID();
        return new VaultImp(vaultOwnerUuid, vaultId, stacks, material, location, when, blockDataString);
    }

    /**
     * Immutable outcome produced by {@link #captureWithDoubleChestSupport(Player, Block, UUID)}.
     * Contract:
     * <ul>
     * <li>If {@code empty} is true: no block removed, no vault created.</li>
     * <li>{@code protectionRemoved}: true if Bolt protection was stripped, false if action was canceled/warned.</li>
     * </ul>
     */
    public record CaptureOutcome(boolean empty, boolean protectionRemoved, VaultImp vault, UUID originalOwner, UUID finalOwner, List<Block> reProtectedHalves) {}

    /**
     * Captures a block into a vault while preserving protection rules.
     * <p>Behavior:</p>
     * <ul>
     * <li>Target Empty & Other Half Full: Warns user, does nothing.</li>
     * <li>Both Empty: Removes protection from BOTH halves.</li>
     * <li>Single Empty: Removes protection.</li>
     * <li>Target Full: Captures vault, re-protects other half.</li>
     * </ul>
     */
    public CaptureOutcome captureWithDoubleChestSupport(Player actor, @NonNull Block block, UUID originalOwner) {
        return captureWithDoubleChestSupport(block, originalOwner, actor.getUniqueId(), actor::sendMessage);
    }

    /**
     * Player-free variant of {@link #captureWithDoubleChestSupport(Player, Block, UUID)}. The vault owner is
     * {@code originalOwner} when non-null, else {@code finalOwnerFallback}; {@code warn} may be null to suppress notices.
     */
    public CaptureOutcome captureWithDoubleChestSupport(@NonNull Block block, UUID originalOwner,
                                                        @NonNull UUID finalOwnerFallback,
                                                        Consumer<Component> warn) {
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID finalOwner = originalOwner != null ? originalOwner : finalOwnerFallback;

        // 1. Non-container path: treat as empty capture (remove protection)
        if (!(block.getState() instanceof Container container)) {
            if (bolt != null) {
                try { bolt.removeProtection(block); } catch (Throwable ignored) {}
            }
            return new CaptureOutcome(true, true, null, originalOwner, finalOwner, List.of());
        }

        List<Block> otherHalves = getHalves(block, container);
        boolean targetIsEmpty = isContainerEmpty(block);

        // Double Chest Logic: Check the state of the other half if it exists
        if (!otherHalves.isEmpty()) {
            Block otherHalf = otherHalves.get(0); // Standard double chests only have one other half
            boolean otherHalfIsEmpty = isContainerEmpty(otherHalf);

            // If the target half is empty but the other half contains items, we abort the operation
            // to prevent accidental unprotection of the full chest. The user is guided to vault the non-empty side.
            if (targetIsEmpty && !otherHalfIsEmpty) {
                if (warn != null) {
                    warn.accept(Text.of(MessageKeys.CAPTURE_DOUBLE_CHEST_ORDER));
                }
                // Return empty=true so no vault is made, but protectionRemoved=false so we don't spam "Protection Removed".
                return new CaptureOutcome(true, false, null, originalOwner, finalOwner, List.of());
            }

            // If both halves of the double chest are empty, we proceed to remove the protection
            // from both blocks to fully unprotect the empty container.
            if (targetIsEmpty) {
                if (bolt != null) {
                    try {
                        bolt.removeProtection(block); // bolt handles 1 protection for double chests
                    } catch (Throwable ignored) {}
                }
                return new CaptureOutcome(true, true, null, originalOwner, finalOwner, List.of());
            }
        }

        // If we are here, either it's a single chest, or the target is not empty.

        if (targetIsEmpty) {
            // Single chest that is empty -> Just remove protection
            if (bolt != null) {
                try { bolt.removeProtection(block); } catch (Throwable ignored) {}
            }
            return new CaptureOutcome(true, true, null, originalOwner, finalOwner, List.of());
        }

        // Nothing is removed here. The caller stores the vault through storeThenRemove, which
        // drops this block's protection and re-protects the other half once the items are safe.
        VaultImp vault = describeAsVault(finalOwnerFallback, block);

        return new CaptureOutcome(false, false, vault, originalOwner, finalOwner, List.copyOf(otherHalves));
    }

    /**
     * Stores the container as a vault for {@code owner}, then removes it from the world. Call on
     * the main thread; the future completes there and never exceptionally.
     */
    private @NotNull CompletableFuture<CaptureResult> storeThenRemove(@NotNull Block block, @NotNull UUID owner,
                                                                    @NotNull List<Block> otherHalves) {
        var plugin = VaultStoragePlugin.getInstance();
        CapturePipeline<Block> pipeline = new CapturePipeline<>(plugin.repository(),
                new BukkitContainerAccess(plugin.getBoltService(), owner, otherHalves, plugin.getLogger()),
                plugin.databaseExecutor(), plugin.getServer().getScheduler().getMainThreadExecutor(plugin),
                plugin.getLogger());
        return pipeline.capture(block, owner);
    }

    /** Tells the owner their container was vaulted. A failed notification changes nothing else. */
    private static void mailOwner(@NotNull Block block, @NotNull UUID actor, @NotNull UUID owner) {
        var plugin = VaultStoragePlugin.getInstance();
        String regionId = plugin.getWorldGuardService().getRegionsAt(block).stream()
                .map(region -> region.id()).findFirst().orElse(null);
        plugin.ownerNotifications().vaultCreated(owner, actor, regionId);
    }

    private static final String CHANGED_MEANWHILE =
            MessageKeys.CAPTURE_CHANGED_MEANWHILE;
    private static final String SAVE_FAILED =
            MessageKeys.CAPTURE_SAVE_FAILED;

    private static @NotNull List<Block> getHalves(Block block, Container container) {
        List<Block> otherHalves = new ArrayList<>();
        if (container instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                InventoryHolder leftHolder = dc.getLeftSide();
                InventoryHolder rightHolder = dc.getRightSide();
                Chest left = (leftHolder instanceof Chest l) ? l : null;
                Chest right = (rightHolder instanceof Chest r) ? r : null;
                if (left != null && right != null) {
                    Block leftBlock = left.getBlock();
                    Block rightBlock = right.getBlock();
                    if (block.equals(leftBlock)) {
                        otherHalves.add(rightBlock);
                    } else if (block.equals(rightBlock)) {
                        otherHalves.add(leftBlock);
                    }
                }
            }
        }
        return otherHalves;
    }



    /** Ensures the YAML for session texts exists. */

    /** Exposes current configured texts. */

    /**
     * Starts an interactive capture session for the given player, with default texts.
     * The session remains active until the player cancels with a left-click.
     */

    /**
     * Starts an interactive capture session for the given player using provided texts.
     * - Installs a per-player dynamic listener listening for left/right clicks.
     * - Shows an action bar with live vaultability.
     * - Applies VaultCapturePolicy on right-click; performs capture & DB persistence.
     * - Session stays active for multiple captures until canceled with left-click.
     */
    public void startCaptureSession(@NotNull Player actor) {
        if (!VaultPermission.ACTION_CAPTURE.has(actor)) {
            actor.sendMessage(Text.of(MessageKeys.CAPTURE_NO_PERMISSION));
            return;
        }
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
        session.switchTo(Mode.CAPTURE);

        // Notify admin mode if override is active
        if (VaultPermission.ADMIN.has(actor)) {
            actor.sendMessage(Text.of(MessageKeys.CAPTURE_ADMIN_MODE_ENABLED));
        }

        final BukkitTask[] actionbarTask = new BukkitTask[1];
        final boolean[] busy = new boolean[]{false};
        // Cooldown: block cancellation for a short period after capture to prevent phantom events from other plugins
        final long[] cancelCooldownUntil = new long[]{0L};

        session.getDynamicListener().setListener(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;

                // Ignore off-hand events to prevent duplicate processing
                if (event.getHand() == EquipmentSlot.OFF_HAND) return;

                // Verify session is still in CAPTURE mode (may have been changed externally)
                if (session.getMode() != Mode.CAPTURE) return;

                Action action = event.getAction();

                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    // Prevent cancellation during cooldown (protects against phantom events after capture)
                    long now = System.currentTimeMillis();
                    if (now < cancelCooldownUntil[0]) {
                        event.setCancelled(true);
                        return; // Ignore this left-click, still in cooldown
                    }
                    event.setCancelled(true);
                    session.getDynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    session.clearActionBarTask();
                    session.switchTo(Mode.NONE);
                    actor.sendMessage(Text.of(MessageKeys.CAPTURE_CAPTURE_CANCELLED));
                    return;
                }

                if (action != Action.RIGHT_CLICK_BLOCK) return;
                event.setCancelled(true);
                Block block = event.getClickedBlock();
                if (block == null) return;
                if (busy[0]) return;
                busy[0] = true;

                // Set cooldown to prevent phantom left-click events from canceling capture (~2 ticks / 100ms)
                cancelCooldownUntil[0] = System.currentTimeMillis() + 100L;

                Block captureBlock = block;
                boolean clickedSign = isSignBlock(block);
                ChestShopService chestShop = VaultStoragePlugin.getInstance().getChestShopService();
                boolean clickedChestShopSign = clickedSign && chestShop.isShopSign(block);

                // Sequential sign->container policy flow:
                // 1) Evaluate sign (non-container) policy and optionally unlock sign.
                // 2) Evaluate container policy on attached container (if present).
                if (clickedChestShopSign) {
                    // Shop sign is handled through its container; it is removed once the container unlocks.
                    Block attachedContainer = resolveAttachedContainerBlock(block);
                    if (attachedContainer == null) {
                        busy[0] = false;
                        return;
                    }
                    captureBlock = attachedContainer;
                } else if (clickedSign) {
                    VaultCapturePolicy.Decision signDecision = VaultCapturePolicy.evaluate(actor, block);
                    UUID signOwner = signDecision.containerOwner();
                    if (signDecision.allowed()) {
                        CaptureOutcome signOutcome = captureWithDoubleChestSupport(actor, block, signOwner);
                        if (signOutcome.protectionRemoved()) {
                            actor.sendMessage(Text.of(MessageKeys.CAPTURE_SIGN_UNLOCKED));
                        }
                    } else {
                        actor.sendMessage(Text.of(MessageKeys.CAPTURE_SIGN_NOT_ALLOWED));
                    }

                    Block attachedContainer = resolveAttachedContainerBlock(block);
                    if (attachedContainer == null) {
                        busy[0] = false;
                        return;
                    }
                    captureBlock = attachedContainer;
                }

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, captureBlock);
                UUID originalOwner = decision.containerOwner();
                if (!decision.allowed()) {
                    // A shop sign on an empty container that cannot be captured is removed only with the
                    // override permission. Region membership alone let any member remove anyone's shop sign.
                    if (chestShop.isAvailable() && isContainerEmpty(captureBlock)) {
                        List<Block> shopSigns = chestShop.findShopSigns(captureBlock);
                        if (!shopSigns.isEmpty()
                                && VaultCapturePolicy.evaluateChestShopSignRemoval(actor, captureBlock).allowed()) {
                            removeAttachedChestShopSigns(actor, shopSigns);
                            actor.sendMessage(Text.of(MessageKeys.CAPTURE_CHEST_SHOP_SIGN_REMOVED));
                            busy[0] = false;
                            return;
                        }
                    }
                    actor.sendMessage(Text.of(clickedSign ? MessageKeys.CAPTURE_CONTAINER_NOT_ALLOWED : MessageKeys.CAPTURE_NOT_ALLOWED));
                    busy[0] = false;
                    return;
                }

                CaptureOutcome outcome = captureWithDoubleChestSupport(actor, captureBlock, originalOwner);
                if (outcome.empty()) {
                    // Only send "Protection removed" if we actually removed protection.
                    // If protectionRemoved is false, it means we sent a warning (e.g., "Vault the other side first").
                    if (outcome.protectionRemoved()) {
                        // Drop any shop sign left on the now-unlocked container.
                        if (chestShop.isAvailable()) {
                            List<Block> shopSigns = chestShop.findShopSigns(captureBlock);
                            if (!shopSigns.isEmpty()) {
                                removeAttachedChestShopSigns(actor, shopSigns);
                                actor.sendMessage(Text.of(MessageKeys.CAPTURE_CHEST_SHOP_SIGN_REMOVED));
                            }
                        }
                        actor.sendMessage(Text.of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
                    }
                    busy[0] = false;
                    return;
                }

                if (originalOwner == null && decision.hasOverride()) {
                    actor.sendMessage(Text.of(MessageKeys.CAPTURE_NO_BOLT_OWNER));
                }

                final Block finalCaptureBlock = captureBlock;
                VaultImp vault = outcome.vault();
                var plugin = VaultStoragePlugin.getInstance();
                UUID finalOwner = outcome.finalOwner();

                ensureValidOwnerUUID(finalOwner, actor).thenAcceptAsync(validatedOwner -> {
                    if (validatedOwner == null) {

                        new BukkitRunnable(){

                            @Override
                            public void run() {
                                actor.sendMessage(Text.of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
                                plugin.getLogger().warning(
                                        "[VaultCaptureService] Vault capture aborted for " + actor.getName() +
                                                " at " + finalCaptureBlock.getWorld().getName() + ":" + finalCaptureBlock.getX() + "," + finalCaptureBlock.getY() + "," + finalCaptureBlock.getZ() +
                                                " - could not validate owner UUID"
                                );
                                busy[0] = false;
                            }
                        }.runTask(plugin);
                        return;
                    }

                    storeThenRemove(finalCaptureBlock, validatedOwner, outcome.reProtectedHalves()).thenAccept(result -> {
                        busy[0] = false;
                        switch (result) {
                            case CaptureResult.Vaulted vaulted -> {
                                var dto = new VaultDtoImp(vaulted.vault(), validatedOwner, List.of(),
                                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                        null, System.currentTimeMillis());
                                plugin.getSessionManager().getOrCreate(actor.getUniqueId()).setLastVaultDto(dto);
                                actor.sendMessage(Text.of(MessageKeys.CAPTURE_CAPTURED_OK));
                                mailOwner(finalCaptureBlock, actor.getUniqueId(), validatedOwner);
                                new PlayerVaultEvent(actor, vault).callEvent();
                            }
                            case CaptureResult.ChangedMeanwhile changed ->
                                    actor.sendMessage(Text.of(CHANGED_MEANWHILE));
                            case CaptureResult.Failed failed ->
                                    actor.sendMessage(Text.of(SAVE_FAILED));
                        }
                    });
                }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
            public void onInteractEntity(PlayerInteractEntityEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;
                if (event.getHand() == EquipmentSlot.OFF_HAND) return;
                if (session.getMode() != Mode.CAPTURE) return;
                if (busy[0]) return;

                Entity clicked = event.getRightClicked();
                if (!(clicked instanceof ItemFrame) && !(clicked instanceof Painting)) return;

                event.setCancelled(true);

                Hanging hang = (Hanging) clicked;
                Block supporting = HangingVaultSupport.resolveSupportingBlock(hang);

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluateHangingCapture(actor, hang, supporting);
                if (!decision.allowed()) {
                    actor.sendMessage(Text.of(MessageKeys.CAPTURE_NOT_ALLOWED));
                    return;
                }

                // Empty item frame: unlock supporting block only (like empty non-container); do not vault or remove entity.
                if (hang instanceof ItemFrame frame && HangingVaultSupport.isItemFrameDisplayEmpty(frame)) {
                    BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                    if (bolt != null) {
                        try {
                            bolt.removeProtection(supporting);
                        } catch (Throwable ignored) {}
                    }
                    actor.sendMessage(Text.of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
                    return;
                }

                List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hang);
                if (stacks.isEmpty()) {
                    return;
                }

                busy[0] = true;
                cancelCooldownUntil[0] = System.currentTimeMillis() + 100L;
                ensureValidOwnerUUID(decision.containerOwner(), actor).thenAccept(validatedOwner -> {
                    if (validatedOwner == null) {
                        new BukkitRunnable() {
                            @Override public void run() {
                                actor.sendMessage(Text.of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
                                busy[0] = false;
                            }
                        }.runTask(VaultStoragePlugin.getInstance());
                        return;
                    }

                    var plugin = VaultStoragePlugin.getInstance();
                    // Stored first, removed after: a failed save leaves the entity where it is.
                    CompletableFuture
                            .supplyAsync(() -> persistHangingStacks(stacks, validatedOwner, actor.getUniqueId(), supporting, true),
                                    plugin.databaseExecutor())
                            .thenAcceptAsync(vaultUuid -> {
                                if (hang.isValid()) {
                                    hang.remove();
                                }
                                Material vm = stacks.get(0).getType();
                                VaultImp vaultEvt = new VaultImp(validatedOwner, vaultUuid, stacks, vm,
                                        supporting.getLocation(), Instant.now(), supporting.getBlockData().getAsString());
                                plugin.getSessionManager().getOrCreate(actor.getUniqueId())
                                        .setLastVaultDto(new VaultDtoImp(vaultUuid, validatedOwner, List.of(),
                                                vm.name(), null, System.currentTimeMillis()));
                                actor.sendMessage(Text.of(MessageKeys.CAPTURE_CAPTURED_OK));
                                new PlayerVaultEvent(actor, vaultEvt).callEvent();
                            }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
                            .exceptionally(failure -> {
                                plugin.getLogger().log(Level.SEVERE, "Hanging capture failed for " + actor.getName()
                                        + "; the entity was left in place.", failure);
                                actor.sendMessage(Text.of(SAVE_FAILED));
                                return null;
                            })
                            .whenComplete((done, failure) -> busy[0] = false);
                });
            }
        });

        actionbarTask[0] = new BukkitRunnable() {
            @Override public void run() {
                if (!actor.isOnline()) {
                    cancel();
                    return;
                }
                Block target = actor.getTargetBlockExact(6);
                if (target == null) {
                    actor.sendActionBar(Text.of(MessageKeys.CAPTURE_ACTION_BAR_IDLE));
                    return;
                }
                ChestShopService chestShop = VaultStoragePlugin.getInstance().getChestShopService();
                if (chestShop.isAvailable() && chestShop.isShopSign(target)) {
                    Block container = resolveAttachedContainerBlock(target);
                    if (container != null) target = container;
                }
                var boltSvc = VaultStoragePlugin.getInstance().getBoltService();
                UUID owner = boltSvc != null ? boltSvc.getOwner(target) : null;
                String ownerName = ownerDisplayNameAsync(owner);

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, target);
                String vaultable = Text.plain(decision.allowed()
                        ? MessageKeys.CAPTURE_ACTION_BAR_VAULTABLE_YES : MessageKeys.CAPTURE_ACTION_BAR_VAULTABLE_NO);
                String regionsList = decision.regionStatuses().stream().map(VaultCapturePolicy.RegionStatus::regionId).sorted().reduce((a,b)->a+", "+b).orElse("");
                Component reasonSegment = reasonSegment(decision, ownerName, regionsList);
                Component adminTag = decision.hasOverride()
                        ? Text.of(MessageKeys.CAPTURE_ACTION_BAR_ADMIN_MODE_TAG) : Component.empty();
                // The owner's name goes in as text; the two segments are components built here.
                actor.sendActionBar(VaultStoragePlugin.getInstance().messages().messageFor(
                        MessageKeys.CAPTURE_ACTION_BAR_CONTAINER,
                        MessageContainer.value("owner", ownerName),
                        MessageContainer.value("vaultable", vaultable),
                        MessageContainer.markup("reason-segment", reasonSegment),
                        MessageContainer.markup("admin", adminTag)));
            }
        }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.getDynamicListener().start();
    }

    /** Simple in-memory cache for UUID->username for actionbar display. */
    private static final ConcurrentHashMap<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Returns a display name for the UUID without blocking the main thread.
     * Uses cache if present; otherwise returns a short UUID and schedules an async resolve.
     */
    private static String ownerDisplayNameAsync(UUID uuid) {
        if (uuid == null) return Text.plain(MessageKeys.CAPTURE_ACTION_BAR_UNPROTECTED_OWNER);
        String cached = NAME_CACHE.get(uuid);
        if (cached != null) return cached;
        String fallback = uuid.toString().substring(0, 8);
        // Async resolve and cache using CompletableFuture API
        VaultStoragePlugin.getInstance().names().nameOf(uuid).thenAccept(name ->
                name.filter(resolved -> !resolved.isBlank()).ifPresent(resolved -> NAME_CACHE.put(uuid, resolved)));
        return fallback;
    }

    /** The " | Reason: ..." part of the action bar; empty when the capture is allowed. */
    private static Component reasonSegment(VaultCapturePolicy.@NonNull Decision decision, String ownerName,
                                           String regionsList) {
        if (decision.allowed()) {
            return Component.empty();
        }
        String key = switch (decision.reason()) {
            case OWNER_SELF_IN_REGION -> MessageKeys.CAPTURE_REASON_OWNER_SELF_IN_REGION;
            case CONTAINER_OWNER_IN_OVERLAP -> MessageKeys.CAPTURE_REASON_CONTAINER_OWNER_IN_OVERLAP;
            case NOT_INVOLVED_NOT_OWNER -> MessageKeys.CAPTURE_REASON_NOT_INVOLVED_NOT_OWNER;
            case UNPROTECTED_NO_OVERRIDE -> MessageKeys.CAPTURE_REASON_UNPROTECTED_NO_OVERRIDE;
            case NOT_IN_REGION -> MessageKeys.CAPTURE_REASON_NOT_IN_REGION;
            case ENTITIES_REQUIRE_ADMIN -> MessageKeys.CAPTURE_REASON_HANGABLE_REQUIRES_ADMIN;
            default -> MessageKeys.CAPTURE_REASON_FALLBACK;
        };
        Component reason = Text.of(key, Map.of("owner", ownerName, "regions", regionsList,
                "reason-code", decision.reason().name()));
        return VaultStoragePlugin.getInstance().messages().messageFor(
                MessageKeys.CAPTURE_ACTION_BAR_REASON_SEGMENT_TEMPLATE, MessageContainer.markup("reason", reason));
    }

    private static final BlockFace[] ADJACENT_FACES = new BlockFace[]{
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private static boolean isSignBlock(@NotNull Block block) {
        return block.getState() instanceof Sign;
    }

    /** Unlocks and removes the given shop signs, notifying ChestShop. {@code actor} may be null for automated captures. Must run on the main thread. */
    private void removeAttachedChestShopSigns(@Nullable Player actor, @NotNull List<Block> shopSigns) {
        ChestShopService chestShop = VaultStoragePlugin.getInstance().getChestShopService();
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        for (Block signBlock : shopSigns) {
            if (bolt != null) {
                try { bolt.removeProtection(signBlock); } catch (Throwable ignored) {}
            }
            chestShop.removeShopSign(actor, signBlock);
        }
    }

    private static Block resolveAttachedContainerBlock(@NotNull Block signBlock) {
        if (!isSignBlock(signBlock)) return null;

        if (signBlock.getBlockData() instanceof WallSign wallSign) {
            Block attached = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
            if (attached.getState() instanceof Container) {
                return attached;
            }
        }

        // Conservative fallback for non-wall signs: check immediate neighbors.
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = signBlock.getRelative(face);
            if (neighbor.getState() instanceof Container) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * Direct one-shot capture with messages and DB persistence.
     * Applies policy, captures/removes the block, persists items when applicable, and sends user feedback.
     * The callback is invoked on the main thread with true when a vault was persisted (non-empty container), false otherwise.
     */
    public void captureDirectAsync(@NotNull Player actor, @NotNull Block block, @NotNull Consumer<Boolean> onDoneMain) {
        VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, block);
        UUID originalOwner = decision.containerOwner();
        if (!decision.allowed()) {
            actor.sendMessage(Text.of(MessageKeys.CAPTURE_NOT_ALLOWED));
            onDoneMain.accept(false);
            return;
        }
        CaptureOutcome outcome = captureWithDoubleChestSupport(actor, block, originalOwner);
        if (outcome.empty()) {
            // Update to respect the protectionRemoved flag
            if (outcome.protectionRemoved()) {
                actor.sendMessage(Text.of(MessageKeys.CAPTURE_EMPTY_CAPTURE_SKIPPED));
            }
            onDoneMain.accept(false);
            return;
        }
        if (originalOwner == null && decision.hasOverride()) {
            actor.sendMessage(Text.of(MessageKeys.CAPTURE_NO_BOLT_OWNER));
        }
        VaultImp vault = outcome.vault();
        UUID finalOwner = outcome.finalOwner();
        var plugin = VaultStoragePlugin.getInstance();

        // The owner is validated first; the rest runs on the main thread, where the pipeline starts.
        ensureValidOwnerUUID(finalOwner, actor).thenAcceptAsync(validatedOwner -> {
            if (validatedOwner == null) {
                actor.sendMessage(Text.of(MessageKeys.CAPTURE_OWNER_UNKNOWN));
                plugin.getLogger().warning(
                    "[VaultCaptureService] Direct vault capture aborted for " + actor.getName() +
                    " at " + block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ() +
                    " - could not validate owner UUID"
                );
                onDoneMain.accept(false);
                return;
            }

            storeThenRemove(block, validatedOwner, outcome.reProtectedHalves()).thenAccept(result -> {
                switch (result) {
                    case CaptureResult.Vaulted vaulted -> {
                        plugin.getSessionManager().getOrCreate(actor.getUniqueId())
                                .setLastVaultDto(new VaultDtoImp(vaulted.vault(), validatedOwner, List.of(),
                                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                        null, System.currentTimeMillis()));
                        actor.sendMessage(Text.of(MessageKeys.CAPTURE_CAPTURED_OK));
                        mailOwner(block, actor.getUniqueId(), validatedOwner);
                        new PlayerVaultEvent(actor, vault).callEvent();
                        onDoneMain.accept(true);
                    }
                    case CaptureResult.ChangedMeanwhile changed -> {
                        actor.sendMessage(Text.of(CHANGED_MEANWHILE));
                        onDoneMain.accept(false);
                    }
                    case CaptureResult.Failed failed -> {
                        actor.sendMessage(Text.of(SAVE_FAILED));
                        onDoneMain.accept(false);
                    }
                }
            });
        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }

    /**
     * Offline-safe capture: no live {@link Player} and no policy evaluation; the caller decides what to vault.
     * The vault is owned by {@code boltOwner} (falls back to {@code initiatorUuid} when null) and created by
     * {@code initiatorUuid}. Must be called on the main thread; persistence runs async (fire-and-forget).
     */
    public @NotNull CompletableFuture<Boolean> captureOfflineAsync(@NotNull Block block,
                                    @NotNull UUID initiatorUuid,
                                    UUID boltOwner,
                                    @NotNull Runnable onVaulted) {
        var plugin = VaultStoragePlugin.getInstance();

        CaptureOutcome outcome = captureWithDoubleChestSupport(block, boltOwner, initiatorUuid, null);
        if (outcome.empty()) {
            return CompletableFuture.completedFuture(false);
        }

        UUID finalOwner = outcome.finalOwner();
        if (!UniqueIdentifierResolver.isValidUUID(finalOwner)) {
            plugin.getLogger().warning(
                    "[VaultCaptureService] Offline capture aborted at " + block.getWorld().getName() + ":"
                            + block.getX() + "," + block.getY() + "," + block.getZ()
                            + " - invalid vault owner UUID: " + finalOwner);
            return CompletableFuture.completedFuture(false);
        }

        return storeThenRemove(block, finalOwner, outcome.reProtectedHalves()).thenApply(result -> {
            if (!(result instanceof CaptureResult.Vaulted vaulted)) {
                // Already logged by the pipeline. The container stays for the next sweep.
                return false;
            }
            plugin.getLogger().info("[VaultCaptureService] Offline vault created: ID=" + vaulted.vault()
                    + " owner=" + finalOwner + " initiator=" + initiatorUuid);
            // Drop any ChestShop sign left where the container was.
            ChestShopService chestShop = plugin.getChestShopService();
            if (chestShop != null && chestShop.isAvailable()) {
                List<Block> shopSigns = chestShop.findShopSigns(block);
                if (!shopSigns.isEmpty()) {
                    removeAttachedChestShopSigns(null, shopSigns);
                }
            }
            // The sweep tells the owner once, at its end; no PlayerVaultEvent for an automated capture.
            onVaulted.run();
            return true;
        });
    }

    /**
     * Offline-safe capture of a Bolt-locked hanging entity (item frame or painting) with no live
     * {@link Player} and no policy evaluation. Its contents (the frame/painting plus any displayed item)
     * are stored in a vault owned by {@code boltOwner}, merged into an existing vault when one has room,
     * and the entity is removed (which also disposes its Bolt protection).
     * <p>Must be called on the main thread with the entity loaded; persistence runs async.</p>
     */
    public @NotNull CompletableFuture<Boolean> captureHangingOfflineAsync(@NotNull Hanging hang, @NotNull UUID boltOwner,
                                           @NotNull UUID initiatorUuid, @NotNull Runnable onVaulted) {
        List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hang);
        if (stacks.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        var plugin = VaultStoragePlugin.getInstance();
        Block supporting = HangingVaultSupport.resolveSupportingBlock(hang);
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        // Held until the entity is dealt with. The sweep only loaded this chunk for a moment, and
        // an entity in an unloaded chunk can no longer be removed: its items would then exist twice.
        Chunk chunk = hang.getLocation().getChunk();
        chunk.addPluginChunkTicket(plugin);
        return CompletableFuture
                .supplyAsync(() -> persistHangingStacks(stacks, boltOwner, initiatorUuid, supporting, false),
                        plugin.databaseExecutor())
                .thenApplyAsync(vaultUuid -> {
                    if (hang.isValid()) {
                        hang.remove();
                    } else {
                        plugin.getLogger().warning("[VaultCaptureService] A hanging entity stored in vault " + vaultUuid
                                + " was gone before it could be removed; its items may exist twice.");
                    }
                    onVaulted.run();
                    return true;
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Offline hanging capture failed at "
                            + supporting.getWorld().getName() + " " + supporting.getX() + "," + supporting.getY() + ","
                            + supporting.getZ() + "; the entity was left in place.", failure);
                    return false;
                })
                .whenCompleteAsync((done, failure) -> chunk.removePluginChunkTicket(plugin), mainThread);
    }

    /**
     * Stores hanging stacks at the end of the owner's oldest vault with room, or in a new vault
     * anchored at {@code supporting}. One transaction, with the chosen vault locked, so two
     * captures for one owner cannot take the same slots. Blocks on the database.
     */
    private UUID persistHangingStacks(@NotNull List<ItemStack> stacks, @NotNull UUID boltOwner, @NotNull UUID creatorUuid,
                                      @NotNull Block supporting, boolean tellOwner) {
        var plugin = VaultStoragePlugin.getInstance();
        List<VaultItem> items = new ArrayList<>(stacks.size());
        for (int offset = 0; offset < stacks.size(); offset++) {
            ItemStack stack = stacks.get(offset);
            items.add(new VaultItem(offset, stack.getAmount(), ItemSerialization.toBytes(stack)));
        }
        // The stored material must be a block: placement uses Block#setType.
        NewVault ifNoneFits = new NewVault(supporting.getWorld().getUID(), supporting.getWorld().getName(),
                supporting.getX(), supporting.getY(), supporting.getZ(), boltOwner, Material.CHEST.name(), null);
        AppendResult result = plugin.repository().appendItems(boltOwner, items,
                HangingVaultSupport.MAX_VAULT_SLOTS, ifNoneFits);
        return switch (result) {
            case AppendResult.Appended appended -> appended.vault();
            case AppendResult.Created created -> {
                plugin.getLogger().info("[VaultCaptureService] Hanging capture created vault ID=" + created.vault()
                        + " owner=" + boltOwner);
                if (tellOwner) {
                    mailOwner(supporting, creatorUuid, boltOwner);
                }
                yield created.vault();
            }
        };
    }
}
