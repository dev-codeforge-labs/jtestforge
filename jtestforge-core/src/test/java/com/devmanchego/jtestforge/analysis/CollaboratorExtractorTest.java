package com.devmanchego.jtestforge.analysis;

import com.devmanchego.jtestforge.model.Collaborator;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CollaboratorExtractorTest {

    @Test
    void theSoleConstructorsParametersAreCollaborators() {
        List<Collaborator> collaborators = extract("""
                class Foo {
                    Foo(PaymentGateway gateway, Logger logger) { }
                }
                """);

        assertThat(collaborators)
                .extracting(Collaborator::name, Collaborator::typeFqn)
                .containsExactly(tuple("gateway", "PaymentGateway"), tuple("logger", "Logger"));
    }

    @Test
    void aClassWithNoConstructorAndNoAnnotatedFieldsHasNoCollaborators() {
        assertThat(extract("""
                class Foo {
                    private String label = "x";
                }
                """)).isEmpty();
    }

    @Test
    void plainUnannotatedFieldsAreNeverCollaborators() {
        // A value field (e.g. a cached default) must not be mistaken for a mock target.
        assertThat(extract("""
                class Foo {
                    Foo(Gateway gateway) { }
                    private java.math.BigDecimal defaultFee = java.math.BigDecimal.ONE;
                }
                """)).extracting(Collaborator::name).containsExactly("gateway");
    }

    @Test
    void fieldsAnnotatedAutowiredInjectOrResourceAreCollaboratorsEvenWithoutAConstructor() {
        List<Collaborator> collaborators = extract("""
                class Foo {
                    @Autowired
                    private Logger logger;
                    @Inject
                    private Clock clock;
                    @Resource
                    private Gateway gateway;
                    private String notInjected;
                }
                """);

        assertThat(collaborators).extracting(Collaborator::name)
                .containsExactlyInAnyOrder("logger", "clock", "gateway");
    }

    @Test
    void withMultipleConstructorsTheAnnotatedOneIsPreferred() {
        List<Collaborator> collaborators = extract("""
                class Foo {
                    Foo() { }
                    @Autowired
                    Foo(Gateway gateway, Logger logger) { }
                    Foo(Gateway gateway, Logger logger, Clock clock) { }
                }
                """);

        assertThat(collaborators).extracting(Collaborator::name)
                .containsExactly("gateway", "logger");
    }

    @Test
    void withMultipleUnannotatedConstructorsTheOneWithMostParametersIsUsed() {
        List<Collaborator> collaborators = extract("""
                class Foo {
                    Foo() { }
                    Foo(Gateway gateway) { }
                    Foo(Gateway gateway, Logger logger) { }
                }
                """);

        assertThat(collaborators).extracting(Collaborator::name)
                .containsExactly("gateway", "logger");
    }

    @Test
    void constructorParametersAndAnnotatedFieldsAreMergedWithoutDuplicatesByName() {
        List<Collaborator> collaborators = extract("""
                class Foo {
                    @Autowired
                    private Logger logger;
                    Foo(Logger logger) { }
                }
                """);

        assertThat(collaborators).extracting(Collaborator::name).containsExactly("logger");
    }

    private List<Collaborator> extract(String classSource) {
        ClassOrInterfaceDeclaration declaration = StaticJavaParser.parse(classSource)
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
        return CollaboratorExtractor.extract(declaration);
    }
}
