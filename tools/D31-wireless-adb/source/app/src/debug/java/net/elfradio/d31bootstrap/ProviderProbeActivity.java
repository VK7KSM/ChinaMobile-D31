package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public final class ProviderProbeActivity extends Activity {
    private static final String TAG = "D31ProviderProbe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Thread(() -> {
            AdbControl.ActionResult result = FirewallControl.inspectInputs(this);
            Log.i(TAG, "成功=" + result.succeeded + "；" + result.log.trim());
            runOnUiThread(this::finish);
        }, "d31-provider-probe").start();
    }
}
