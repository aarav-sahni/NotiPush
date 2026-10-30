import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("background", Path(__file__).with_name("diagnose-background.py"))
background = importlib.util.module_from_spec(spec)
spec.loader.exec_module(background)


class BackgroundParserTests(unittest.TestCase):
    def test_setting_filter_keeps_boolean_power_metadata_only(self):
        raw = "doze_always_on=1\noplus_aod_custom_text=private example\nbluetooth_name=personal\ndeep_sleep_switch=true\naod_image=/personal/path\n"
        self.assertEqual({"doze_always_on": "1", "deep_sleep_switch": "true"}, background.boolean_settings(raw))

    def test_process_filter_excludes_other_apps_and_notification_text(self):
        raw = """ACTIVITY MANAGER RUNNING PROCESSES
  *APP* UID 10001 ProcessRecord{a 123:other.app/u0a1}
    curProcState=2 cached=false isFrozen=false
  *APP* UID 10002 ProcessRecord{b 124:dev.notipush/u0a2}
    curProcState=6 cached=false isFrozen=true hasForegroundServices=false
    unrelated=private
  *APP* UID 10003 ProcessRecord{c 125:another.app/u0a3}
    curProcState=19 cached=true isFrozen=false
"""
        self.assertEqual({"curProcState": "6", "cached": "false", "isFrozen": "true", "hasForegroundServices": "false"}, background.process_state(raw))

    def test_notification_access_checks_exact_package(self):
        self.assertTrue(background.listener_enabled("other/pkg:dev.notipush/dev.notipush.NotificationBridge"))
        self.assertFalse(background.listener_enabled("dev.notipush.fake/Listener"))
        self.assertFalse(background.listener_enabled("null"))

    def test_power_state_excludes_other_dump_content(self):
        self.assertEqual({"mWakefulness": "Dozing", "mDeviceIdleMode": "true"},
                         background.power_state("mWakefulness=Dozing\nmDeviceIdleMode=true\nprivate=example\n"))

    def test_whitelist_and_appop_modes(self):
        self.assertEqual(["user"], background.whitelist_kinds("user,dev.notipush,10002\nuser,other.app,10003"))
        self.assertEqual("ignore", background.appop_mode("RUN_ANY_IN_BACKGROUND: ignore; time=recent"))
        self.assertEqual("unavailable", background.appop_mode("Unknown operation"))

    def test_display_parser_does_not_confuse_history_with_current_state(self):
        raw = "state change: state ON\n  mDisplayState=DOZE_SUSPEND\nhistory state OFF\n"
        self.assertEqual(["DOZE_SUSPEND"], background.display_states(raw))
