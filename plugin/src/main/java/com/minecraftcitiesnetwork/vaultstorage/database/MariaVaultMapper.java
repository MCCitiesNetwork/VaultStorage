package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface MariaVaultMapper extends VaultMapper {

    @Override
    @Insert("""
            INSERT INTO Vault (uuid, ownerUuid, worldUuid, x, y, z, material, blockData, status,
                               createdTime, updatedTime)
            VALUES (#{vault.uuid}, #{vault.ownerUuid}, #{vault.worldUuid}, #{vault.x}, #{vault.y}, #{vault.z},
                    #{vault.material}, #{vault.blockData}, #{vault.status},
                    #{vault.createdTime}, #{vault.updatedTime})
            """)
    int insert(@Param("vault") @NotNull VaultEntity vault);

    @Override
    @Select("""
            SELECT uuid, ownerUuid, worldUuid, x, y, z, material, blockData, status, createdTime, updatedTime
            FROM Vault
            WHERE uuid = #{uuid}
            """)
    @ConstructorArgs({
            @Arg(column = "uuid", javaType = UUID.class),
            @Arg(column = "ownerUuid", javaType = UUID.class),
            @Arg(column = "worldUuid", javaType = UUID.class),
            @Arg(column = "x", javaType = int.class),
            @Arg(column = "y", javaType = int.class),
            @Arg(column = "z", javaType = int.class),
            @Arg(column = "material", javaType = String.class),
            @Arg(column = "blockData", javaType = String.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "createdTime", javaType = Instant.class),
            @Arg(column = "updatedTime", javaType = Instant.class)
    })
    @Nullable VaultEntity selectByUuid(@Param("uuid") @NotNull UUID uuid);

    @Override
    @Select("""
            SELECT ownerUuid
            FROM Vault
            WHERE uuid = #{uuid}
            FOR UPDATE
            """)
    @Nullable UUID selectOwnerForUpdate(@Param("uuid") @NotNull UUID uuid);

    @Override
    @Update("""
            UPDATE Vault
            SET ownerUuid = #{ownerUuid}, updatedTime = #{updatedTime}
            WHERE uuid = #{uuid}
            """)
    int updateOwner(@Param("uuid") @NotNull UUID uuid, @Param("ownerUuid") @NotNull UUID ownerUuid,
                    @Param("updatedTime") @NotNull Instant updatedTime);

    @Override
    @Update("""
            UPDATE Vault
            SET updatedTime = #{updatedTime}
            WHERE uuid = #{uuid}
            """)
    int touch(@Param("uuid") @NotNull UUID uuid, @Param("updatedTime") @NotNull Instant updatedTime);

    @Override
    @Delete("""
            DELETE FROM Vault
            WHERE uuid = #{uuid}
            """)
    int delete(@Param("uuid") @NotNull UUID uuid);

    @Override
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM Vault
            <where>
                <if test="ownerUuid != null">ownerUuid = #{ownerUuid}</if>
                <if test="worldUuid != null">AND worldUuid = #{worldUuid}</if>
            </where>
            </script>
            """)
    int count(@Param("ownerUuid") @Nullable UUID ownerUuid, @Param("worldUuid") @Nullable UUID worldUuid);

    @Override
    @Select("""
            <script>
            SELECT v.uuid, v.ownerUuid, v.worldUuid, v.x, v.y, v.z, v.material, v.createdTime,
                   (SELECT COUNT(*) FROM VaultItem i WHERE i.vaultUuid = v.uuid) AS itemCount
            FROM Vault v
            <where>
                <if test="ownerUuid != null">v.ownerUuid = #{ownerUuid}</if>
                <if test="worldUuid != null">AND v.worldUuid = #{worldUuid}</if>
            </where>
            ORDER BY v.createdTime, v.uuid
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "uuid", javaType = UUID.class),
            @Arg(column = "ownerUuid", javaType = UUID.class),
            @Arg(column = "worldUuid", javaType = UUID.class),
            @Arg(column = "x", javaType = int.class),
            @Arg(column = "y", javaType = int.class),
            @Arg(column = "z", javaType = int.class),
            @Arg(column = "material", javaType = String.class),
            @Arg(column = "createdTime", javaType = Instant.class),
            @Arg(column = "itemCount", javaType = int.class)
    })
    @NotNull List<VaultSummary> selectPage(@Param("ownerUuid") @Nullable UUID ownerUuid,
                                           @Param("worldUuid") @Nullable UUID worldUuid,
                                           @Param("offset") int offset, @Param("limit") int limit);

    @Override
    @Update("""
            UPDATE Vault
            SET status = 'CLAIMED'
            WHERE uuid = #{uuid}
            AND status IS NULL
            """)
    int claim(@Param("uuid") @NotNull UUID uuid);

    @Override
    @Update("""
            UPDATE Vault
            SET status = NULL
            WHERE uuid = #{uuid}
            """)
    int release(@Param("uuid") @NotNull UUID uuid);

    @Override
    @Select("""
            SELECT uuid
            FROM Vault
            WHERE status = 'CLAIMED'
            """)
    @NotNull List<UUID> selectClaimed();

    @Override
    @Update("""
            UPDATE Vault
            SET status = NULL
            WHERE status = 'CLAIMED'
            """)
    int releaseAll();

    @Override
    @Select("""
            SELECT v.uuid AS uuid, COALESCE(MAX(i.slot), -1) AS highestSlot
            FROM Vault v
            LEFT JOIN VaultItem i ON i.vaultUuid = v.uuid
            WHERE v.ownerUuid = #{ownerUuid}
            AND v.status IS NULL
            GROUP BY v.uuid, v.createdTime
            HAVING COALESCE(MAX(i.slot), -1) + #{needed} < #{maxSlots}
            ORDER BY v.createdTime, v.uuid
            LIMIT 1
            FOR UPDATE
            """)
    @ConstructorArgs({
            @Arg(column = "uuid", javaType = UUID.class),
            @Arg(column = "highestSlot", javaType = int.class)
    })
    @Nullable VaultRoom selectOldestWithRoom(@Param("ownerUuid") @NotNull UUID ownerUuid,
                                             @Param("needed") int needed, @Param("maxSlots") int maxSlots);
}
