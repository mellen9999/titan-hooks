# titan-hooks

one tiny apk for the unihertz titan 2: things a physical side key should do that no
stock app does. each hook is an exported activity with no ui, so the phone's own
"shortcut keys" setting can launch it. nothing runs in the background.

## hooks

**music delete** — `org.heatsync.titanhooks.MusicDelete`
skips musicolet to the next track and deletes the one that was playing from the
`Music` folder, which syncthing then removes from every other machine. the file is
found by title + artist (+ album) in the media store, and it refuses when the match
is missing, ambiguous, or outside `Music`. every delete is appended to
`Music/.deleted.log`. a toast says what happened.

## build

    ./build.sh          # needs android build-tools + platform android.jar, javac, zip

## install (once, over adb)

    adb install -r build/titan-hooks.apk
    adb shell cmd notification allow_listener org.heatsync.titanhooks/.MediaListener
    adb shell appops set --uid org.heatsync.titanhooks MANAGE_EXTERNAL_STORAGE allow
    adb shell pm grant org.heatsync.titanhooks android.permission.READ_MEDIA_AUDIO

the notification-listener grant is what lets the app see musicolet's media session;
it never reads notifications. all-files access is what lets it delete a file it did
not create.

then bind a key: settings > shortcut keys > pick the key and press type > apps > titan hooks.
or over adb (top key, double press):

    adb shell settings put system func2_double_press_package org.heatsync.titanhooks
    adb shell settings put system func2_double_press_activity org.heatsync.titanhooks.MusicDelete
