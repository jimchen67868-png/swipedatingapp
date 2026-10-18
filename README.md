# Swipe: a Tantan-style dating app (Kotlin + Jetpack Compose)

## Run
1. Android Studio (Koala or newer) > File > Open > select this folder.
2. Let Gradle sync (it downloads the wrapper and dependencies).
3. Run on an emulator or device (Android 7.0+).

## What works
- Swipe deck with drag gestures, LIKE/NOPE stamps, Pass/Like buttons
- Mock matching (60% chance a like becomes a match)
- Matches list and chat with a mock auto-reply
- All data is in memory (data/Repository.kt)

## Going live
- Replace Repository.kt with Firebase (Auth, Firestore, Storage, FCM) or your own API.
- Add Coil for real photos (see the comment in ProfileCard).
- Add phone login, profile editing, location-based discovery, report/block and
  photo moderation (required by Google Play for dating apps).
