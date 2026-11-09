package com.ysulab.yapper.reminders;

import android.content.Context;
import android.content.SharedPreferences;
import com.ysulab.yapper.settings.SecretStore;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;

/**
 * The owner's Microsoft account for To Do: app (client) id, chosen list, and tokens. The refresh
 * and access tokens live in {@link SecretStore} (Android Keystore); nothing is logged.
 */
public final class MsAccount {

    private static final String PREFS = "ms_account";
    private static final String RT = "ms_refresh_token";
    private static final String AT = "ms_access_token";

    private final SharedPreferences prefs;
    private final SecretStore secrets;

    public MsAccount(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secrets = new SecretStore(app);
    }

    public String clientId() {
        return prefs.getString("client_id", "");
    }

    public void setClientId(String id) {
        String v = id == null ? "" : id.trim();
        if (!v.equals(clientId())) {
            signOut(); // tokens belong to the old registration
        }
        prefs.edit().putString("client_id", v).apply();
    }

    public boolean isSignedIn() {
        return !secrets.get(RT).isEmpty();
    }

    public String listId() {
        return prefs.getString("list_id", "");
    }

    public String listName() {
        return prefs.getString("list_name", "");
    }

    public void setList(String id, String name) {
        prefs.edit().putString("list_id", id).putString("list_name", name).apply();
    }

    /** Signed in and a list chosen: reminders can go to To Do. */
    public boolean isReady() {
        return isSignedIn() && !listId().isEmpty() && MsAuth.isValidClientId(clientId());
    }

    public void signOut() {
        secrets.put(RT, "");
        secrets.put(AT, "");
        prefs.edit().remove("at_exp").remove("list_id").remove("list_name").apply();
    }

    /** Starts a sign-in: remembers verifier + state, returns the URL to open in the browser. */
    public String beginSignIn() {
        SecureRandom r = new SecureRandom();
        String verifier = MsAuth.randomToken(r);
        String state = MsAuth.randomToken(r);
        prefs.edit().putString("pkce_verifier", verifier).putString("pkce_state", state).apply();
        return new MsAuth(clientId(), WebCall.DEFAULT).authorizeUrl(verifier, state);
    }

    /** Finishes a sign-in from the redirect. Network: call off the main thread. */
    public void finishSignIn(String code, String state) throws IOException, MsAuth.AuthException {
        String expected = prefs.getString("pkce_state", "");
        String verifier = prefs.getString("pkce_verifier", "");
        if (expected.isEmpty() || !expected.equals(state)) {
            throw new MsAuth.AuthException("Sign-in state mismatch. Try again.", true);
        }
        prefs.edit().remove("pkce_state").remove("pkce_verifier").apply();
        save(new MsAuth(clientId(), WebCall.DEFAULT).redeemCode(code, verifier, System.currentTimeMillis()));
    }

    /** A valid access token, refreshed when needed. Network. */
    public synchronized String accessToken() throws IOException, MsAuth.AuthException {
        long exp = prefs.getLong("at_exp", 0);
        String at = secrets.get(AT);
        if (!at.isEmpty() && System.currentTimeMillis() < exp - 120_000) {
            return at;
        }
        String rt = secrets.get(RT);
        if (rt.isEmpty()) {
            throw new MsAuth.AuthException("Not signed in to Microsoft", true);
        }
        MsAuth.Tokens t = new MsAuth(clientId(), WebCall.DEFAULT).refresh(rt, System.currentTimeMillis());
        save(t);
        return t.accessToken;
    }

    private void save(MsAuth.Tokens t) {
        secrets.put(AT, t.accessToken);
        if (!t.refreshToken.isEmpty()) {
            secrets.put(RT, t.refreshToken);
        }
        prefs.edit().putLong("at_exp", t.expiresAtMs).apply();
    }

    public List<TodoClient.TaskList> lists() throws Exception {
        return new TodoClient(accessToken(), WebCall.DEFAULT).lists();
    }

    /** The To Do side of {@link ReminderSync}. */
    public ReminderSync.Todo todo() {
        return new ReminderSync.Todo() {
            @Override
            public String create(String taskJson) throws Exception {
                return new TodoClient(accessToken(), WebCall.DEFAULT).createTask(listId(), taskJson);
            }

            @Override
            public void delete(String listId, String taskId) throws Exception {
                new TodoClient(accessToken(), WebCall.DEFAULT).deleteTask(listId, taskId);
            }

            @Override
            public String listId() {
                return MsAccount.this.listId();
            }
        };
    }
}
