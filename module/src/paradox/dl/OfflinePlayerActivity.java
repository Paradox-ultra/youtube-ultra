package paradox.dl;
import android.app.*; import android.content.*; import android.graphics.*; import android.media.MediaPlayer; import android.net.Uri; import android.os.*; import android.view.*; import android.widget.*;
import java.util.*;
/** v10.13: plays a downloaded video from the Queue / Watch later page, fully offline, with previous / next and automatic advance.
 *  Monochrome, tap the video to show or hide the controls. If the next entry is not downloaded it opens in YouTube instead (needs network).
 *  v10.21: drawn icons (the Unicode media glyphs rendered as colour emoji on many phones), rotation keeps the video that is playing
 *  (it used to jump back to the first one), the play/pause icon follows the real state, and the controls hide themselves while playing. */
public class OfflinePlayerActivity extends Activity {
  static final int PLAY=0, PAUSE=1, PREV=2, NEXT=3, BACK10=4, FWD10=5;
  static final long HIDE_MS=3500;
  VideoView vv; TextView title, time; SeekBar seek; ImageButton play; View ui; String list, id, path; final Handler h=new Handler(Looper.getMainLooper()); boolean seeking; int resumeAt;
  int dp(float v){ return Ui.dp(this,v); }
  @Override protected void onCreate(Bundle b){ super.onCreate(b); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    list=getIntent().getStringExtra("list"); id=getIntent().getStringExtra("id"); path=getIntent().getStringExtra("path");
    if (b!=null) { resumeAt=b.getInt("pos",0); if (b.getString("id")!=null) id=b.getString("id"); if (b.getString("path")!=null) path=b.getString("path"); }
    FrameLayout root=new FrameLayout(this); root.setBackgroundColor(0xFF000000); vv=new VideoView(this); root.addView(vv,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
    ui=new FrameLayout(this); FrameLayout f=(FrameLayout)ui; root.addView(ui,new FrameLayout.LayoutParams(-1,-1));
    title=Ui.medium(this,"",15,0xFFFFFFFF); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setPadding(dp(16),dp(14),dp(16),dp(14)); title.setBackgroundColor(0x99000000);
    f.addView(title,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
    LinearLayout bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setBackgroundColor(0x99000000); bottom.setPadding(dp(12),dp(6),dp(12),dp(10));
    LinearLayout sr=new LinearLayout(this); sr.setGravity(Gravity.CENTER_VERTICAL); time=Ui.text(this,"00:00 / 00:00",12,0xFFFFFFFF,false); seek=new SeekBar(this);
    seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean u){ if (u) { vv.seekTo(p); time.setText(Ui.time(p)+" / "+Ui.time(vv.getDuration())); } }
      public void onStartTrackingTouch(SeekBar s){ seeking=true; h.removeCallbacks(autoHide); } public void onStopTrackingTouch(SeekBar s){ seeking=false; poke(); } });
    sr.addView(seek,new LinearLayout.LayoutParams(0,-2,1f)); sr.addView(time); bottom.addView(sr);
    LinearLayout br=new LinearLayout(this); br.setGravity(Gravity.CENTER);
    br.addView(btn(PREV,"Previous",v -> go(-1))); br.addView(btn(BACK10,"Back 10 seconds",v -> vv.seekTo(Math.max(0,vv.getCurrentPosition()-10000))));
    play=btn(PAUSE,"Pause",v -> { if (vv.isPlaying()) vv.pause(); else vv.start(); syncPlay(); }); br.addView(play);
    br.addView(btn(FWD10,"Forward 10 seconds",v -> vv.seekTo(Math.min(vv.getDuration(),vv.getCurrentPosition()+10000)))); br.addView(btn(NEXT,"Next",v -> go(+1))); bottom.addView(br);
    f.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
    vv.setOnClickListener(v -> toggle()); root.setOnClickListener(v -> toggle());
    vv.setOnCompletionListener(mp -> { if (!go(+1)) { Toast.makeText(this,"End of the list",Toast.LENGTH_SHORT).show(); finish(); } });
    vv.setOnErrorListener((mp,w,x) -> { Toast.makeText(this,"Can't play this file",Toast.LENGTH_SHORT).show(); if (!go(+1)) finish(); return true; });
    setContentView(root); hideBars(); load(path); tick.run(); }
  /** Media icons drawn on a 24-unit grid in white, like the rest of the module's icons. */
  static final class MediaIcon extends android.graphics.drawable.Drawable { int kind; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); final Path path=new Path();
    MediaIcon(int k){ kind=k; p.setColor(0xFFFFFFFF); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
    @Override public void draw(Canvas c){ Rect b=getBounds(); float s=Math.min(b.width(),b.height())/24f; c.save(); c.translate(b.left+(b.width()-24*s)/2f, b.top+(b.height()-24*s)/2f); c.scale(s,s); path.reset(); p.setStyle(Paint.Style.FILL);
      switch (kind){
        case PLAY: path.moveTo(8,5); path.lineTo(19,12); path.lineTo(8,19); path.close(); c.drawPath(path,p); break;
        case PAUSE: c.drawRoundRect(new RectF(6.5f,5,10.5f,19),1,1,p); c.drawRoundRect(new RectF(13.5f,5,17.5f,19),1,1,p); break;
        case PREV: c.drawRect(5.5f,6,7.5f,18,p); path.moveTo(18.5f,6); path.lineTo(9,12); path.lineTo(18.5f,18); path.close(); c.drawPath(path,p); break;
        case NEXT: c.drawRect(16.5f,6,18.5f,18,p); path.moveTo(5.5f,6); path.lineTo(15,12); path.lineTo(5.5f,18); path.close(); c.drawPath(path,p); break;
        case BACK10: case FWD10: { boolean back=kind==BACK10; p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); RectF o=new RectF(4,4,20,20);
          c.drawArc(o, back? -60: 240, back? -280: 280, false, p);                     // open circle with the gap at the top
          float ax= back? 9.2f: 14.8f; path.moveTo(ax+(back? 3.2f: -3.2f),2.2f); path.lineTo(ax,4.4f); path.lineTo(ax+(back? 3.2f: -3.2f),6.8f); c.drawPath(path,p);
          p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(7.2f); p.setFakeBoldText(true); c.drawText("10",12,14.6f,p); p.setFakeBoldText(false); break; } }
      c.restore(); }
    @Override public void setAlpha(int a){ p.setAlpha(a); } @Override public void setColorFilter(ColorFilter f){ p.setColorFilter(f); } @Override public int getOpacity(){ return PixelFormat.TRANSLUCENT; } }
  ImageButton btn(int kind, String label, View.OnClickListener l){ ImageButton b=new ImageButton(this); b.setImageDrawable(new MediaIcon(kind)); b.setScaleType(ImageView.ScaleType.FIT_CENTER); b.setPadding(dp(12),dp(10),dp(12),dp(10)); b.setContentDescription(label);
    android.graphics.drawable.GradientDrawable m=new android.graphics.drawable.GradientDrawable(); m.setShape(android.graphics.drawable.GradientDrawable.OVAL); m.setColor(0xFFFFFFFF);
    b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, m));
    b.setOnClickListener(v -> { l.onClick(v); poke(); }); b.setLayoutParams(new LinearLayout.LayoutParams(dp(56),dp(52))); return b; }
  void syncPlay(){ boolean on=false; try { on=vv.isPlaying(); } catch (Throwable t){} ((MediaIcon)play.getDrawable()).kind= on? PAUSE: PLAY; play.getDrawable().invalidateSelf(); play.setContentDescription(on? "Pause": "Play"); }
  final Runnable autoHide=new Runnable(){ public void run(){ try { if (vv.isPlaying() && !seeking) ui.setVisibility(View.GONE); } catch (Throwable t){} } };
  /** Controls were used: keep them on screen a little longer. */
  void poke(){ h.removeCallbacks(autoHide); h.postDelayed(autoHide, HIDE_MS); }
  void toggle(){ boolean show=ui.getVisibility()!=View.VISIBLE; ui.setVisibility(show? View.VISIBLE: View.GONE); if (show) poke(); }
  void hideBars(){ getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
  void load(String p){ path=p; try { QueueStore.Entry cur=null; for (QueueStore.Entry e: QueueRun.store(this).all(list)) if (e.id.equals(id)) cur=e;
      title.setText(cur==null||cur.title.isEmpty()? "Offline video": cur.title); final int at=resumeAt; resumeAt=0;
      Uri u=Open.uri(this,p); if (u==null) throw new java.io.FileNotFoundException(String.valueOf(p));
      vv.setVideoURI(u); vv.setOnPreparedListener(mp -> { if (at>0) mp.seekTo(at); vv.start(); syncPlay(); seek.setMax(Math.max(1,vv.getDuration())); ui.setVisibility(View.VISIBLE); poke(); });
    } catch (Throwable t){ Toast.makeText(this,"Can't open the file",Toast.LENGTH_SHORT).show(); } }
  /** Next (+1) or previous (-1) entry: a downloaded one plays here; one that is not downloaded opens in YouTube. */
  boolean go(int dir){ QueueStore s=QueueRun.store(this); int idx= dir>0? s.nextIndex(list,id): s.prevIndex(list,id); if (idx<0) return false;
    QueueStore.Entry e=s.all(list).get(idx); Store.Item d=QueueRun.downloaded(this,e.id); QueueRun.list=list;
    if (d!=null) { id=e.id; QueueRun.id=e.id; QueueRun.online=false; load(d.path); return true; }
    QueueRun.play(this,list,e); finish(); return true; }
  final Runnable tick=new Runnable(){ public void run(){ try { if (vv.getDuration()>0 && !seeking) { seek.setProgress(vv.getCurrentPosition()); time.setText(Ui.time(vv.getCurrentPosition())+" / "+Ui.time(vv.getDuration())); } } catch (Throwable t){} h.postDelayed(this,500); } };
  @Override protected void onSaveInstanceState(Bundle o){ super.onSaveInstanceState(o); try { o.putInt("pos",vv.getCurrentPosition()); o.putString("id",id); o.putString("path",path); } catch (Throwable t){} }
  @Override protected void onPause(){ super.onPause(); try { vv.pause(); syncPlay(); ui.setVisibility(View.VISIBLE); h.removeCallbacks(autoHide); } catch (Throwable t){} }
  @Override protected void onDestroy(){ super.onDestroy(); h.removeCallbacksAndMessages(null); }
  @Override public void onWindowFocusChanged(boolean f){ super.onWindowFocusChanged(f); if (f) hideBars(); }
}
