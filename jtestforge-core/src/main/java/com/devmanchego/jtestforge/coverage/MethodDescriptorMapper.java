package com.devmanchego.jtestforge.coverage;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a JVM method descriptor's parameter types into the same dot-separated form
 * {@code TypeResolution} produces from the AST — jtestforge-implementation-plan.md phase
 * 8: "correct for overloads, generics erasure, varargs and inner classes."
 *
 * <p>A JVM descriptor already carries what {@code CoverageMethodJoiner} needs precisely
 * because it is erased: {@code List<String>} and {@code List<Integer>} are both just
 * {@code Ljava/util/List;} in the descriptor, exactly as generics erasure makes them
 * identical at the bytecode level. The AST side of the join must be erased to match
 * (stripping {@code <...>} type arguments), which {@code CoverageMethodJoiner} does; this
 * class only decodes the descriptor itself.
 *
 * <p>Varargs are not distinguished from an ordinary array parameter at the descriptor
 * level - {@code String... args} and {@code String[] args} both decode as
 * {@code java.lang.String[]}. That is the JVM's own behaviour (the {@code ACC_VARARGS}
 * flag lives on the method, not in the descriptor), and {@code CoverageMethodJoiner}
 * relies on the AST recording a vararg parameter's type as the array type too.
 */
final class MethodDescriptorMapper {

    private MethodDescriptorMapper() {
    }

    /** @return the descriptor's parameter types, in declaration order. */
    static List<String> parameterTypesOf(String jvmDescriptor) {
        List<String> types = new ArrayList<>();
        int index = 1; // skip the opening '('
        while (index < jvmDescriptor.length() && jvmDescriptor.charAt(index) != ')') {
            int[] end = new int[1];
            types.add(decodeType(jvmDescriptor, index, end));
            index = end[0];
        }
        return types;
    }

    /** Decodes one type starting at {@code start}; writes the index just past it into {@code endOut[0]}. */
    private static String decodeType(String descriptor, int start, int[] endOut) {
        int index = start;
        int arrayDimensions = 0;
        while (descriptor.charAt(index) == '[') {
            arrayDimensions++;
            index++;
        }

        String baseType;
        char code = descriptor.charAt(index);
        switch (code) {
            case 'L' -> {
                int semicolon = descriptor.indexOf(';', index);
                baseType = descriptor.substring(index + 1, semicolon).replace('/', '.').replace('$', '.');
                index = semicolon + 1;
            }
            case 'B' -> {
                baseType = "byte";
                index++;
            }
            case 'C' -> {
                baseType = "char";
                index++;
            }
            case 'D' -> {
                baseType = "double";
                index++;
            }
            case 'F' -> {
                baseType = "float";
                index++;
            }
            case 'I' -> {
                baseType = "int";
                index++;
            }
            case 'J' -> {
                baseType = "long";
                index++;
            }
            case 'S' -> {
                baseType = "short";
                index++;
            }
            case 'Z' -> {
                baseType = "boolean";
                index++;
            }
            default -> throw new IllegalArgumentException(
                    "Unrecognised type code '" + code + "' in descriptor: " + descriptor);
        }

        endOut[0] = index;
        return baseType + "[]".repeat(arrayDimensions);
    }
}
