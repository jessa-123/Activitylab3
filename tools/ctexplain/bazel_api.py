# Lint as: python3
"""API for Bazel calls for config, cquery, and required fragment info.

There's no Python Bazel API so we invoke Bazel as a subprocess.
"""
import json
import os
import re
import subprocess
from tools.ctexplain.types import Configuration
from tools.ctexplain.types import ConfiguredTarget
from tools.ctexplain.types import HostConfiguration
from tools.ctexplain.types import NullConfiguration
from typing import Tuple

def run_bazel_in_client(args):
    """Calls bazel within the current workspace. For production use.

    Tests use an alternative invoker that goes through test infrastructure.
    """
    result = subprocess.run(
        ["bazel"] + args,
        cwd=os.getcwd(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE)
    return [
        result.returncode,
        result.stdout.decode("utf-8").split(os.linesep),
        result.stderr]
      
class BazelApi():
  def __init__(self, run_bazel = run_bazel_in_client):
    self.run_bazel = run_bazel

  def cquery(self, args):
      """Calls cquery with the given arguments.

      Args:
        args: A list of cquery command-line arguments, one argument per entry.

      Returns:
        (success: bool, stderr: str, cts: Tuple[ConfiguredTarget]), where
        success is True iff the query succeeded, stderr contains the query's
        stderr (regardless of success value), and cts is the configured targets
        found by the query if successful, empty otherwise.
      """
      base_args = ["cquery", "--show_config_fragments=transitive"]
      (returncode, stdout, stderr) = self.run_bazel(base_args + args)
      if returncode is not 0:
          return (False, stderr, ())
      
      cts = set()
      for line in stdout:
        ctinfo = _parse_cquery_result_line(line)
        if ctinfo is not None:
            cts.add(ctinfo)
             
      return (True, stderr, tuple(cts))

  def get_config(self, config_hash):
    """Calls "bazel config" with the given config hash.

    Args:
      config_hash: A config hash as reported by "bazel cquery".

    Returns:
      A types.Configuration with the matching configuration or None if no match
      is found.

    Raises:
      ValueError on any parsing problems.
    """
    if config_hash == "HOST":
      return HostConfiguration()
    elif config_hash == "null":
      return NullConfiguration()

    base_args = ["config", "--output=json"]
    (returncode, stdout, stderr) = self.run_bazel(base_args + [config_hash])
    if returncode != 0:
        raise ValueError("Could not get config: " + stderr)
    config_json = json.loads(os.linesep.join(stdout))
    fragments = [
        fragment["name"].split(".")[-1]
        for fragment in config_json["fragments"]
    ]
    options = {
        entry["name"].split(".")[-1]: entry["options"]
        for entry in config_json["fragmentOptions"]
    }
    return Configuration(fragments, options)
  

##############################################
# Regex patterns for matching cquery results:

# Label: "//" followed by one or more non-space characters.
_label_pattern = "(\/\/[\S]+)"  # pylint: disable=anomalous-backslash-in-string
# Config hash: one or more non-")" characters surrounded by "()".
_config_pattern = "\(([^\)]+)\)"  # pylint: disable=anomalous-backslash-in-string
# Required fragments: zero or more non-"]" characters surrounded by "[]".
_fragments_pattern = "\[([^\]]*)\]"  # pylint: disable=anomalous-backslash-in-string

# The required fragments pattern is optional. Null-configured targets don't list
# required fragments.
_cquery_line_matcher = re.compile(
    f"{_label_pattern} {_config_pattern}( {_fragments_pattern})?")

##############################################

# TODO(gregce): have cquery --output=jsonproto support --show_config_fragments
# so we can replace all this regex parsing with JSON reads.
def _parse_cquery_result_line(line):
  """Converts a cquery output line to a ConfiguredTargetInfo.

  Expected input is:

      "<label> (<config hash>) [configFragment1, configFragment2, ...]"

  or:
      "<label> (null)"

  Args:
    line: The expected input.

  Returns:
    Corresponding ConfiguredTarget if the line matches else None.
  """
  match = _cquery_line_matcher.match(line)
  if not match:
    return None
  transitive_fragments = match.group(4).split(", ") if match.group(4) else []
  return ConfiguredTarget(
      label=match.group(1),
      config=None, # Not yet available: we'll need `bazel config` to get this.
      config_hash=match.group(2),
      transitive_fragments=tuple(transitive_fragments))
