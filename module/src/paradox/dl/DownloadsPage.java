package paradox.dl;
import android.app.*; import android.content.*; import android.os.*; import android.view.*; import android.widget.*; import android.net.Uri; import android.graphics.*;
import java.io.File; import java.util.*;
/** Our downloads list as a self-refreshing view. Hosted by DownloadsActivity and, embedded, inside YouTube's own You -> Downloads page. */
public class DownloadsPage extends FrameLayout implements Store.Listener {
  Ui.Pal P; int TXT, SUB; final boolean embedded;
  final ScrollView sv; final LinearLayout list; final Handler h=new Handler(Looper.getMainLooper()); boolean pending, live; final Map<String,Bitmap> thumbs=new HashMap<>();
  public DownloadsPage(Context c, boolean embedded){ super(c); this.embedded=embedded; P=Ui.pal(c); TXT=P.text; SUB=P.sub;
    setBackgroundColor(P.bg);
    sv=new ScrollView(c); sv.setClipToPadding(false); sv.setFillViewport(true); list=new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0,Ui.dp(c,4),0,Ui.dp(c,16)); sv.addView(list, new FrameLayout.LayoutParams(-1,-2));
    if (embedded) list.setPadding(0,Ui.dp(c,4),0,Ui.dp(c,88));
    addView(sv,new FrameLayout.LayoutParams(-1,-1)); }
  /** Bottom/side padding for the activity version (edge-to-edge); YouTube pads the embedded one itself. */
  public void padInsets(){ Ui.onInsets(sv,(t,bt,l,r) -> sv.setPadding(l,0,r,bt)); }
  @Override protected void onAttachedToWindow(){ super.onAttachedToWindow(); start(); }
  @Override protected void onDetachedFromWindow(){ stop(); super.onDetachedFromWindow(); }
  @Override protected void onWindowVisibilityChanged(int v){ super.onWindowVisibilityChanged(v); if (v==VISIBLE && isAttachedToWindow()) { start(); render(); } }
  void start(){ if (live) return; live=true; Store.listen(this); render();
    final Context ac=getContext().getApplicationContext(); new Thread(new Runnable(){ public void run(){ if (Store.adoptSaved(ac)) Store.notifyChanged(); } }, "paradox-adopt").start(); }   // v10.12: files saved in Movies/Music/YouTube Ultra always show up here
  void stop(){ if (!live) return; live=false; Store.unlisten(this); }
  @Override public void changed(){ if (pending) return; pending=true; h.postDelayed(() -> { pending=false; if (live) render(); }, 400); }
  Bitmap thumb(Store.Item it){ if (it.thumb==null || it.thumb.isEmpty()) return null; Bitmap b=thumbs.get(it.thumb); if (b==null && new File(it.thumb).exists()){ BitmapFactory.Options o=new BitmapFactory.Options(); o.inSampleSize=2; b=BitmapFactory.decodeFile(it.thumb,o); if (b!=null) thumbs.put(it.thumb,b);} return b; }
  static boolean active(Store.Item it){ return !it.status.equals("done"); }
  TextView header(String s){ Context c=getContext(); TextView t=Ui.medium(c,s,16,TXT); t.setPadding(Ui.dp(c,16),Ui.dp(c,12),Ui.dp(c,16),Ui.dp(c,6)); return t; }
  /** v10.13: the two lists (queue, offline Watch later) one tap from Downloads. */
  View shortcuts(final Context c){ LinearLayout r=new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(Ui.dp(c,16),Ui.dp(c,8),Ui.dp(c,16),Ui.dp(c,4)); QueueStore qs=QueueRun.store(c);
    for (int k=0;k<2;k++) { final boolean later=k==1; int n=qs.size(later? QueueStore.LATER: QueueStore.QUEUE); TextView t=Ui.medium(c,(later? "Watch later": "Queue")+(n>0? "  "+n: ""),14,TXT); t.setGravity(Gravity.CENTER); t.setBackground(Ui.round(P.chip, Ui.dp(c,18), 0)); t.setPadding(Ui.dp(c,16),0,Ui.dp(c,16),0);
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,Ui.dp(c,36)); if (k==1) lp.leftMargin=Ui.dp(c,8); r.addView(t,lp);
      t.setOnClickListener(v -> { Intent i=new Intent(c, QueueActivity.class).putExtra("list", later? QueueStore.LATER: QueueStore.QUEUE); if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i); }); }
    return r; }
  public void render(){ final Context c=getContext(); Ui.Pal np=Ui.pal(c); if (np.dark!=P.dark){ P=np; TXT=P.text; SUB=P.sub; setBackgroundColor(P.bg); thumbs.clear(); } list.removeAllViews(); list.addView(shortcuts(c)); List<Store.Item> all=Store.all(c);
    if (all.isEmpty()){ LinearLayout e=new LinearLayout(c); e.setOrientation(LinearLayout.VERTICAL); e.setGravity(Gravity.CENTER_HORIZONTAL); e.setPadding(Ui.dp(c,24),Ui.dp(c,96),Ui.dp(c,24),0);
      FrameLayout ib=new FrameLayout(c); ib.setBackground(Ui.round(P.chip, Ui.dp(c,28), 0)); ImageView i=new ImageView(c); i.setImageDrawable(new Ui.Icon(Ui.I_DOWNLOAD,SUB)); ib.addView(i,new FrameLayout.LayoutParams(Ui.dp(c,40),Ui.dp(c,40),Gravity.CENTER)); e.addView(ib,new LinearLayout.LayoutParams(Ui.dp(c,88),Ui.dp(c,88)));
      TextView m=Ui.medium(c,"No downloads yet",20,TXT); m.setGravity(Gravity.CENTER); m.setPadding(0,Ui.dp(c,20),0,Ui.dp(c,6)); e.addView(m);
      TextView s=Ui.text(c,"Tap Download on any video. It shows up here and in your gallery.",14,SUB,false); s.setGravity(Gravity.CENTER); e.addView(s); list.addView(e); return; }
    List<Store.Item> act=new ArrayList<>(), done=new ArrayList<>(); for (Store.Item it: all) (active(it)? act: done).add(it);
    if (!act.isEmpty()){ int running=0; for (Store.Item it: act) if (!(it.status.equals("paused")||it.status.equals("cancelled")||it.status.equals("failed"))) running++; list.addView(header((running>0? "Downloading · ": "In progress · ")+act.size())); for (Store.Item it: act) list.addView(row(it)); }
    if (!done.isEmpty()){ if (!act.isEmpty()) list.addView(header("Downloaded · "+done.size())); for (Store.Item it: done) list.addView(row(it)); }
    if (!done.isEmpty()){ TextView f=Ui.text(c,"Also saved in your gallery: Movies/YouTube Ultra (video), Music/YouTube Ultra (audio)",12,SUB,false); f.setGravity(Gravity.CENTER); f.setPadding(Ui.dp(c,24),Ui.dp(c,16),Ui.dp(c,24),Ui.dp(c,8)); list.addView(f); } }
  View row(final Store.Item it){ final Context c=getContext();
      LinearLayout row=new LinearLayout(c); row.setPadding(Ui.dp(c,16),Ui.dp(c,8),Ui.dp(c,4),Ui.dp(c,8)); Ui.setRow(row,P); row.setStateListAnimator(Ui.pressAnim(0.98f));
      FrameLayout tf=new FrameLayout(c); ImageView iv=new ImageView(c); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(P.chip);
      Bitmap bm=thumb(it); if (bm!=null) iv.setImageBitmap(bm); tf.addView(iv,new FrameLayout.LayoutParams(-1,-1));
      String badge= it.audio? "♪ audio": it.label; if (badge!=null && !badge.isEmpty()){ TextView bd=Ui.text(c,badge,11,0xFFFFFFFF,false); bd.setBackground(Ui.round(0xCC000000, Ui.dp(c,4),0)); bd.setPadding(Ui.dp(c,4),Ui.dp(c,1),Ui.dp(c,4),Ui.dp(c,1));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END); lp.setMargins(0,0,Ui.dp(c,4),Ui.dp(c, it.status.equals("done")? 4: 16)); tf.addView(bd,lp); }
      if (!it.status.equals("done")){ Ui.SheenBar pb=new Ui.SheenBar(c, 0xFFFFFFFF, 0x66000000); pb.setProgress(it.progress, it.status.equals("downloading")||it.status.equals("merging")||it.status.equals("converting")||it.status.equals("resolving")||it.status.equals("queued"));
        FrameLayout.LayoutParams pl=new FrameLayout.LayoutParams(-1,Ui.dp(c,4),Gravity.BOTTOM); pl.setMargins(Ui.dp(c,6),0,Ui.dp(c,6),Ui.dp(c,6)); tf.addView(pb,pl); }
      row.addView(tf,new LinearLayout.LayoutParams(Ui.dp(c,160),Ui.dp(c,90)));
      LinearLayout col=new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(Ui.dp(c,12),0,0,0);
      TextView ti=Ui.text(c,it.title,15,TXT,false); ti.setMaxLines(2); ti.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(ti);
      String st;
      switch (it.status){ case "downloading": st="Downloading · "+it.progress+"%\n"+Ui.size(it.done)+" / "+Ui.size(it.total)+" · "+String.format(Locale.US,"%.1f MB/s", it.speed/1048576); break;
        case "done": st=(it.channel==null||it.channel.isEmpty()? "": it.channel+" · ")+Ui.size(it.total); break;
        case "failed": st= reason(it)+" · tap to retry"; break;
        case "converting": st="Converting to MP3 · "+it.progress+"%"; break; case "merging": st= it.audio? "Saving MP3…": (it.progress>0 && it.progress<100? "Saving MP4 · "+it.progress+"%": "Saving MP4…"); break; case "resolving": st="Preparing…"; break;
        case "paused": case "cancelled": st="Paused · "+it.progress+"% · tap to resume"; break; default: st="Waiting…"; }
      TextView s=Ui.text(c,st,13,it.status.equals("failed")? (P.dark? 0xFFFF8A80: 0xFFC62828): SUB,false); s.setPadding(0,Ui.dp(c,4),0,0); s.setMaxLines(3); s.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(s);   // the light-theme red was unreadable on white
      row.addView(col,new LinearLayout.LayoutParams(0,-2,1f));
      FrameLayout menu=new FrameLayout(c); android.graphics.drawable.GradientDrawable mm=new android.graphics.drawable.GradientDrawable(); mm.setShape(android.graphics.drawable.GradientDrawable.OVAL); mm.setColor(0xFFFFFFFF);
      menu.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(P.ripple), null, mm)); menu.setClickable(true); menu.setContentDescription("Action menu"); ImageView mic=new ImageView(c); mic.setImageDrawable(new Ui.Icon(Ui.I_MORE,TXT)); menu.addView(mic,new FrameLayout.LayoutParams(Ui.dp(c,20),Ui.dp(c,20),Gravity.CENTER));
      LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(Ui.dp(c,40),Ui.dp(c,40)); mlp.gravity=Gravity.TOP; row.addView(menu,mlp);
      row.setOnClickListener(v -> { try { if (it.status.equals("done")) Open.play(c, it, false);
        else if (it.status.equals("failed")||it.status.equals("paused")||it.status.equals("cancelled")) DownloadService.start(c, it.id); } catch (Throwable t){ android.util.Log.e("ParadoxDL","open",t); } });
      menu.setOnClickListener(v -> { PopupMenu pm=new PopupMenu(c, v); boolean done=it.status.equals("done"); boolean stopped=stopped(it);
        if (done){ pm.getMenu().add(0,4,0,"Open with…"); pm.getMenu().add(0,3,0,"Share"); pm.getMenu().add(0,1,0,"Delete from downloads"); }
        else { if (stopped) pm.getMenu().add(0,6,0, it.status.equals("failed")? "Retry": "Resume"); else if (!it.status.equals("merging") && !it.status.equals("converting")) pm.getMenu().add(0,5,0,"Pause");   // saving/converting is short and can't resume midway
          if (it.status.equals("failed") && it.error!=null && !it.error.isEmpty()) pm.getMenu().add(0,7,0,"Why it failed");
          pm.getMenu().add(0,2,0,"Cancel download"); }
        pm.setOnMenuItemClickListener(mi -> { try { switch (mi.getItemId()){
            case 1: confirmDelete(c, it); return true;
            case 2: DownloadService.cancel(c,it.id); Store.remove(c,it); dropThumb(it); break;
            case 4: Open.play(c, it, true); break;
            case 5: DownloadService.cancel(c,it.id); break;            // the engine stops, keeps its parts and the item shows "Paused · tap to resume"
            case 6: DownloadService.start(c, it.id); break;
            case 7: dialog(c).setTitle("Download failed").setMessage(it.error).setPositiveButton("Retry",(d,w) -> DownloadService.start(c,it.id)).setNegativeButton("Close",null).show(); return true;
            default: Open.share(c, it); } } catch (Throwable t){ android.util.Log.e("ParadoxDL","menu",t); }
          render(); return true; }); pm.show(); });
      return row; }
  static boolean stopped(Store.Item it){ return it.status.equals("paused")||it.status.equals("cancelled")||it.status.equals("failed"); }
  /** First line of the saved error (was a bare "Download failed" for everything except MP3/MP4 errors). */
  static String reason(Store.Item it){ String e= it.error==null? "": it.error.trim(); if (e.isEmpty()) return "Download failed"; int nl=e.indexOf('\n'); if (nl>0) e=e.substring(0,nl).trim(); if (e.length()>90) e=e.substring(0,87)+"…"; return e; }
  android.app.AlertDialog.Builder dialog(Context c){ return new android.app.AlertDialog.Builder(c, P.dark? android.R.style.Theme_DeviceDefault_Dialog_Alert: android.R.style.Theme_DeviceDefault_Light_Dialog_Alert); }
  void dropThumb(Store.Item it){ try { if (it.thumb!=null && !it.thumb.isEmpty()) { thumbs.remove(it.thumb); new File(it.thumb).delete(); } } catch (Throwable t){} }
  /** Deleting a finished download removes the file from the phone, so ask first (it used to happen on one tap), and say so when Android kept the file. */
  void confirmDelete(final Context c, final Store.Item it){
    dialog(c).setTitle("Delete download?").setMessage("“"+it.title+"” will be deleted from your phone and from "+(it.audio? "Music": "your gallery")+".")
      .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d,w) -> { boolean gone=false;
        try { if (it.path==null || it.path.isEmpty()) gone=true; else if (it.path.startsWith("content:")) gone= c.getContentResolver().delete(Uri.parse(it.path),null,null)>0 || !Open.exists(c, Uri.parse(it.path)); else { File f=new File(it.path); gone= f.delete() || !f.exists(); } } catch (Throwable e){ gone=false; }
        Store.remove(c,it); dropThumb(it); render();
        if (!gone) android.widget.Toast.makeText(c, "Removed from the list. Android didn't let YouTube Ultra delete the file itself (it was saved by an earlier install); delete it from your gallery.", android.widget.Toast.LENGTH_LONG).show(); }).show(); }
}
