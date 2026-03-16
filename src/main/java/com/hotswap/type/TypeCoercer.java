package com.hotswap.type;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotswap.annotation.HotSwapType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Converts raw String values from config sources into target Java types.
 *
 * <p>Handles both explicit {@link HotSwapType} hints and automatic
 * type inference from the annotated field's declared type.</p>
 *
 * @since 1.0.0
 */
public class TypeCoercer {

    private static final Logger log = LoggerFactory.getLogger(TypeCoercer.class);

    private final ObjectMapper objectMapper;

    public TypeCoercer() {
        this.objectMapper = new ObjectMapper();
    }

    public TypeCoercer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Coerce a raw string value to the target type.
     *
     * @param rawValue the raw string from the config source
     * @param type     the explicit type hint (or INFERRED)
     * @param field    the target field (used for INFERRED and JSON type resolution)
     * @return the coerced value
     * @throws HotSwapTypeException if coercion fails
     */
    public Object coerce(String rawValue, HotSwapType type, Field field) {
        if (rawValue == null) {
            return null;
        }

        HotSwapType resolvedType = type;
        if (type == HotSwapType.INFERRED) {
            resolvedType = inferType(field);
        }

        try {
            return doCoerce(rawValue, resolvedType, field);
        } catch (Exception e) {
            throw new HotSwapTypeException(
                    "Failed to coerce value '" + rawValue + "' to " + resolvedType
                            + " for field " + field.getDeclaringClass().getSimpleName() + "." + field.getName(),
                    e);
        }
    }

    /**
     * Infer the {@link HotSwapType} from a field's declared Java type.
     *
     * @param field the annotated field
     * @return the inferred HotSwapType
     */
    public HotSwapType inferType(Field field) {
        Class<?> fieldType = field.getType();

        if (fieldType == boolean.class || fieldType == Boolean.class) {
            return HotSwapType.BOOLEAN;
        }
        if (fieldType == String.class) {
            return HotSwapType.STRING;
        }
        if (fieldType == int.class || fieldType == Integer.class) {
            return HotSwapType.INTEGER;
        }
        if (fieldType == long.class || fieldType == Long.class) {
            return HotSwapType.LONG;
        }
        if (fieldType == double.class || fieldType == Double.class) {
            return HotSwapType.DOUBLE;
        }

        // Complex types default to JSON deserialization
        log.debug("Field {}.{} has complex type {}, defaulting to JSON coercion",
                field.getDeclaringClass().getSimpleName(), field.getName(), fieldType.getSimpleName());
        return HotSwapType.JSON;
    }

    private Object doCoerce(String rawValue, HotSwapType type, Field field) throws Exception {
        return switch (type) {
            case BOOLEAN -> parseBoolean(rawValue);
            case STRING -> rawValue;
            case INTEGER -> Integer.parseInt(rawValue.trim());
            case LONG -> Long.parseLong(rawValue.trim());
            case DOUBLE -> Double.parseDouble(rawValue.trim());
            case JSON -> objectMapper.readValue(rawValue, field.getType());
            case INFERRED -> rawValue; // Fallback — should not reach here
        };
    }

    private Boolean parseBoolean(String value) {
        String trimmed = value.trim().toLowerCase();
        if ("true".equals(trimmed) || "1".equals(trimmed) || "yes".equals(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equals(trimmed) || "0".equals(trimmed) || "no".equals(trimmed)) {
            return Boolean.FALSE;
        }
        throw new HotSwapTypeException("Cannot parse boolean from: '" + value + "'");
    }
}
