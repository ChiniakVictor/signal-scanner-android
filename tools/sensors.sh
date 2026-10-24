P=com.victor.signalscanner
A="am start -n $P/.MainActivity"
dump() { uiautomator dump /sdcard/ss_ui.xml >/dev/null 2>&1; grep -o 'text="[^"]*"' /sdcard/ss_ui.xml | grep -v 'text=""' | sed -n "$1"; rm -f /sdcard/ss_ui.xml; }
echo "focus before: $(dumpsys window | grep mCurrentFocus | tail -1 | sed 's/.*u0 //')"
echo "location before: $(cmd location is-location-enabled)"
pm grant $P android.permission.RECORD_AUDIO
logcat -c
$A --ei tab 5 >/dev/null; sleep 6
$A --ez calibrate true >/dev/null 2>&1; sleep 4
echo "--- magnet A"; dump '16,40p'
sleep 3; echo "--- magnet B"; dump '16,30p'
screencap -p /sdcard/ss_magnet.png
$A --ei tab 6 >/dev/null 2>&1; sleep 2
$A --ez ultra true >/dev/null 2>&1; sleep 10
echo "--- ultra"; dump '16,60p'
echo "mic users: $(dumpsys audio | grep -i -A2 'RecordActivityMonitor\|rec update' | grep -c signalscanner)"
screencap -p /sdcard/ss_ultra.png
$A --ez ultra false >/dev/null 2>&1; sleep 2
dumpsys audio | grep -i -E 'riid|session' | grep -i -m5 -E 'signalscanner|uid:10272'
$A --ei tab 8 >/dev/null 2>&1; sleep 4
echo "--- radios"; dump '1,400p' | grep -A3 -E 'WI-FI AWARE|Status|UWB|RTT|Barometer|Light|Proximity|ALL SENSORS' | head -40
echo "--- crashes"; logcat -d -s AndroidRuntime:E | tail -5
$A --ei tab 0 >/dev/null 2>&1
