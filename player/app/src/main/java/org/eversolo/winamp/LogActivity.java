package org.eversolo.winamp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.eversolo.winamp.core.CrashHandler;
import org.eversolo.winamp.core.LogShipper;
import org.eversolo.winamp.core.Logs;

import java.io.File;

/**
 * The on-device log console.
 *
 * This device has no usable ADB, so this screen is the only way to see what the app is
 * doing while standing in front of it. Reached by long-pressing the title bar.
 */
public class LogActivity extends Activity implements Logs.Listener {

    private TextView out;
    private ScrollView scroll;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("LOG CONSOLE");
        title.setTextColor(0xFF00FF66);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Send to dev machine", v -> {
            LogShipper.shipBuffer();
            Toast.makeText(this, LogShipper.isEnabled()
                    ? "sent" : "no dev host configured", Toast.LENGTH_SHORT).show();
        }));
        buttons.addView(button("Clear", v -> {
            Logs.clear();
            out.setText("");
        }));
        buttons.addView(button("Last crash", v -> {
            File f = CrashHandler.latestCrash(this);
            Toast.makeText(this, f == null ? "no crashes recorded" : f.getName(),
                    Toast.LENGTH_LONG).show();
            if (f != null) Logs.i("LogConsole", "latest crash file: " + f.getAbsolutePath());
        }));
        root.addView(buttons);

        out = new TextView(this);
        out.setTextColor(0xFFCCCCCC);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        out.setTextIsSelectable(true);

        scroll = new ScrollView(this);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        out.setText(Logs.dump());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onResume() { super.onResume(); Logs.addListener(this); }
    @Override protected void onPause() { super.onPause(); Logs.removeListener(this); }

    @Override
    public void onLine(final String line) {
        ui.post(() -> {
            out.append(line + "\n");
            scroll.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
