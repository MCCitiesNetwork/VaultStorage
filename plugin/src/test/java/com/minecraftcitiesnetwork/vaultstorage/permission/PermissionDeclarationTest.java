package com.minecraftcitiesnetwork.vaultstorage.permission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PermissionDeclarationTest {

    private static final Pattern DECLARED = Pattern.compile("(?m)^  (vaultstorage\\.[a-z.]+):\\s*$");

    @Test
    void everyNodeInCodeIsDeclaredAndEveryDeclaredNodeIsInCode() throws IOException {
        String descriptor;
        try (InputStream stream = VaultPermission.class.getResourceAsStream("/paper-plugin.yml")) {
            Assertions.assertNotNull(stream, "paper-plugin.yml is not on the classpath");
            descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> declared = new TreeSet<>();
        Matcher matcher = DECLARED.matcher(descriptor);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        Set<String> inCode = new TreeSet<>();
        for (VaultPermission permission : VaultPermission.values()) {
            inCode.add(permission.node());
        }
        Assertions.assertEquals(8, inCode.size(), "expected the eight existing nodes");
        Assertions.assertEquals(inCode, declared);
    }
}
