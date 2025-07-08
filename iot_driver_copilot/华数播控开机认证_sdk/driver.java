import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP Server driver for 华数 WasuSDK device, exposing endpoints to initialize, authenticate,
 * and set test mode. All configuration is via environment variables.
 */
public class WasuSDKHttpDriver {

    // Environment variable names
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";
    private static final String ENV_DEVICE_PROTOCOL = "DEVICE_PROTOCOL";
    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";

    // Device API paths (assuming HTTP-based SDK API)
    private static final String DEVICE_INIT_PATH = "/init";
    private static final String DEVICE_CHECKIN_PATH = "/checkin";
    private static final String DEVICE_TEST_PATH = "/test";

    // HTTP Response codes
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_ERROR = 500;

    public static void main(String[] args) throws Exception {
        String serverHost = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        int serverPort = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(serverHost, serverPort), 0);

        server.createContext("/init", new ProxyHandler(DEVICE_INIT_PATH));
        server.createContext("/checkin", new ProxyHandler(DEVICE_CHECKIN_PATH));
        server.createContext("/test", new ProxyHandler(DEVICE_TEST_PATH));

        server.setExecutor(null);
        System.out.printf("WasuSDK HTTP driver started on http://%s:%d%n", serverHost, serverPort);
        server.start();
    }

    // Handler to proxy requests to the device and return JSON responses.
    static class ProxyHandler implements HttpHandler {
        private final String deviceApiPath;

        ProxyHandler(String deviceApiPath) {
            this.deviceApiPath = deviceApiPath;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, HTTP_BAD_REQUEST, "{\"error\":\"Only POST supported\"}");
                return;
            }

            String requestBody = readRequestBody(exchange);
            Map<String, String> deviceEnv = getDeviceEnv();
            if (deviceEnv == null) {
                sendJsonResponse(exchange, HTTP_INTERNAL_ERROR, "{\"error\":\"Device environment variables not set\"}");
                return;
            }

            String deviceUrl = String.format("%s://%s:%s%s",
                    deviceEnv.get("protocol"),
                    deviceEnv.get("ip"),
                    deviceEnv.get("port"),
                    deviceApiPath
            );

            try {
                String deviceResp = forwardToDevice(deviceUrl, requestBody);
                sendJsonResponse(exchange, HTTP_OK, deviceResp);
            } catch (IOException ex) {
                sendJsonResponse(exchange, HTTP_INTERNAL_ERROR, String.format("{\"error\":\"%s\"}", ex.getMessage()));
            }
        }
    }

    // Read and return request body as string
    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = is.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    // Forward request to device and return device response as string
    private static String forwardToDevice(String deviceUrl, String jsonBody) throws IOException {
        URL url = new URL(deviceUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        InputStream is = (responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            throw new IOException("No response from device");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = is.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    // Send a JSON response to the HTTP client
    private static void sendJsonResponse(HttpExchange exchange, int status, String jsonBody) throws IOException {
        byte[] resp = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, resp.length);
        OutputStream os = exchange.getResponseBody();
        os.write(resp);
        os.close();
    }

    // Get device connection parameters from environment variables
    private static Map<String, String> getDeviceEnv() {
        String ip = System.getenv(ENV_DEVICE_IP);
        String port = System.getenv(ENV_DEVICE_PORT);
        String protocol = System.getenv(ENV_DEVICE_PROTOCOL);
        if (ip == null || port == null || protocol == null) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        map.put("ip", ip);
        map.put("port", port);
        map.put("protocol", protocol);
        return map;
    }

    // Get environment variable or default value
    private static String getEnvOrDefault(String env, String def) {
        String value = System.getenv(env);
        return (value != null && !value.isEmpty()) ? value : def;
    }
}