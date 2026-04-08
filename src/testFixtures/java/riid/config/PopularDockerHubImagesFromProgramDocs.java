package riid.config;

import java.util.List;

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

    private PopularDockerHubImagesFromProgramDocs() { }
}
