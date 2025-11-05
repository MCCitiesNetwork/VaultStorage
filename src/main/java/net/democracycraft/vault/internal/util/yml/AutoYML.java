package net.democracycraft.vault.internal.util.yml;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.util.config.DataFolder;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * AutoYML is a small reflection-based utility to serialize/deserialize simple Java POJOs
 * to and from YAML files using SnakeYAML.
 *
 * Features:
 * - Supports nested objects with a no-arg constructor.
 * - Supports Lists, Sets, arrays, and Map<String, V>.
 * - Supports primitive wrappers, strings, numbers, booleans, and enums (case-insensitive).
 * - Skips static, transient, and synthetic fields.
 * - Optional header comment at the top of the generated YAML.
 * - Thread-safety: all file I/O operations are synchronized per AutoYML instance.
 *
 * Limitations:
 * - Map keys are handled primarily as strings. Non-string keys are best avoided.
 * - Requires a no-argument constructor for nested objects.
 * - Concurrency is not coordinated across different AutoYML instances pointing to the same file.
 *
 * @param <T> Root data type (recommended to implement Serializable)
 */
public class AutoYML<T extends Serializable> {

    private final Class<T> clazz;
    private final File file;
    private final String header;
    private final Yaml yaml;
    // Synchronization guard for file I/O and YAML access
    private final Object ioLock = new Object();

    /**
     * Creates a new AutoYML handler for a specific class and file.
     *
     * @param clazz  Root class handled by this instance.
     * @param file   Target YAML file.
     * @param header Optional header comment written at the top of the file.
     */
    public AutoYML(Class<T> clazz, File file, String header) {
        this.clazz = clazz;
        this.file = file;
        this.header = header;

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        // Keep wide lines but still readable.
        opts.setWidth(120);
        this.yaml = new Yaml(opts);

        ensureParent();
    }

    /**
     * Ensures the parent directory for the file exists.
     */
    private void ensureParent() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean ok = parent.mkdirs();
            if (!ok && !parent.exists()) {
                VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING,
                        "Could not create parent directories for: " + parent.getAbsolutePath());
            }
        }
    }

    /**
     * @return true if the YAML file exists.
     */
    public boolean exists() {
        synchronized (ioLock) {
            return file.exists();
        }
    }

    /**
     * Deletes the YAML file if present.
     *
     * @return true if the file was deleted.
     */
    public boolean delete() {
        synchronized (ioLock) {
            return file.delete();
        }
    }

    /**
     * Loads the YAML file into an instance of {@code T}.
     * Returns null if the file does not exist, is empty, or cannot be parsed.
     */
    public T load() {
        synchronized (ioLock) {
            if (!file.exists()) return null;
            try (InputStream in = new FileInputStream(file)) {
                Object raw = yaml.load(in);
                if (!(raw instanceof Map)) return null; // Empty or invalid root
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                return buildFromMap(map, clazz);
            } catch (Exception e) {
                VaultStoragePlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to load YAML: " + file, e);
                return null;
            }
        }
    }

    /**
     * Loads the YAML file or creates and saves a default instance when load fails.
     *
     * @param defaultSupplier Provides a default instance when loading fails or file is missing.
     * @return Loaded or newly created instance.
     */
    public T loadOrCreate(Supplier<T> defaultSupplier) {
        synchronized (ioLock) {
            T result = load();
            if (result == null) {
                result = defaultSupplier.get();
                save(result);
            }
            return result;
        }
    }

    /**
     * Saves the given object to the YAML file.
     *
     * @param obj Instance to serialize.
     */
    public void save(T obj) {
        synchronized (ioLock) {
            Map<String, Object> map = toMap(obj);
            try (Writer w = new FileWriter(file)) {
                if (header != null && !header.isEmpty()) {
                    for (String line : header.split("\n")) {
                        w.write("# " + line + "\n");
                    }
                    w.write("\n");
                }
                yaml.dump(map, w);
            } catch (IOException e) {
                VaultStoragePlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to save YAML: " + file, e);
            }
        }
    }

    /**
     * Build an instance of target type from a YAML-backed map.
     * Fields not present in the map are left as the object's defaults.
     * If a key is present but cannot be converted (e.g., wrong type), the field's default is preserved.
     */
    private <X> X buildFromMap(Map<String, Object> map, Class<X> target) {
        try {
            Constructor<X> ctor = target.getDeclaredConstructor();
            ctor.setAccessible(true);
            X instance = ctor.newInstance();

            for (Field field : getAllFields(target)) {
                field.setAccessible(true);
                String name = field.getName();
                if (!map.containsKey(name)) continue; // preserve default value
                Object raw = map.get(name);
                Object converted = convertValue(raw, field.getGenericType());
                if (converted == null && field.getType().isPrimitive()) continue;
                try {
                    field.set(instance, converted);
                } catch (Exception setEx) {
                    VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING,
                            "Could not set field '" + name + "' on " + target.getSimpleName() + ": " + converted,
                            setEx);
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Cannot build instance of " + target.getSimpleName(), e);
        }
    }

    /**
     * Converts a raw YAML-loaded value into the requested Java type.
     * Handles primitives/wrappers, enums, nested POJOs, collections, arrays, and maps.
     */
    private Object convertValue(Object raw, Type type) {
        if (raw == null) return null;

        if (type instanceof Class<?> pClass) {

            // Primitives and common simple types
            if (pClass == String.class) return String.valueOf(raw);
            if (pClass == Integer.class || pClass == int.class) return toInteger(raw);
            if (pClass == Long.class || pClass == long.class) return toLong(raw);
            if (pClass == Double.class || pClass == double.class) return toDouble(raw);
            if (pClass == Float.class || pClass == float.class) return toFloat(raw);
            if (pClass == Short.class || pClass == short.class) return toShort(raw);
            if (pClass == Byte.class || pClass == byte.class) return toByte(raw);
            if (pClass == Character.class || pClass == char.class) return toChar(raw);
            if (pClass == Boolean.class || pClass == boolean.class) return toBoolean(raw);

            // Enums (case-insensitive by name)
            if (pClass.isEnum()) {
                Object[] constants = pClass.getEnumConstants();
                String s = String.valueOf(raw);
                for (Object c : constants) {
                    if (((Enum<?>) c).name().equalsIgnoreCase(s)) {
                        return c;
                    }
                }
                return null; // unknown enum constant
            }

            // Arrays
            if (pClass.isArray()) {
                Class<?> comp = pClass.getComponentType();
                if (raw instanceof Collection<?>) {
                    Collection<?> coll = (Collection<?>) raw;
                    Object array = Array.newInstance(comp, coll.size());
                    int i = 0;
                    for (Object item : coll) {
                        Array.set(array, i++, convertValue(item, comp));
                    }
                    return array;
                }
                return null;
            }

            // Nested POJO
            if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                return buildFromMap(map, pClass);
            }

            // Fallback: if type matches, return as-is; otherwise string value
            if (pClass.isInstance(raw)) return raw;
            return String.valueOf(raw);
        }

        // Handle parameterized types (List<T>, Set<T>, Map<K,V>)
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type rawType = pt.getRawType();

            if (rawType instanceof Class<?>) {
                Class<?> rawClass = (Class<?>) rawType;

                // List<T>
                if (List.class.isAssignableFrom(rawClass)) {
                    Type argType = pt.getActualTypeArguments()[0];
                    if (!(raw instanceof Collection)) return null;
                    Collection<?> rawColl = (Collection<?>) raw;
                    return rawColl.stream()
                            .map(item -> convertValue(item, argType))
                            .collect(Collectors.toList());
                }

                // Set<T>
                if (Set.class.isAssignableFrom(rawClass)) {
                    Type argType = pt.getActualTypeArguments()[0];
                    if (!(raw instanceof Collection)) return null;
                    Collection<?> rawColl = (Collection<?>) raw;
                    LinkedHashSet<Object> set = new LinkedHashSet<>();
                    for (Object item : rawColl) set.add(convertValue(item, argType));
                    return set;
                }

                // Map<K,V> (primarily supports String keys)
                if (Map.class.isAssignableFrom(rawClass)) {
                    Type keyType = pt.getActualTypeArguments()[0];
                    Type valType = pt.getActualTypeArguments()[1];
                    if (!(raw instanceof Map)) return null;
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> in = (Map<Object, Object>) raw;
                    LinkedHashMap<Object, Object> out = new LinkedHashMap<>();
                    for (Map.Entry<Object, Object> e : in.entrySet()) {
                        Object k = e.getKey();
                        Object v = e.getValue();
                        Object convKey = convertMapKey(k, keyType);
                        Object convVal = convertValue(v, valType);
                        out.put(convKey, convVal);
                    }
                    return out;
                }
            }
        }

        // Unknown generic type: return raw
        return raw;
    }

    /** Serialize an object graph into a map suitable for SnakeYAML. */
    private Map<String, Object> toMap(Object obj) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : getAllFields(obj.getClass())) {
            field.setAccessible(true);
            try {
                Object value = field.get(obj);
                result.put(field.getName(), serializeValue(value));
            } catch (IllegalAccessException e) {
                VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING,
                        "Could not read field '" + field.getName() + "' from " + obj.getClass().getSimpleName(), e);
            }
        }
        return result;
    }

    /** Converts a value into a YAML-serializable form (maps, lists, primitives, strings). */
    private Object serializeValue(Object value) {
        if (value == null) return null;

        // Primitives and simple wrappers
        if (value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;

        // Enums by name
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();

        // Arrays -> List
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(serializeValue(Array.get(value, i)));
            return out;
        }

        // Collections
        if (value instanceof Collection<?>) {
            Collection<?> coll = (Collection<?>) value;
            return coll.stream().map(this::serializeValue).collect(Collectors.toList());
        }

        // Map keys are forced to strings (via toString) for YAML
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                out.put(k, serializeValue(e.getValue()));
            }
            return out;
        }

        // Nested POJO
        return toMap(value);
    }

    /**
     * Creates a configured AutoYML instance resolving the file under the plugin's data folder.
     *
     * @param clazz       Root class handled by this instance.
     * @param fileName    Target file name (".yml" appended if missing).
     * @param dataFolder  Logical subfolder where the file will be placed.
     * @param header      Optional header comment.
     */
    public static <T extends Serializable> AutoYML<T> create(
            Class<T> clazz, String fileName, DataFolder dataFolder, String header
    ) {
        File folder = new File(VaultStoragePlugin.getInstance().getDataFolder(), dataFolder.getPath());
        if (!folder.exists()) {
            boolean ok = folder.mkdirs();
            if (!ok && !folder.exists()) {
                VaultStoragePlugin.getInstance().getLogger().log(Level.WARNING,
                        "Could not create data folder: " + folder.getAbsolutePath());
            }
        }
        if (!fileName.endsWith(".yml")) fileName += ".yml";
        File file = new File(folder, fileName);
        return new AutoYML<>(clazz, file, header);
    }

    /** Lightweight, local Supplier to avoid additional dependencies. */
    @FunctionalInterface
    public interface Supplier<V> {
        V get();
    }

    // ------------------------- Helpers -------------------------

    /** Collects all non-static, non-transient, non-synthetic fields from the class hierarchy. */
    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) continue;
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        return fields;
    }

    private static Integer toInteger(Object raw) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        try { return Integer.parseInt(String.valueOf(raw).trim()); } catch (Exception ignored) { return null; }
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Number) return ((Number) raw).longValue();
        try { return Long.parseLong(String.valueOf(raw).trim()); } catch (Exception ignored) { return null; }
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try { return Double.parseDouble(String.valueOf(raw).trim()); } catch (Exception ignored) { return null; }
    }

    private static Float toFloat(Object raw) {
        if (raw instanceof Number) return ((Number) raw).floatValue();
        try { return Float.parseFloat(String.valueOf(raw).trim()); } catch (Exception ignored) { return null; }
    }

    private static Short toShort(Object raw) {
        if (raw instanceof Number) return ((Number) raw).shortValue();
        try { return Short.parseShort(String.valueOf(raw).trim()); } catch (Exception ignored) { return null; }
    }

    private static Byte toByte(Object raw) {
        if (raw instanceof Number) return ((Number) raw).byteValue();
        try { return Byte.parseByte(String.valueOf(raw).trim()); } catch (Exception ignored) { return null; }
    }

    private static Character toChar(Object raw) {
        String s = String.valueOf(raw);
        return s.isEmpty() ? null : s.charAt(0);
    }

    private static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("true", "t", "yes", "y", "on", "1").contains(s)) return true;
        if (Arrays.asList("false", "f", "no", "n", "off", "0").contains(s)) return false;
        return null;
    }

    /** Converts a raw map key into the requested key type (String or Enum supported). */
    private Object convertMapKey(Object key, Type keyType) {
        if (keyType instanceof Class<?>) {
            Class<?> kc = (Class<?>) keyType;
            if (kc == String.class || kc == Object.class) return String.valueOf(key);
            if (kc.isEnum()) {
                String s = String.valueOf(key);
                for (Object c : kc.getEnumConstants()) {
                    if (((Enum<?>) c).name().equalsIgnoreCase(s)) return c;
                }
                return null;
            }
        }
        // Fallback to string key
        return String.valueOf(key);
    }
}
