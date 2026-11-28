package utnfc.isi.back.tpi.animales;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reglas de arquitectura verificadas automaticamente en ms-animales (WS-6).
 */
@AnalyzeClasses(packages = "utnfc.isi.back.tpi.animales", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag("unit")
class ArquitecturaTest {

    @ArchTest
    static final ArchRule controladoresNoAccedenARepositorios = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule dominioNoDependeDeCapasWebNiServicios = noClasses()
            .that().resideInAPackage("..model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..",
                    "..controller..",
                    "..service..",
                    "..repository..",
                    "..dto..");

    @ArchTest
    static final ArchRule sinDependenciasDeOtrosMicroservicios = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "utnfc.isi.back.tpi.adopciones..",
                    "utnfc.isi.back.tpi.donaciones..",
                    "utnfc.isi.back.tpi.gateway..");

    @ArchTest
    static final ArchRule sinLombok = noClasses()
            .should().dependOnClassesThat().resideInAPackage("lombok..");

    @ArchTest
    static final ArchRule sinValueDisperso = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.beans.factory.annotation.Value");
}
