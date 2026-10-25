package fast.home;

import android.app.Activity;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

// Full-screen HDMI input. Amazon's Live TV app answers the standard "show this input" intent by opening its last channel
// (Pluto TV), so we tune the input ourselves with a TvView instead.
public class InputActivity extends Activity {
  TvView tv;
  TextView msg;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    FrameLayout f = new FrameLayout(this);
    f.setBackgroundColor(0xFF000000);
    tv = new TvView(this);
    f.addView(tv, new FrameLayout.LayoutParams(-1, -1));
    msg = new TextView(this);
    msg.setTextColor(0xCCFFFFFF);
    msg.setTextSize(22);
    msg.setGravity(Gravity.CENTER);
    f.addView(msg, new FrameLayout.LayoutParams(-1, -1));
    setContentView(f);
    tv.setCallback(new TvView.TvInputCallback() {
      @Override public void onVideoAvailable(String id) { msg.setText(""); }
      @Override public void onVideoUnavailable(String id, int reason) {
        msg.setText(reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING ? "" : "No signal");
      }
      @Override public void onConnectionFailed(String id) { msg.setText("Couldn't open this input"); }
    });
  }

  @Override protected void onStart() {
    super.onStart();
    String id = getIntent().getStringExtra("input");
    if (id != null) tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
  }

  @Override protected void onStop() {
    tv.reset(); // release the input when we leave
    super.onStop();
  }
}
