package com.ysulab.yapper.assist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import com.ysulab.yapper.MainActivity;
import com.ysulab.yapper.R;

/**
 * Invisible trampoline: starts or stops a ramble and closes. Used by the Quick Settings tile, the
 * launcher shortcut and the daily nudges. A visible activity is what lets Android 14+ start the
 * microphone service.
 */
public final class RambleActivity extends Activity {

    public static final String EXTRA_MODE = RambleService.EXTRA_MODE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RambleService.isRunning()) {
            startService(new Intent(this, RambleService.class).setAction(RambleService.ACTION_STOP));
            Toast.makeText(this, R.string.ramble_saved, Toast.LENGTH_SHORT).show();
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class));
            Toast.makeText(this, R.string.capture_no_mic_hint, Toast.LENGTH_LONG).show();
        } else {
            String mode = getIntent().getStringExtra(EXTRA_MODE);
            if (mode == null && getIntent().getData() != null) {
                mode = getIntent().getData().getLastPathSegment();
            }
            startForegroundService(RambleService.startIntent(this, mode));
            Toast.makeText(this, R.string.ramble_started, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
