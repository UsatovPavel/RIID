package riid.p2p;

import java.util.Map;

import DragonflyCommon.v2.Common;
import DragonflyDfdaemon.v2.Dfdaemon;

/**
 * Builds DownloadTaskRequest for dfdaemon DownloadTask RPC.
 */
public final class DownloadTaskRequestBuilder {

    private DownloadTaskRequestBuilder() {
    }

    /**
     * Builds request for OCI blob download.
     *
     * @param url         blob URL (e.g. https://registry/v2/repo/blobs/sha256:xxx)
     * @param outputPath  path where dfdaemon will write the file (hardlink/copy)
     * @param digest      optional digest (e.g. sha256:xxx) for validation
     * @param headers     optional request headers (e.g. Authorization for registry)
     */
    public static Dfdaemon.DownloadTaskRequest build(String url, String outputPath,
                                            String digest, Map<String, String> headers) {
        Common.Download.Builder download = Common.Download.newBuilder()
                .setUrl(url)
                .setNeedPieceContent(false)
                .setEnableTaskIdBasedBlobDigest(true);
        if (outputPath != null && !outputPath.isBlank()) {
            download.setOutputPath(outputPath);
        }
        if (digest != null && !digest.isBlank()) {
            download.setDigest(digest);
        }
        if (headers != null && !headers.isEmpty()) {
            download.putAllRequestHeader(headers);
        }
        return Dfdaemon.DownloadTaskRequest.newBuilder()
                .setDownload(download.build())
                .build();
    }
}
