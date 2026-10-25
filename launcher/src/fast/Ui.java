package fast.home;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

// Drawing shared by Fast Home and its Home-button helper (a separate APK), so the helper's cover matches the home screen.
final class Ui {
  static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density); }

  static int[] grad(int top) {
    return new int[] {top, Color.rgb(Color.red(top) * 3 / 10, Color.green(top) * 3 / 10, Color.blue(top) * 3 / 10), 0xFF000000};
  }

  // Big clock, date and weather. The Home-button helper's cover draws the same header, so it stays put when Fast Home appears.
  static LinearLayout header(Context c, TextClock date, TextView weather, TextView alert) {
    LinearLayout header = new LinearLayout(c);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setPadding(dp(c, 78), dp(c, 18), dp(c, 64), dp(c, 2));
    TextClock big = new TextClock(c);
    big.setFormat12Hour("h:mm a");
    big.setFormat24Hour("H:mm");
    big.setTextColor(0xFFFFFFFF);
    big.setTextSize(52);
    big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    header.addView(big);
    LinearLayout sub = new LinearLayout(c); // default gravity lines the items up by text baseline; the weather icon makes that text taller
    date.setFormat12Hour("EEEE, MMMM d");
    date.setFormat24Hour("EEEE, MMMM d");
    sub.addView(date);
    sub.addView(weather);
    sub.addView(alert);
    for (TextView t : new TextView[] {date, weather, alert}) {
      t.setTextColor(new android.content.res.ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {0xFFFFFFFF, 0xAAFFFFFF}));
      t.setTextSize(18);
      t.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.leftMargin = -dp(c, 12); // the date's highlight padding shouldn't push it out of line with the clock
    header.addView(sub, lp);
    header.setClipToPadding(false); // ...and its highlight sits in the header's padding, so don't clip it
    return header;
  }

  static CharSequence withIcon(Context c, String pre, int kind, String post, int sizeDp) {
    SpannableStringBuilder b = new SpannableStringBuilder(pre + "  " + post);
    Drawable d = new WxIco(kind);
    d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
    b.setSpan(new IconSpan(d), pre.length(), pre.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return b;
  }

  // `s` followed by a warning triangle in `color`; unchanged when color is 0 (no alerts).
  static CharSequence alertMark(Context c, CharSequence s, int color, int sizeDp) {
    if (color == 0) return s;
    SpannableStringBuilder b = new SpannableStringBuilder(s).append(s.length() > 0 ? "   " : " ");
    Drawable d = new Ico(30, color);
    d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
    b.setSpan(new IconSpan(d), b.length() - 1, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return b;
  }

  // Icon centered on the text that leaves the line height alone (ImageSpan grows it), so header highlights match.
  static class IconSpan extends android.text.style.ReplacementSpan {
    final Drawable d;
    IconSpan(Drawable d) { this.d = d; }
    @Override public int getSize(Paint p, CharSequence t, int start, int end, Paint.FontMetricsInt fm) {
      if (fm != null) p.getFontMetricsInt(fm); // plain text height, even when the icon is the whole line
      return d.getBounds().right;
    }
    @Override public void draw(Canvas c, CharSequence t, int start, int end, float x, int top, int y, int bottom, Paint p) {
      Paint.FontMetricsInt fm = p.getFontMetricsInt();
      c.save();
      c.translate(x, y + (fm.ascent + fm.descent - d.getBounds().bottom) / 2f);
      d.draw(c);
      c.restore();
    }
  }

  // simple line icons drawn in code: 0 HDMI, 1 wifi, 2 display, 3 apps, 4 bluetooth, 5 info, 6 sliders, 7 accessibility, 8 account, 9 gear,
  // 10-19 input devices (see DEV_NAMES), 20 picture, 21 resize, 22 text, 23 thermometer, 24 speaker, 25 eye, 26 eye crossed out, 27 tick,
  // 28 question mark, 29 reset arrow, 30 warning triangle (filled)
  static class Ico extends Drawable {
    final int t, color;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    Ico(int t) { this(t, 0xFFFFFFFF); }
    Ico(int t, int color) { this.t = t; this.color = color; }
    @Override public void draw(Canvas c) {
      Rect b = getBounds();
      c.save();
      c.translate(b.left, b.top);
      c.scale(b.width() / 24f, b.height() / 24f);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      Path q = new Path();
      switch (t) {
        case 0:
          q.moveTo(3, 7); q.lineTo(21, 7); q.lineTo(21, 13); q.lineTo(17.5f, 17.5f); q.lineTo(6.5f, 17.5f); q.lineTo(3, 13); q.close();
          c.drawPath(q, p);
          for (int i = 0; i < 4; i++) c.drawLine(8 + i * 2.7f, 10, 8 + i * 2.7f, 13, p);
          break;
        case 1:
          for (int r : new int[] {4, 8, 12}) c.drawArc(new RectF(12 - r, 19 - r, 12 + r, 19 + r), 225, 90, false, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(12, 19, 1.6f, p);
          break;
        case 2:
          c.drawRoundRect(new RectF(3, 5, 21, 16), 1.5f, 1.5f, p);
          c.drawLine(12, 16, 12, 20, p);
          c.drawLine(8, 20, 16, 20, p);
          break;
        case 3:
          for (float[] r : new float[][] {{4, 4}, {14, 4}, {4, 14}, {14, 14}}) c.drawRoundRect(new RectF(r[0], r[1], r[0] + 6, r[1] + 6), 1.5f, 1.5f, p);
          break;
        case 4:
          q.moveTo(7, 8); q.lineTo(17, 16); q.lineTo(12, 21); q.lineTo(12, 3); q.lineTo(17, 8); q.lineTo(7, 16);
          c.drawPath(q, p);
          break;
        case 5:
          c.drawCircle(12, 12, 9, p);
          c.drawLine(12, 11, 12, 17, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(12, 7.6f, 1.2f, p);
          break;
        case 6:
          for (float[] r : new float[][] {{6, 8}, {12, 16}, {18, 10}}) {
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(4, r[0], 20, r[0], p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(r[1], r[0], 2.4f, p);
          }
          break;
        case 7:
          c.drawCircle(12, 5, 2.2f, p);
          c.drawLine(5, 9, 19, 9, p);
          c.drawLine(12, 9, 12, 14, p);
          c.drawLine(12, 14, 8.5f, 20, p);
          c.drawLine(12, 14, 15.5f, 20, p);
          break;
        case 8:
          c.drawCircle(12, 8, 4, p);
          c.drawArc(new RectF(4, 15, 20, 31), 195, 150, false, p);
          break;
        case 10: // PlayStation: triangle, circle, cross, square
          q.moveTo(12, 2.5f); q.lineTo(15, 7.5f); q.lineTo(9, 7.5f); q.close();
          c.drawPath(q, p);
          c.drawCircle(19, 12, 2.8f, p);
          c.drawLine(9.5f, 16, 14.5f, 21, p);
          c.drawLine(14.5f, 16, 9.5f, 21, p);
          c.drawRect(2.5f, 9.5f, 7.5f, 14.5f, p);
          break;
        case 11: // Xbox
          c.drawCircle(12, 12, 9, p);
          c.drawLine(8.5f, 8.5f, 15.5f, 15.5f, p);
          c.drawLine(15.5f, 8.5f, 8.5f, 15.5f, p);
          break;
        case 12: // Switch
          c.drawRoundRect(new RectF(3, 4, 10, 20), 2.5f, 2.5f, p);
          c.drawRoundRect(new RectF(14, 4, 21, 20), 2.5f, 2.5f, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(6.5f, 8.5f, 1.4f, p);
          c.drawCircle(17.5f, 15.5f, 1.4f, p);
          break;
        case 13: // game controller
          c.drawRoundRect(new RectF(2.5f, 7.5f, 21.5f, 17.5f), 5, 5, p);
          c.drawLine(7, 10.5f, 7, 14.5f, p);
          c.drawLine(5, 12.5f, 9, 12.5f, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(16, 11.5f, 1, p);
          c.drawCircle(18.5f, 13.5f, 1, p);
          break;
        case 14: // streaming box
          c.drawRoundRect(new RectF(3, 9, 21, 16), 2, 2, p);
          c.drawLine(11, 12.5f, 17, 12.5f, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(7, 12.5f, 1, p);
          break;
        case 15: // computer
          c.drawRoundRect(new RectF(5, 5.5f, 19, 15), 1.5f, 1.5f, p);
          c.drawLine(2.5f, 18.5f, 21.5f, 18.5f, p);
          break;
        case 16: // cable / satellite box
          c.drawRoundRect(new RectF(2.5f, 8, 21.5f, 16), 1.5f, 1.5f, p);
          c.drawLine(5.5f, 12, 11, 12, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(18, 12, 1.1f, p);
          break;
        case 17: // disc player
          c.drawCircle(12, 12, 9, p);
          c.drawCircle(12, 12, 2.5f, p);
          break;
        case 18: // soundbar / receiver
          c.drawRoundRect(new RectF(2, 8.5f, 22, 15.5f), 3, 3, p);
          c.drawCircle(6.5f, 12, 1.6f, p);
          c.drawCircle(17.5f, 12, 1.6f, p);
          c.drawLine(10, 12, 14, 12, p);
          break;
        case 19: // camera
          c.drawRoundRect(new RectF(3, 8, 21, 19), 2.5f, 2.5f, p);
          c.drawCircle(12, 13.5f, 3.5f, p);
          q.moveTo(8, 8); q.lineTo(9.5f, 5.5f); q.lineTo(14.5f, 5.5f); q.lineTo(16, 8);
          c.drawPath(q, p);
          break;
        case 20: // picture
          c.drawRoundRect(new RectF(3, 5, 21, 19), 2, 2, p);
          q.moveTo(3.5f, 17); q.lineTo(9, 11.5f); q.lineTo(13, 15.5f); q.lineTo(16, 12.5f); q.lineTo(20.5f, 17);
          c.drawPath(q, p);
          c.drawCircle(15.5f, 9, 1.5f, p);
          break;
        case 21: // resize
          c.drawRoundRect(new RectF(3, 13, 11, 21), 1.5f, 1.5f, p);
          c.drawLine(13, 11, 20.5f, 3.5f, p);
          c.drawLine(20.5f, 3.5f, 15, 3.5f, p);
          c.drawLine(20.5f, 3.5f, 20.5f, 9, p);
          break;
        case 22: // text
          c.drawLine(5, 5, 19, 5, p);
          c.drawLine(12, 5, 12, 19.5f, p);
          c.drawLine(9, 19.5f, 15, 19.5f, p);
          break;
        case 23: // thermometer
          c.drawRoundRect(new RectF(9.8f, 3, 14.2f, 15), 2.2f, 2.2f, p);
          c.drawCircle(12, 18, 3.2f, p);
          c.drawLine(12, 8, 12, 15.5f, p);
          break;
        case 24: // speaker
          q.moveTo(3.5f, 9.5f); q.lineTo(7.5f, 9.5f); q.lineTo(12.5f, 5); q.lineTo(12.5f, 19); q.lineTo(7.5f, 14.5f); q.lineTo(3.5f, 14.5f); q.close();
          c.drawPath(q, p);
          c.drawArc(new RectF(11, 8.5f, 18, 15.5f), -50, 100, false, p);
          c.drawArc(new RectF(10, 5, 22, 19), -50, 100, false, p);
          break;
        case 25: case 26: // eye; 26 crossed out
          q.moveTo(2.5f, 12); q.quadTo(12, 2.5f, 21.5f, 12); q.quadTo(12, 21.5f, 2.5f, 12); q.close();
          c.drawPath(q, p);
          c.drawCircle(12, 12, 3, p);
          if (t == 26) c.drawLine(4, 4, 20, 20, p);
          break;
        case 27: // tick
          q.moveTo(5, 12.5f); q.lineTo(10, 17.5f); q.lineTo(19.5f, 7);
          c.drawPath(q, p);
          break;
        case 28: // question mark
          c.drawCircle(12, 12, 9, p);
          q.moveTo(9.4f, 9.6f); q.cubicTo(9.4f, 6.6f, 14.6f, 6.6f, 14.6f, 9.6f); q.cubicTo(14.6f, 11.6f, 12, 11.8f, 12, 14);
          c.drawPath(q, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(12, 17.2f, 1.1f, p);
          break;
        case 29: // reset arrow
          c.drawArc(new RectF(4, 4, 20, 20), -90, 270, false, p);
          q.moveTo(1.5f, 14); q.lineTo(4, 11); q.lineTo(6.5f, 14);
          c.drawPath(q, p);
          break;
        case 30:
          q.moveTo(12, 3.5f); q.lineTo(21.5f, 20); q.lineTo(2.5f, 20); q.close();
          p.setStyle(Paint.Style.FILL_AND_STROKE); // the stroke rounds the corners
          c.drawPath(q, p);
          p.setColor(0xFF15171C);
          p.setStyle(Paint.Style.STROKE);
          c.drawLine(12, 9.5f, 12, 14, p);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(12, 16.8f, 1.15f, p);
          break;
        default:
          c.drawCircle(12, 12, 3, p);
          c.drawCircle(12, 12, 6.5f, p);
          for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            c.drawLine(12 + (float) Math.cos(a) * 6.5f, 12 + (float) Math.sin(a) * 6.5f, 12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f, p);
          }
      }
      c.restore();
    }
    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter f) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }

  // coloured weather icons: 0 sun, 1 partly cloudy, 2 cloud, 3 fog, 4 rain, 5 snow, 6 thunderstorm, 7 windy, 8 clear night
  static class WxIco extends Drawable {
    final int t;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    WxIco(int t) { this.t = t; }
    void cloud(Canvas c, float dx, float dy, float sc, int color) {
      c.save();
      c.translate(dx, dy);
      c.scale(sc, sc);
      p.setStyle(Paint.Style.FILL);
      p.setColor(color);
      c.drawCircle(8, 14, 4, p);
      c.drawCircle(13, 11, 5, p);
      c.drawCircle(17.5f, 14, 3.5f, p);
      c.drawRoundRect(new RectF(4, 13, 21, 18), 2.5f, 2.5f, p);
      c.restore();
    }
    void line(Canvas c, float x1, float y1, float x2, float y2, int color) {
      p.setStyle(Paint.Style.STROKE);
      p.setColor(color);
      c.drawLine(x1, y1, x2, y2, p);
    }
    void sun(Canvas c, float cx, float cy, float r, float rayFrom, float rayTo) {
      p.setStyle(Paint.Style.FILL);
      p.setColor(0xFFFFC83D);
      c.drawCircle(cx, cy, r, p);
      for (int i = 0; i < 8; i++) {
        double a = Math.PI / 4 * i;
        line(c, cx + (float) Math.cos(a) * rayFrom, cy + (float) Math.sin(a) * rayFrom, cx + (float) Math.cos(a) * rayTo, cy + (float) Math.sin(a) * rayTo, 0xFFFFC83D);
      }
    }
    @Override public void draw(Canvas c) {
      Rect b = getBounds();
      c.save();
      c.translate(b.left, b.top);
      c.scale(b.width() / 24f, b.height() / 24f);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      switch (t) {
        case 0: sun(c, 12, 12, 4.6f, 7.2f, 9.8f); break;
        case 1: sun(c, 8.5f, 8.5f, 3.4f, 5.4f, 7.4f); cloud(c, 2, 4, 0.85f, 0xFFF1F4FA); break;
        case 2: cloud(c, 0, 1.5f, 1f, 0xFFE8ECF4); break;
        case 3:
          cloud(c, 0.5f, -2, 0.85f, 0xFFC9D0DC);
          line(c, 5, 17, 19, 17, 0xFFC9D0DC);
          line(c, 7, 20.5f, 17, 20.5f, 0xFFC9D0DC);
          break;
        case 4:
          cloud(c, 0, -2.5f, 0.9f, 0xFFB8C4D8);
          for (float x : new float[] {8, 12.5f, 17}) line(c, x, 16, x - 1.5f, 20.5f, 0xFF63B3FF);
          break;
        case 5:
          cloud(c, 0, -2.5f, 0.9f, 0xFFB8C4D8);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xFFFFFFFF);
          c.drawCircle(8, 18.5f, 1.3f, p);
          c.drawCircle(12.5f, 21, 1.3f, p);
          c.drawCircle(17, 18.5f, 1.3f, p);
          break;
        case 6: {
          cloud(c, 0, -3, 0.9f, 0xFF8E9AB0);
          Path q = new Path();
          q.moveTo(13.5f, 12.5f); q.lineTo(9, 19); q.lineTo(12.5f, 19); q.lineTo(10.5f, 23.5f); q.lineTo(16, 16.5f); q.lineTo(12.5f, 16.5f); q.lineTo(14.5f, 12.5f); q.close();
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xFFFFD43D);
          c.drawPath(q, p);
          break;
        }
        case 7: {
          p.setStyle(Paint.Style.STROKE);
          p.setColor(0xFFBFD3EA);
          Path q = new Path();
          q.moveTo(3, 9.5f); q.lineTo(14, 9.5f); q.cubicTo(18, 9.5f, 18, 4.5f, 14.5f, 4.5f);
          q.moveTo(3, 14); q.lineTo(19, 14); q.cubicTo(23, 14, 23, 19.5f, 19, 19.5f);
          q.moveTo(3, 18.5f); q.lineTo(10, 18.5f);
          c.drawPath(q, p);
          break;
        }
        default: {
          Path moon = new Path(), cut = new Path();
          moon.addCircle(12, 12, 8, Path.Direction.CW);
          cut.addCircle(16.5f, 8.5f, 7.5f, Path.Direction.CW);
          moon.op(cut, Path.Op.DIFFERENCE);
          p.setStyle(Paint.Style.FILL);
          p.setColor(0xFFF3E7B0);
          c.drawPath(moon, p);
        }
      }
      c.restore();
    }
    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter f) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }
}
