package dev.augie.wend;

import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Model Context Protocol's JSON-RPC messages, as Wend's MCP server ({@link AgentServer})
 * answers them: initialize, ping, tools/list and tools/call, for the tools of {@link AgentTools}.
 * It offers no resources, prompts or server-sent events. Any thread, as the tools are.
 */
final class McpProtocol {
    /** The protocol versions it speaks, newest first; a client asking for another gets the newest. */
    static final List<String> VERSIONS = Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26");
    private static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601, INVALID_PARAMS = -32602;
    private final AgentTools tools;

    McpProtocol(AgentTools tools) { this.tools = tools; }

    /** @return the response to the message {@code body}, or null for a notification or a response, which get none */
    JSONObject answer(String body) {
        JSONObject message;
        try {
            message = new JSONObject(body);
        } catch (JSONException error) {
            return error(JSONObject.NULL, PARSE_ERROR, "Wend's MCP server takes one JSON-RPC message as a JSON object: " + error.getMessage());
        }
        Object id = message.opt("id");
        String method = message.optString("method", "");
        if (id == null || message.has("result") || message.has("error")) return null;
        if (method.isEmpty()) return error(id, INVALID_REQUEST, "The request has no method");
        JSONObject params = message.optJSONObject("params");
        if (params == null) params = new JSONObject();
        try {
            switch (method) {
                case "initialize": return result(id, initialize(params));
                case "ping": return result(id, new JSONObject());
                case "tools/list": return result(id, new JSONObject().put("tools", tools.list()));
                case "tools/call": return call(id, params);
                default: return error(id, METHOD_NOT_FOUND, "Wend's MCP server has no method " + method
                        + "; it answers initialize, ping, tools/list and tools/call");
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JSONObject initialize(JSONObject params) throws JSONException {
        String asked = params.optString("protocolVersion", "");
        return new JSONObject()
                .put("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(0))
                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                .put("serverInfo", new JSONObject().put("name", "wend").put("version", BuildConfig.VERSION_NAME))
                .put("instructions", "Wend is the launcher of a car's head unit. Its tools drive Google Maps there, and the head unit itself.");
    }

    private JSONObject call(Object id, JSONObject params) throws JSONException {
        String name = params.optString("name", "");
        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) arguments = new JSONObject();
        AgentTools.Result result;
        try {
            result = tools.call(name, arguments);
        } catch (AgentTools.UnknownTool unknown) {
            return error(id, INVALID_PARAMS, unknown.getMessage());
        }
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", result.text));
        if (result.image != null) {
            content.put(new JSONObject().put("type", "image").put("mimeType", result.imageType)
                    .put("data", android.util.Base64.encodeToString(result.image, android.util.Base64.NO_WRAP)));
        }
        return result(id, new JSONObject().put("content", content).put("isError", result.error));
    }

    private static JSONObject result(Object id, JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject error(Object id, int code, String text) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", new JSONObject().put("code", code).put("message", text));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
