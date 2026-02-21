package riid.p2p;

import java.util.Map;

import DragonflyCommon.v2.Download;
import DragonflyCommon.v2.Priority;
import DragonflyCommon.v2.TaskType;
import DragonflyDfdaemon.v2.DownloadTaskRequest;

/**
 * Builds DownloadTaskRequest for dfdaemon DfdaemonDownload.DownloadTask RPC (v2 API).
 */
public final class DownloadTaskRequestBuilder {

    private DownloadTaskRequestBuilder() {
    }

    /**
     * Builds request for OCI blob download.
     *
     * @param url         blob URL (e.g. https://registry/v2/repo/blobs/sha256:xxx)
     * @param outputPath  path where dfdaemon will write the file (container path for unix socket)
     * @param digest      optional digest (e.g. sha256:xxx) for validation
     * @param headers     optional request headers (e.g. Authorization for registry)
     */
    public static DownloadTaskRequest build(String url, String outputPath,
                                            String digest, Map<String, String> headers) {
        Download.Builder download = Download.newBuilder()
                .setUrl(url)
                .setOutputPath(outputPath)
                .setType(TaskType.STANDARD)
                .setPriority(Priority.LEVEL0);
        if (digest != null && !digest.isBlank()) {
            download.setDigest(digest);
        }
        if (headers != null && !headers.isEmpty()) {
            download.putAllRequestHeader(headers);
        }
        return DownloadTaskRequest.newBuilder()
                .setDownload(download.build())
                .build();
    }
}
