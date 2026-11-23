-- A sweep that has been scheduled and has not finished. One per region: a new occupant change
-- replaces the sweep already waiting for that region.
CREATE TABLE IF NOT EXISTS PendingSweep (
    worldUuid UUID NOT NULL,
    regionId VARCHAR(255) NOT NULL,
    initiatorUuid UUID NOT NULL,
    dueTime DATETIME(3) NOT NULL,
    PRIMARY KEY (worldUuid, regionId)
);
