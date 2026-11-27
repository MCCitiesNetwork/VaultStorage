package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;

public interface MariaVaultItemMapper extends VaultItemMapper {

    @Override
    @Insert("""
            <script>
            INSERT INTO VaultItem (vaultUuid, slot, amount, item)
            VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.vaultUuid}, #{item.slot}, #{item.amount}, #{item.item})
            </foreach>
            </script>
            """)
    int insertAll(@Param("items") @NotNull List<VaultItemEntity> items);

    @Override
    @Select("""
            SELECT vaultUuid, slot, amount, item
            FROM VaultItem
            WHERE vaultUuid = #{vaultUuid}
            ORDER BY slot
            """)
    @ConstructorArgs({
            @Arg(column = "vaultUuid", javaType = UUID.class),
            @Arg(column = "slot", javaType = int.class),
            @Arg(column = "amount", javaType = int.class),
            @Arg(column = "item", javaType = byte[].class)
    })
    @NotNull List<VaultItemEntity> selectByVault(@Param("vaultUuid") @NotNull UUID vaultUuid);

    @Override
    @Delete("""
            DELETE FROM VaultItem
            WHERE vaultUuid = #{vaultUuid}
            """)
    int deleteByVault(@Param("vaultUuid") @NotNull UUID vaultUuid);

    @Override
    @Delete("""
            DELETE i FROM VaultItem i
            JOIN Vault v ON v.uuid = i.vaultUuid
            WHERE i.vaultUuid = #{vaultUuid}
              AND i.slot BETWEEN #{firstSlot} AND #{lastSlot}
              AND v.claimToken IS NULL
            """)
    int deleteFreeSlots(@Param("vaultUuid") @NotNull UUID vaultUuid, @Param("firstSlot") int firstSlot,
                    @Param("lastSlot") int lastSlot);
}
