package com.minecraftcitiesnetwork.vaultstorage.realty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.NotNull;

public interface MariaPendingSweepMapper extends PendingSweepMapper {

    @Override
    @Insert("""
            INSERT INTO PendingSweep (worldUuid, regionId, initiatorUuid, dueTime)
            VALUES (#{sweep.worldUuid}, #{sweep.regionId}, #{sweep.initiatorUuid}, #{sweep.dueTime})
            ON DUPLICATE KEY UPDATE initiatorUuid = VALUES(initiatorUuid), dueTime = VALUES(dueTime)
            """)
    int upsert(@Param("sweep") @NotNull PendingSweep sweep);

    @Override
    @Select("""
            SELECT worldUuid, regionId, initiatorUuid, dueTime
            FROM PendingSweep
            ORDER BY dueTime, regionId
            """)
    @ConstructorArgs({
            @Arg(column = "worldUuid", javaType = UUID.class),
            @Arg(column = "regionId", javaType = String.class),
            @Arg(column = "initiatorUuid", javaType = UUID.class),
            @Arg(column = "dueTime", javaType = Instant.class)
    })
    @NotNull List<PendingSweep> selectAll();

    @Override
    @Delete("""
            DELETE FROM PendingSweep
            WHERE worldUuid = #{worldUuid}
            AND regionId = #{regionId}
            """)
    int delete(@Param("worldUuid") @NotNull UUID worldUuid, @Param("regionId") @NotNull String regionId);
}
