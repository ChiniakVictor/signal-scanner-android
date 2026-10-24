echo "focus: $(dumpsys window | grep mCurrentFocus | tail -1)"
dumpsys power | grep -m1 mWakefulness
dumpsys device_state | grep -m1 mCommittedState
