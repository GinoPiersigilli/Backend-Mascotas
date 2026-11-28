package ar.org.refugio.gateway;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reglas de arquitectura verificadas automaticamente en gateway (WS-6 / ADR-09).
 */
@AnalyzeClasses(packages = "ar.org.refugio.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag("unit")
class ArquitecturaTest {

    @ArchTest
    static final ArchRule gatewayNoDependeDeServiciosNiDeCommon = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ar.org.refugio.common..",
                    "ar.org.refugio.animales..",
                    "ar.org.refugio.adopciones..",
                    "ar.org.refugio.donaciones..");

    @ArchTest
    static final ArchRule sinLombok = noClasses()
            .should().dependOnClassesThat().resideInAPackage("lombok..");

    @ArchTest
    static final ArchRule sinValueDisperso = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value");
}
