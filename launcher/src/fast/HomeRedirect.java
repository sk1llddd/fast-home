package fast.home;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

// Optional helper APK. Fire OS handles Home before any app can, so we react when Amazon's home screen resumes: first via
// the activity log (~380 ms early, needs READ_LOGS), with the accessibility window event as a slower fallback.
public class HomeRedirect extends AccessibilityService {
  static final String STOCK_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
  static final String STOCK_RESUME = "com.amazon.tv.launcher/.ui.HomeActivity_vNext";
  static final ComponentName FAST_HOME = new ComponentName("fast.home", "fast.home.Home");
  volatile boolean stop;
  volatile long lastGo, shownAt;
  final Handler ui = new Handler(Looper.getMainLooper());
  View cover;
  final Runnable hide = this::hideNow;
  // What Fast Home looked like when it was last left, so the cover matches it.
  int top = 0xFF1B2A4A, kind, alert;
  String wx = "";

  final BroadcastReceiver fromHome = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      if ("fast.home.SHOWN".equals(i.getAction())) { shownAt = System.currentTimeMillis(); hideNow(); return; }
      top = i.getIntExtra("top", top);
      kind = i.getIntExtra("kind", kind);
      alert = i.getIntExtra("alert", 0);
      wx = i.getStringExtra("wx") == null ? "" : i.getStringExtra("wx");
    }
  };

  // Opaque cover in Fast Home's own background colour: Amazon's screen finishes drawing underneath, unseen.
  void showCover() {
    ui.post(() -> {
      if (cover != null) { ui.removeCallbacks(hide); ui.postDelayed(hide, 2500); return; }
      FrameLayout v = new FrameLayout(this);
      TextView weather = new TextView(this), mark = new TextView(this);
      if (!wx.isEmpty()) weather.setText(Ui.withIcon(this, "", kind, wx, 24));
      mark.setText(Ui.alertMark(this, "", alert, 22));
      v.addView(Ui.header(this, new TextClock(this), weather, mark));
      v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Ui.grad(top)));
      WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT);
      try { ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, lp); cover = v; } catch (RuntimeException ignored) { return; }
      ui.postDelayed(hide, 2500); // safety: never leave the screen covered
    });
  }

  void hideNow() {
    View v = cover;
    if (v == null) return;
    cover = null;
    ui.removeCallbacks(hide);
    v.animate().alpha(0f).setDuration(160).withEndAction(() -> { try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(v); } catch (RuntimeException ignored) {} }).start();
  }

  // fast = the early log signal. The late accessibility fallback is stale if Fast Home came up after our last redirect.
  void goHome(boolean fast) {
    long now = System.currentTimeMillis();
    if (now - lastGo < (fast ? 400 : 1500) || (!fast && shownAt > lastGo && now - shownAt < 1500)) return;
    lastGo = now;
    showCover();
    try {
      startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(FAST_HOME)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    } catch (RuntimeException notInstalled) { ui.post(this::hideNow); }
  }

  @Override protected void onServiceConnected() {
    stop = false;
    IntentFilter f = new IntentFilter("fast.home.SHOWN");
    f.addAction("fast.home.STATE");
    registerReceiver(fromHome, f, "fast.home.COVER", null); // only Fast Home (same signing key) may send these
    new Thread(() -> {
      while (!stop) {
        try {
          Process p = new ProcessBuilder("logcat", "-b", "events", "-T", "1", "-v", "brief", "wm_set_resumed_activity:I", "*:S").redirectErrorStream(true).start();
          try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String l; !stop && (l = r.readLine()) != null; ) if (l.contains(STOCK_RESUME)) goHome(true);
          } finally { p.destroy(); }
        } catch (Exception ignored) {} // no READ_LOGS or logcat died: the accessibility fallback still works
        try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
      }
    }, "home-watch").start();
  }

  @Override public boolean onUnbind(Intent i) {
    stop = true;
    try { unregisterReceiver(fromHome); } catch (IllegalArgumentException notRegistered) {}
    ui.post(this::hideNow);
    return super.onUnbind(i);
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    CharSequence cn = e.getClassName();
    if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && cn != null && STOCK_HOME.contentEquals(cn)) goHome(false);
  }

  @Override public void onInterrupt() {}
}
