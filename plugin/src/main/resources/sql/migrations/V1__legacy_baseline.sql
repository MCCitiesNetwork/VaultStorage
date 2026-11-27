-- The schema VaultStorage 1.x created, exactly as production has it. On a live database every
-- statement is a no-op; on an empty one it gives the later steps the shape they convert from.
CREATE TABLE IF NOT EXISTS `worlds` (
  `name` varchar(255) DEFAULT NULL,
  `uuid` varchar(36) NOT NULL,
  PRIMARY KEY (`uuid`),
  UNIQUE KEY `uq_world_name` (`name`),
  KEY `name_idx` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `vaults` (
  `blockData` varchar(255) DEFAULT NULL,
  `createdAtEpochMillis` bigint(20) DEFAULT NULL,
  `material` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `type` varchar(255) DEFAULT NULL,
  `updatedAtEpochMillis` bigint(20) DEFAULT NULL,
  `uuid` varchar(36) NOT NULL,
  `worldUuid` varchar(36) DEFAULT NULL,
  `x` int(11) NOT NULL,
  `y` int(11) NOT NULL,
  `z` int(11) NOT NULL,
  PRIMARY KEY (`uuid`),
  KEY `blockData_idx` (`blockData`),
  KEY `createdAtEpochMillis_idx` (`createdAtEpochMillis`),
  KEY `material_idx` (`material`),
  KEY `status_idx` (`status`),
  KEY `type_idx` (`type`),
  KEY `updatedAtEpochMillis_idx` (`updatedAtEpochMillis`),
  KEY `worldUuid_idx` (`worldUuid`),
  KEY `x_idx` (`x`),
  KEY `y_idx` (`y`),
  KEY `z_idx` (`z`),
  KEY `idx_vault_loc` (`worldUuid`,`x`,`y`,`z`),
  CONSTRAINT `fk_vault_world` FOREIGN KEY (`worldUuid`) REFERENCES `worlds` (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `vault_owners` (
  `ownerUuid` varchar(36) DEFAULT NULL,
  `vaultUuid` varchar(36) NOT NULL,
  PRIMARY KEY (`vaultUuid`),
  KEY `ownerUuid_idx` (`ownerUuid`),
  KEY `idx_vo_owner` (`ownerUuid`),
  CONSTRAINT `fk_vo_vault` FOREIGN KEY (`vaultUuid`) REFERENCES `vaults` (`uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `vault_items` (
  `amount` int(11) NOT NULL,
  `item` blob DEFAULT NULL,
  `slot` int(11) NOT NULL,
  `uuid` varchar(36) NOT NULL,
  `vaultUuid` varchar(36) DEFAULT NULL,
  PRIMARY KEY (`uuid`),
  UNIQUE KEY `uq_vi_slot` (`vaultUuid`,`slot`),
  KEY `amount_idx` (`amount`),
  KEY `item_idx` (`item`(3072)),
  KEY `slot_idx` (`slot`),
  KEY `vaultUuid_idx` (`vaultUuid`),
  CONSTRAINT `fk_vi_vault` FOREIGN KEY (`vaultUuid`) REFERENCES `vaults` (`uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
