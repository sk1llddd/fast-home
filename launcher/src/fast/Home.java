package fast.home;

import android.animation.*;
import android.app.Activity;
import android.app.Dialog;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.AudioManager;
import android.media.tv.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import org.json.*;
import java.util.*;
import static fast.home.Ui.*;

public class Home extends Activity {
  static final String[] BG_NAMES = {"App color", "Midnight", "Aurora", "Ocean", "Sunset", "Forest", "Graphite", "Black"};
  static final int[][] BG = {
    {0xFF1B2A4A, 0xFF0A0F1C, 0xFF000000}, // [0] = "App color": colors come from the focused icon at runtime
    {0xFF1B2A4A, 0xFF0A0F1C, 0xFF000000}, {0xFF3B1E6B, 0xFF14102E, 0xFF000000},
    {0xFF0B4F6C, 0xFF06202E, 0xFF000000}, {0xFF7A2E3A, 0xFF2A1220, 0xFF000000},
    {0xFF1E4D3A, 0xFF0B1F18, 0xFF000000}, {0xFF3A3A3F, 0xFF16161A, 0xFF000000},
    {0xFF000000, 0xFF000000, 0xFF000000}};
  static final String[] ACC_NAMES = {"Purple (default)", "Blue", "Teal", "Green", "Orange", "Red", "Pink", "Gold", "Gray", "Custom hex..."};
  static final int[] ACC = {0, 0xFF2C4F8F, 0xFF1E7F78, 0xFF2F7A3E, 0xFFB5582B, 0xFF9B2F2F, 0xFFA23F6A, 0xFF9A7414, 0xFF55606B}; // 0 = default
  static final int DEFAULT_ACCENT = 0xFF4A3F7A;
  // Photo blur by scroll depth: level 0 (top, big clock) sharp; deeper rows get a little blur.
  // {shrink divisor, box radius, passes}; divisor 0 = no blur. Raise the numbers for more blur.
  static final int[][] PHOTO_LEVELS = {{0, 0, 0}, {1, 1, 1}, {2, 1, 1}};
  static final int[] TILE_DP = {180, 240, 300};
  static final String[] TILE_NAMES = {"Small", "Medium", "Large"};
  static final String LUNA = "com.amazon.spiderpork";
  // Packages named like Amazon's that aren't Amazon's own apps: Prime Video (firebat on Fire OS, avod on older builds), YouTube, AirPlay.
  static final Set<String> NOT_AMAZON = new HashSet<>(Arrays.asList("com.amazon.firebat", "com.amazon.avod", "com.amazon.firetv.youtube", "com.apple.airplay.amazon.airplay"));
  static final String ST = "com.amazon.tv.settings.v2/.tv.";
  // "action:" entries go through Android's standard settings intents, which Amazon forwards internally; the direct pages for
  // Display & Sounds, Preferences and Account need a permission only Amazon's launcher holds, so "All Settings" covers those.
  static final String[][] SETTINGS = {
    {"All Settings", "action:android.settings.SETTINGS", "6"},
    {"Network", ST + "network.NetworkActivity", "1"},
    {"Apps", ST + "applications.ApplicationsActivity", "3"},
    {"Controllers & Bluetooth", ST + "controllers_bluetooth_devices.ControllersAndBluetoothActivity", "4"},
    {"My Fire TV", ST + "device.DeviceActivity", "5"},
    {"Accessibility", "action:android.settings.ACCESSIBILITY_SETTINGS", "7"}};

  static class Item {
    String key, label, pkg, inputId; Intent intent; ResolveInfo ri; int color, icon; boolean app;
  }

  SharedPreferences p;
  ScrollView sv;
  List<List<View>> grid = new ArrayList<>();
  Map<Integer, Integer> rowCol = new HashMap<>(); // last focused column per row
  FrameLayout root;
  LinearLayout content;
  String sig = "", focusKey;
  Map<String, Bitmap> art = new HashMap<>();
  Map<String, Boolean> artBanner = new HashMap<>();
  int artW;
  List<String> ordered = new ArrayList<>();
  GradientDrawable bgGrad = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF1B2A4A, 0xFF0A0F1C, 0xFF000000});
  int curTop = 0xFF1B2A4A;
  int lastTop = 0xFF1B2A4A; // top of the background gradient, sent to the Home-button helper for its cover
  ValueAnimator colorAnim;
  GradientDrawable custBg; // the Customize tile background, kept in step with the accent
  GradientDrawable pillBg; // top time/weather bar background: invisible until a tile slides under it
  boolean barBlack, barCheckPending;
  ValueAnimator pillAnim;
  Map<String, Integer> tints = new HashMap<>();
  Item focusItem;
  boolean noReveal, small;
  int wxKind;
  String wxPlain = ""; // "64°F  Cloudy", sent to the helper with wxKind
  boolean frameDrawn; // first frame since resume has been drawn
  boolean resumed; // true while Fast Home is the foreground activity
  LinearLayout floatBar;
  TextView weatherBig, weatherSmall, alertChip;
  TextClock dateChip;
  final List<View> headerRow = new ArrayList<>(); // grid row 0: date, weather and alert chips, whichever are showing
  Map<String, Runnable> inputStylers = new HashMap<>();
  TvInputManager tm;
  TvInputManager.TvInputCallback tvCb;

  int dp(int v) { return Ui.dp(this, v); }
  int tileW() { return dp(TILE_DP[p.getInt("size", 1)]); }
  Set<String> set(String k) { return new HashSet<>(p.getStringSet(k, new HashSet<String>())); }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    p = getSharedPreferences("p", 0);
    initDefaults();
    root = new FrameLayout(this);
    root.setClipChildren(false);
    findPhotos();

    dateChip = new TextClock(this);
    weatherBig = new TextView(this);
    alertChip = new TextView(this);
    LinearLayout header = header(this, dateChip, weatherBig, alertChip);
    chip(dateChip, () -> calendarMenu(0, 1));
    chip(weatherBig, this::forecastMenu);
    chip(alertChip, () -> alertsMenu(0));

    sv = new ScrollView(this) {
      @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
    };
    sv.setVerticalScrollBarEnabled(false);
    sv.setClipChildren(false);
    sv.setClipToPadding(false);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setClipChildren(false);
    LinearLayout outer = new LinearLayout(this);
    outer.setOrientation(LinearLayout.VERTICAL);
    outer.setClipChildren(false);
    outer.setPadding(0, 0, 0, dp(80));
    outer.addView(header);
    outer.addView(content);
    sv.addView(outer);
    root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
    tvCb = new TvInputManager.TvInputCallback() {
      @Override public void onInputStateChanged(String id, int state) {
        Runnable r = inputStylers.get("in:" + id);
        if (r != null) r.run();
      }
    };

    // small clock + weather, shown only once the big header has scrolled away
    floatBar = new LinearLayout(this);
    floatBar.setGravity(Gravity.CENTER_VERTICAL);
    GradientDrawable pill = new GradientDrawable();
    pill.setColor(0xCC000000); // black, but only shown while something is behind the bar (see checkBar)
    pill.setAlpha(0);
    pillBg = pill;
    pill.setCornerRadius(dp(26));
    floatBar.setBackground(pill);
    floatBar.setPadding(dp(22), dp(8), dp(22), dp(8));
    floatBar.setAlpha(0f);
    floatBar.setVisibility(View.GONE);
    weatherSmall = new TextView(this);
    weatherSmall.setTextColor(0xCCFFFFFF);
    weatherSmall.setTextSize(20);
    weatherSmall.setShadowLayer(8, 0, 2, 0xDD000000); // legible over tiles without a background layer
    TextClock tiny = new TextClock(this);
    tiny.setFormat12Hour("EEE, MMM d    h:mm a");
    tiny.setFormat24Hour("EEE, MMM d    H:mm");
    tiny.setTextColor(0xCCFFFFFF);
    tiny.setTextSize(20);
    tiny.setShadowLayer(8, 0, 2, 0xDD000000);
    floatBar.addView(weatherSmall);
    floatBar.addView(tiny);
    FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    fp.setMargins(0, dp(24), dp(64), 0);
    root.addView(floatBar, fp);
    sv.setOnScrollChangeListener((v, x, y, ox, oy) -> {
      photoLevelTo(levelFor(y));
      updateBar();
      boolean sm = y > dp(110);
      if (sm == small) return;
      small = sm;
      fade(floatBar, sm);
    });
    setContentView(root);
    applyBg();
    showWeather();
    if (!p.getBoolean("guided", false)) { p.edit().putBoolean("guided", true).apply(); root.post(() -> guide(0)); }
  }

  // A header item you can move onto with Up: it glows in the accent color like a menu row and opens `open` on Select.
  void chip(TextView t, Runnable open) {
    t.setFocusable(true);
    t.setClickable(true);
    GradientDrawable on = new GradientDrawable();
    on.setCornerRadius(dp(14));
    StateListDrawable sl = new StateListDrawable();
    sl.setEnterFadeDuration(140);
    sl.setExitFadeDuration(140);
    sl.addState(new int[] {android.R.attr.state_focused}, on);
    t.setBackground(sl);
    t.setOnFocusChangeListener((v, f) -> {
      on.setColor(accent());
      if (f) { focusItem = null; glide(sv, "scrollY", 0); }
    });
    t.setOnClickListener(v -> { after = () -> {}; open.run(); });
  }

  // First-install defaults (also what "Reset everything" returns to): Luna pinned, MediaTek's OpApp hidden.
  void initDefaults() {
    if (!p.contains("init2")) {
      p.edit().putBoolean("init2", true).putStringSet("fav", new HashSet<>(Arrays.asList(LUNA)))
          .putStringSet("hid", new HashSet<>(Arrays.asList("com.mediatek.opapp"))).apply();
    }
  }

  @Override protected void onStart() {
    super.onStart();
    try { tm.registerCallback(tvCb, new Handler(Looper.getMainLooper())); } catch (Exception ignored) {}
    for (Runnable r : inputStylers.values()) r.run();
  }

  // While the home screen is up, refresh weather and alerts once they are 10 minutes old.
  final Runnable poll = new Runnable() {
    @Override public void run() {
      refreshWeather(false);
      root.postDelayed(this, 5 * 60 * 1000);
    }
  };

  @Override protected void onPause() {
    root.removeCallbacks(poll);
    resumed = false;
    sendBroadcast(new Intent("fast.home.STATE").setPackage(HELPER).putExtra("top", lastTop).putExtra("kind", wxKind)
        .putExtra("wx", wxPlain).putExtra("alert", alertColor()), COVER_PERMISSION); // what the helper's cover shows next time
    super.onPause();
  }

  @Override protected void onStop() {
    super.onStop();
    try { tm.unregisterCallback(tvCb); } catch (Exception ignored) {}
  }

  boolean inputActive(String id) {
    try { return tm.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED; } catch (Exception e) { return false; }
  }

  @Override protected void onResume() {
    super.onResume();
    resumed = true;
    frameDrawn = false;
    String s = signature();
    if (!s.equals(sig)) { sig = s; rebuild(); }
    root.removeCallbacks(poll);
    root.post(poll);
    root.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
      boolean fired;
      @Override public void onDraw() {
        if (fired) return;
        fired = true;
        root.post(() -> {
          root.getViewTreeObserver().removeOnDrawListener(this);
          frameDrawn = true;
          maybeLiftCover();
        });
      }
    });
    root.invalidate();
    root.postDelayed(this::coverNotNeeded, 900); // backup in case no draw event fires
  }

  @Override protected void onNewIntent(Intent in) {
    super.onNewIntent(in);
    if (Intent.ACTION_MAIN.equals(in.getAction()) && in.hasCategory(Intent.CATEGORY_HOME)) {
      focusKey = null;
      resetToTop();
    }
    if (resumed) root.post(this::coverNotNeeded); // already on screen; otherwise onResume lifts it
  }

  // The optional Home-button helper (separate APK) covers Amazon's home screen until this one is drawn.
  static final String HELPER = "fast.home.redirect", COVER_PERMISSION = "fast.home.COVER";

  void coverNotNeeded() { sendBroadcast(new Intent("fast.home.SHOWN").setPackage(HELPER), COVER_PERMISSION); }

  String helperVersion() {
    try { return getPackageManager().getPackageInfo(HELPER, 0).versionName; } catch (PackageManager.NameNotFoundException e) { return null; }
  }

  String homeButtonStatus() {
    if (helperVersion() == null) return "Amazon's home screen";
    for (android.accessibilityservice.AccessibilityServiceInfo i : ((android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE))
        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
      if (i.getId().startsWith(HELPER + "/")) return "Opens Fast Home";
    return "Helper off (Settings › Accessibility)";
  }

  // Lift the redirect cover once our window is both drawn and focused; earlier and the previous app shows through.
  void maybeLiftCover() {
    if (frameDrawn && resumed && hasWindowFocus()) root.postDelayed(this::coverNotNeeded, 60); // let the frame reach the screen
  }

  @Override public void onWindowFocusChanged(boolean f) {
    super.onWindowFocusChanged(f);
    // Returning windows hand focus to the first focusable view (the date); before any key press it belongs on the first app.
    if (f && noReveal && headerRow.contains(root.findFocus())) root.post(this::focusFirstApp);
    if (f) maybeLiftCover();
  }

  // Home pressed: back to the first app at the top, without rebuilding (rebuilding costs ~0.5 s on this TV).
  void resetToTop() {
    noReveal = true;
    stopGlides();
    sv.scrollTo(0, 0);
    for (int i = 0; i < content.getChildCount(); i++)
      if (content.getChildAt(i) instanceof HorizontalScrollView) content.getChildAt(i).scrollTo(0, 0);
    rowCol.clear();
    focusFirstApp();
  }

  void focusFirstApp() {
    for (View t : tiles(content)) {
      String k = (String) t.getTag();
      if (!k.startsWith("in:") && !k.startsWith("st:")) { t.requestFocus(); break; }
    }
  }

  @Override public void onBackPressed() {} // as the home screen there is nowhere to go back to

  @Override public boolean onKeyDown(int k, KeyEvent e) {
    if (k == KeyEvent.KEYCODE_MENU) { customize(); return true; }
    return super.onKeyDown(k, e);
  }

  // ponytail: package list is re-queried on every resume; cheap at ~30 apps, cache if it ever isn't
  String signature() {
    StringBuilder sb = new StringBuilder();
    for (Item i : items()) sb.append(i.key).append('|');
    return sb.toString();
  }

  List<Item> items() {
    PackageManager pm = getPackageManager();
    List<Item> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
      for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue; // leanback entry wins
        Item i = new Item();
        i.app = true; i.pkg = pkg; i.key = pkg; i.ri = ri;
        i.label = ri.loadLabel(pm).toString();
        i.intent = new Intent(Intent.ACTION_MAIN).addCategory(cat)
            .setComponent(new ComponentName(pkg, ri.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(i);
      }
    return out;
  }

  List<Item> inputs() {
    List<Item> out = new ArrayList<>();
    try {
      int n = 0;
      List<TvInputInfo> all = new ArrayList<>(tm.getTvInputList());
      Collections.sort(all, (a, c) -> a.getId().compareTo(c.getId()));
      for (TvInputInfo ti : all) {
        if (ti.getType() != TvInputInfo.TYPE_HDMI) continue;
        n++;
        CharSequence l = ti.loadCustomLabel(this);
        if (l == null || l.length() == 0) l = ti.loadLabel(this);
        Item i = new Item();
        i.key = "in:" + ti.getId();
        i.inputId = ti.getId();
        i.icon = p.getInt("inico:" + ti.getId(), 0);
        i.label = (l == null || l.length() == 0) ? "HDMI " + n : l.toString();
        String nm = p.getString("inname:" + ti.getId(), "");
        if (!nm.isEmpty()) i.label = nm;
        i.color = 0xFF1F4E8C;
        i.intent = new Intent(this, InputActivity.class).putExtra("input", ti.getId()); // see InputActivity: Amazon's Live TV mishandles the standard intent
        out.add(i);
      }
    } catch (Exception ignored) {}
    return out;
  }

  List<Item> settings() {
    List<Item> out = new ArrayList<>();
    Item c = new Item();
    c.key = "st:customize"; c.label = "Customize"; c.color = accent(); c.icon = 9;
    out.add(c);
    for (String[] s : SETTINGS) {
      Intent in = s[1].startsWith("action:") ? new Intent(s[1].substring(7)) : new Intent().setComponent(ComponentName.unflattenFromString(s[1]));
      in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (getPackageManager().resolveActivity(in, 0) == null) continue; // other Fire OS builds may lack a page
      Item i = new Item();
      i.key = "st:" + s[0]; i.label = s[0]; i.intent = in; i.color = 0xFF3A3F4D; i.icon = Integer.parseInt(s[2]);
      out.add(i);
    }
    return out;
  }

  void sortByOrder(List<Item> l) {
    Collections.sort(l, (a, b) -> {
      int x = ordered.indexOf(a.key), y = ordered.indexOf(b.key);
      if (x < 0) x = Integer.MAX_VALUE;
      if (y < 0) y = Integer.MAX_VALUE;
      return x != y ? Integer.compare(x, y) : a.label.compareToIgnoreCase(b.label);
    });
  }

  void rebuild() {
    ordered = new ArrayList<>(Arrays.asList(p.getString("ord", "").split(",")));
    Set<String> fav = set("fav"), hid = set("hid");
    List<Item> favs = new ArrayList<>(), apps = new ArrayList<>(), amz = new ArrayList<>();
    for (Item i : items()) {
      if (hid.contains(i.pkg)) continue;
      if (fav.contains(i.pkg)) favs.add(i);
      else if (!NOT_AMAZON.contains(i.pkg) && (i.pkg.startsWith("com.amazon.") || i.pkg.startsWith("amazon.") || i.pkg.contains(".amazon."))) amz.add(i);
      else apps.add(i);
    }
    sortByOrder(favs); sortByOrder(apps); sortByOrder(amz);

    if (tileW() != artW) { art.clear(); artBanner.clear(); artW = tileW(); } // tile size changed: cached art is the wrong size
    stopGlides();
    content.removeAllViews();
    grid.clear();
    grid.add(headerRow);
    inputStylers.clear();
    section("Inputs", inputs(), false);
    section("Favorites", favs, true);
    section("Apps", apps, true);
    if (!p.getBoolean("noamz", false)) section("Amazon", amz, true);
    section("Settings", settings(), false);
    View f = null, first = null, firstApp = null;
    for (View t : tiles(content)) {
      String k = (String) t.getTag();
      if (first == null) first = t;
      if (firstApp == null && !k.startsWith("in:") && !k.startsWith("st:")) firstApp = t;
      if (k.equals(focusKey)) f = t;
    }
    boolean fresh = f == null; // fresh start: begin on the first app but keep the big clock in view
    if (f == null) f = firstApp != null ? firstApp : first;
    if (f != null) {
      if (fresh) { noReveal = true; sv.scrollTo(0, 0); }
      f.requestFocus();
    }
  }

  List<View> tiles(ViewGroup g) {
    List<View> out = new ArrayList<>();
    for (int i = 0; i < g.getChildCount(); i++) {
      View c = g.getChildAt(i);
      if (c.getTag() instanceof String && c.isFocusable()) out.add(c);
      else if (c instanceof ViewGroup) out.addAll(tiles((ViewGroup) c));
    }
    return out;
  }

  void section(String title, List<Item> items, boolean hasMenu) {
    if (items.isEmpty()) return;
    {
      TextView h = new TextView(this);
      h.setText(title.toUpperCase());
      h.setTextColor(0x66FFFFFF);
      h.setTextSize(13);
      h.setLetterSpacing(0.12f);
      h.setPadding(dp(78), dp(6), 0, 0);
      content.addView(h);
    }

    boolean asGrid = p.getInt("layout", 0) == 1;
    ViewGroup holder;
    int cols = Integer.MAX_VALUE; // rows: one navigable line per section
    if (asGrid) {
      cols = Math.max(1, (getResources().getDisplayMetrics().widthPixels - dp(128)) / (tileW() + dp(28)));
      GridLayout g = new GridLayout(this);
      g.setColumnCount(cols);
      g.setPadding(dp(64), dp(10), dp(64), dp(14));
      holder = g;
      content.addView(g);
    } else {
      LinearLayout r = new LinearLayout(this);
      r.setPadding(dp(64), dp(10), dp(64), dp(14));
      r.setClipChildren(false);
      r.setClipToPadding(false);
      HorizontalScrollView hs = new HorizontalScrollView(this) {
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
      };
      hs.setHorizontalScrollBarEnabled(false);
      hs.setOnScrollChangeListener((v, x, y, ox, oy) -> updateBar());
      hs.setClipChildren(false);
      hs.setClipToPadding(false);
      hs.addView(r);
      holder = r;
      content.addView(hs);
    }
    holder.setClipChildren(false);
    List<View> row = new ArrayList<>();
    for (Item i : items) {
      View t = tile(i, items, hasMenu);
      row.add(t);
      ViewGroup.MarginLayoutParams lp = asGrid ? new GridLayout.LayoutParams() : new LinearLayout.LayoutParams(0, 0);
      lp.width = (i.app) ? tileW() : Math.min(tileW(), dp(170)); lp.height = -2;
      lp.setMargins(dp(14), dp(8), dp(14), dp(8));
      holder.addView(t, lp);
    }
    for (int a = 0; a < row.size(); a += cols) grid.add(new ArrayList<>(row.subList(a, Math.min(row.size(), a + cols))));
  }

  int[] pos(View v) {
    for (int r = 0; r < grid.size(); r++) { int c = grid.get(r).indexOf(v); if (c >= 0) return new int[] {r, c}; }
    return null;
  }

  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    int k = e.getKeyCode();
    boolean arrow = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
    if (!arrow || grid.isEmpty()) return super.dispatchKeyEvent(e);
    if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
    noReveal = false; // first real key press ends the "stay at the top" start state
    int[] cur = pos(root.findFocus());
    if (cur == null) // focus fell outside the grid (after Home, say): move from the last focused tile instead
      for (View t : tiles(content)) if (t.getTag().equals(focusKey)) cur = pos(t);
    if (cur == null) {
      for (List<View> row : grid) if (!row.isEmpty()) { row.get(0).requestFocus(); break; }
      return true;
    }
    int r = cur[0], c = cur[1];
    rowCol.put(r, c);
    if (k == KeyEvent.KEYCODE_DPAD_LEFT) c--;
    else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) c++;
    else {
      r += k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
      if (r < 0 || r >= grid.size()) return true;
      Integer m = rowCol.get(r);
      c = Math.min(m != null ? m : r == 0 ? 0 : c, grid.get(r).size() - 1); // into the header: start on the date
    }
    if (r >= 0 && r < grid.size() && c >= 0 && c < grid.get(r).size()) { tick(k); grid.get(r).get(c).requestFocus(); }
    return true; // never fall through to Android's geometric focus search
  }

  static final float SOUND_VOL = 0.5f; // navigation tick loudness, 0..1

  // Fire OS's own Left/Right/Down tick sounds. The volume overload plays even if the TV's UI sounds are switched off.
  void tick(int k) {
    if (!p.getBoolean("sound", true)) return;
    int fx = k == KeyEvent.KEYCODE_DPAD_LEFT ? AudioManager.FX_FOCUS_NAVIGATION_LEFT : k == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.FX_FOCUS_NAVIGATION_RIGHT
        : k == KeyEvent.KEYCODE_DPAD_UP ? AudioManager.FX_FOCUS_NAVIGATION_UP : AudioManager.FX_FOCUS_NAVIGATION_DOWN;
    ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(fx, SOUND_VOL);
  }

  static final TimeInterpolator POP = new OvershootInterpolator(1.6f), EASE = new DecelerateInterpolator(1.8f);
  Map<View, ObjectAnimator> glides = new HashMap<>();

  // Eased scroll of a row (scrollX) or the page (scrollY). Each view has its own animator, so a row keeps gliding after
  // you move to the next one; a new target picks up from wherever the running glide is.
  void glide(View v, String prop, int to) {
    ObjectAnimator old = glides.remove(v);
    if (old != null) old.cancel();
    int from = prop.equals("scrollX") ? v.getScrollX() : v.getScrollY();
    if (from == to) return;
    ObjectAnimator a = ObjectAnimator.ofInt(v, prop, from, to);
    a.setDuration(240);
    a.setInterpolator(EASE);
    a.start();
    glides.put(v, a);
  }

  void stopGlides() {
    for (ObjectAnimator a : glides.values()) a.cancel();
    glides.clear();
  }

  void reveal(View tile) {
    Rect r = new Rect(0, 0, tile.getWidth(), tile.getHeight());
    sv.offsetDescendantRectToMyCoords(tile, r);
    int[] ps = pos(tile);
    int maxY = Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight());
    glide(sv, "scrollY", ps != null && ps[0] <= 1 ? 0 : Math.min(maxY, Math.max(0, r.top - dp(200)))); // first tile row keeps the header in view
    for (ViewParent pr = tile.getParent(); pr != null; pr = pr.getParent())
      if (pr instanceof HorizontalScrollView) {
        HorizontalScrollView hs = (HorizontalScrollView) pr;
        Rect q = new Rect(0, 0, tile.getWidth(), tile.getHeight());
        hs.offsetDescendantRectToMyCoords(tile, q);
        int x = hs.getScrollX();
        if (q.right > x + hs.getWidth() - dp(100)) x = q.right - hs.getWidth() + dp(100);
        if (q.left < x + dp(64)) x = q.left - dp(64);
        glide(hs, "scrollX", Math.max(0, x));
        break;
      }
  }

  View tile(Item it, List<Item> section, boolean hasMenu) {
    PackageManager pm = getPackageManager();
    int w = it.app ? tileW() : Math.min(tileW(), dp(170));
    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    col.setGravity(Gravity.CENTER_HORIZONTAL);
    col.setClipChildren(false);
    col.setFocusable(true);
    col.setClickable(true);
    col.setTag(it.key);

    FrameLayout card = new FrameLayout(this);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(it.app ? 0xFF2A3350 : it.color);
    bg.setCornerRadius(dp(18));
    if (it.key.equals("st:customize")) custBg = bg;
    card.setBackground(bg);
    card.setClipToOutline(true);
    card.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18)); }
    });
    if (it.app) {
      ImageView img = new ImageView(this);
      String ak = it.key + "@" + w;
      Bitmap bm = art.get(ak);
      boolean isBanner;
      if (bm != null) isBanner = artBanner.get(ak);
      else {
        Drawable d = banner(it);
        isBanner = d != null;
        if (!isBanner) d = it.ri.loadIcon(pm);
        // Keep only a tile-sized copy: the source art is often several times larger and would stay decoded in memory.
        int bh = w * 9 / 16, bw = isBanner ? w : bh - dp(28);
        if (!isBanner) bh = bw;
        bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(bm));
        Bitmap hw = bm.copy(Bitmap.Config.HARDWARE, false); // GPU-only copy: no second copy in the native heap
        if (hw != null) { bm.recycle(); bm = hw; }
        art.put(ak, bm);
        artBanner.put(ak, isBanner);
      }
      img.setScaleType(isBanner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
      img.setImageBitmap(bm);
      card.addView(img, new FrameLayout.LayoutParams(-1, -1));
    } else {
      LinearLayout in = new LinearLayout(this);
      in.setOrientation(LinearLayout.VERTICAL);
      in.setGravity(Gravity.CENTER);
      in.setPadding(dp(8), dp(6), dp(8), dp(6));
      ImageView ic = new ImageView(this);
      ic.setImageDrawable(new Ico(it.icon));
      in.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));
      TextView t = new TextView(this);
      t.setText(it.label);
      t.setTextColor(0xFFFFFFFF);
      t.setTextSize(13);
      t.setGravity(Gravity.CENTER);
      t.setMaxLines(2);
      t.setPadding(0, dp(4), 0, 0);
      in.addView(t);
      if (it.inputId != null) { // greyed out while the port reports no signal
        TextView st = new TextView(this);
        st.setTextSize(11);
        st.setGravity(Gravity.CENTER);
        in.addView(st);
        Runnable style = () -> {
          boolean on = inputActive(it.inputId);
          bg.setColor(on ? it.color : 0xFF1E232E);
          ic.setAlpha(on ? 1f : 0.35f);
          t.setTextColor(on ? 0xFFFFFFFF : 0x66FFFFFF);
          st.setText(on ? "Connected" : "No signal");
          st.setTextColor(on ? 0xBBFFFFFF : 0x44FFFFFF);
        };
        style.run();
        inputStylers.put(it.key, style);
      }
      card.addView(in, new FrameLayout.LayoutParams(-1, -1));
    }
    col.addView(card, new LinearLayout.LayoutParams(w, it.app ? w * 9 / 16 : dp(92)));

    boolean always = p.getBoolean("labels", false);
    TextView tv = it.app ? new TextView(this) : null; // compact system tiles carry their label inside
    if (tv != null) {
      tv.setText(it.label);
      tv.setTextColor(0xFFFFFFFF);
      tv.setTextSize(18);
      tv.setSingleLine();
      tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
      tv.setAlpha(always ? 0.65f : 0f);
      tv.setPadding(0, dp(12), 0, 0);
      col.addView(tv);
    }

    col.setOnFocusChangeListener((v, f) -> {
      float s = f ? 1.12f : 1f;
      v.animate().scaleX(s).scaleY(s).setDuration(f ? 220 : 160).setInterpolator(f ? POP : EASE).start();
      if (tv != null) tv.animate().alpha(f ? 1f : (always ? 0.65f : 0f)).setDuration(160).setInterpolator(EASE).start();
      if (f) { focusKey = it.key; focusItem = it; tint(it); v.post(() -> { if (!noReveal) reveal(v); updateBar(); }); }
    });
    col.setOnClickListener(v -> {
      if (it.intent == null) { customize(); return; }
      if (it.inputId != null && !inputActive(it.inputId)) { // empty port: launching it just falls through to another app
        Toast.makeText(this, it.label + ": no signal", Toast.LENGTH_SHORT).show();
        return;
      }
      try { startActivity(it.intent); } catch (Exception ex) { Toast.makeText(this, "Can't open " + it.label, Toast.LENGTH_SHORT).show(); }
    });
    if (hasMenu && it.app) col.setOnLongClickListener(v -> { itemMenu(it, section); return true; });
    else if (it.inputId != null) col.setOnLongClickListener(v -> { inputMenu(it, null); return true; });
    return col;
  }

  void itemMenu(Item it, List<Item> section) {
    boolean fav = set("fav").contains(it.pkg);
    String[] opts = {fav ? "Unpin from Favorites" : "Pin to Favorites", "Move left", "Move right", "Hide", "App info"};
    menu(it.label, null, opts, -1, w -> {
          if (w == 0) { Set<String> s = set("fav"); if (fav) s.remove(it.pkg); else s.add(it.pkg); p.edit().putStringSet("fav", s).apply(); }
          else if (w == 1 || w == 2) move(it, section, w == 1 ? -1 : 1);
          else if (w == 3) { Set<String> s = set("hid"); s.add(it.pkg); p.edit().putStringSet("hid", s).apply(); }
          else {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ex) { Toast.makeText(this, "No app info page", Toast.LENGTH_SHORT).show(); }
            return;
          }
          rebuild();
        });
  }

  static final String[] DEV_NAMES = {"HDMI (default)", "PlayStation", "Xbox", "Nintendo Switch", "Game controller", "Streaming box", "Computer", "Cable / satellite box", "Disc player", "Soundbar / receiver", "Camera", "TV"};
  static final int[] DEV_ICONS = {0, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 2};

  void inputMenu(Item it, Runnable done) {
    Runnable then = () -> { if (done != null) done.run(); };
    String[] opts = {"Rename", "Change icon", "Reset to default"};
    menu(it.label, null, opts, -1, w -> {
      if (w == 0) prompt("Name this input", "For example: PlayStation 5", it.label, n -> {
        if (!n.isEmpty()) { p.edit().putString("inname:" + it.inputId, n).apply(); rebuild(); }
        then.run();
      }, then);
      else if (w == 1) menu("Icon for " + it.label, null, DEV_NAMES, indexOf(DEV_ICONS, it.icon), i -> { p.edit().putInt("inico:" + it.inputId, DEV_ICONS[i]).apply(); rebuild(); then.run(); });
      else { p.edit().remove("inname:" + it.inputId).remove("inico:" + it.inputId).apply(); rebuild(); then.run(); }
    });
  }

  static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return -1; }

  void move(Item it, List<Item> section, int dir) {
    List<String> keys = new ArrayList<>();
    for (Item i : section) keys.add(i.key);
    int a = keys.indexOf(it.key), b = a + dir;
    if (b < 0 || b >= keys.size()) return;
    Collections.swap(keys, a, b);
    for (String k : ordered) if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
    p.edit().putString("ord", android.text.TextUtils.join(",", keys)).apply();
  }

  // Every .jpg/.jpeg/.png in launcher/assets/ becomes a Background choice, named from its file name
  // ("new_york_sunset.jpeg" -> "New York Sunset"; an ALL-CAPS word keeps its capitals).
  List<String> photos = new ArrayList<>();

  void findPhotos() {
    try {
      for (String n : getAssets().list("")) {
        String l = n.toLowerCase(Locale.US);
        if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")) photos.add(n);
      }
    } catch (IOException ignored) {}
    Collections.sort(photos, String.CASE_INSENSITIVE_ORDER);
  }

  static String photoLabel(String file) {
    StringBuilder sb = new StringBuilder();
    for (String w : file.replaceFirst("\\.[^.]*$", "").split("[_\\s]+")) {
      if (w.isEmpty()) continue;
      if (sb.length() > 0) sb.append(' ');
      sb.append(w.length() > 1 && w.equals(w.toUpperCase(Locale.US)) ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.US));
    }
    return sb.toString();
  }

  int bgCount() { return BG.length + photos.size(); }
  String bgName(int i) { return i < BG.length ? BG_NAMES[i] : photoLabel(photos.get(i - BG.length)); }

  Bitmap[] photoLv = new Bitmap[PHOTO_LEVELS.length]; // the same picture at each blur level (GPU-only bitmaps)
  volatile boolean photoLoading;
  int photoShown = 0, photoTarget = 0;
  volatile int loadedPhoto = -1; // which photo photoLv currently holds
  ValueAnimator photoAnim;

  // Decode, centre-crop to 16:9 and build every blur level once, off the UI thread.
  synchronized void loadPhoto(int idx) {
    if (loadedPhoto == idx && photoLv[0] != null) return;
    final int W = 1920, H = 1080;
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    try (InputStream in = getAssets().open(photos.get(idx))) { BitmapFactory.decodeStream(in, null, o); } catch (IOException e) { return; }
    if (o.outWidth <= 0) return;
    o.inJustDecodeBounds = false;
    o.inSampleSize = Math.max(1, Math.min(o.outWidth / W, o.outHeight / H));
    Bitmap src;
    try (InputStream in = getAssets().open(photos.get(idx))) { src = BitmapFactory.decodeStream(in, null, o); } catch (IOException e) { return; }
    if (src == null) return;
    float sc = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
    Bitmap crop = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565); // photo needs no alpha: half the memory
    Matrix m = new Matrix();
    m.postScale(sc, sc);
    m.postTranslate((W - src.getWidth() * sc) / 2, (H - src.getHeight() * sc) / 2);
    new Canvas(crop).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
    src.recycle();
    Bitmap[] lv = new Bitmap[PHOTO_LEVELS.length];
    for (int i = 0; i < lv.length; i++) lv[i] = gpuCopy(blurred(crop, PHOTO_LEVELS[i][0], PHOTO_LEVELS[i][1], PHOTO_LEVELS[i][2]));
    crop.recycle();
    Bitmap[] old = photoLv;
    photoLv = lv;
    loadedPhoto = idx;
    for (Bitmap b : old) if (b != null && b != lv[0]) b.recycle(); // free the previous picture
  }

  Bitmap gpuCopy(Bitmap b) {
    Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false); // GPU-only: no CPU copy kept in memory
    if (hw == null) return b;
    b.recycle();
    return hw;
  }

  // Blur only the picture (never the UI): shrink by `scale`, box-blur, scale back up. scale 0 = untouched copy.
  Bitmap blurred(Bitmap src, int scale, int r, int passes) {
    final int W = src.getWidth(), H = src.getHeight();
    if (scale == 0) return src.copy(src.getConfig(), true);
    Bitmap small = scale == 1 ? src.copy(Bitmap.Config.ARGB_8888, true)
        : Bitmap.createScaledBitmap(src, W / scale, H / scale, true).copy(Bitmap.Config.ARGB_8888, true);
    int sw = small.getWidth(), sh = small.getHeight();
    int[] px = new int[sw * sh], tmp = new int[sw * sh];
    small.getPixels(px, 0, sw, 0, 0, sw, sh);
    for (int i = 0; i < passes; i++) {
      blurPass(px, tmp, sw, sh, r, true);
      blurPass(tmp, px, sw, sh, r, false);
    }
    small.setPixels(px, 0, sw, 0, 0, sw, sh);
    Bitmap out = scale == 1 ? small : Bitmap.createScaledBitmap(small, W, H, true);
    if (out != small) small.recycle();
    return out.getConfig() == Bitmap.Config.RGB_565 ? out : out.copy(Bitmap.Config.RGB_565, false);
  }

  int levelFor(int scrollY) { return scrollY < dp(80) ? 0 : scrollY < dp(300) ? 1 : 2; }

  void photoBg(int level) {
    root.setBackground(new LayerDrawable(new Drawable[] {new BitmapDrawable(getResources(), photoLv[level]), new ColorDrawable(0xAA000000)}));
  }

  // Cross-fade between blur levels as the page scrolls; at rest only one picture layer is drawn.
  void photoLevelTo(int level) {
    int cur = p.getInt("bg", 0) - BG.length;
    if (cur < 0 || loadedPhoto != cur || photoLv[0] == null || level == photoTarget) return;
    photoTarget = level;
    if (photoAnim != null) photoAnim.cancel();
    final int from = photoShown;
    if (from == level) { photoBg(level); return; }
    BitmapDrawable top = new BitmapDrawable(getResources(), photoLv[level]);
    root.setBackground(new LayerDrawable(new Drawable[] {new BitmapDrawable(getResources(), photoLv[from]), top, new ColorDrawable(0xAA000000)}));
    top.setAlpha(0);
    photoAnim = ValueAnimator.ofInt(0, 255);
    photoAnim.setDuration(220);
    photoAnim.addUpdateListener(v -> top.setAlpha((int) v.getAnimatedValue()));
    photoAnim.addListener(new AnimatorListenerAdapter() {
      boolean cancelled;
      @Override public void onAnimationCancel(Animator a) { cancelled = true; }
      @Override public void onAnimationEnd(Animator a) { if (!cancelled) { photoShown = level; photoBg(level); } }
    });
    photoAnim.start();
  }

  // one separable box-blur pass (running sums, edges clamped)
  static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
    int len = horiz ? w : h, lines = horiz ? h : w, n = 2 * r + 1;
    for (int l = 0; l < lines; l++) {
      int rs = 0, gs = 0, bs = 0;
      for (int i = -r; i <= r; i++) {
        int c = src[horiz ? l * w + Math.min(Math.max(i, 0), len - 1) : Math.min(Math.max(i, 0), len - 1) * w + l];
        rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255;
      }
      for (int i = 0; i < len; i++) {
        dst[horiz ? l * w + i : i * w + l] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
        int a = src[horiz ? l * w + Math.min(i + r + 1, len - 1) : Math.min(i + r + 1, len - 1) * w + l];
        int b = src[horiz ? l * w + Math.max(i - r, 0) : Math.max(i - r, 0) * w + l];
        rs += ((a >> 16) & 255) - ((b >> 16) & 255); gs += ((a >> 8) & 255) - ((b >> 8) & 255); bs += (a & 255) - (b & 255);
      }
    }
  }

  void applyBg() {
    int i = p.getInt("bg", 0);
    if (i == 0) { setTop(curTop); root.setBackground(bgGrad); return; }
    if (i >= BG.length) {
      int idx = i - BG.length;
      if (idx < photos.size()) {
        if (loadedPhoto == idx && photoLv[0] != null) {
          photoTarget = photoShown = levelFor(sv.getScrollY());
          if (photoAnim != null) photoAnim.cancel();
          photoBg(photoShown);
          return;
        }
        if (!photoLoading) { // decoding a big JPEG takes a moment: do it off the UI thread, gradient until it is ready
          photoLoading = true;
          new Thread(() -> { loadPhoto(idx); photoLoading = false; runOnUiThread(this::applyBg); }).start();
        }
      }
      i = 1;
    }
    int[] c = BG[Math.min(i, BG.length - 1)];
    lastTop = c[0];
    root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, c));
  }

  // The bar has no background of its own; it turns black only while a tile is underneath it.
  void updateBar() {
    if (barCheckPending) return; // coalesce: at most one check per frame
    barCheckPending = true;
    root.postOnAnimation(() -> { barCheckPending = false; checkBar(); });
  }

  void checkBar() {
    if (floatBar == null || pillBg == null || floatBar.getVisibility() != View.VISIBLE) return;
    Rect bar = new Rect(), t = new Rect();
    boolean need = false;
    if (floatBar.getGlobalVisibleRect(bar)) {
      bar.inset(-dp(8), -dp(6));
      scan:
      for (List<View> row : grid)
        for (View v : row)
          if (v instanceof ViewGroup && ((ViewGroup) v).getChildAt(0).getGlobalVisibleRect(t) && Rect.intersects(t, bar)) { need = true; break scan; }
    }
    if (need == barBlack) return;
    barBlack = need;
    if (pillAnim != null) pillAnim.cancel();
    pillAnim = ValueAnimator.ofInt(pillBg.getAlpha(), need ? 255 : 0);
    pillAnim.setDuration(150);
    pillAnim.addUpdateListener(a -> pillBg.setAlpha((int) a.getAnimatedValue()));
    pillAnim.start();
  }

  void fade(View v, boolean in) {
    if (in) { v.setVisibility(View.VISIBLE); if (v == floatBar) v.post(this::updateBar); }
    v.animate().alpha(in ? 1f : 0f).setDuration(200).withEndAction(() -> {
      if (in) return;
      v.setVisibility(View.GONE);
      if (v == floatBar && pillBg != null) { barBlack = false; pillBg.setAlpha(0); } // reset for next time
    }).start();
  }

  // Accent: the Customize tile, menu highlights, and the background behind inputs and settings.
  int accent() { int a = p.getInt("accent", 0); return a == 0 ? DEFAULT_ACCENT : a; }

  void paintAccent() {
    if (custBg != null) custBg.setColor(accent());
  }

  void setTop(int top) {
    curTop = top;
    lastTop = top;
    bgGrad.setColors(grad(top));
  }

  int bgFrom(int base) {
    float[] h = new float[3];
    Color.colorToHSV(base, h);
    h[1] = Math.min(h[1], 0.75f);
    h[2] = 0.45f;
    return Color.HSVToColor(h);
  }

  Drawable banner(Item it) {
    PackageManager pm = getPackageManager();
    Drawable d = it.ri.activityInfo.loadBanner(pm);
    return d != null ? d : it.ri.activityInfo.applicationInfo.loadBanner(pm);
  }

  // "App color" background: the weighted average of the app art's saturated pixels, cached per app.
  int iconColor(Item it) {
    if (!it.app) return bgFrom(accent()); // inputs, settings and Customize share the accent; apps keep their own icon colour
    Integer c = tints.get(it.key);
    if (c != null) return c;
    Drawable d = banner(it);
    if (d == null) d = it.ri.loadIcon(getPackageManager());
    Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
    d.setBounds(0, 0, 24, 24);
    d.draw(new Canvas(b));
    float[] hsv = new float[3];
    double r = 0, g = 0, bl = 0, wsum = 0;
    for (int y = 0; y < 24; y++)
      for (int x = 0; x < 24; x++) {
        int px = b.getPixel(x, y);
        if (Color.alpha(px) < 128) continue;
        Color.colorToHSV(px, hsv);
        if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue; // skip white/grey/black backdrops
        double w = hsv[1] * hsv[2];
        r += Color.red(px) * w; g += Color.green(px) * w; bl += Color.blue(px) * w; wsum += w;
      }
    int out = bgFrom(wsum > 0 ? Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum)) : 0xFF3A4256);
    tints.put(it.key, out);
    return out;
  }

  Runnable pendingTint;

  void tint(Item it) {
    if (p.getInt("bg", 0) != 0) return;
    if (pendingTint != null) root.removeCallbacks(pendingTint);
    pendingTint = () -> { // wait for the focus to settle; fast scrolling would otherwise restart this every key press
      if (colorAnim != null) colorAnim.cancel();
      ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), curTop, iconColor(it));
      a.setDuration(300);
      a.addUpdateListener(v -> setTop((int) v.getAnimatedValue()));
      a.start();
      colorAnim = a;
    };
    root.postDelayed(pendingTint, 140);
  }

  // Weather: Open-Meteo for the forecast, ipwho.is to locate the TV by IP (no API keys).
  static String wxName(int c) {
    if (c == 0) return "Clear";
    if (c <= 2) return "Partly cloudy";
    if (c == 3) return "Cloudy";
    if (c == 45 || c == 48) return "Fog";
    if (c >= 51 && c <= 57) return "Drizzle";
    if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return "Rain";
    if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "Snow";
    if (c >= 95) return "Thunderstorm";
    return "";
  }

  static int wxKind(int c, boolean day) {
    if (c == 0) return day ? 0 : 8;
    if (c <= 2) return day ? 1 : 2;
    if (c == 45 || c == 48) return 3;
    if ((c >= 51 && c <= 57) || (c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return 4;
    if ((c >= 71 && c <= 77) || c == 85 || c == 86) return 5;
    if (c >= 95) return 6;
    return 2;
  }

  // cache format: temp|code|city|timestamp|wind|isDay
  void showWeather() {
    try { showWeatherUnsafe(); } catch (RuntimeException e) { weatherBig.setText(""); weatherSmall.setText(""); wxPlain = ""; }
    alertChip.setText(withAlert("", 22));
    weatherBig.setVisibility(weatherBig.length() > 0 ? View.VISIBLE : View.GONE);
    alertChip.setVisibility(alertChip.length() > 0 ? View.VISIBLE : View.GONE);
    headerRow.clear();
    headerRow.add(dateChip);
    for (TextView t : new TextView[] {weatherBig, alertChip}) if (t.getVisibility() == View.VISIBLE) headerRow.add(t);
  }

  void showWeatherUnsafe() {
    String[] a = p.getString("w", "").split("\\|", 6);
    if (a.length < 4) { weatherBig.setText(""); weatherSmall.setText(""); wxPlain = ""; return; }
    boolean c = p.getBoolean("celsius", false);
    int code = Integer.parseInt(a[1]);
    boolean windy = code <= 3 && a.length > 4 && Double.parseDouble(a[4]) >= (c ? 32 : 20);
    int kind = windy ? 7 : wxKind(code, a.length < 6 || a[5].equals("1"));
    String t = a[0] + "\u00b0" + (c ? "C" : "F");
    wxKind = kind;
    wxPlain = t + "  " + (windy ? "Windy" : wxName(code));
    weatherBig.setText(withIcon(this, "", kind, wxPlain, 24));
    weatherSmall.setText(new android.text.SpannableStringBuilder(withAlert(withIcon(this, "", kind, t, 22), 20)).append("    "));
  }

  String get(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(8000);
    c.setReadTimeout(8000);
    c.setRequestProperty("User-Agent", "FastHome/1.0 (Fire TV launcher)"); // required by api.weather.gov
    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
      StringBuilder sb = new StringBuilder();
      for (String l; (l = r.readLine()) != null; ) sb.append(l);
      return sb.toString();
    } finally { c.disconnect(); }
  }

  // National Weather Service alerts (US only; elsewhere the request fails and the list stays empty).
  // Kept on failure, so a brief NWS outage doesn't hide an active warning.
  void fetchAlerts(double lat, double lon) {
    try {
      JSONArray f = new JSONObject(get(String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon))).getJSONArray("features");
      JSONArray out = new JSONArray();
      for (int i = 0; i < f.length(); i++) {
        JSONObject a = f.getJSONObject(i).getJSONObject("properties");
        out.put(new JSONObject().put("event", str(a, "event")).put("severity", str(a, "severity")).put("headline", str(a, "headline"))
            .put("description", str(a, "description")).put("instruction", str(a, "instruction"))
            .put("until", a.isNull("ends") ? str(a, "expires") : str(a, "ends")));
      }
      p.edit().putString("nws", out.toString()).apply();
    } catch (Exception ignored) {}
  }

  static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k); }

  JSONArray alerts() {
    try { return new JSONArray(p.getString("nws", "[]")); } catch (JSONException e) { return new JSONArray(); }
  }

  static final int[] SEV_COLOR = {0xFFFFFFFF, 0xFFFFC83D, 0xFFFF4D4D}; // minor or unknown, moderate, severe or extreme

  static int sevRank(String s) { return "Extreme".equals(s) || "Severe".equals(s) ? 2 : "Moderate".equals(s) ? 1 : 0; }

  // Color of the worst active alert, or 0 when there are none.
  int alertColor() {
    JSONArray a = alerts();
    int worst = -1;
    for (int i = 0; i < a.length(); i++) worst = Math.max(worst, sevRank(a.optJSONObject(i).optString("severity")));
    return worst < 0 ? 0 : SEV_COLOR[worst];
  }

  CharSequence withAlert(CharSequence s, int sizeDp) { return alertMark(this, s, alertColor(), sizeDp); }

  String until(JSONObject a) {
    try {
      Date d = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(a.optString("until"));
      return "until " + android.text.format.DateUtils.formatSameDayTime(d.getTime(), System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
    } catch (Exception e) { return ""; }
  }

  // Month grid with today circled; the buttons below flip months. offset 0 = this month, focus = button to start on.
  void calendarMenu(int offset, int focus) {
    Calendar cal = Calendar.getInstance();
    int today = cal.get(Calendar.DAY_OF_MONTH);
    cal.set(Calendar.DAY_OF_MONTH, 1);
    cal.add(Calendar.MONTH, offset);
    LinearLayout list = column();
    Dialog d = sheet(new java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.getTime()), null, list);
    GridLayout g = new GridLayout(this);
    g.setColumnCount(7);
    int cell = dp(77), first = cal.getFirstDayOfWeek();
    String[] names = java.text.DateFormatSymbols.getInstance().getShortWeekdays(); // 1 = Sunday
    for (int i = 0; i < 7; i++) g.addView(calCell(names[(first - 1 + i) % 7 + 1], 13, 0x66FFFFFF), cell, dp(30));
    for (int i = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7; i > 0; i--) g.addView(new View(this), cell, dp(34));
    for (int day = 1; day <= cal.getActualMaximum(Calendar.DAY_OF_MONTH); day++) {
      TextView t = calCell(String.valueOf(day), 16, 0xDDFFFFFF);
      if (offset == 0 && day == today) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accent());
        t.setBackground(new InsetDrawable(dot, (cell - dp(34)) / 2, 0, (cell - dp(34)) / 2, 0));
        t.setTextColor(0xFFFFFFFF);
      }
      g.addView(t, cell, dp(34));
    }
    list.addView(g);
    LinearLayout nav = new LinearLayout(this);
    nav.setPadding(0, dp(12), 0, 0);
    List<View> buttons = new ArrayList<>();
    buttons.add(row(nav, "\u2039 Previous", false, null, () -> close(d, () -> calendarMenu(offset - 1, 0))));
    if (offset != 0) buttons.add(row(nav, "Today", false, null, () -> close(d, () -> calendarMenu(0, 1))));
    buttons.add(row(nav, "Next \u203a", false, null, () -> close(d, () -> calendarMenu(offset + 1, offset + 1 == 0 ? 1 : 2))));
    for (View b : buttons) { b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); ((LinearLayout) b).setGravity(Gravity.CENTER); }
    list.addView(nav);
    open(d);
    buttons.get(Math.min(focus, buttons.size() - 1)).requestFocus();
  }

  TextView calCell(String s, int sp, int color) {
    TextView t = label(s, sp, color);
    t.setGravity(Gravity.CENTER);
    return t;
  }

  // Current details plus 7 days, from the forecast cached with the last weather fetch.
  void forecastMenu() {
    boolean c = p.getBoolean("celsius", false);
    String deg = "\u00b0", unit = deg + (c ? "C" : "F");
    String[] w = p.getString("w", "").split("\\|", 6);
    List<Opt> rows = new ArrayList<>();
    String body;
    try {
      JSONObject fc = new JSONObject(p.getString("fc", "")), now = fc.getJSONObject("current"), d = fc.getJSONObject("daily");
      body = "Feels like " + Math.round(now.getDouble("apparent_temperature")) + unit + "    \u00b7    Humidity " + now.getInt("relative_humidity_2m")
          + "%    \u00b7    Wind " + Math.round(now.getDouble("wind_speed_10m")) + (c ? " km/h" : " mph");
      JSONArray days = d.getJSONArray("time");
      java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US), out = new java.text.SimpleDateFormat("EEEE", Locale.getDefault());
      for (int i = 0; i < days.length(); i++) {
        int code = d.getJSONArray("weather_code").getInt(i);
        String name = i == 0 ? "Today" : i == 1 ? "Tomorrow" : out.format(in.parse(days.getString(i)));
        int rain = d.getJSONArray("precipitation_probability_max").optInt(i, 0);
        rows.add(new Opt(name + ": " + wxName(code) + (rain >= 10 ? "    " + rain + "% rain" : "") + "    "
            + Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)) + deg + " / " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)) + deg,
            new WxIco(wxKind(code, true)), true, null));
      }
    } catch (Exception e) {
      body = "The forecast isn't loaded yet. It arrives with the next weather update, within 10 minutes once the TV is online.";
      rows.clear();
    }
    rows.add(new Opt("\u2039 Back", null, true, null));
    menu("Forecast" + (w.length > 2 && !w[2].isEmpty() ? " for " + w[2] : ""), body, rows, -1, 0, x -> after.run());
  }

  void alertsMenu(int focus) {
    JSONArray a = alerts();
    int n = a.length();
    List<Opt> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      JSONObject x = a.optJSONObject(i);
      String u = until(x);
      rows.add(new Opt(x.optString("event") + (u.isEmpty() ? "" : ": " + u), new Ico(30, SEV_COLOR[sevRank(x.optString("severity"))]), true, null));
    }
    rows.add(new Opt("\u2039 Back", null, true, null));
    menu("Weather alerts", n == 0 ? "No active National Weather Service alerts here." : "From the National Weather Service. Select one to read it.",
        rows, -1, Math.min(focus, rows.size() - 1), w -> {
      if (w < n) alertDetail(a.optJSONObject(w), () -> alertsMenu(w));
      else after.run();
    });
  }

  // Full alert text below a Back row; Down scrolls the text.
  void alertDetail(JSONObject a, Runnable back) {
    LinearLayout list = column();
    Dialog d = sheet(a.optString("event"), null, list);
    View b = row(list, "\u2039 Back", false, null, () -> close(d, back));
    String text = a.optString("headline") + "\n\n" + a.optString("description") + (a.optString("instruction").isEmpty() ? "" : "\n\n" + a.optString("instruction"));
    TextView t = label(text.replaceAll("(?<!\n)\n(?!\n)", " "), 16, 0xCCFFFFFF); // NWS hard-wraps lines at ~70 characters
    t.setLineSpacing(0, 1.25f);
    t.setPadding(dp(18), dp(12), dp(18), dp(8));
    list.addView(t);
    open(d);
    b.requestFocus();
  }

  volatile boolean wxBusy;

  void refreshWeather(boolean force) {
    if (wxBusy) return; // offline, each resume would otherwise start another fetch
    String[] a = p.getString("w", "").split("\\|", 6);
    long ts = 0;
    try { if (a.length == 6) ts = Long.parseLong(a[3]); } catch (NumberFormatException ignored) {} // unreadable cache just means "refetch"
    if (!force && System.currentTimeMillis() - ts < 10 * 60 * 1000) return;
    wxBusy = true;
    new Thread(() -> {
      try {
        double lat, lon;
        String city, manual = p.getString("wcity", "");
        if (!manual.isEmpty()) {
          JSONObject r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(manual, "UTF-8")))
              .getJSONArray("results").getJSONObject(0);
          lat = r.getDouble("latitude"); lon = r.getDouble("longitude"); city = r.getString("name");
        } else {
          JSONObject g = new JSONObject(get("https://ipwho.is/"));
          if (!g.optBoolean("success")) return;
          lat = g.getDouble("latitude"); lon = g.getDouble("longitude"); city = g.optString("city");
          if (!p.contains("celsius")) p.edit().putBoolean("celsius", !"US".equals(g.optString("country_code"))).apply();
        }
        boolean c = p.getBoolean("celsius", false);
        JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
            + "&current=temperature_2m,weather_code,wind_speed_10m,is_day,apparent_temperature,relative_humidity_2m"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=7"
            + "&temperature_unit=" + (c ? "celsius" : "fahrenheit") + "&wind_speed_unit=" + (c ? "kmh" : "mph")));
        p.edit().putString("fc", w.toString()).apply();
        w = w.getJSONObject("current");
        p.edit().putString("w", Math.round(w.getDouble("temperature_2m")) + "|" + w.getInt("weather_code") + "|" + city.replace("|", "") + "|" + System.currentTimeMillis() + "|" + Math.round(w.getDouble("wind_speed_10m")) + "|" + w.getInt("is_day")).apply();
        fetchAlerts(lat, lon);
        runOnUiThread(this::showWeather);
      } catch (Exception ignored) { // offline or API down: keep showing the last reading
      } finally { wxBusy = false; }
    }).start();
  }

  void weatherLocation() {
    String[] o = {"Auto (use this TV's IP location)", "Enter a city..."};
    menu("Weather location", null, o, p.getString("wcity", "").isEmpty() ? 0 : 1, w -> {
      if (w == 0) { p.edit().remove("wcity").remove("w").apply(); showWeather(); refreshWeather(true); after.run(); return; }
      prompt("City", "For example: Austin", p.getString("wcity", ""), c -> {
        if (!c.isEmpty()) { p.edit().putString("wcity", c).remove("w").apply(); showWeather(); refreshWeather(true); }
        after.run();
      }, after);
    });
  }

  void backgroundDialog() {
    String[] names = new String[bgCount()];
    for (int i = 0; i < names.length; i++) names[i] = bgName(i);
    menu("Background", null, names, curBg(), w -> {
      p.edit().putInt("bg", w).apply();
      applyBg();
      if (w == 0 && focusItem != null) tint(focusItem);
      after.run();
    });
  }

  int curBg() { return Math.max(0, Math.min(p.getInt("bg", 0), bgCount() - 1)); } // a removed picture falls back to the last choice

  String accentName() {
    int a = p.getInt("accent", 0);
    for (int i = 0; i < ACC.length; i++) if (ACC[i] == a) return ACC_NAMES[i];
    return String.format("Custom #%06X", a & 0xFFFFFF);
  }

  void setAccent(int a) {
    p.edit().putInt("accent", a).apply();
    paintAccent();
    if (focusItem != null && !focusItem.app) tint(focusItem); // refresh the background if focus is on a non-app tile
  }

  void accentDialog() {
    int cur = indexOf(ACC, p.getInt("accent", 0));
    menu("Accent color", null, ACC_NAMES, cur < 0 ? ACC.length : cur, w -> {
      if (w < ACC.length) { setAccent(ACC[w]); after.run(); return; }
      prompt("Custom color", "#RRGGBB", "", h -> {
        try { setAccent(Color.parseColor(h.startsWith("#") ? h : "#" + h) | 0xFF000000); }
        catch (IllegalArgumentException bad) { Toast.makeText(this, "Not a valid color", Toast.LENGTH_SHORT).show(); }
        after.run();
      }, after);
    });
  }

  Runnable after = this::customize; // where a sub-menu returns to: the Customize row or guide step that opened it

  // A menu row. ownMenu false means a toggle: the menu rebuilds the home screen and reopens on the same row.
  static class Opt {
    final String label;
    final Drawable icon;
    final boolean ownMenu;
    final Runnable act;

    Opt(String label, Drawable icon, boolean ownMenu, Runnable act) {
      this.label = label;
      this.icon = icon;
      this.ownMenu = ownMenu;
      this.act = act;
    }
  }

  static List<Opt> plain(String... rows) {
    List<Opt> l = new ArrayList<>();
    for (String r : rows) l.add(new Opt(r, null, true, null));
    return l;
  }

  // Settings shown in both Customize and the guide.
  Opt bgOpt() { return new Opt("Background: " + bgName(curBg()), new Ico(20), true, this::backgroundDialog); }

  Opt accentOpt() {
    GradientDrawable swatch = new GradientDrawable(); // the accent row shows the color itself
    swatch.setShape(GradientDrawable.OVAL);
    swatch.setColor(accent());
    swatch.setStroke(dp(2), 0x99FFFFFF);
    return new Opt("Accent color: " + accentName(), swatch, true, this::accentDialog);
  }

  Opt sizeOpt() {
    int s = p.getInt("size", 1);
    return new Opt("Tile size: " + TILE_NAMES[s], new Ico(21), false, () -> p.edit().putInt("size", (s + 1) % 3).apply());
  }

  Opt layoutOpt() {
    boolean grid = p.getInt("layout", 0) == 1;
    return new Opt("Layout: " + (grid ? "Grid" : "Rows"), new Ico(3), false, () -> p.edit().putInt("layout", grid ? 0 : 1).apply());
  }

  Opt labelsOpt() {
    boolean on = p.getBoolean("labels", false);
    return new Opt("Labels: " + (on ? "Always" : "On focus"), new Ico(22), false, () -> p.edit().putBoolean("labels", !on).apply());
  }

  Opt soundOpt() {
    boolean on = p.getBoolean("sound", true);
    return new Opt("Navigation sound: " + (on ? "On" : "Off"), new Ico(24), false, () -> p.edit().putBoolean("sound", !on).apply());
  }

  void customize() { customize(0); }

  void customize(int focus) {
    String city = p.getString("wcity", "");
    int hid = hidden().size();
    boolean noamz = p.getBoolean("noamz", false), celsius = p.getBoolean("celsius", false);
    List<Opt> opts = Arrays.asList(
        bgOpt(), accentOpt(), sizeOpt(), layoutOpt(), labelsOpt(),
        new Opt("Amazon apps row: " + (noamz ? "Hidden" : "Shown"), new Ico(noamz ? 26 : 25), false, () -> p.edit().putBoolean("noamz", !noamz).apply()),
        new Opt("Weather location: " + (city.isEmpty() ? "Auto (IP)" : city), new WxIco(1), true, this::weatherLocation),
        new Opt("Temperature: " + (celsius ? "°C" : "°F"), new Ico(23), false, () -> {
          p.edit().putBoolean("celsius", !celsius).remove("w").apply();
          showWeather();
          refreshWeather(true);
        }),
        soundOpt(),
        new Opt("Weather alerts: " + (alerts().length() == 0 ? "None" : String.valueOf(alerts().length())),
            new Ico(30, alertColor() == 0 ? 0x66FFFFFF : alertColor()), true, () -> alertsMenu(0)),
        new Opt("Hidden apps: " + (hid == 0 ? "None" : String.valueOf(hid)), new Ico(26), true, () -> hiddenApps(0)),
        new Opt("Setup guide", new Ico(28), true, () -> guide(0)),
        new Opt("About", new Ico(5), true, this::about),
        new Opt("Reset settings", new Ico(29), true, this::resetMenu),
        new Opt("Done", new Ico(27), true, null));
    menu("Customize", null, opts, -1, focus, w -> {
      Opt o = opts.get(w);
      if (o.act == null) return;
      after = () -> customize(w);
      o.act.run();
      if (!o.ownMenu) { rebuild(); customize(w); }
    });
  }

  void about() {
    String ver = "?";
    try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (PackageManager.NameNotFoundException ignored) {}
    List<Opt> rows = plain(
        "Version: " + ver,
        "Device: " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE,
        helperVersion() == null ? "Helper service: Not installed" : "Helper version: " + helperVersion(),
        "Home button: " + homeButtonStatus(),
        "Apps: " + items().size() + " installed, " + hidden().size() + " hidden",
        "Weather: Open-Meteo, ipwho.is, NWS alerts",
        "‹ Back");
    menu("About Fast Home", "A fast, simple home screen for Fire TV.", rows, -1, rows.size() - 1, w -> after.run());
  }

  void resetMenu() {
    menu("Reset settings",
        "Appearance: background, accent, tile size, layout, labels, Amazon row and sound go back to their defaults.\n"
        + "Everything: also clears favorites, app order, hidden apps, input names and icons, and the weather location.",
        plain("Reset appearance", "Reset everything", "Cancel"), -1, 2, w -> {
      if (w == 2) { after.run(); return; }
      SharedPreferences.Editor e = p.edit();
      if (w == 0) for (String k : new String[] {"bg", "accent", "size", "layout", "labels", "noamz", "sound"}) e.remove(k);
      else e.clear().putBoolean("guided", true); // keep the guide from popping up again
      e.apply();
      initDefaults();
      paintAccent();
      applyBg();
      if (w == 1) { showWeather(); refreshWeather(true); }
      rebuild();
      Toast.makeText(this, w == 0 ? "Appearance reset" : "All settings reset", Toast.LENGTH_SHORT).show();
      after.run();
    });
  }

  // Hidden apps that are still installed, by name. The "hid" set keeps uninstalled packages, so never count it directly.
  List<ApplicationInfo> hidden() {
    PackageManager pm = getPackageManager();
    List<ApplicationInfo> apps = new ArrayList<>();
    for (String k : set("hid")) try { apps.add(pm.getApplicationInfo(k, 0)); } catch (PackageManager.NameNotFoundException gone) {}
    Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
    return apps;
  }

  void hiddenApps(int focus) {
    PackageManager pm = getPackageManager();
    List<ApplicationInfo> apps = hidden();
    int n = apps.size();
    List<Opt> rows = new ArrayList<>();
    for (ApplicationInfo a : apps) rows.add(new Opt(a.loadLabel(pm).toString(), a.loadIcon(pm), true, null));
    if (n > 1) rows.add(new Opt("Unhide all", new Ico(25), true, null));
    rows.add(new Opt("‹ Back", null, true, null));
    menu("Hidden apps", n == 0 ? "Nothing is hidden. To hide an app, hold Select on it and choose Hide." : "Select an app to put it back on the home screen.",
        rows, -1, Math.min(focus, rows.size() - 1), w -> {
      Set<String> hid = set("hid");
      if (w < n) hid.remove(apps.get(w).packageName);
      else if (n > 1 && w == n) hid.clear();
      else { after.run(); return; }
      p.edit().putStringSet("hid", hid).apply();
      rebuild();
      if (hidden().isEmpty()) after.run(); else hiddenApps(w);
    });
  }

  // Setup guide: one menu per topic, with shortcuts to the settings it describes.
  static final String[][] GUIDE = {
    {"Welcome to Fast Home", "Arrows move, Select opens.\nHold Select on a tile to see its options.\nThe Menu button (☰) opens Customize at any time.\n\nThis guide shows the basics. Press Back to close it."},
    {"Inputs", "Hold Select on an HDMI tile to rename it (for example \"PlayStation 5\") or pick an icon: console, cable box, computer and more.\nA greyed-out input has nothing plugged in or switched on."},
    {"Colors", "Background: \"App color\" follows the app you're on. You can also pick a fixed gradient or a photo.\nAccent: colors the Customize tile, and the background while you're on inputs or settings."},
    {"App order", "Hold Select on any app to:\n• Pin to Favorites: moves it to the top row\n• Move left / Move right: changes its place\n• Hide: removes it (Customize › Hidden apps brings it back)"},
    {"Layout & sound", "Rows: each section scrolls sideways. Grid: shows every app at once.\nTile size and labels change how much fits on screen.\nNavigation sound ticks as you move between tiles."},
    {"You're all set", "Press Menu (☰) to change any of this later. You can open this guide again from Customize."}};

  void guide(int step) { guide(step, -1); }

  // The Inputs step (1) only applies to devices with HDMI inputs: Fire TVs and the Cube, not Fire Sticks.
  int guideStep(int step, int dir) { return step == 1 && inputs().isEmpty() ? step + dir : step; }

  // focus: row to start on; -1 = Next
  void guide(int step, int focus) {
    List<Opt> opts = new ArrayList<>();
    if (step == 1) {
      List<Item> ins = inputs();
      if (!ins.isEmpty()) opts.add(new Opt("Set up an input…", new Ico(0), true, () -> pickInput(ins, after)));
    } else if (step == 2) opts.addAll(Arrays.asList(bgOpt(), accentOpt()));
    else if (step == 4) opts.addAll(Arrays.asList(layoutOpt(), sizeOpt(), labelsOpt(), soundOpt()));
    boolean last = step == GUIDE.length - 1;
    int next = opts.size();
    opts.add(new Opt(last ? "Done" : "Next ›", null, true, () -> { if (last) resetToTop(); else guide(guideStep(step + 1, 1)); }));
    if (step > 0) opts.add(new Opt("‹ Back", null, true, () -> guide(guideStep(step - 1, -1))));
    boolean noInputs = inputs().isEmpty();
    int num = step + (noInputs && step > 1 ? 0 : 1), total = GUIDE.length - (noInputs ? 1 : 0);
    android.text.SpannableString title = new android.text.SpannableString(GUIDE[step][0] + "    " + num + " of " + total);
    title.setSpan(new android.text.style.ForegroundColorSpan(0x66FFFFFF), GUIDE[step][0].length(), title.length(), 0);
    title.setSpan(new android.text.style.RelativeSizeSpan(0.65f), GUIDE[step][0].length(), title.length(), 0);
    Dialog d = menu(title, GUIDE[step][1], opts, -1, focus < 0 ? next : focus, w -> {
      Opt o = opts.get(w);
      after = () -> guide(step, w);
      o.act.run();
      if (!o.ownMenu) { rebuild(); guide(step, w); }
    });
    d.setOnCancelListener(x -> resetToTop()); // Back closes the guide
    afterLayout(() -> spotlight(d, guideTargets(step)));
  }

  // The tiles each step talks about: 1 inputs, 2 the Customize tile (it wears the accent), 3 the first app.
  List<View> guideTargets(int step) {
    List<View> out = new ArrayList<>();
    for (View t : tiles(content)) {
      String k = (String) t.getTag();
      boolean in = k.startsWith("in:"), st = k.startsWith("st:");
      if (step == 1 ? in : step == 2 ? k.equals("st:customize") : step == 3 && !in && !st && out.isEmpty()) out.add(t);
    }
    return out;
  }

  void afterLayout(Runnable r) {
    if (!root.isLayoutRequested()) { r.run(); return; }
    root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
      @Override public void onGlobalLayout() { root.getViewTreeObserver().removeOnGlobalLayoutListener(this); r.run(); }
    });
  }

  // One dim layer behind every menu, so switching menus never flickers; the guide cuts spotlight holes in it.
  View scrim;
  List<View> spotTargets = new ArrayList<>();
  Dialog spotOwner;
  ValueAnimator spotPulse;
  int openMenus;
  final Paint scrimDim = new Paint(), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

  void scrim(boolean on) {
    if (scrim == null) {
      scrimDim.setColor(0xA6000000);
      ring.setStyle(Paint.Style.STROKE);
      ring.setStrokeWidth(dp(3));
      ring.setColor(0xFFFFFFFF);
      scrim = new View(this) {
        @Override protected void onDraw(Canvas c) {
          Path hole = new Path();
          hole.setFillType(Path.FillType.EVEN_ODD);
          hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
          List<RectF> rs = new ArrayList<>();
          for (View t : spotTargets) { RectF f = cardRect(t); rs.add(f); hole.addRoundRect(f, dp(26), dp(26), Path.Direction.CW); }
          c.drawPath(hole, scrimDim);
          for (RectF f : rs) c.drawRoundRect(f, dp(26), dp(26), ring);
        }
      };
      scrim.setAlpha(0f);
      root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }
    scrim.animate().cancel();
    if (on) scrim.setVisibility(View.VISIBLE);
    scrim.animate().alpha(on ? 1f : 0f).setDuration(200).setInterpolator(EASE)
        .withEndAction(() -> { if (!on) scrim.setVisibility(View.GONE); }).start();
  }

  RectF cardRect(View tile) {
    View card = ((ViewGroup) tile).getChildAt(0);
    Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
    root.offsetDescendantRectToMyCoords(card, r);
    RectF f = new RectF(r);
    f.inset(-dp(12), -dp(12)); // room for the focus zoom
    return f;
  }

  void spotlight(Dialog d, List<View> targets) {
    clearSpot();
    if (targets.isEmpty() || !d.isShowing()) return;
    stopGlides();
    for (View t : targets) for (ViewParent pr = t.getParent(); pr != null; pr = pr.getParent())
      if (pr instanceof HorizontalScrollView) ((View) pr).scrollTo(0, 0);
    Rect r = new Rect(0, 0, 1, 1);
    sv.offsetDescendantRectToMyCoords(targets.get(0), r);
    sv.scrollTo(0, Math.max(0, r.top - dp(40)));
    RectF first = cardRect(targets.get(0));
    Window w = d.getWindow();
    WindowManager.LayoutParams a = w.getAttributes();
    if (targets.size() == 1 && first.right < root.getWidth() / 2f) { // a single tile on the left: menu beside it
      a.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
      a.x = dp(40);
      a.width = dp(520);
    } else a.gravity = first.centerY() > root.getHeight() / 2f ? Gravity.TOP : Gravity.BOTTOM; // keep clear of the row
    w.setAttributes(a);
    spotTargets.addAll(targets);
    spotOwner = d;
    spotPulse = ValueAnimator.ofInt(90, 255);
    spotPulse.setDuration(900);
    spotPulse.setRepeatMode(ValueAnimator.REVERSE);
    spotPulse.setRepeatCount(ValueAnimator.INFINITE);
    spotPulse.addUpdateListener(v -> { ring.setAlpha((int) v.getAnimatedValue()); scrim.invalidate(); });
    spotPulse.start();
  }

  void clearSpot() {
    if (spotPulse != null) spotPulse.cancel();
    spotPulse = null;
    spotTargets.clear();
    spotOwner = null;
    if (scrim != null) scrim.invalidate();
  }

  void pickInput(List<Item> ins, Runnable done) {
    if (ins.size() == 1) { inputMenu(ins.get(0), done); return; }
    String[] names = new String[ins.size()];
    for (int i = 0; i < names.length; i++) names[i] = ins.get(i).label;
    menu("Which input?", null, names, -1, w -> inputMenu(ins.get(w), done));
  }

  // Every list and text box uses this panel; the stock Fire OS dialog looked out of place.
  interface Pick { void on(int i); }
  interface Typed { void on(String s); }

  TextView label(CharSequence s, int sp, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    return t;
  }

  // Rounded dark card: title, optional body, then `list` (scrolls when it doesn't fit). Caller adds rows, then show()s it.
  Dialog sheet(CharSequence title, CharSequence body, LinearLayout list) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(28), dp(26), dp(28), dp(20));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xF513161E);
    bg.setCornerRadius(dp(24));
    bg.setStroke(dp(1), 0x1FFFFFFF);
    c.setBackground(bg);
    c.addView(label(title, 24, 0xFFFFFFFF));
    if (body != null) {
      TextView b = label(body, 16, 0xB3FFFFFF);
      b.setLineSpacing(0, 1.25f);
      b.setPadding(0, dp(10), 0, 0);
      c.addView(b);
    }
    ScrollView sc = new ScrollView(this);
    sc.setVerticalScrollBarEnabled(false);
    sc.setPadding(0, dp(16), 0, 0);
    sc.addView(list);
    c.addView(sc);
    FrameLayout outer = new FrameLayout(this); // keeps a tall menu off the screen edges; its list scrolls instead
    outer.setPadding(0, dp(28), 0, dp(28));
    outer.addView(c);
    Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
    d.setContentView(outer);
    Window w = d.getWindow();
    w.setBackgroundDrawable(new ColorDrawable(0));
    w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); // the shared scrim dims instead
    w.setWindowAnimations(0);
    w.setLayout(dp(600), -2);
    d.setOnDismissListener(x -> {
      openMenus--;
      if (spotOwner == d) clearSpot();
      root.post(() -> { if (openMenus == 0) scrim(false); }); // the next menu, if any, has opened by now
    });
    d.setOnKeyListener((x, k, e) -> {
      if (k == KeyEvent.KEYCODE_BACK) {
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) close(d, null);
        return true;
      }
      if (e.getAction() == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
        View cur = d.getCurrentFocus();
        if (cur != null && cur.focusSearch(k == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN) != null) tick(k); // silent at the ends
      }
      return false;
    });
    outer.setAlpha(0f);
    outer.setScaleX(0.94f);
    outer.setScaleY(0.94f);
    outer.setTranslationY(dp(16));
    outer.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(220).setInterpolator(EASE).start();
    return d;
  }

  void open(Dialog d) {
    openMenus++;
    scrim(true);
    d.show();
  }

  // Fade a menu out, then act (open the next menu, apply a change). then == null: Back was pressed.
  void close(Dialog d, Runnable then) {
    View v = d.findViewById(android.R.id.content);
    if (v == null || v.getTag() != null) return; // already closing
    v.setTag(true);
    v.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(120).setInterpolator(new AccelerateInterpolator()).withEndAction(() -> {
      if (then == null) { d.cancel(); return; }
      d.dismiss();
      then.run();
    }).start();
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  // One menu row; "Label: value" puts the value on the right. Highlighted in the accent color while focused.
  View row(LinearLayout list, String text, boolean check, Drawable icon, Runnable click) {
    LinearLayout r = new LinearLayout(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(dp(18), dp(12), dp(18), dp(12));
    r.setFocusable(true);
    r.setClickable(true);
    GradientDrawable on = new GradientDrawable();
    on.setColor(accent());
    on.setCornerRadius(dp(14));
    StateListDrawable sl = new StateListDrawable();
    sl.setEnterFadeDuration(140);
    sl.setExitFadeDuration(140);
    sl.addState(new int[] {android.R.attr.state_focused}, on);
    r.setBackground(sl);
    int cut = text.indexOf(": ");
    if (icon != null) {
      ImageView iv = new ImageView(this);
      iv.setImageDrawable(icon);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(26), dp(26));
      ip.rightMargin = dp(16);
      r.addView(iv, ip);
    }
    r.addView(label(cut < 0 ? text : text.substring(0, cut), 18, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -2, 1));
    String val = ((cut < 0 ? "" : text.substring(cut + 2)) + (check ? "  ✓" : "")).trim();
    if (!val.isEmpty()) r.addView(label(val, 16, 0xB3FFFFFF));
    r.setOnClickListener(v -> click.run());
    list.addView(r, -1, -2);
    return r;
  }

  Dialog menu(CharSequence title, CharSequence body, String[] rows, int checked, Pick pick) { return menu(title, body, plain(rows), checked, checked, pick); }

  // checked: row marked with a tick (-1 none). focus: row to start on.
  Dialog menu(CharSequence title, CharSequence body, List<Opt> rows, int checked, int focus, Pick pick) {
    LinearLayout list = column();
    Dialog d = sheet(title, body, list);
    View f = null;
    for (int i = 0; i < rows.size(); i++) {
      final int n = i;
      View r = row(list, rows.get(i).label, i == checked, rows.get(i).icon, () -> close(d, () -> pick.on(n)));
      if (i == 0 || i == focus) f = r;
    }
    open(d);
    if (f != null) f.requestFocus();
    return d;
  }

  void prompt(String title, String hint, String initial, Typed ok, Runnable cancel) {
    LinearLayout list = column();
    EditText et = new EditText(this);
    et.setSingleLine();
    et.setText(initial);
    et.setSelection(et.length());
    et.setHint(hint);
    et.setTextSize(18);
    et.setTextColor(0xFFFFFFFF);
    et.setHintTextColor(0x66FFFFFF);
    et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    GradientDrawable box = new GradientDrawable();
    box.setColor(0x14FFFFFF);
    box.setCornerRadius(dp(14));
    box.setStroke(dp(2), 0x33FFFFFF);
    et.setBackground(box);
    et.setPadding(dp(18), dp(14), dp(18), dp(14));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(10);
    list.addView(et, lp);
    Dialog d = sheet(title, null, list);
    Runnable save = () -> close(d, () -> ok.on(et.getText().toString().trim()));
    et.setOnEditorActionListener((v, a, e) -> { save.run(); return true; });
    row(list, "Save", false, null, save);
    row(list, "Cancel", false, null, () -> close(d, cancel));
    d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    open(d);
    et.requestFocus();
  }
}
