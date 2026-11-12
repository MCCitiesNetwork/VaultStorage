package net.democracycraft.vault.api.ui;


import java.util.List;

public interface ParentMenu extends Menu{

    List<ChildMenu> getChildMenus();

    void addChildMenu(ChildMenu childMenu);

    void addChildMenus(List<ChildMenu> childMenus);


}
