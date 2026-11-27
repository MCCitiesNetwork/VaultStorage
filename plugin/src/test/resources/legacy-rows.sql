-- Invented rows in the shape production stores them. No real player data.
INSERT INTO worlds (name, uuid) VALUES
    ('city', 'aaaaaaaa-0000-0000-0000-000000000001'),
    ('wilds', 'aaaaaaaa-0000-0000-0000-000000000002');
INSERT INTO vaults (blockData, createdAtEpochMillis, material, status, type, updatedAtEpochMillis, uuid, worldUuid, x, y, z) VALUES
    ('minecraft:chest[facing=north,type=single,waterlogged=false]', 1731134320396, 'CHEST', NULL, NULL, 1731134320396,
     'bbbbbbbb-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 10, 64, -20),
    ('minecraft:barrel[facing=up,open=false]', 1762927416979, 'BARREL', NULL, NULL, 1762927416979,
     'bbbbbbbb-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', 11, 64, -20),
    (NULL, 1762927416979, 'CHEST', NULL, NULL, 1762927416979,
     'bbbbbbbb-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000002', -300, 70, 4000);
INSERT INTO vault_owners (ownerUuid, vaultUuid) VALUES
    ('cccccccc-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001'),
    ('cccccccc-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002'),
    ('cccccccc-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000003');
INSERT INTO vault_items (amount, item, slot, uuid, vaultUuid) VALUES
    (64, REPEAT('a', 40), 0, 'dddddddd-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001'),
    (1, CONCAT('<', REPEAT('b', 59998), '>'), 1, 'dddddddd-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000001'),
    (16, REPEAT('c', 40), 0, 'dddddddd-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000002'),
    (3, REPEAT('d', 40), 5, 'dddddddd-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000002'),
    (1, REPEAT('e', 40), 0, 'dddddddd-0000-0000-0000-000000000005', 'bbbbbbbb-0000-0000-0000-000000000003');
