package com.minecraftcitiesnetwork.vaultstorage.permission;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PermissionsTest {

    @Test
    void everyConstantIsDeclaredWithADescriptionAndADefault() throws Exception {
        Map<String, Map<String, Object>> declared = declared();
        for (String node : constants()) {
            Map<String, Object> entry = declared.get(node);
            Assertions.assertNotNull(entry, node + " is not declared in paper-plugin.yml");
            Assertions.assertNotNull(entry.get("description"), node + " has no description");
            Assertions.assertNotNull(entry.get("default"), node + " has no default");
        }
    }

    @Test
    void everyDeclaredNodeHasAConstant() throws Exception {
        Assertions.assertEquals(constants(), declared().keySet());
    }

    @Test
    void theAdminNodeIsTheParentOfEveryOtherNode() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) declared().get(Permissions.ADMIN).get("children");
        Set<String> others = new HashSet<>(constants());
        others.remove(Permissions.ADMIN);

        Assertions.assertEquals(others, children.keySet());
        Assertions.assertTrue(children.values().stream().allMatch(Boolean.TRUE::equals));
    }

    @Test
    void everyNodeFollowsTheNamingScheme() throws Exception {
        for (String node : constants()) {
            Assertions.assertTrue(node.matches("vaultstorage(\\.[a-z]+)+"), node);
        }
    }

    private static Set<String> constants() throws IllegalAccessException {
        Set<String> nodes = new HashSet<>();
        for (Field field : Permissions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                nodes.add((String) field.get(null));
            }
        }
        Assertions.assertTrue(nodes.size() >= 10, "reflection found only " + nodes.size() + " nodes");
        return nodes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> declared() throws IOException {
        try (InputStream stream = Permissions.class.getResourceAsStream("/paper-plugin.yml")) {
            Assertions.assertNotNull(stream, "paper-plugin.yml is not on the test classpath");
            Map<String, Object> descriptor = new Yaml().load(stream);
            return (Map<String, Map<String, Object>>) descriptor.get("permissions");
        }
    }
}
