# usage: sh ss-autoloc.sh off|on   (sets precondition, opens app, presses Home, reports)
P=com.victor.signalscanner
st() { echo "  [$1] location=$(cmd location is-location-enabled) mode=$(settings get secure location_mode) focus=$(dumpsys window | grep mCurrentFocus | tail -1 | sed 's/.*u0 //')"; }
echo "focus before: $(dumpsys window | grep mCurrentFocus | tail -1)"
cmd location set-location-enabled $([ "$1" = on ] && echo true || echo false); sleep 2
st precondition
logcat -c
am start -n $P/.MainActivity --ei tab 0 >/dev/null
for i in 2 4 6; do sleep 2; st "open +${i}s"; done
sleep 14
uiautomator dump /sdcard/ss_ui.xml >/dev/null 2>&1
echo "  tabs: $(grep -o -E 'text="(Wi-Fi|Bluetooth|Cell) \([0-9]+\)"' /sdcard/ss_ui.xml | tr '\n' ' ')"
grep -o 'text="Location is off[^"]*"' /sdcard/ss_ui.xml | head -1
rm -f /sdcard/ss_ui.xml
input keyevent KEYCODE_HOME
for i in 1 2 3 4 5; do sleep 1; st "home +${i}s"; done
sleep 4; st "home +9s"
echo "--- log"; logcat -d -s SSAutoLoc:* AndroidRuntime:E | tail -8
