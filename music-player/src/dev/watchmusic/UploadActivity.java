package dev.watchmusic;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.Inet4Address;

/**
 * "Add Music": while this screen is open, the watch serves an upload page on the Wi-Fi.
 * It shows the address to open on a computer or iPhone and the code the page asks for.
 * The screen stays on, and Wi-Fi is requested, because Wear OS turns Wi-Fi off when it's idle.
 */
public class UploadActivity extends Activity implements UploadServer.Listener {
    private TextView address, status, codeView;
    private UploadServer server;
    private ConnectivityManager connectivity;
    private boolean confirming; // the system delete dialog is showing over this screen

    private final ConnectivityManager.NetworkCallback wifi = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties props) {
            for (LinkAddress a : props.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    String host = a.getAddress().getHostAddress() + ":" + UploadServer.PORT;
                    runOnUiThread(() -> {
                        address.setText(host);
                        address.setTextColor(Ui.LABEL);
                        if (status.getText().toString().startsWith("Waiting")) status.setText("Ready");
                    });
                    return;
                }
            }
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(() -> {
                address.setText("No Wi-Fi");
                address.setTextColor(Ui.SECONDARY);
                status.setText("Waiting for Wi-Fi…");
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // the server lives as long as the screen
        connectivity = getSystemService(ConnectivityManager.class);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(Math.round(24 * dp), 0, Math.round(24 * dp), 0);

        TextView title = text("Add Music", 14, Ui.PINK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView hint = text("On the same Wi-Fi, open", 11, Ui.SECONDARY);
        hint.setPadding(0, Math.round(4 * dp), 0, 0);
        root.addView(hint);

        address = text("Connecting…", 13, Ui.SECONDARY);
        address.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        address.setSingleLine(true);
        root.addView(address);

        TextView codeLabel = text("Code", 11, Ui.SECONDARY);
        codeLabel.setPadding(0, Math.round(6 * dp), 0, 0);
        root.addView(codeLabel);

        server = new UploadServer(this, this); // it picks the code
        codeView = text(server.code(), 22, Ui.LABEL);
        codeView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        codeView.setLetterSpacing(0.15f);
        root.addView(codeView);

        status = text("Waiting for Wi-Fi…", 11, Ui.GREEN);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        status.setPadding(0, Math.round(2 * dp), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(Math.round(120 * dp), -2));

        setContentView(root);

        connectivity.requestNetwork(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi);
        try {
            server.start();
        } catch (IOException e) {
            Log.w(PlayerService.TAG, "can't start upload server", e);
            status.setText("Can't start: " + e.getMessage());
            status.setTextColor(Ui.RED);
        }
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Leaving the screen ends the upload session, except while the delete confirmation is over it. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!confirming) finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        confirming = false;
        status.setText(resultCode == RESULT_OK ? "Deleted" : "Not deleted");
    }

    @Override
    protected void onDestroy() {
        server.stop();
        connectivity.unregisterNetworkCallback(wifi);
        super.onDestroy();
    }

    @Override
    public void onStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    public void onNewCode(String code) {
        runOnUiThread(() -> {
            codeView.setText(code);
            status.setText("Too many tries");
        });
    }

    @Override
    public void onNeedsConfirm(PendingIntent request) {
        runOnUiThread(() -> {
            try {
                confirming = true;
                startIntentSenderForResult(request.getIntentSender(), 1, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Log.w(PlayerService.TAG, "delete confirmation", e);
            }
        });
    }
}
