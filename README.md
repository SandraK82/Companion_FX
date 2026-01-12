🌐 **English** | [Deutsch](README.de.md) | [Français](README.fr.md)

> ⚠️ **Warning:** This app should only be used on a dedicated secondary phone in follower mode, as it significantly restricts normal smartphone usage.

# Companion FX

Companion app for CamAPS FX that reads glucose values from the screen and uploads them to Nightscout.

## Features

- **Screen Reading**: Reads glucose values, trends, and pump data from CamAPS FX
- **Nightscout Integration**: Uploads glucose readings to your Nightscout instance
- **SAGE/IAGE Tracking**: Monitors sensor age and insulin age, uploads to Nightscout
- **Home Screen Widget**: Displays current glucose value and trend graph
- **Multi-Language**: Supports German, English, and French CamAPS FX versions

## Installation

1. Download and install the APK
2. Grant required permissions (see below)
3. Configure your Nightscout URL and API Secret in Settings
4. Enable the Accessibility Service

## Required Permissions

### Accessibility Service (Required)

The app uses Android's Accessibility Service to read glucose data from CamAPS FX.

**Setup:**
1. Open Android **Settings**
2. Go to **Accessibility** (or search for "Accessibility")
3. Find **Companion FX** in the list
4. Enable the service
5. Confirm the permission dialog

### Battery Optimization (Recommended)

For reliable background readings, disable battery optimization for the app:

1. Open Android **Settings**
2. Go to **Apps** > **Companion FX**
3. Tap **Battery**
4. Select **Unrestricted** or **Don't optimize**

On some devices (Samsung, Xiaomi, Huawei), you may also need to:
- Add the app to "Protected Apps" or "Auto-start" list
- Disable "Adaptive Battery" for this app

### Notification Permission (Android 13+)

The app will request notification permission on first launch. This is needed for:
- Showing the foreground service notification
- Lockscreen reading notifications

## Configuration

### Nightscout Settings

1. Enter your Nightscout URL (e.g., `https://your-nightscout.herokuapp.com`)
2. Enter your API Secret
3. Test the connection
4. Enable Nightscout sync

### Reading Interval

Configure how often the app reads glucose values:
- Default: 1 minute
- Options: 1 min, 5 min, 15 min

### SAGE/IAGE Interval

Configure how often sensor and insulin age are checked:
- Default: 30 minutes
- Options: 1 min, 15 min, 30 min, 1 hour, 6 hours

## Supported Devices

- **Android**: 8.0 (API 26) and higher
- **CamAPS FX**: All versions with German, English, or French UI

## Troubleshooting

### App doesn't read glucose values

1. Ensure Accessibility Service is enabled
2. Check that CamAPS FX is set as the target app
3. Verify battery optimization is disabled

### Nightscout upload fails

1. Check your Nightscout URL (no trailing slash)
2. Verify API Secret is correct
3. Test connection in Settings

### Widget doesn't update

1. Check that the service is running (notification should be visible)
2. Remove and re-add the widget
3. Disable battery optimization

## Privacy

- All data is stored locally on your device
- Data is only sent to your personal Nightscout instance
- No data is sent to any other servers

## License

MIT License - see [LICENSE](LICENSE)

## Disclaimer

This app is not affiliated with CamAPS FX, Ypsomed, or Abbott. It is an independent companion app for personal diabetes management. Always verify glucose readings with your CGM or blood glucose meter for medical decisions.
