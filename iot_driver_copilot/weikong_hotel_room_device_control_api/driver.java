import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/**
 * Weikong Hotel Room Device Control API Driver HTTP Server
 * All configuration is via environment variables:
 *  - WK_DEVICE_IP:       Target device IP address
 *  - WK_DEVICE_PORT:     Target device port (default 80)
 *  - WK_SERVER_HOST:     HTTP server bind host (default 0.0.0.0)
 *  - WK_SERVER_PORT:     HTTP server port (default 8080)
 *  - WK_DEVICE_API_PATH: API base path on device (default /api)
 *  - WK_DEVICE_API_TOKEN: (optional) Auth token if required by device
 */
public class WeikongHotelRoomDriver {
    private static final String DEVICE_IP = getenv("WK_DEVICE_IP", "127.0.0.1");
    private static final int DEVICE_PORT = Integer.parseInt(getenv("WK_DEVICE_PORT", "80"));
    private static final String SERVER_HOST = getenv("WK_SERVER_HOST", "0.0.0.0");
    private static final int SERVER_PORT = Integer.parseInt(getenv("WK_SERVER_PORT", "8080"));
    private static final String DEVICE_API_PATH = getenv("WK_DEVICE_API_PATH", "/api");
    private static final String DEVICE_API_TOKEN = getenv("WK_DEVICE_API_TOKEN", null);

    public static void main(String[] args) throws Exception {
        InetSocketAddress addr = new InetSocketAddress(SERVER_HOST, SERVER_PORT);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/status", new StatusHandler());
        server.createContext("/curtain", new ProxyHandler("/curtain", "POST"));
        server.createContext("/proj", new ProxyHandler("/proj", "POST"));
        server.createContext("/light", new ProxyHandler("/light", "POST"));
        server.createContext("/ac", new ProxyHandler("/ac", "POST"));
        server.createContext("/clean", new ProxyHandler("/clean", "POST"));
        server.createContext("/tv", new ProxyHandler("/tv", "POST"));

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        System.out.printf("Weikong Hotel Room Driver HTTP server started at http://%s:%d\n", SERVER_HOST, SERVER_PORT);
        server.start();
    }

    // Handler for GET /status
    static class StatusHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            String deviceUrl = buildDeviceUrl("/status");
            HttpURLConnection conn = (HttpURLConnection) new URL(deviceUrl).openConnection();
            conn.setRequestMethod("GET");
            setCommonHeaders(conn);

            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            byte[] resp = readAllBytes(in);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(code, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        }
    }

    // Handler for all POST endpoints
    static class ProxyHandler implements HttpHandler {
        private final String path;
        private final String method;

        ProxyHandler(String path, String method) {
            this.path = path;
            this.method = method;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            String deviceUrl = buildDeviceUrl(path);
            HttpURLConnection conn = (HttpURLConnection) new URL(deviceUrl).openConnection();
            conn.setRequestMethod(method);
            setCommonHeaders(conn);
            conn.setDoOutput(true);

            // Copy request body (JSON)
            byte[] reqBody = readAllBytes(exchange.getRequestBody());
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(reqBody.length));
            OutputStream out = conn.getOutputStream();
            out.write(reqBody);
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            byte[] resp = readAllBytes(in);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(code, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        }
    }

    private static String buildDeviceUrl(String apiPath) {
        String base = String.format("http://%s:%d%s", DEVICE_IP, DEVICE_PORT, DEVICE_API_PATH);
        if (!apiPath.startsWith("/")) apiPath = "/" + apiPath;
        return base + apiPath;
    }

    private static void setCommonHeaders(HttpURLConnection conn) {
        if (DEVICE_API_TOKEN != null && !DEVICE_API_TOKEN.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + DEVICE_API_TOKEN);
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] resp = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bout.write(buf, 0, n);
        }
        return bout.toByteArray();
    }

    private static String getenv(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
}