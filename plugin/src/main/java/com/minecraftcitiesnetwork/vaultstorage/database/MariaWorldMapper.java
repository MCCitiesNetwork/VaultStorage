package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.jetbrains.annotations.NotNull;

public interface MariaWorldMapper extends WorldMapper {

    @Override
    @Insert("""
            INSERT INTO World (uuid, name)
            VALUES (#{uuid}, #{name})
            ON DUPLICATE KEY UPDATE name = VALUES(name)
            """)
    int upsert(@Param("uuid") @NotNull UUID uuid, @Param("name") @NotNull String name);
}
