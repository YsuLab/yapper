package com.ysulab.yapper.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API keys encrypted with an AES-GCM key that lives in the Android Keystore (never leaves it).
 * Ciphertext sits in private SharedPreferences, excluded from backup ({@code allowBackup=false}).
 * Values are never logged.
 */
public final class SecretStore {

    private static final String KEY_ALIAS = "yapper_secrets_v1";
    private static final String PREFS = "secrets";
    private static final int IV_LEN = 12;

    private final SharedPreferences prefs;

    public SecretStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void put(String name, String value) {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(name).apply();
            return;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            prefs.edit().putString(name, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Keystore encryption failed", e);
        }
    }

    /** Returns "" when unset or unreadable (e.g. Keystore reset after a lock-screen change). */
    public String get(String name) {
        String stored = prefs.getString(name, null);
        if (stored == null) {
            return "";
        }
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean has(String name) {
        return prefs.contains(name);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
