package com.ysulab.yapper.reminders;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.ysulab.yapper.R;
import com.ysulab.yapper.work.CaptureScheduler;
import java.util.ArrayList;
import java.util.List;

/**
 * Microsoft sign-in. Started with {@link #EXTRA_START} it opens the browser; Microsoft then redirects
 * to {@code yapper://auth?code=…}, which lands here again. Then the owner picks the To Do list that
 * Samsung Reminder syncs.
 */
public final class AuthActivity extends Activity {

    public static final String EXTRA_START = "start";
    /** Only pick a list (already signed in). */
    public static final String EXTRA_PICK_LIST = "pick_list";

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        MsAccount acct = new MsAccount(this);
        if (intent.getBooleanExtra(EXTRA_START, false)) {
            if (!MsAuth.isValidClientId(acct.clientId())) {
                toast(getString(R.string.ms_need_client_id));
                finish();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(acct.beginSignIn())));
            } catch (ActivityNotFoundException e) {
                toast(getString(R.string.ms_no_browser));
            }
            finish();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_PICK_LIST, false)) {
            loadLists(acct);
            return;
        }
        Uri data = intent.getData();
        if (data == null || !"yapper".equals(data.getScheme())) {
            finish();
            return;
        }
        String error = data.getQueryParameter("error");
        String code = data.getQueryParameter("code");
        String state = data.getQueryParameter("state");
        if (error != null || code == null) {
            String desc = data.getQueryParameter("error_description");
            toast(getString(R.string.ms_sign_in_failed, error == null ? "no code" : error
                    + (desc == null ? "" : ": " + desc)));
            finish();
            return;
        }
        new Thread(() -> {
            try {
                acct.finishSignIn(code, state);
                main.post(() -> loadLists(acct));
            } catch (Exception e) {
                main.post(() -> {
                    toast(getString(R.string.ms_sign_in_failed, e.getMessage()));
                    finish();
                });
            }
        }).start();
    }

    private void loadLists(MsAccount acct) {
        new Thread(() -> {
            try {
                List<TodoClient.TaskList> lists = acct.lists();
                main.post(() -> pick(acct, lists));
            } catch (Exception e) {
                main.post(() -> {
                    toast(getString(R.string.ms_lists_failed, e.getMessage()));
                    finish();
                });
            }
        }).start();
    }

    private void pick(MsAccount acct, List<TodoClient.TaskList> lists) {
        if (isFinishing()) {
            return;
        }
        if (lists.isEmpty()) {
            toast(getString(R.string.ms_no_lists));
            finish();
            return;
        }
        List<String> names = new ArrayList<>();
        int preselect = 0;
        for (int i = 0; i < lists.size(); i++) {
            TodoClient.TaskList l = lists.get(i);
            names.add(l.name + ("defaultList".equals(l.wellknown) ? " (default)" : ""));
            if (l.id.equals(acct.listId()) || acct.listId().isEmpty()
                    && l.name.toLowerCase(java.util.Locale.ROOT).contains("reminder")) {
                preselect = i;
            }
        }
        final int[] chosen = {preselect};
        new AlertDialog.Builder(this)
                .setTitle(R.string.ms_pick_list)
                .setSingleChoiceItems(names.toArray(new String[0]), preselect, (d, which) -> chosen[0] = which)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    TodoClient.TaskList l = lists.get(chosen[0]);
                    acct.setList(l.id, l.name);
                    toast(getString(R.string.ms_connected, l.name));
                    new Thread(() -> CaptureScheduler.reconcile(getApplicationContext())).start();
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
    }
}
