package com.minecraftcitiesnetwork.vaultstorage.config;

public enum DataFolder {
    MENUS("menus"),
    MAIL("mail");


    private final String path;

    DataFolder(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
