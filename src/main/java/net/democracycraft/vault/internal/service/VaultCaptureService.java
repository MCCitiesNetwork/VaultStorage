package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.event.PlayerVaultEvent;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.MojangService;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.session.VaultSessionManager.Mode;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import net.democracycraft.vault.internal.util.config.DataFolder;

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
     * Captures the given container block's inventory and metadata into a new Vault.
     * Contract:
     * - The block must be a Container; otherwise throws IllegalArgumentException. (Non-container blocks never reach this path.)
     * - Clears the block's inventory and removes the block (sets to AIR).
     * - Returns a new {@link VaultImp} with items, material, location, timestamp, and block data string.
     * - Does NOT perform an emptiness check; callers should invoke {@link #isContainerEmpty(Block)} beforehand when conditional behavior is required.
     * <p>This method must be called on the main thread.</p>
     */
    public VaultImp captureFromBlock(Player actor, Block block) {
        if (!(block.getState() instanceof Container container)) {
            throw new IllegalArgumentException("Target block is not a container.");
        }
        Inventory captureInv = (container instanceof Chest chest) ? chest.getBlockInventory() : container.getInventory();
        List<ItemStack> stacks = Arrays.stream(captureInv.getContents())
                .filter(Objects::nonNull)
                .filter(is -> is.getType() != Material.AIR && is.getAmount() > 0)
                .collect(Collectors.toList());

        // Clear inventory and remove block
        captureInv.clear();
        Material material = block.getType();
        var location = block.getLocation();
        Instant when = Instant.now();
        String blockDataString = block.getBlockData().getAsString();
        block.setType(Material.AIR);

        UUID vaultId = UUID.randomUUID();
        UUID owner = actor.getUniqueId();
        return new VaultImp(owner, vaultId, stacks, material, location, when, blockDataString);
    }

    /**
     * Immutable outcome produced by {@link #captureWithDoubleChestSupport(Player, Block, UUID)}.
     * Contract:
     * <ul>
     *   <li>If {@code empty} is true: no block removed (original block remains), no vault created, bolt protection removed, other halves not re-protected.</li>
     *   <li>If {@code empty} is false (container with contents): block removed & vaulted, remaining halves (if any) re-protected under {@code finalOwner}.</li>
     * </ul>
     * For non-container blocks {@code empty} will always be true.
     */
    public record CaptureOutcome(boolean empty, VaultImp vault, UUID originalOwner, UUID finalOwner, List<Block> reProtectedHalves) {}

    /**
     * Captures a block into a vault while preserving protection rules.
     * <p>Behavior:</p>
     * <ul>
     *   <li>Non-container blocks: treated as empty capture (protection removed, block kept, no vault).</li>
     *   <li>Container blocks: if inventory empty -> same as non-container (no vault). If non-empty -> items persisted, block removed.</li>
     *   <li>Double chests: remaining half(s) re-protected when vault created.</li>
     * </ul>
     * Ownership transfer: final owner is originalOwner if present else actor.
     */
    public CaptureOutcome captureWithDoubleChestSupport(Player actor, Block block, UUID originalOwner) {
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID finalOwner = originalOwner != null ? originalOwner : actor.getUniqueId();

        // Non-container path: treat as empty
        if (!(block.getState() instanceof Container container)) {
            if (bolt != null) {
                try { bolt.removeProtection(block); } catch (Throwable ignored) {}
            }
            return new CaptureOutcome(true, null, originalOwner, finalOwner, List.of());
        }

        List<Block> otherHalves = getHalves(block, container);

        boolean empty = isContainerEmpty(block);

        // Always remove protection from clicked block
        if (bolt != null) {
            try { bolt.removeProtection(block); } catch (Throwable ignored) {}
        }

        if (empty) {
            return new CaptureOutcome(true, null, originalOwner, finalOwner, List.of());
        }

        // Capture block contents into vault (removes block)
        VaultImp vault = captureFromBlock(actor, block);

        // Re-protect other half(s) after removal
        List<Block> reProtected = new ArrayList<>();
        if (bolt != null && !otherHalves.isEmpty()) {
            for (Block half : otherHalves) {
                try {
                    bolt.createProtection(half, finalOwner);
                    reProtected.add(half);
                } catch (Throwable ignored) {}
            }
        }

        return new CaptureOutcome(false, vault, originalOwner, finalOwner, List.copyOf(reProtected));
    }

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

    /**
     * YAML-backed, configurable texts for interactive capture session and action bar.
     * Provide values already formatted with MiniMessage or plain text; placeholders are resolved by the caller.
     */
    public static class SessionTexts implements Dto {
        public String captureCancelled = "Capture cancelled.";
        public String notAllowed = "<red>Not allowed: region/block rules.</red>";
        public String emptyCaptureSkipped = "Entity unlocked";
        public String capturedOk = "Vault captured.";
        public String noBoltOwner = "<yellow>No Bolt owner found; you will be set as the vault owner.</yellow>";
        public String actionBarIdle = "<yellow>Capture mode</yellow> - Right-click a block. <gray>Left-click to cancel.</gray>";
        public String actionBarContainer = "<gray>Owner:</gray> <white>%owner%</white> <gray>| Vaultable:</gray> <white>%vaultable%</white><gray>%reasonSegment%</gray>%admin%";
        public String actionBarReasonSegmentTemplate = " | Reason: %reason%";
        public String actionBarReasonAllowedBlank = "";
        public String reasonOwnerSelfInRegion = "you participate in region and also own the block";
        public String reasonContainerOwnerInOverlap = "block owner participates in region";
        public String reasonNotInvolvedNotOwner = "not involved and not block owner";
        public String reasonUnprotectedNoOverride = "unprotected block and no override";
        public String reasonNotInRegion = "block not in a region";
        public String reasonFallback = "cannot";
        public String actionBarUnprotectedOwner = "unprotected";
        public String actionBarVaultableYes = "yes";
        public String actionBarVaultableNo = "no";
        /**
         * One-time notification shown when starting a capture session and the actor has admin override permission.
         * Use MiniMessage formatting if desired; leave empty to disable.
         */
        public String adminModeEnabled = "<gold>Admin mode enabled.</gold>";
        /**
         * Optional tag appended to the action bar while admin override is active.
         * Injected via the %admin% placeholder in actionBarContainer. Leave empty to hide.
         */
        public String actionBarAdminModeTag = "<gray> |</gray> <gold> ADMIN</gold>";
    }

    private static final String HEADER = String.join("\n",
            "VaultCaptureService session texts configuration.",
            "All strings accept MiniMessage or plain text.",
            "Placeholders:",
            "- %owner% -> Bolt owner name or UUID when unavailable",
            "- %vaultable% -> yes/no from actionBarVaultableYes/No",
            "- %reason% -> human-readable reason (in actionBarReasonSegmentTemplate)",
            "- %reasonCode% -> policy code",
            "- %regions% -> comma-separated region ids",
            "- %admin% -> admin override tag (actionBarAdminModeTag) when override is active" );

    private static final AutoYML<SessionTexts> YML = AutoYML.create(SessionTexts.class, "VaultCaptureSession", DataFolder.MENUS, HEADER);
    private static SessionTexts cfg() { return YML.loadOrCreate(SessionTexts::new); }
    /** Ensures the YAML for session texts exists. */
    public static void ensureSessionConfig() { YML.loadOrCreate(SessionTexts::new); }

    /** Exposes current configured texts. */
    public SessionTexts sessionTexts() { return cfg(); }

    /**
     * Starts an interactive capture session for the given player, with default texts.
     * The session remains active until the player cancels with a left-click.
     */
    public void startCaptureSession(@NotNull Player actor) { startCaptureSession(actor, cfg()); }

    /**
     * Starts an interactive capture session for the given player using provided texts.
     * - Installs a per-player dynamic listener listening for left/right clicks.
     * - Shows an action bar with live vaultability.
     * - Applies VaultCapturePolicy on right-click; performs capture & DB persistence.
     * - Session stays active for multiple captures until cancelled with left-click.
     */
    public void startCaptureSession(@NotNull Player actor, @NotNull SessionTexts texts) {
        if (!VaultPermission.ACTION_CAPTURE.has(actor)) {
            actor.sendMessage("You don't have permission to capture blocks.");
            return;
        }
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
        session.switchTo(Mode.CAPTURE);

        // Notify admin mode if override is active
        if (VaultPermission.ADMIN.has(actor)) {
            String msg = texts.adminModeEnabled;
            if (msg != null && !msg.isBlank()) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(msg));
            }
        }

        final BukkitTask[] actionbarTask = new BukkitTask[1];
        final boolean[] busy = new boolean[]{false};

        session.getDynamicListener().setListener(new Listener() {
            @org.bukkit.event.EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;
                org.bukkit.event.block.Action action = event.getAction();
                event.setCancelled(true);

                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    session.getDynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    session.clearActionBarTask();
                    session.switchTo(Mode.NONE);
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.captureCancelled));
                    return;
                }

                if (action != Action.RIGHT_CLICK_BLOCK) return;
                Block block = event.getClickedBlock();
                if (block == null) return;
                if (busy[0]) return;
                busy[0] = true;

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, block);
                UUID originalOwner = decision.containerOwner();
                if (!decision.allowed()) {
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.notAllowed));
                    busy[0] = false;
                    return;
                }

                CaptureOutcome outcome = captureWithDoubleChestSupport(actor, block, originalOwner);
                if (outcome.empty()) {
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.emptyCaptureSkipped));
                    busy[0] = false;
                    return;
                }

                if (originalOwner == null && decision.hasOverride()) {
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.noBoltOwner));
                }

                VaultImp vault = outcome.vault();
                var plugin = VaultStoragePlugin.getInstance();
                UUID finalOwner = outcome.finalOwner();

                new BukkitRunnable() {
                    @Override public void run() {
                        var vaultService = plugin.getVaultService();
                        UUID worldId = block.getWorld().getUID();
                        UUID newId;
                        {
                            var created = vaultService.createVault(worldId, actor.getUniqueId(), block.getX(), block.getY(), block.getZ(), finalOwner,
                                    vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                    vault.blockDataString());
                            newId = created.uuid;
                        }
                        List<ItemStack> items = vault.contents();
                        List<VaultItemEntity> batch = new ArrayList<>(items.size());
                        for (int idx = 0; idx < items.size(); idx++) {
                            ItemStack itemStack = items.get(idx);
                            if (itemStack == null) continue;
                            VaultItemEntity vie = new VaultItemEntity();
                            vie.vaultUuid = newId;
                            vie.slot = idx;
                            vie.amount = itemStack.getAmount();
                            vie.item = ItemSerialization.toBytes(itemStack);
                            batch.add(vie);
                        }
                        if (!batch.isEmpty()) {
                            vaultService.putItems(newId, batch);
                        }
                        new BukkitRunnable() {
                            @Override public void run() {
                                var dto = new VaultDtoImp(newId, finalOwner, List.of(),
                                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                        null, System.currentTimeMillis());
                                VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId()).setLastVaultDto(dto);
                                actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.capturedOk));

                                new PlayerVaultEvent(actor, vault).callEvent();

                                busy[0] = false;
                            }
                        }.runTask(plugin);
                    }
                }.runTaskAsynchronously(plugin);
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
                    actor.sendActionBar(MiniMessageUtil.parseOrPlain(texts.actionBarIdle));
                    return;
                }
                var boltSvc = VaultStoragePlugin.getInstance().getBoltService();
                UUID owner = boltSvc != null ? boltSvc.getOwner(target) : null;
                String ownerName = ownerDisplayNameAsync(owner);

                VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, target);
                String vaultable = decision.allowed() ? texts.actionBarVaultableYes : texts.actionBarVaultableNo;
                String regionsList = decision.regionStatuses().stream().map(VaultCapturePolicy.RegionStatus::regionId).sorted().reduce((a,b)->a+", "+b).orElse("");
                String reasonSegment = getReasonSegment(decision, ownerName, regionsList, texts);
                String adminTag = decision.hasOverride() ? texts.actionBarAdminModeTag : "";
                Map<String,String> ph = Map.of(
                        "%owner%", ownerName,
                        "%vaultable%", vaultable,
                        "%reasonSegment%", reasonSegment,
                        "%admin%", adminTag
                );
                actor.sendActionBar(MiniMessageUtil.parseOrPlain(texts.actionBarContainer, ph));
            }
        }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.getDynamicListener().start();
    }

    /** Simple in-memory cache for UUID->username for actionbar display. */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Returns a display name for the UUID without blocking the main thread.
     * Uses cache if present; otherwise returns a short UUID and schedules an async resolve via MojangService.
     */
    private static String ownerDisplayNameAsync(UUID uuid) {
        if (uuid == null) return cfg().actionBarUnprotectedOwner;
        String cached = NAME_CACHE.get(uuid);
        if (cached != null) return cached;
        String fallback = uuid.toString().substring(0, 8);
        // Async resolve and cache
        new BukkitRunnable(){
            @Override public void run(){
                MojangService ms = VaultStoragePlugin.getInstance().getMojangService();
                if (ms == null) return;
                String name = ms.getUsername(uuid);
                if (name != null && !name.isBlank()) NAME_CACHE.put(uuid, name);
            }
        }.runTaskAsynchronously(VaultStoragePlugin.getInstance());
        return fallback;
    }

    private static String getReasonSegment(VaultCapturePolicy.Decision decision, String ownerName, String regionsList, SessionTexts cfg) {
        String reasonText;
        if (decision.allowed()) {
            reasonText = cfg.actionBarReasonAllowedBlank;
        } else {
            switch (decision.reason()) {
                case OWNER_SELF_IN_REGION -> reasonText = cfg.reasonOwnerSelfInRegion;
                case CONTAINER_OWNER_IN_OVERLAP -> reasonText = cfg.reasonContainerOwnerInOverlap;
                case NOT_INVOLVED_NOT_OWNER -> reasonText = cfg.reasonNotInvolvedNotOwner;
                case UNPROTECTED_NO_OVERRIDE -> reasonText = cfg.reasonUnprotectedNoOverride;
                case NOT_IN_REGION -> reasonText = cfg.reasonNotInRegion;
                default -> reasonText = cfg.reasonFallback;
            }
            Map<String,String> reasonPh = Map.of(
                    "%owner%", ownerName,
                    "%regions%", regionsList,
                    "%reasonCode%", decision.reason().name()
            );
            String resolved = reasonText;
            for (var e : reasonPh.entrySet()) resolved = resolved.replace(e.getKey(), e.getValue());
            reasonText = resolved;
        }
        return decision.allowed() ? cfg.actionBarReasonAllowedBlank : cfg.actionBarReasonSegmentTemplate.replace("%reason%", reasonText);
    }

    /**
     * Direct one-shot capture with messages and DB persistence.
     * Applies policy, captures/removes the block, persists items when applicable, and sends user feedback.
     * The callback is invoked on the main thread with true when a vault was persisted (non-empty container), false otherwise.
     */
    public void captureDirectAsync(@NotNull Player actor, @NotNull Block block, @NotNull SessionTexts texts, @NotNull Consumer<Boolean> onDoneMain) {
        VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, block);
        UUID originalOwner = decision.containerOwner();
        if (!decision.allowed()) {
            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.notAllowed));
            onDoneMain.accept(false);
            return;
        }
        CaptureOutcome outcome = captureWithDoubleChestSupport(actor, block, originalOwner);
        if (outcome.empty()) {
            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.emptyCaptureSkipped));
            onDoneMain.accept(false);
            return;
        }
        if (originalOwner == null && decision.hasOverride()) {
            actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.noBoltOwner));
        }
        VaultImp vault = outcome.vault();
        UUID finalOwner = outcome.finalOwner();
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable(){
            @Override public void run() {
                var vaultService = plugin.getVaultService();
                UUID worldId = block.getWorld().getUID();
                UUID newId;
                {
                    var created = vaultService.createVault(worldId, actor.getUniqueId(), block.getX(), block.getY(), block.getZ(), finalOwner,
                            vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                            vault.blockDataString());
                    newId = created.uuid;
                }
                List<ItemStack> items = vault.contents();
                List<VaultItemEntity> batch = new ArrayList<>(items.size());
                for (int idx = 0; idx < items.size(); idx++) {
                    ItemStack itemStack = items.get(idx);
                    if (itemStack == null) continue;
                    VaultItemEntity vie = new VaultItemEntity();
                    vie.vaultUuid = newId;
                    vie.slot = idx;
                    vie.amount = itemStack.getAmount();
                    vie.item = ItemSerialization.toBytes(itemStack);
                    batch.add(vie);
                }
                if (!batch.isEmpty()) vaultService.putItems(newId, batch);

                new BukkitRunnable(){
                    @Override public void run() {
                        VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId())
                                .setLastVaultDto(new VaultDtoImp(newId, finalOwner, List.of(),
                                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(), null, System.currentTimeMillis()));
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(texts.capturedOk));

                        new PlayerVaultEvent(actor, vault).callEvent();

                        onDoneMain.accept(true);
                }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }
}
