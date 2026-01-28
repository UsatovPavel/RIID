package riid.integration.architecture;

import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@Tag("Arch")
@AnalyzeClasses(packages = "riid", importOptions = ImportOption.DoNotIncludeTests.class)
class AppDependencyArchTest {

    @ArchTest
    static final ArchRule client_should_not_depend_on_app =
            noClasses()
                    .that().resideInAPackage("riid.client..")
                    .should().dependOnClassesThat().resideInAPackage("riid.app..");

    @ArchTest
    static final ArchRule dispatcher_should_not_depend_on_app =
            noClasses()
                    .that().resideInAPackage("riid.dispatcher..")
                    .should().dependOnClassesThat(
                            resideInAPackage("riid.app..")
                                    .and(resideOutsideOfPackage("riid.app.fs.."))
                    );
    @ArchTest
    static final ArchRule runtime_should_not_depend_on_app =
            noClasses()
                    .that().resideInAPackage("riid.runtime..")
                    .should().dependOnClassesThat().resideInAPackage("riid.app..");

    @ArchTest
    static final ArchRule cache_should_not_depend_on_app =
            noClasses()
                    .that().resideInAPackage("riid.cache..")
                    .should().dependOnClassesThat(
                            resideInAPackage("riid.app..")
                                    .and(resideOutsideOfPackage("riid.app.fs.."))
                    );
                    
    @ArchTest
    static final ArchRule p2p_should_not_depend_on_app =
            noClasses()
                    .that().resideInAPackage("riid.p2p..")
                    .should().dependOnClassesThat(
                            resideInAPackage("riid.app..")
                                    .and(resideOutsideOfPackage("riid.app.fs.."))
                    );
}

