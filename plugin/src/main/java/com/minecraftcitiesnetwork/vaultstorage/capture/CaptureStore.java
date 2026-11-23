package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.AppendResult;
import com.minecraftcitiesnetwork.vaultstorage.database.NewVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import java.util.UUID;
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
     * @param vault the vault that holds the items
     * @param undo  takes back exactly what this capture stored; blocks on the database
     */
    record Stored(@NotNull UUID vault, @NotNull Runnable undo) {
    }

    /** A vault of its own for each capture, as a container gets: placed back, it is that block again. */
    static @NotNull CaptureStore inNewVault(@NotNull VaultRepository repository) {
        return (snapshot, owner) -> {
            UUID created = repository.create(newVault(snapshot, owner, snapshot.material(), snapshot.blockData()),
                    snapshot.items());
            return new Stored(created, () -> repository.delete(created));
        };
    }

    /**
     * After the last used slot of the owner's oldest vault with room, or in a new vault when
     * none has: for an item frame or a painting, which is an item or two and no block. An
     * item's slot is then its offset from the first free one. A vault made for them is stored
     * as a chest, because placing a vault back needs a block.
     *
     * @param maxSlots how many slots such a vault may fill
     */
    static @NotNull CaptureStore inVaultWithRoom(@NotNull VaultRepository repository, int maxSlots) {
        return (snapshot, owner) -> {
            AppendResult appended = repository.appendItems(owner, snapshot.items(), maxSlots,
                    newVault(snapshot, owner, Material.CHEST.name(), null));
            return new Stored(appended.vault(), () -> repository.undoAppend(appended));
        };
    }

    private static @NotNull NewVault newVault(@NotNull ContainerSnapshot snapshot, @NotNull UUID owner,
                                              @NotNull String material, @Nullable String blockData) {
        return new NewVault(snapshot.worldUuid(), snapshot.worldName(), snapshot.x(), snapshot.y(), snapshot.z(),
                owner, material, blockData);
    }
}
