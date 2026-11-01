package dev.augie.roadhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** App-icon launches must use the same Home task as the hardware Home button. */
public final class LaunchActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
