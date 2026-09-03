package com.devmanchego.jtestforge.coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MethodDescriptorMapperTest {

    @Test
    void decodesNoArguments() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("()V")).isEmpty();
    }

    @Test
    void decodesEveryPrimitiveTypeCode() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("(BCDFIJSZ)V"))
                .containsExactly("byte", "char", "double", "float", "int", "long", "short", "boolean");
    }

    @Test
    void decodesAReferenceType() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("(Ljava/lang/String;)V"))
                .containsExactly("java.lang.String");
    }

    @Test
    void decodesMultipleMixedParameters() {
        assertThat(MethodDescriptorMapper.parameterTypesOf(
                "(Ljava/math/BigDecimal;Ljava/util/Currency;I)Ljava/math/BigDecimal;"))
                .containsExactly("java.math.BigDecimal", "java.util.Currency", "int");
    }

    @Test
    void decodesAnArrayOfPrimitives() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("([I)I")).containsExactly("int[]");
    }

    @Test
    void decodesAnArrayOfReferenceType() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("([Ljava/lang/String;)V"))
                .containsExactly("java.lang.String[]");
    }

    @Test
    void decodesMultiDimensionalArrays() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("([[I)V")).containsExactly("int[][]");
    }

    @Test
    void convertsNestedClassBinaryNamesToDotForm() {
        assertThat(MethodDescriptorMapper.parameterTypesOf("(Lcom/acme/calc/Calculator$Formatter;)V"))
                .containsExactly("com.acme.calc.Calculator.Formatter");
    }

    @Test
    void aVarargsParameterDecodesIdenticallyToAnOrdinaryArray() {
        // The JVM makes no distinction: ACC_VARARGS lives on the method, not the
        // descriptor. sum(int... values) and sum(int[] values) produce the same "([I)I".
        List<String> varargsForm = MethodDescriptorMapper.parameterTypesOf("([I)I");
        List<String> arrayForm = MethodDescriptorMapper.parameterTypesOf("([I)I");

        assertThat(varargsForm).isEqualTo(arrayForm).containsExactly("int[]");
    }
}
