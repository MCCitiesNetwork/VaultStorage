package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.AppendResult;
import com.minecraftcitiesnetwork.vaultstorage.database.Claim;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimReleases;
import com.minecraftcitiesnetwork.vaultstorage.database.DeleteResult;
import com.minecraftcitiesnetwork.vaultstorage.database.NewVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a capture puts what it read, and how that is taken back if the world changed
 * meanwhile. Both methods block on the database.
 */
public interface CaptureStore {

    /** Stores the snapshot's items for the owner: either all of them or none. */
    @NotNull Stored store(@NotNull ContainerSnapshot snapshot, @NotNull UUID owner);

    /**
     * What one capture stored.
     *
     * @param vault  the vault that holds the items
     * @param undo   takes back exactly what this capture stored, and says whether it could: false
     *               when someone holds the vault by now. Blocks on the database.
     * @param settle run once the target is gone from the world, on the database executor: lets
     *               go of what the store held for the capture. Never throws. Null when the
     *               store held nothing.
     */
    record Stored(@NotNull UUID vault, @NotNull BooleanSupplier undo, @Nullable Runnable settle) {
    }

    /**
     * A vault of its own for each capture, as a container gets: placed back, it is that block
     * again. The capture holds the vault until it has removed the container, so nobody opens or
     * places a copy the capture may still have to take back.
     */
    static @NotNull CaptureStore inNewVault(@NotNull VaultRepository repository, @NotNull ClaimReleases releases) {
        return (snapshot, owner) -> {
            Claim held = repository.createClaimed(
                    newVault(snapshot, owner, snapshot.material(), snapshot.blockData()), snapshot.items());
            return new Stored(held.vault(), () -> repository.delete(held) instanceof DeleteResult.Deleted,
                    () -> releases.release(held));
        };
    }

    /**
     * After the last used slot of the owner's oldest vault with room, or in a new vault when
     * none has: for an item frame or a painting, which is an item or two and no block. An
     * item's slot is then its offset from the first free one. A vault made for them is stored
     * as a chest, because placing a vault back needs a block. Such a vault is not held: the next
     * frame of a sweep joins it. Its undo leaves a vault that someone has opened meanwhile.
     *
     * @param maxSlots how many slots such a vault may fill
     */
    static @NotNull CaptureStore inVaultWithRoom(@NotNull VaultRepository repository, int maxSlots) {
        return (snapshot, owner) -> {
            AppendResult appended = repository.appendItems(owner, snapshot.items(), maxSlots,
                    newVault(snapshot, owner, Material.CHEST.name(), null));
            return new Stored(appended.vault(), () -> repository.undoAppend(appended), null);
        };
    }

    private static @NotNull NewVault newVault(@NotNull ContainerSnapshot snapshot, @NotNull UUID owner,
                                              @NotNull String material, @Nullable String blockData) {
        return new NewVault(snapshot.worldUuid(), snapshot.worldName(), snapshot.x(), snapshot.y(), snapshot.z(),
                owner, material, blockData);
    }
}
