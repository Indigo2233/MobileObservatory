"""Generated temporary fixtures validate evidence rules, not real field performance."""
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + ".py"))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result); return result


baseline = module("wf_baseline")
generator = module("generate_wide_field_index")


class EvidenceRulesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.manifest = self.root / "manifest.json"
        self.image = self.root / "fixture.fit"; self.image.write_bytes(b"synthetic test fixture only")
        self.truth = self.root / "fixture.reference.json"
        baseline.write_json(self.truth, {"frame": "J2000", "imageYAxis": "DOWN", "raDeg": 40., "decDeg": 20.,
            "rotationDeg": 47., "fovWidthDeg": 40., "fovHeightDeg": 30.})
        self.event = {"schema": 1, "sessionId": "unit-test", "elapsedRealtimeNanos": 1_000_000_000,
            "success": True, "raDeg": 40., "decDeg": 20., "rotationDeg": 47., "fovWidthDeg": 40., "fovHeightDeg": 30.,
            "frameWidth": 1600, "frameHeight": 1200, "elapsedMs": 4, "device": {"isEmulator": False},
            "capture": {"imageName": self.image.name, "imageSha256": baseline.sha(self.image),
                "logicalCameraId": "0", "exposureNs": 500_000_000}, "diagnostics": {}, "metrics": {}}
        # Attestations below are synthetic inputs for parser tests. No result is published as physical evidence.
        self.sample = {"sampleId": "synthetic-parser-case", "captureGroupId": "group", "evidenceType": "REAL_CAPTURE",
            "testedRevision": "unit-test-only", "image": self.image.name, "imageYAxis": "DOWN", "equivalentFocalLengthMm": 24,
            "sky": {"place": "urban", "moon": False}, "reference": {"source": "ASTAP_W08", "version": "test-only",
                "independent": True, "forImageSha256": baseline.sha(self.image), "artifact": self.truth.name,
                "artifactSha256": baseline.sha(self.truth)}}

    def tearDown(self): self.temp.cleanup()

    def report(self, samples=None, events=None):
        baseline.write_json(self.manifest, {"schema": 1, "samples": samples if samples is not None else [self.sample]})
        return baseline.summarize(self.manifest, events if events is not None else [self.event])

    def test_zero_data_has_unknown_rates_and_explicit_missing_gate(self):
        report = self.report([], [])
        self.assertEqual("missing_evidence", report["status"]); self.assertIsNone(report["successRate"])
        self.assertIsNone(report["acceptedMismatchRate"]); self.assertEqual(0, report["centerErrorDeg"]["count"])

    def test_bound_reference_and_input_are_required_before_error_statistics(self):
        report = self.report()
        self.assertEqual(1, report["eligibleRealGroups"]); self.assertEqual(0, report["centerErrorDeg"]["max"])
        self.sample["reference"]["forImageSha256"] = "wrong"
        report = self.report(); self.assertEqual(0, report["eligibleRealGroups"])

    def test_emulator_and_unverified_scaffold_never_count_as_real(self):
        self.event["device"]["isEmulator"] = True
        self.assertEqual(0, self.report()["eligibleRealGroups"])
        self.event["device"]["isEmulator"] = False; self.sample["evidenceType"] = "UNVERIFIED"
        self.assertEqual(0, self.report()["eligibleRealGroups"])

    def test_repeating_one_image_thirty_times_does_not_pass(self):
        report = self.report([dict(self.sample, sampleId=str(i), captureGroupId=str(i)) for i in range(30)])
        self.assertEqual(1, report["eligibleRealGroups"]); self.assertEqual("missing_evidence", report["status"])

    def test_invalid_accepted_coordinates_are_not_counted_as_eligible(self):
        self.event["raDeg"] = None
        report = self.report(); self.assertEqual(0, report["eligibleRealGroups"])
        self.assertEqual(0, report["acceptedSolutions"])

    def test_wrong_roll_and_center_are_accepted_mismatches(self):
        self.event["raDeg"] = 42.; self.event["rotationDeg"] = 80.
        report = self.report(); self.assertEqual(1, report["acceptedMismatches"])
        self.assertGreater(report["centerErrorDeg"]["max"], 1)

    def test_missing_and_modified_reference_do_not_count(self):
        self.sample["reference"]["artifact"] = None
        self.assertEqual(0, self.report()["eligibleRealGroups"])
        self.sample["reference"]["artifact"] = self.truth.name; self.truth.write_text("{}")
        self.assertEqual(0, self.report()["eligibleRealGroups"])

    def test_safe_solver_failure_is_not_a_success_or_accepted_false_solve(self):
        self.event["success"] = False; self.event["raDeg"] = None
        report = self.report(); self.assertEqual(1, report["eligibleRealGroups"])
        self.assertEqual(0, report["acceptedSolutions"]); self.assertIsNone(report["acceptedMismatchRate"])

    def test_counter_reset_or_missing_metric_does_not_become_zero_allocation(self):
        first = dict(self.event, metrics={"bytesAllocatedCounter": 1000})
        second = dict(self.event, elapsedRealtimeNanos=2_000_000_000, metrics={"bytesAllocatedCounter": 900})
        self.assertEqual(0, baseline.performance([first, second])["observedAllocationBytesPerSecond"]["count"])
        second["metrics"]["bytesAllocatedCounter"] = 1400
        self.assertEqual(400, baseline.performance([first, second])["observedAllocationBytesPerSecond"]["max"])

    def test_nonfinite_json_is_rejected_instead_of_becoming_a_precise_metric(self):
        path = self.root / "bad.jsonl"
        path.write_text('{"schema":1,"elapsedMs":NaN}\n')
        with self.assertRaises(ValueError): baseline.read_events(path)

    def test_tan_wcs_evaluates_wf_center_and_downward_rows_position_angle(self):
        # A unit fixture, not an ASTAP result: CRPIX at WF's zero-based center +1.
        cards = {"SIMPLE": "T", "BITPIX": 16, "NAXIS": 2, "NAXIS1": 1600, "NAXIS2": 1200,
            "CTYPE1": "RA---TAN", "CTYPE2": "DEC--TAN", "RADESYS": "ICRS", "CRVAL1": 40., "CRVAL2": 20.,
            "CRPIX1": 801., "CRPIX2": 601., "CD1_1": .01, "CD1_2": 0., "CD2_1": 0., "CD2_2": -.01}
        lines = []
        for key, value in cards.items():
            value = "'" + value + "'" if isinstance(value, str) and value != "T" else str(value)
            lines.append((f"{key:8}= {value}").ljust(80))
        text = ("".join(lines) + "END".ljust(80)); text += " " * (-len(text) % 2880)
        path = self.root / "reference.wcs"; path.write_bytes(text.encode("ascii"))
        result = baseline.wcs_reference(path, 1600, 1200)
        self.assertAlmostEqual(40, result["raDeg"]); self.assertAlmostEqual(20, result["decDeg"])
        self.assertAlmostEqual(0, result["rotationDeg"]); self.assertGreater(result["fovWidthDeg"], 15)

    def test_resource_emulator_short_charging_data_cannot_claim_endurance(self):
        samples = [{"schema": 1, "kind": "resource_sample", "elapsedSeconds": i,
            "isEmulator": True, "externallyPowered": True, "phaseAnnotation": "test"} for i in (0, 2)]
        report = baseline.summarize_resources(samples)
        self.assertEqual("missing_evidence", report["status"]); self.assertIsNone(report["consumedMicroampHours"])

    def test_charge_counter_changes_on_emulator_or_charging_are_only_observed_deltas(self):
        samples = [{"kind": "resource_sample", "elapsedSeconds": i, "isEmulator": True,
            "externallyPowered": True, "batteryChargeMicroampHours": 1000-i} for i in (0, 2)]
        report = baseline.summarize_resources(samples)
        self.assertEqual(2, report["observedChargeCounterDeltaMicroampHours"])
        self.assertIsNone(report["consumedMicroampHours"])

    def test_two_far_apart_samples_do_not_establish_continuous_three_hour_baseline(self):
        samples = [{"kind": "resource_sample", "elapsedSeconds": i, "isEmulator": False,
            "externallyPowered": False, "batteryChargeMicroampHours": 100000-i,
            "totalPssBytes": 1000, "batteryTemperatureCelsius": 30.} for i in (0, 10800)]
        report = baseline.summarize_resources(samples)
        self.assertEqual("missing_evidence", report["status"])
        self.assertIn("continuous_sampling", report["missingEvidence"])
        self.assertIsNone(report["consumedMicroampHours"])

    def test_angle_handles_wrap_and_declination_not_planar_hypot(self):
        self.assertAlmostEqual(2, baseline.angle(359, 0, 1, 0), places=8)
        self.assertLess(baseline.angle(0, 89, 180, 89), 2.001)


class IndexGeneratorTest(unittest.TestCase):
    def test_resources_are_deterministic_and_identity_changes_with_geometry(self):
        stars = [(40., 20., 1., 1), (30., 25., 1.2, 2), (50., 16., 1.4, 3), (35., 10., 1.6, 4), (47., 29., 1.8, 5)]
        first, metadata = generator.generate(stars, 1); second, _ = generator.generate(stars, 1)
        self.assertEqual(first, second); self.assertEqual(metadata["bytes"], len(first))
        changed = list(stars); changed[0] = (41., 20., 1., 1)
        self.assertNotEqual(generator.fingerprint(stars), generator.fingerprint(changed))


if __name__ == "__main__": unittest.main()
