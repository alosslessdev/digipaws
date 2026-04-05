# DigiPaws Comprehensive Logging Documentation

## Overview
A comprehensive logging system has been added to track every service lifecycle event, accessibility event handling, and blocker actions. This will help identify why accessibility services are crashing.

## Logging System

### AppLogger Utility (`neth.iecal.curbox.utils.AppLogger`)
Central logging utility with specialized methods for different log types:

#### Initialization
- Automatically initialized in `Curbox.onCreate()`
- Logs written to both:
  - **Logcat**: Tagged with "DigiPaws" for Android Studio monitoring
  - **File**: `app_debug.log` in app cache directory

#### Log Types & Methods

1. **Function Entry/Exit Logging**
   ```kotlin
   AppLogger.functionEntry(TAG, "functionName", "arg1=value1, arg2=value2")
   AppLogger.functionExit(TAG, "functionName", returnValue)
   AppLogger.functionError(TAG, "functionName", exception)
   ```

2. **Variable Logging**
   ```kotlin
   AppLogger.logVariable(TAG, "variableName", value)
   ```

3. **Service Lifecycle Logging**
   ```kotlin
   AppLogger.logServiceLifecycle(TAG, "onCreate()", "Details here")
   ```

4. **Accessibility Event Logging**
   ```kotlin
   AppLogger.logAccessibilityEvent(TAG, "EVENT_TYPE", "packageName", "details")
   ```

5. **Blocker Action Logging**
   ```kotlin
   AppLogger.logBlockerAction(TAG, "BlockerName", "action", "result")
   ```

6. **State Change Logging**
   ```kotlin
   AppLogger.logStateChange(TAG, "component", "oldState", "newState")
   ```

## What's Being Logged

### Service Lifecycle (`BaseBlockingService.kt`)
- ✅ `onServiceConnected()` - Service connected to accessibility framework
- ✅ `onAccessibilityEvent()` - Entry to event handler
- ✅ `onInterrupt()` - Service interrupted
- ✅ `onUnbind()` - Unbinding from accessibility service  
- ✅ `onDestroy()` - Service being destroyed
- ✅ `pressBack()` / `pressHome()` - Navigation actions with error handling

### AppBlockerService
- ✅ `onCreate()` - Service creation and initialization
- ✅ `onServiceConnected()` - Complete setup of all blockers with detailed logging
- ✅ `onAccessibilityEvent()` - Event type identification and per-blocker check results
- ✅ Background worker - Event processing with error tracking
- ✅ `onDestroy()` - Resource cleanup with individual error isolation
- ✅ Broadcast receiver - Picker notification handling

### AppBlocker
- ✅ `doAppBlockerCheck()` - App blocking logic with:
  - Cooldown status checks
  - Time-block validation
  - Usage limit calculations
  - Remaining usage tracking
- ✅ `setupAppBlocker()` - Blocker initialization and app list loading
- ✅ `setupReceivers()` - Broadcast receiver registration
- ✅ `handlePutCooldownIntentBroadcast()` - Cooldown application

### ReelBlocker
- ✅ `doViewBlockerCheck()` - Reel blocking logic with:
  - View detection per ID
  - Cooldown checking
  - Time-based vs count-based logic
  - Warning screen triggering
- ✅ `setupBlocker()` - Config loading and initialization
- ✅ `setupReceivers()` - Receiver registration
- ✅ `applyCooldown()` - Cooldown application with duration tracking

### UsageTrackingService
- ✅ `onServiceConnected()` - Service setup and permissions checking
- ✅ `onAccessibilityEvent()` - Event routing to trackers
- ✅ `onDestroy()` - Tracker cleanup

## Accessing the Logs

### In Android Studio (Logcat)
1. Open **Logcat** tab
2. Filter by tag **"DigiPaws"** to see all app logs
3. Logs show in real-time

### Log File on Device
The log file is stored at:
```
/data/data/neth.iecal.curbox/cache/app_debug.log
```

### Reading from Device via ADB
```bash
# View live logs
adb logcat | grep DigiPaws

# Extract the log file
adb pull /data/data/neth.iecal.curbox/cache/app_debug.log ./app_debug.log

# View the file
cat app_debug.log
```

### In Settings (if UI added)
Consider adding a settings option to:
- View recent logs in-app
- Export logs via email
- Clear old logs

## Log Output Format

### Logcat
```
2024-01-15 10:30:45.123 D/DigiPaws [AppBlockerService] >>> ENTER: onAccessibilityEvent (eventType=TYPE_WINDOW_STATE_CHANGED)
2024-01-15 10:30:45.456 I/DigiPaws [AppBlocker] [SERVICE] onServiceConnected() - All blockers setup successfully
2024-01-15 10:30:45.789 I/DigiPaws [AppBlocker] [AppBlocker] App blocking triggered => usage=45000ms, remaining=15000ms
```

### File Log
```
[10:30:45.123] D/AppBlockerService: >>> ENTER: onAccessibilityEvent (eventType=TYPE_WINDOW_STATE_CHANGED)
[10:30:45.456] I/AppBlocker: [SERVICE] onServiceConnected() - All blockers setup successfully  
[10:30:45.789] I/AppBlocker: [AppBlocker] App blocking triggered => usage=45000ms, remaining=15000ms
```

## Debugging Tips

### Service Binding Issues
Search logs for: `onServiceConnected` and `onUnbind`
- Check if these are called in sequence
- Look for `onDestroy` being called unexpectedly

### Accessibility Event Processing
Search logs for: `onAccessibilityEvent` and `EVENT` 
- Verify events are being received
- Check for error messages in event processing

### Blocker Setup Failures
Search logs for: `Setup started/complete` and `ERROR`
- Look at the setup sequence in `onServiceConnected`
- Check for exceptions during initialization

### Crashes
Search logs for: `!!! ERROR` and `Exception`
- These mark error points where exceptions were caught
- Provides full exception stack trace

### Cooldown Issues  
Search logs for: `cooldown` and `remaining`
- Check cooldown timers being set
- Verify end times are in the future

## Variable Examples to Watch

Key variables being logged:
- `current_usage_ms` - App usage in milliseconds
- `usage_limit_ms` - Configured usage limit
- `remaining_usage_ms` - Time before app is blocked
- `current_daily_reel_count` - Number of reels watched today
- `daily_reel_limit` - Max reels allowed per day
- `cooldown_end_time` - When cooldown expires
- `screen_width/screen_height` - Display dimensions
- `reel_blocker_active` - Whether reel blocking is enabled

## Potential Crash Sources (Based on Logging)

The logging will help identify:

1. **Service Binding/Unbinding Issues**
   - Watch for `onLibpulseConnect` and `onUnbind` timing
   - Check if `onDestroy` is properly cleaning up
   - Verify no operations happen after unbind

2. **Accessibility Event Issues**
   - May be trying to performGlobalAction() during unbind state
   - Check if `rootInActiveWindow` is null (causes NPE)
   - Verify event recycling is working

3. **Resource Leaks**
   - Check `onDestroy()` cleanup sequence
   - Verify all receivers are unregistered
   - Ensure coroutine scopes are cancelled

4. **Configuration Loading**
   - Check DataStore access timing
   - Look for null pointer exceptions in config loading
   - Verify app list is loaded before first event

## Next Steps

1. **Run the app and trigger the crash**
2. **Extract the logs** (`adb pull /data/data/neth.iecal.curbox/cache/app_debug.log`)
3. **Analyze the logs** looking for:
   - Missing lifecycle callbacks
   - Exceptions in setup
   - State inconsistencies
4. **Share the logs** for debugging assistance

The comprehensive logging should now show exactly where the service is crashing and why!
