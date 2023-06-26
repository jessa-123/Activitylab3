"""Smoke test for packages_used."""

import json
import os
import unittest


def read_data_file(basename):
  path = os.path.join(
      os.getenv("TEST_SRCDIR"),
      "io_bazel/tools/compliance",
      basename)
  with open(path, "rt", encoding="utf-8") as f:
    return f.read()


class PackagesUsedTest(unittest.TestCase):

  def test_found_key_licenses(self):
    raw_json = read_data_file("bazel_packages.json")
    content = json.loads(raw_json)
    found_top_level_license = False
    found_zlib = False
    for l in content["licenses"]:
      if l["label"] == "//:license":
        found_top_level_license = True
      if l["label"] == "//third_party/zlib:license":
        found_zlib = True
    self.assertTrue(found_top_level_license)
    self.assertTrue(found_zlib)

  def test_found_remote_packages(self):
    raw_json = read_data_file("bazel_packages.json")
    content = json.loads(raw_json)
    self.assertIn(
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_proto",
        content["packages"])



if __name__ == "__main__":
  unittest.main()
