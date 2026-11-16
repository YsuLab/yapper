package com.ysulab.yapper.reminders;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Microsoft identity platform, OAuth 2.0 authorization code flow with PKCE, for a personal
 * Microsoft account ({@code /consumers}). No MSAL: a public client, a custom-scheme redirect and
 * three HTTP calls are all Yapper needs. Pure Java, testable.
 */
public final class MsAuth {

    public static final String AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    public static final String REDIRECT_URI = "yapper://auth";
    public static final String SCOPES = "Tasks.ReadWrite offline_access";

    /** Tokens from one token response. {@code refreshToken} may be rotated on every refresh. */
    public static final class Tokens {
        public String accessToken = "";
        public String refreshToken = "";
        public long expiresAtMs;
    }

    /** Auth failed for good (revoked consent, expired refresh token): the owner must sign in again. */
    public static final class AuthException extends Exception {
        public final boolean needsSignIn;

        public AuthException(String message, boolean needsSignIn) {
            super(message);
            this.needsSignIn = needsSignIn;
        }
    }

    private final String authority;
    private final String clientId;
    private final WebCall web;

    public MsAuth(String clientId, WebCall web) {
        this(AUTHORITY, clientId, web);
    }

    public MsAuth(String authority, String clientId, WebCall web) {
        this.authority = authority;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.web = web;
    }

    public static boolean isValidClientId(String id) {
        return id != null && id.trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /** 64 random bytes, base64url: a PKCE code verifier (43–128 chars) or a state value. */
    public static String randomToken(SecureRandom random) {
        byte[] b = new byte[48];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The URL to open in the browser. */
    public String authorizeUrl(String verifier, String state) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", clientId);
        q.put("response_type", "code");
        q.put("redirect_uri", REDIRECT_URI);
        q.put("response_mode", "query");
        q.put("scope", SCOPES);
        q.put("state", state);
        q.put("code_challenge", challenge(verifier));
        q.put("code_challenge_method", "S256");
        q.put("prompt", "select_account");
        return authority + "/authorize?" + form(q);
    }

    public Tokens redeemCode(String code, String verifier, long nowMs) throws IOException, AuthException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", REDIRECT_URI);
        f.put("code_verifier", verifier);
        f.put("scope", SCOPES);
        return token(f, nowMs, "");
    }

    public Tokens refresh(String refreshToken, long nowMs) throws IOException, AuthException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        f.put("scope", SCOPES);
        return token(f, nowMs, refreshToken);
    }

    private Tokens token(Map<String, String> form, long nowMs, String oldRefresh) throws IOException, AuthException {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        WebCall.Response r = web.send("POST", authority + "/token", h, form(form));
        try {
            JSONObject o = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
            if (!r.ok()) {
                String err = o.optString("error", "http_" + r.code);
                // invalid_grant = refresh token expired/revoked; invalid_client = bad registration.
                boolean signIn = "invalid_grant".equals(err) || "invalid_client".equals(err)
                        || "unauthorized_client".equals(err) || "interaction_required".equals(err);
                if (!signIn && r.code >= 500) {
                    throw new IOException("Token endpoint HTTP " + r.code);
                }
                throw new AuthException(err + ": " + WebCall.snippet(o.optString("error_description", "")), signIn);
            }
            Tokens t = new Tokens();
            t.accessToken = o.getString("access_token");
            t.refreshToken = o.optString("refresh_token", oldRefresh);
            t.expiresAtMs = nowMs + Math.max(60, o.optLong("expires_in", 3600)) * 1000L;
            return t;
        } catch (JSONException e) {
            throw new IOException("Unexpected token response: " + WebCall.snippet(r.body));
        }
    }

    static String form(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
