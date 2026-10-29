import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("diagnose", Path(__file__).with_name("diagnose-install.py"))
diagnose = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnose)


class InstallDiagnosticTest(unittest.TestCase):
    def test_actual_failure_detail_is_classified(self):
        failure = diagnose.parse_failure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package dev.notipush signatures do not match]")
        self.assertEqual("INSTALL_FAILED_UPDATE_INCOMPATIBLE", failure["code"])
        self.assertIn("signing identity", failure["explanation"])

    def test_success_and_generic_scan_are_not_assigned_a_cause(self):
        self.assertIsNone(diagnose.parse_failure("Performing Push Install\nSuccess"))
        self.assertIsNone(diagnose.parse_failure("App Guard found no errors. App not installed."))

    def test_unknown_and_parse_codes_keep_the_exact_code(self):
        failure = diagnose.parse_failure("Failure [INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: bad resource table]")
        self.assertEqual("INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION", failure["code"])

    def test_only_relevant_system_lines_are_retained(self):
        lines = diagnose.installation_lines("system unrelated line\nPackageManager: dev.notipush staged\nother unrelated line\nINSTALL_FAILED_USER_RESTRICTED: denied")
        self.assertEqual(["PackageManager: dev.notipush staged", "INSTALL_FAILED_USER_RESTRICTED: denied"], lines)

    def test_unauthorized_and_emulator_devices_are_distinguished(self):
        devices = diagnose.parse_devices("List of devices attached\nphone unauthorized\nemulator-5554 device product:test\nsecond offline\n")
        self.assertEqual("unauthorized", devices[0]["state"])
        self.assertFalse(devices[0]["emulator"])
        self.assertTrue(devices[1]["emulator"])
        self.assertEqual("offline", devices[2]["state"])


if __name__ == "__main__":
    unittest.main()
