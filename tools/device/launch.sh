# usage: sh ss-launch.sh [wait_seconds]
P=com.victor.signalscanner
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION NEARBY_WIFI_DEVICES BLUETOOTH_SCAN BLUETOOTH_CONNECT; do pm grant $P android.permission.$p 2>/dev/null; done
echo "focus before: $(dumpsys window | grep mCurrentFocus | tail -1)"
echo "throttle setting: $(settings get global wifi_scan_throttle_enabled)"
logcat -c
am start -n $P/.MainActivity >/dev/null
sleep ${1:-40}
echo "--- crashes"; logcat -d -s AndroidRuntime:E SSScanner:* | tail -20
echo "focus now: $(dumpsys window | grep mCurrentFocus | tail -1)"
