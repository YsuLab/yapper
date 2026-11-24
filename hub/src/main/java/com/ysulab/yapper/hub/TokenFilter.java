package com.ysulab.yapper.hub;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every {@code /api/} call needs {@code Authorization: Bearer <token>}. Each device has its own token
 * (HUB_TOKENS), so one can be revoked alone. Tokens are compared as SHA-256 digests in constant time.
 * Static board files are public: they hold no data.
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    static final int MIN_TOKEN_LENGTH = 24;
    static final String DEVICE_ATTR = "hub.device";
    private static final Logger log = LoggerFactory.getLogger(TokenFilter.class);

    private record Known(byte[] digest, String device) {}

    private final List<Known> known = new ArrayList<>();

    public TokenFilter(HubProperties props) {
        for (Map.Entry<String, String> e : props.deviceByToken().entrySet()) {
            known.add(new Known(sha256(e.getKey()), e.getValue()));
        }
        if (known.isEmpty()) {
            log.warn("No device tokens set (HUB_TOKENS). Every API call will be refused.");
        } else {
            log.info("{} device token(s) loaded", known.size());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String device = device(req.getHeader("Authorization"));
        if (device == null) {
            res.setStatus(401);
            res.setHeader("WWW-Authenticate", "Bearer");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        req.setAttribute(DEVICE_ATTR, device);
        res.setHeader("Cache-Control", "no-store");
        chain.doFilter(req, res);
    }

    String device(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        byte[] d = sha256(header.substring(7).trim());
        String found = null;
        for (Known k : known) {
            if (MessageDigest.isEqual(k.digest, d) && found == null) {
                found = k.device;
            }
        }
        return found;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
