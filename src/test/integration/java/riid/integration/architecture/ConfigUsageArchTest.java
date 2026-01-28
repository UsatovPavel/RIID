package riid.integration.architecture;

import org.junit.jupiter.api.Tag;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@Tag("Arch")
@AnalyzeClasses(packages = "riid", importOptions = ImportOption.DoNotIncludeTests.class)
class ConfigUsageArchTest {

    @ArchTest
    static final ArchRule config_should_only_be_used_by_app =
            noClasses()
                    .that().resideOutsideOfPackage("riid.app..")
                    .and().resideOutsideOfPackage("riid.integration..")
                    .and().resideOutsideOfPackage("riid.config..")
                    .should().dependOnClassesThat().resideInAPackage("riid.config..");
}

