# How to Clear Icon Cache and See New Icons

## Method 1: Force Stop Launcher (Quickest)
1. Go to **Settings > Apps**
2. Find your launcher app (e.g., "Pixel Launcher", "One UI Home", etc.)
3. Tap **Force Stop**
4. Go back to home screen - icons should refresh

## Method 2: Clear Launcher Cache
1. Go to **Settings > Apps**
2. Find your launcher app
3. Tap **Storage & cache**
4. Tap **Clear Cache** (NOT Clear Storage - that would reset your home screen layout)
5. Restart your phone

## Method 3: Reinstall the App
1. Uninstall MTB Analyzer
2. Install it again with: `./gradlew installDebug`
3. The new icon should appear immediately

## Method 4: Change Icon Shape (if supported)
Some launchers let you change icon shape which forces a refresh:
1. Long press on home screen
2. Go to **Home settings** or **Styles & wallpapers**
3. Change icon shape (circle, square, etc.)
4. Change it back if desired

## Method 5: Restart Device
Sometimes a simple restart is all that's needed to clear icon caches.

## Note for Developers
When testing icon changes during development:
- The launcher cache can be very persistent
- Some devices cache more aggressively than others
- Pixel devices usually update faster than Samsung/other OEM launchers
- Third-party launchers (Nova, etc.) may have their own cache settings