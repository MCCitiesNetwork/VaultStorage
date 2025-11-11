package net.democracycraft.vault.internal.util.config;

public enum DataFolder {
    MENUS("menus");


    private final String path;

    DataFolder(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
