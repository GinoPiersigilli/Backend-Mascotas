package utnfc.isi.back.tpi.adopciones;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reglas de arquitectura verificadas automaticamente en ms-adopciones (WS-6).
 */
@AnalyzeClasses(packages = "utnfc.isi.back.tpi.adopciones", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule controladoresNoAccedenARepositorios = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule dominioNoDependeDeCapasWebServiciosNiClientes = noClasses()
            .that().resideInAPackage("..model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..",
                    "..controller..",
                    "..service..",
                    "..client..",
                    "..repository..",
                    "..dto..");

    @ArchTest
    static final ArchRule sinDependenciasDeOtrosMicroservicios = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "utnfc.isi.back.tpi.animales..",
                    "utnfc.isi.back.tpi.donaciones..",
                    "utnfc.isi.back.tpi.gateway..");

    @ArchTest
    static final ArchRule sinLombok = noClasses()
            .should().dependOnClassesThat().resideInAPackage("lombok..");

    @ArchTest
    static final ArchRule sinValueDisperso = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value");
}
