# CloudRoll

Your Telegram photos, videos and music in one gallery, plus free unlimited backup of your phone's photos to Telegram.

CloudRoll is an unofficial app that uses the Telegram API. It is not made by Telegram.

## What it does

- **Gallery**: every photo and video from all your chats and channels, newest first, grouped by day. Filter by photos, videos or files, and search names and captions.
- **Viewer**: swipe between photos, pinch or double-tap to zoom. Videos start playing in seconds while they download, and seeking jumps the download to that point (the same approach as TMPlayer).
- **Chats**: open any chat or channel and see its photos, videos, music, voice messages and files. Save everything of one kind to the phone in one tap.
- **Music**: every song and voice message from your chats as a music player, with background play, lock-screen controls, shuffle, repeat, speed, a sleep timer and favourites.
- **Backup**: backs up the phone's photos and videos, at full quality, to a private channel called "CloudRoll Backup". It can run automatically on Wi-Fi or while charging, free up space on the phone, and restore everything to a new phone.
- **Save to phone**: photos, videos, music and files go into Pictures, Movies, Music or Download, in CloudRoll folders. Chats that turn saving off stay respected.
- **Privacy**: app lock with fingerprint, face or screen lock, and chats can be hidden from Gallery and Music.
- **Updates**: every time it opens it checks this repository's releases, and offers the update in a pop-up.

## First start

CloudRoll uses your own free Telegram API key:

1. Open [my.telegram.org](https://my.telegram.org) and log in with your phone number.
2. Tap **API development tools**, fill in any app title and short name, platform Android, then **Create application**.
3. Copy **App api_id** and **App api_hash** into CloudRoll, then sign in with your phone number and the code Telegram sends.

The key and the sign-in stay on the phone only. CloudRoll has no server of its own.

## Install

Download the newest `CloudRoll-x.y.z.apk` from [Releases](../../releases) on the phone and open it. Later versions install from inside the app.

## Built with

- [TDLib](https://core.telegram.org/tdlib), Telegram's own library, through [tdl-coroutines](https://github.com/g000sha256/tdl-coroutines) (Apache 2.0)
- [Media3 ExoPlayer](https://developer.android.com/media/media3) for video and music
- Jetpack Compose and Material 3
- [ZXing](https://github.com/zxing/zxing) for the QR sign-in code

Every push to `main` builds a signed APK on GitHub Actions and publishes it as a release.
