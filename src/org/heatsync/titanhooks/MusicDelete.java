package org.heatsync.titanhooks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// side key -> this: skip musicolet to the next track, delete the one it was playing from the
// synced music folder, log it. syncthing carries the delete to every other machine.
public final class MusicDelete extends Activity {
  static final String PLAYER = "in.krosbits.musicolet";
  static final File MUSIC = new File(Environment.getExternalStorageDirectory(), "Music");
  static final File LOG = new File(MUSIC, ".deleted.log");
  static final Uri AUDIO = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
  static final long SKIP_WAIT_MS = 3000;
  static final long POLL_MS = 50;

  final Handler main = new Handler(Looper.getMainLooper());

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    guard(this::run);
  }

  interface Step { void go() throws Exception; }

  // every step ends the activity unless it hands off to a later one
  void guard(Step s) {
    try {
      s.go();
      return;
    } catch (SecurityException e) {
      say("titan hooks: missing access (" + e.getMessage() + ")");
    } catch (Exception e) {
      say("titan hooks: " + e);
    }
    finish();
  }

  void run() throws Exception {
    MediaSessionManager msm = getSystemService(MediaSessionManager.class);
    MediaController player = null;
    for (MediaController c : msm.getActiveSessions(new ComponentName(this, MediaListener.class)))
      if (PLAYER.equals(c.getPackageName())) player = c;
    if (player == null) { say("musicolet not running"); finish(); return; }
    // only the track actually playing may be deleted: a paused musicolet behind youtube music is not it
    PlaybackState st = player.getPlaybackState();
    if (st == null || st.getState() != PlaybackState.STATE_PLAYING) { say("musicolet not playing"); finish(); return; }
    MediaMetadata md = player.getMetadata();
    if (md == null) { say("nothing playing"); finish(); return; }
    String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
    String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
    String album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
    if (title.isEmpty()) { say("no title in metadata"); finish(); return; }

    List<long[]> ids = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    find(title, artist, album, ids, paths);
    if (paths.isEmpty()) find(title, artist, null, ids, paths);
    if (paths.isEmpty()) { say("not found: " + title); finish(); return; }
    if (paths.size() > 1) { say("ambiguous (" + paths.size() + "): " + title); finish(); return; }

    File f = new File(paths.get(0));
    if (!f.getCanonicalPath().startsWith(MUSIC.getCanonicalPath() + "/")) { say("refusing: outside Music"); finish(); return; }

    // musicolet drops a vanished track from its queue and advances on its own; deleting before
    // our skip lands made it advance twice. so skip, wait until it is on another track, then delete.
    final MediaController p = player;
    final long queueItem = st.getActiveQueueItemId();
    final String was = title + "\n" + artist + "\n" + album;
    final long deadline = SystemClock.uptimeMillis() + SKIP_WAIT_MS;
    p.getTransportControls().skipToNext();
    main.post(new Runnable() {
      @Override public void run() {
        Runnable again = this;
        guard(() -> {
          if (moved(p, queueItem, was)) { delete(f, ids.get(0)[0]); finish(); }
          else if (SystemClock.uptimeMillis() > deadline) { say("skip did not land, kept: " + f.getName()); finish(); }
          else main.postDelayed(again, POLL_MS);
        });
      }
    });
  }

  static boolean moved(MediaController p, long queueItem, String was) {
    PlaybackState st = p.getPlaybackState();
    if (queueItem != MediaSession.QueueItem.UNKNOWN_ID && st != null
        && st.getActiveQueueItemId() != MediaSession.QueueItem.UNKNOWN_ID)
      return st.getActiveQueueItemId() != queueItem;
    MediaMetadata md = p.getMetadata();
    if (md == null) return false;
    String now = str(md, MediaMetadata.METADATA_KEY_TITLE) + "\n" + str(md, MediaMetadata.METADATA_KEY_ARTIST)
      + "\n" + str(md, MediaMetadata.METADATA_KEY_ALBUM);
    return !now.equals(was);
  }

  void delete(File f, long id) throws Exception {
    getContentResolver().delete(ContentUris.withAppendedId(AUDIO, id), null, null);
    if (f.exists() && !f.delete()) { say("delete failed: " + f.getName()); return; }
    File dir = f.getParentFile();
    String[] left = dir.list();
    if (left != null && left.length == 0) dir.delete();
    try (FileWriter w = new FileWriter(LOG, true)) {
      w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()) + " titan:" + f.getPath() + "\n");
    }
    say("deleted: " + f.getName());
  }

  // media store already indexes Music with tags; one query beats probing files
  void find(String title, String artist, String album, List<long[]> ids, List<String> paths) {
    StringBuilder sel = new StringBuilder(MediaStore.Audio.Media.TITLE + "=? COLLATE NOCASE");
    List<String> args = new ArrayList<>();
    args.add(title);
    if (!artist.isEmpty()) { sel.append(" AND " + MediaStore.Audio.Media.ARTIST + "=? COLLATE NOCASE"); args.add(artist); }
    if (album != null && !album.isEmpty()) { sel.append(" AND " + MediaStore.Audio.Media.ALBUM + "=? COLLATE NOCASE"); args.add(album); }
    String[] proj = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA };
    ContentResolver r = getContentResolver();
    try (Cursor c = r.query(AUDIO, proj, sel.toString(), args.toArray(new String[0]), null)) {
      if (c == null) return;
      while (c.moveToNext()) {
        String p = c.getString(1);
        if (p == null || p.contains("/.stversions/") || !new File(p).exists()) continue;
        ids.add(new long[] { c.getLong(0) });
        paths.add(p);
      }
    }
  }

  static String str(MediaMetadata md, String key) {
    CharSequence v = md.getText(key);
    return v == null ? "" : v.toString().trim();
  }

  void say(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
}
