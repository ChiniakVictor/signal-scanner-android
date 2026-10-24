P=com.victor.signalscanner/.MainActivity
S=/data/local/tmp/ss-shot.sh
focus() { dumpsys window | grep mCurrentFocus | tail -1; }
echo "== focus at start: $(focus)"
case "$(focus)" in *launcher*|*signalscanner*) ;; *) echo "phone in use - abort"; exit 1;; esac
logcat -c
am start -n $P >/dev/null; sleep 70
echo "== wifi list"; sh $S ss_wifi --ei tab 0 --ez channels false | head -40
echo "== wifi detail"; sh $S ss_wifi_detail --ei tab 0 --es select aa:b4:e9:37:8c:f6 | head -80
echo "== channels"; sh $S ss_channels --ei tab 0 --ez channels true | head -12
am start -n $P --ez channels false >/dev/null
echo "== bt"; sh $S ss_bt --ei tab 1 | head -60
echo "== nfc"; sh $S ss_nfc --ei tab 2 | head -25
dumpsys nfc | grep -i -E "reader|mState" | head -5
echo "== throttle test: 10 s interval for 100 s"
am start -n $P --ei tab 3 --ei wifi_interval 10 >/dev/null; sleep 100
sh $S ss_throttle --ei tab 3 | grep -A40 "RECENT SCAN" | head -40
am start -n $P --ei wifi_interval 30 --ei tab 0 >/dev/null
echo "== export"; am start -n $P --es export json >/dev/null; am start -n $P --es export wifi >/dev/null; sleep 2; am start -n $P --es export bt >/dev/null; sleep 4
ls -la /sdcard/Download/SignalScanner/
echo "== crashes"; logcat -d -s AndroidRuntime:E | tail -10
echo "== focus at end: $(focus)"
