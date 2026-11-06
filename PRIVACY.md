# Privacy

Story-Board is an offline-first app with no server of its own.

**What stays on your phone**
- Books, chapters, versions, the story bible, captured replies, AI history and roundtable sessions (an app database).
- Pictures in the gallery (app storage, hidden from the phone's photo gallery).
- API keys you add, encrypted with a key held in the Android Keystore.
- Logins to AI websites, kept as cookies inside the app's own browser, like any browser.

**What leaves your phone**
- Prompts and story text you send to an AI go to that AI's website or API, and are handled under that service's own terms and privacy policy.
- Pictures are downloaded from the site that made them.
- Backups go only to the folder you pick (for example a local folder or a cloud-synced one). API keys and website logins are never included in backups.
- Story files (`.storyboard`), EPUB and PDF exports go wherever you share them.

**What Story-Board never does**
- No analytics, crash reporting, advertising or tracking.
- No account and no data sent to the developer.

Android's own cloud backup is turned off for this app (`allowBackup="false"`) so logins and keys are not copied by the system.
