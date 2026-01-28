package riid.integration.architecture;

import org.junit.jupiter.api.Tag;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@Tag("Arch")
@AnalyzeClasses(packages = "riid", importOptions = ImportOption.DoNotIncludeTests.class)
class ClientRuntimeDependencyArchTest {

    @ArchTest
    static final ArchRule client_should_not_depend_on_runtime =
            noClasses()
                    .that().resideInAPackage("riid.client..")
                    .should().dependOnClassesThat().resideInAPackage("riid.runtime..");

    @ArchTest
    static final ArchRule runtime_should_not_depend_on_client =
            noClasses()
                    .that().resideInAPackage("riid.runtime..")
                    .should().dependOnClassesThat().resideInAPackage("riid.client..");

    @ArchTest
    static final ArchRule runtime_should_not_depend_on_dispatcher =
            noClasses()
                    .that().resideInAPackage("riid.runtime..")
                    .should().dependOnClassesThat().resideInAPackage("riid.dispatcher..");
}

