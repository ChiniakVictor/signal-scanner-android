echo "loc_enabled=$(cmd location is-location-enabled)"
echo "location_mode=$(settings get secure location_mode)"
echo "nfc_on=$(settings get global nfc_on)"
dumpsys nfc 2>/dev/null | grep -i -m3 -E 'mState|isNfcEnabled|mIsReaderModeEnabled|State'
echo "throttle=$(settings get global wifi_scan_throttle_enabled)"
echo "focus: $(dumpsys window | grep mCurrentFocus | tail -1)"
dumpsys device_state | grep -m1 mCommittedState
