package dev.octoshrimpy.quik.feature.phone;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import dev.octoshrimpy.quik.R;

public final class SipSettingsActivity extends AppCompatActivity implements SipEngine.Listener {
    private static final int COLOR_ON = Color.rgb(20, 122, 91);
    private static final int COLOR_OFF = Color.rgb(160, 173, 179);
    private static final int COLOR_ERROR = Color.rgb(194, 92, 38);

    private CheckBox enabled;
    private EditText server;
    private EditText port;
    private EditText username;
    private EditText password;
    private EditText realm;
    private Spinner transport;
    private TextView status;
    private View statusDot;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.d31_sip_settings_activity);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        enabled = findViewById(R.id.sipEnabled);
        server = findViewById(R.id.sipServer);
        port = findViewById(R.id.sipPort);
        username = findViewById(R.id.sipUsername);
        password = findViewById(R.id.sipPassword);
        realm = findViewById(R.id.sipRealm);
        transport = findViewById(R.id.sipTransport);
        status = findViewById(R.id.sipStatus);
        statusDot = findViewById(R.id.sipStatusDot);

        transport.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"TLS", "TCP", "UDP"}));
        findViewById(R.id.sipSave).setOnClickListener(v -> save());
        findViewById(R.id.sipCancel).setOnClickListener(v -> finish());
        findViewById(R.id.sipPasswordToggle).setOnClickListener(this::togglePassword);
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SipEngine.get().addListener(this);
    }

    @Override
    protected void onStop() {
        SipEngine.get().removeListener(this);
        super.onStop();
    }

    private void load() {
        SipConfigStore.Profile profile = new SipConfigStore(this).load();
        enabled.setChecked(profile.enabled);
        server.setText(profile.server);
        port.setText(Integer.toString(profile.port));
        username.setText(profile.username);
        password.setText(profile.password);
        realm.setText(profile.realm);
        transport.setSelection(profile.transport == SipConfigStore.Transport.TLS ? 0 :
            profile.transport == SipConfigStore.Transport.TCP ? 1 : 2);
    }

    private void save() {
        int parsedPort;
        try {
            parsedPort = Integer.parseInt(port.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, R.string.d31_sip_invalid_port, Toast.LENGTH_SHORT).show();
            return;
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            Toast.makeText(this, R.string.d31_sip_invalid_port, Toast.LENGTH_SHORT).show();
            return;
        }
        SipConfigStore.Transport selected = transport.getSelectedItemPosition() == 0
            ? SipConfigStore.Transport.TLS
            : transport.getSelectedItemPosition() == 1
                ? SipConfigStore.Transport.TCP : SipConfigStore.Transport.UDP;
        new SipConfigStore(this).save(enabled.isChecked(), server.getText().toString(), parsedPort,
            username.getText().toString(), password.getText().toString(), realm.getText().toString(), selected);
        if (enabled.isChecked()) SipService.restart(this);
        else startService(new android.content.Intent(this, SipService.class).setAction(SipService.ACTION_STOP));
        Toast.makeText(this, R.string.d31_sip_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void togglePassword(View ignored) {
        int position = password.getSelectionStart();
        boolean hidden = (password.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
        password.setInputType(InputType.TYPE_CLASS_TEXT |
            (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        password.setSelection(Math.max(0, position));
    }

    @Override
    public void onRegistrationChanged(boolean registered, String detail) {
        runOnUiThread(() -> {
            if (registered) {
                status.setText("已连接 · " + (detail == null ? "OK" : detail));
                status.setTextColor(COLOR_ON);
                if (statusDot != null) statusDot.setBackgroundTintList(ColorStateList.valueOf(COLOR_ON));
            } else if (detail == null || detail.contains("未配置") || detail.contains("未启动")) {
                status.setText("未配置");
                status.setTextColor(COLOR_OFF);
                if (statusDot != null) statusDot.setBackgroundTintList(ColorStateList.valueOf(COLOR_OFF));
            } else {
                status.setText("离线 · " + detail);
                status.setTextColor(COLOR_ERROR);
                if (statusDot != null) statusDot.setBackgroundTintList(ColorStateList.valueOf(COLOR_ERROR));
            }
        });
    }

    @Override public void onMessageReceived(String peer, String body) {}
    @Override public void onMessageStatus(long id, boolean sent, String detail) {}
}
