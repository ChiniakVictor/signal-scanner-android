# usage: sh ss-shot.sh NAME [am extras...]
N=$1; shift
if [ $# -gt 0 ]; then am start -n com.victor.signalscanner/.MainActivity "$@" >/dev/null; sleep 3; fi
screencap -p /sdcard/$N.png
uiautomator dump /sdcard/ss_ui.xml >/dev/null 2>&1
grep -o 'text="[^"]*"' /sdcard/ss_ui.xml | grep -v 'text=""' | head -60
