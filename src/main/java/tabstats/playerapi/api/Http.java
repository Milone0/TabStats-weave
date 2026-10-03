package tabstats.playerapi.api;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

/**
 * The one HTTP client every API talks through. The timeouts matter more than anything else here:
 * without them a single hung connection would hold a worker thread for good.
 */
final class Http {
    private static final int TIMEOUT_MS = 5_000;

    static final CloseableHttpClient CLIENT;

    static {
        PoolingHttpClientConnectionManager connections = new PoolingHttpClientConnectionManager();
        connections.setMaxTotal(32);
        connections.setDefaultMaxPerRoute(16);

        CLIENT = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(TIMEOUT_MS)
                        .setSocketTimeout(TIMEOUT_MS)
                        .setConnectionRequestTimeout(TIMEOUT_MS)
                        .build())
                .build();
    }

    private Http() {
    }
}
