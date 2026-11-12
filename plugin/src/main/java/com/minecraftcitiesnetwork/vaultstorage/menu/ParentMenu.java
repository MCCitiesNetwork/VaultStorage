package com.minecraftcitiesnetwork.vaultstorage.menu;


import java.util.List;

public interface ParentMenu extends Menu{

    List<ChildMenu> getChildMenus();

    void addChildMenu(ChildMenu childMenu);

    void addChildMenus(List<ChildMenu> childMenus);


}
