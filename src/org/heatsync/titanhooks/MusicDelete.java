package org.heatsync.titanhooks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      run();
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
    if (player == null) { say("musicolet not running"); return; }
    MediaMetadata md = player.getMetadata();
    if (md == null) { say("nothing playing"); return; }
    String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
    String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
    String album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
    if (title.isEmpty()) { say("no title in metadata"); return; }

    List<long[]> ids = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    find(title, artist, album, ids, paths);
    if (paths.isEmpty()) find(title, artist, null, ids, paths);
    if (paths.isEmpty()) { say("not found: " + title); return; }
    if (paths.size() > 1) { say("ambiguous (" + paths.size() + "): " + title); return; }

    File f = new File(paths.get(0));
    if (!f.getCanonicalPath().startsWith(MUSIC.getCanonicalPath() + "/")) { say("refusing: outside Music"); return; }

    player.getTransportControls().skipToNext();
    getContentResolver().delete(ContentUris.withAppendedId(AUDIO, ids.get(0)[0]), null, null);
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
