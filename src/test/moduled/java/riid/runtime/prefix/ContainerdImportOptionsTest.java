package riid.runtime.prefix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static riid.runtime.prefix.PrefixImportFixtures.hex;
import static riid.runtime.prefix.PrefixImportFixtures.layerDigestHex;
import static riid.runtime.prefix.PrefixImportFixtures.layoutWithBlobs;
import static riid.runtime.prefix.PrefixImportFixtures.manifest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import riid.core.model.manifest.Manifest;
import riid.runtime.BoundedCommandExecution.ShellResult;
import riid.runtime.RuntimeConfig;
import riid.runtime.adapter.ContainerdRuntimeAdapter;
import riid.runtime.adapter.ContainerdRuntimeAdapter.ImportOptions;
import riid.runtime.adapter.ImageReference;
import riid.runtime.adapter.IncrementalImageImport;

/**
 * The {@code ctr images import} switches AGENT-95 adds. Every one of them is
 * off by default, and {@code ctr} rejects two of them in combination, so what
 * the command line ends up saying is worth pinning down.
 */
@Tag("filesystem")
class ContainerdImportOptionsTest {

    private static final ImageReference IMAGE = new ImageReference("library/python", "latest");
    private static final int LAYERS_COUNT = 3;
    private static final String DISCARD = "--discard-unpacked-layers";
    private static final String LOCAL = "--local";

    @TempDir
    private Path workDir;

    @Test
    void byDefaultTheCommandIsTheOneRiidAlwaysEmitted() throws Exception {
        List<String> cmd = wholeImportCommand(ImportOptions.defaults());

        assertEquals(List.of("ctr", "images", "import"), cmd.subList(0, cmd.size() - 1),
                "no switch may appear unless it was configured");
    }

    @Test
    void snapshotterIsPassedThroughWhenConfigured() throws Exception {
        List<String> cmd = wholeImportCommand(new ImportOptions("erofs", false));

        assertEquals(List.of("--snapshotter", "erofs"), cmd.subList(3, 5));
    }

    @Test
    void blankSnapshotterLeavesCtrItsOwnDefault() throws Exception {
        assertFalse(wholeImportCommand(new ImportOptions("  ", false)).contains("--snapshotter"),
                "an empty value must not become an empty argument");
    }

    /**
     * ctr 2.2 refuses the flag on its own: discarding is implemented by the local
     * importer, not by the transfer service the import otherwise uses.
     */
    @Test
    void discardingUnpackedLayersDragsLocalAlongWithIt() throws Exception {
        List<String> cmd = wholeImportCommand(new ImportOptions(null, true));

        assertTrue(cmd.contains(LOCAL), "--discard-unpacked-layers without --local is rejected by ctr");
        assertEquals(cmd.indexOf(LOCAL) + 1, cmd.indexOf(DISCARD));
    }

    /**
     * Every step of a prefix import gets the same switches, the intermediate ones
     * included: verified on a clean containerd, the image still runs afterwards.
     */
    @Test
    void everyPrefixStepCarriesTheConfiguredSwitches() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter(new ImportOptions(null, true));

        runSession(adapter);

        List<List<String>> imports = adapter.importCommands();
        assertEquals(LAYERS_COUNT, imports.size(), "two prefixes and the real image");
        for (List<String> cmd : imports) {
            assertTrue(cmd.contains(DISCARD), cmd.toString());
        }
    }

    @Test
    void byDefaultAPrefixImportEmitsNoSwitchEither() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter(ImportOptions.defaults());

        runSession(adapter);

        for (List<String> cmd : adapter.importCommands()) {
            assertFalse(cmd.contains(DISCARD), cmd.toString());
            assertFalse(cmd.contains(LOCAL), cmd.toString());
        }
    }

    @Test
    void configTurnsTheYamlKeysIntoImportOptions() {
        RuntimeConfig config = new RuntimeConfig(null, null, null, null, "erofs", true);

        assertEquals(new ImportOptions("erofs", true), config.containerdImportOptions());
        assertEquals(ImportOptions.defaults(),
                new RuntimeConfig(null, null, null, null, null, null).containerdImportOptions(),
                "keys left out of the YAML mean every switch stays off");
    }

    /** The command of a plain, non-prefix import of a file. */
    private List<String> wholeImportCommand(ImportOptions options) throws Exception {
        Path image = Files.writeString(workDir.resolve("image.tar"), "not really a tar");
        RecordingAdapter adapter = new RecordingAdapter(options);
        adapter.importImage(image);
        return adapter.importCommands().get(0);
    }

    private void runSession(RecordingAdapter adapter) throws Exception {
        Manifest manifest = manifest(LAYERS_COUNT);
        Path blobs = layoutWithBlobs(workDir, manifest);
        try (IncrementalImageImport session = adapter.beginIncrementalImport(IMAGE, manifest)) {
            session.imageConfig(blobs.resolve(hex(manifest.config().digest())));
            for (int i = 0; i < LAYERS_COUNT; i++) {
                session.importLayer(manifest.layers().get(i), blobs.resolve(layerDigestHex(i)));
            }
            session.finish();
        }
    }

    /**
     * Records the command lines instead of running {@code tar} and {@code ctr}. The
     * piped path reports its command through {@code startProcess}, the plain one
     * through {@code runCommand}.
     */
    private static final class RecordingAdapter extends ContainerdRuntimeAdapter {
        private final List<List<String>> commands = new CopyOnWriteArrayList<>();

        private RecordingAdapter(ImportOptions options) {
            super("ctr", null, null, options, true);
        }

        /** Only the imports, in the order they were handed over. */
        private List<List<String>> importCommands() {
            List<List<String>> imports = new ArrayList<>();
            for (List<String> cmd : commands) {
                if (cmd.contains("import")) {
                    imports.add(cmd);
                }
            }
            return imports;
        }

        @Override
        protected Process startProcess(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            // "tar" writes nothing, so "ctr" sees an empty stdin and exits at once
            return new ProcessBuilder("true").start();
        }

        @Override
        protected ShellResult runCommand(List<String> command) {
            commands.add(List.copyOf(command));
            return new ShellResult(0, "", "");
        }
    }
}
