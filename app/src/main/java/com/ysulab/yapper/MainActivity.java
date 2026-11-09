package com.ysulab.yapper;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** M0 placeholder screen. Shows the build so the owner can confirm an update installed. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView buildInfo = findViewById(R.id.build_info);
        buildInfo.setText(BuildInfo.describe(
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE));
    }
}
