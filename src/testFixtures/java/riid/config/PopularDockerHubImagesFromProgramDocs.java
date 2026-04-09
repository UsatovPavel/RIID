package riid.config;

import java.util.List;
import java.util.Objects;

/**
 * First 30 repository names (tag {@code latest} everywhere) from
 * {@code internalDocs/programsDocumentation/external environment/registry/PopularDockerImagesSizes.txt} —
 * data lines immediately after the header, no sorting applied.
 */
public final class PopularDockerHubImagesFromProgramDocs {

    public static final String POPULAR_IMAGES_REFERENCE = "latest";

    /**
     * 30 entries: file rows 2–31 (1-based line numbers), header excluded.
     */
    public static final List<String> FIRST_30_REPOSITORIES = List.of(
            "library/hello-seattle",
            "library/hola-mundo",
            "library/cirros",
            "library/jobber",
            "library/photon",
            "library/api-firewall",
            "library/eggdrop",
            "library/hitch",
            "library/spiped",
            "library/express-gateway",
            "library/thrift",
            "library/alt",
            "library/hylang",
            "library/irssi",
            "library/unit",
            "library/euleros",
            "library/clefos",
            "library/krakend",
            "library/neurodebian",
            "library/almalinux",
            "library/clearlinux",
            "library/sl",
            "library/fluentd",
            "library/celery",
            "library/rapidoid",
            "library/satosa",
            "library/swipl",
            "library/mageia",
            "library/emqx",
            "library/liquibase");

    /**
     * PR15 scenario (c) warm-cache runs: same order as {@link #FIRST_30_REPOSITORIES} minus repositories
     * that routinely fail on typical amd64 Linux (wrong architecture in the Hub manifest).
     * Currently excludes {@code library/clefos}.
     */
    public static final List<String> SCENARIO_C_WARM_REPOSITORIES = FIRST_30_REPOSITORIES.stream()
            .filter(PopularDockerHubImagesFromProgramDocs::includedInScenarioCWarm)
            .toList();

    private static boolean includedInScenarioCWarm(String repo) {
        return !Objects.equals(repo, "library/clefos");
    }

    private PopularDockerHubImagesFromProgramDocs() { }
}
