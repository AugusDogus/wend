package dev.augie.wend;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Wend's MCP server: lets an agent use {@link AgentTools} over MCP's Streamable HTTP transport,
 * each JSON-RPC message POSTed to {@code /mcp} and answered as JSON, at port {@value #PORT}.
 *
 * <p>Who may use it ({@link #allowed}): the head unit itself (or a computer through
 * {@code adb forward tcp:7311 tcp:7311}), and any machine of the owner's tailnet, over it, such as
 * the host of an MCP gateway agents reach. The tailnet is the boundary: Tailscale's WireGuard ties
 * each tailnet address to one machine, and the tailnet's access rules say which may reach this
 * port. The car's own network is turned away. It turns away browsers' requests, which carry an
 * Origin, so no web page can drive it. Started
 * once per process, as Wend's process starts ({@link Starter}), whether for its home screen or for
 * a report from the Maps bridge module.
 */
final class AgentServer {
    static final int PORT = 7311;
    private static final int MAX_HEADERS = 16 * 1024, MAX_BODY = 1024 * 1024, READ_TIMEOUT_MS = 10_000;
    private static AgentServer started;
    private final McpProtocol protocol;
    private final ExecutorService connections = Executors.newFixedThreadPool(4);

    private AgentServer(McpProtocol protocol) { this.protocol = protocol; }

    /** Starts the server as Wend's process starts: Android creates a process's providers before anything else runs in it. */
    public static final class Starter extends ContentProvider {
        @Override public boolean onCreate() {
            start(getContext());
            return true;
        }
        // Answers nothing; it only starts the server.
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }

    /** Starts the server unless it runs already. Main thread. */
    static void start(Context context) {
        if (started != null) return;
        started = new AgentServer(new McpProtocol(new AgentTools(context.getApplicationContext())));
        new Thread(started::listen, "WendAgentServer").start();
    }

    private void listen() {
        // Every interface, for the tailnet's; allowed() turns away all but the unit and the tailnet.
        try (ServerSocket server = new ServerSocket(PORT, 8)) {
            Log.i(AgentTools.TAG, "Agents can use Wend's tools over MCP at http://127.0.0.1:" + PORT + "/mcp, and from the"
                    + " tailnet at this unit's Tailscale address");
            while (true) {
                Socket socket = server.accept();
                connections.execute(() -> serve(socket));
            }
        } catch (IOException | RuntimeException error) {
            Log.e(AgentTools.TAG, "Wend's MCP server stopped: it could not listen on port " + PORT
                    + " (another app may hold that port). Wend works as before, but agents cannot use its tools"
                    + " until Wend restarts", error);
        }
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();
            if (!allowed(connection.getInetAddress(), connection.getLocalAddress())) {
                Log.w(AgentTools.TAG, "Turned away " + connection.getInetAddress().getHostAddress() + ", which is not on the tailnet");
                respond(out, 403, "text/plain", "Wend's MCP server answers this head unit and its tailnet, at the unit's Tailscale"
                        + " address\n");
                return;
            }
            String head = readHead(in);
            if (head == null) {
                respond(out, 400, "text/plain", "Wend's MCP server takes HTTP/1.1 requests with headers under 16 KiB\n");
                return;
            }
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            if (request.length < 3 || !request[1].split("\\?")[0].equals("/mcp")) {
                respond(out, 404, "text/plain", "Wend's MCP server answers at /mcp\n");
            } else if (headers.containsKey("origin")) {
                respond(out, 403, "text/plain", "Wend's MCP server does not answer web pages\n");
            } else if (!request[0].equals("POST")) {
                respond(out, 405, "text/plain", "Wend's MCP server takes each message as a POST; it streams no events\n");
            } else {
                int length = contentLength(headers.get("content-length"));
                if (length < 0) {
                    respond(out, 411, "text/plain", "Wend's MCP server needs a Content-Length of at most 1 MiB\n");
                    return;
                }
                byte[] body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) return;
                    read += n;
                }
                // The tools go to the main thread for what they need there.
                JSONObject answer = protocol.answer(new String(body, StandardCharsets.UTF_8));
                if (answer == null) respond(out, 202, "text/plain", "");
                else respond(out, 200, "application/json", answer.toString());
            }
        } catch (Exception error) {
            Log.w(AgentTools.TAG, "Wend's MCP server could not answer a request; the agent may retry it", error);
        }
    }

    /**
     * @param from where a connection comes from
     * @param to the unit's address it came to
     * @return whether it may use the tools: from the unit itself, or over the tailnet (from a
     *     Tailscale address to the unit's)
     */
    static boolean allowed(InetAddress from, InetAddress to) {
        return from.isLoopbackAddress() || tailnet(from) && tailnet(to);
    }

    /** @return whether {@code address} is a Tailscale address, in 100.64.0.0/10 */
    private static boolean tailnet(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    /** @return the request line and headers, without the blank line after them; null if too long or cut off */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int lastFour = 0;
        while (head.size() < MAX_HEADERS) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            lastFour = lastFour << 8 | b;
            if (lastFour == ('\r' << 24 | '\n' << 16 | '\r' << 8 | '\n')) {
                byte[] bytes = head.toByteArray();
                return new String(bytes, 0, bytes.length - 4, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    private static int contentLength(String header) {
        if (header == null) return -1;
        try {
            int length = Integer.parseInt(header);
            return length >= 0 && length <= MAX_BODY ? length : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static void respond(OutputStream out, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 202 ? "Accepted" : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : "Length Required";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: "
                + bytes.length + "\r\n" + (status == 405 ? "Allow: POST\r\n" : "") + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }
}
