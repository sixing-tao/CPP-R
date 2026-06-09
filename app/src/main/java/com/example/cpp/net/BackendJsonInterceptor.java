package com.example.cppr.net;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class BackendJsonInterceptor implements Interceptor {
    private static final String TAG = "BackendJSON";
    private static final int MAX_BYTES = 256 * 1024; // 256KB

    private static final Set<String> SENSITIVE_KEYS;
    static {
        SENSITIVE_KEYS = new HashSet<>();
        SENSITIVE_KEYS.add("authorization");
        SENSITIVE_KEYS.add("cookie");
        SENSITIVE_KEYS.add("cookies");
        SENSITIVE_KEYS.add("token");
        SENSITIVE_KEYS.add("access_token");
        SENSITIVE_KEYS.add("refresh_token");
        SENSITIVE_KEYS.add("password");
        SENSITIVE_KEYS.add("passwd");
        SENSITIVE_KEYS.add("secret");
        SENSITIVE_KEYS.add("apikey");
        SENSITIVE_KEYS.add("api_key");
        SENSITIVE_KEYS.add("auth");
    }

    @Override
    public Response intercept(Chain chain) throws java.io.IOException {
        Response response = chain.proceed(chain.request());

        // Only in debug builds we should have been added, but guard anyway
        // Check content type is JSON
        ResponseBody body = response.body();
        if (body == null) return response;

        MediaType mediaType = body.contentType();
        String contentType = mediaType != null ? mediaType.toString() : response.header("Content-Type", "");
        if (contentType == null) contentType = "";
        String ctLower = contentType.toLowerCase(Locale.US);
        boolean isJson = ctLower.contains("application/json") || ctLower.contains("+json");
        
        // Log response headers for debugging
        Log.d(TAG, "Response: " + response.code() + " " + response.message());
        Log.d(TAG, "Content-Type: " + contentType);
        Log.d(TAG, "Content-Length: " + (body.contentLength() >= 0 ? body.contentLength() : "unknown"));
        
        if (!isJson) {
            Log.w(TAG, "Non-JSON response detected. Content-Type: " + contentType);
            // For non-JSON responses, log first few bytes to help debug
            try {
                ResponseBody peeked = response.peekBody(1024); // Only peek 1KB for non-JSON
                byte[] bytes = peeked.bytes();
                if (bytes.length > 0) {
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < Math.min(bytes.length, 64); i++) {
                        hex.append(String.format("%02X ", bytes[i] & 0xFF));
                    }
                    Log.w(TAG, "First 64 bytes (hex): " + hex.toString());
                    
                    // Try to detect if it's compressed
                    if (bytes.length >= 2) {
                        if ((bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
                            Log.w(TAG, "Response appears to be GZIP compressed");
                        } else if ((bytes[0] & 0xFF) == 0x78 && (bytes[1] & 0xFF) == 0x9C) {
                            Log.w(TAG, "Response appears to be DEFLATE compressed");
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to peek response body for debugging", e);
            }
            return response; // Non-JSON -> no further logging
        }

        // Peek without consuming original body
        ResponseBody peeked = response.peekBody(MAX_BYTES);
        Charset charset = (mediaType != null && mediaType.charset() != null)
                ? mediaType.charset()
                : StandardCharsets.UTF_8;
        String raw = peeked.source().buffer().clone().readString(charset);
        if (raw == null) raw = "";

        String output;
        try {
            // Try pretty print via JSON library
            String trimmed = raw.trim();
            if (trimmed.startsWith("{")) {
                JSONObject jo = new JSONObject(trimmed);
                maskJsonObject(jo);
                output = jo.toString(2);
            } else if (trimmed.startsWith("[")) {
                JSONArray ja = new JSONArray(trimmed);
                maskJsonArray(ja);
                output = ja.toString(2);
            } else {
                // Not a JSON starting char but content-type says json, log as-is
                Log.w(TAG, "Content-Type claims JSON but response doesn't start with { or [");
                output = raw;
            }
        } catch (Throwable t) {
            // Fallback: raw output
            Log.w(TAG, "Failed to parse JSON, logging raw response", t);
            output = raw;
        }

        // Single log entry
        Log.d(TAG, output);
        return response;
    }

    private static void maskJsonObject(JSONObject jo) {
        try {
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jo.opt(key);
                if (value == null || value == JSONObject.NULL) continue;

                if (isSensitiveKey(key)) {
                    jo.put(key, "***");
                } else if (value instanceof JSONObject) {
                    maskJsonObject((JSONObject) value);
                } else if (value instanceof JSONArray) {
                    maskJsonArray((JSONArray) value);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static void maskJsonArray(JSONArray ja) {
        try {
            for (int i = 0; i < ja.length(); i++) {
                Object v = ja.opt(i);
                if (v instanceof JSONObject) {
                    maskJsonObject((JSONObject) v);
                } else if (v instanceof JSONArray) {
                    maskJsonArray((JSONArray) v);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        return SENSITIVE_KEYS.contains(key.toLowerCase(Locale.US));
    }
}


