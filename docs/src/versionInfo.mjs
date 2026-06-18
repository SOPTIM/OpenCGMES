// Build-time version + build metadata resolved from git state.
//
// Used by docusaurus.config.js to (a) inject the latest released version of each component into
// install snippets via the remark-versions plugin, and (b) show which commit/date the docs were
// built from. Because the docs are built from `main`, the "latest version" is the newest release
// tag (`<component>-vX.Y.Z`) reachable from HEAD.
//
// Everything degrades gracefully: if git is unavailable or no tags exist, component versions fall
// back to `0.0.0-SNAPSHOT` (matching the in-repo placeholder) and build info to a local default.
// An env override `OPENCGMES_<COMPONENT>_VERSION` (e.g. OPENCGMES_CIMXML_VERSION) wins if set.
//
// The tag→version logic lives in scripts/compute-version.sh (shared with the build/release
// workflows); this module just calls it with `--released` so the docs and the build use one source
// of truth.

import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const computeVersionScript = path.join(repoRoot, 'scripts', 'compute-version.sh');

function git(args) {
  return execSync(`git ${args}`, {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'ignore'],
  })
    .toString()
    .trim();
}

function latestReleaseVersion(component) {
  const override = process.env[`OPENCGMES_${component.toUpperCase()}_VERSION`];
  if (override) return override.trim();

  try {
    const version = execSync(`bash "${computeVersionScript}" ${component} --released`, {
      cwd: repoRoot,
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim();
    return version || '0.0.0-SNAPSHOT';
  } catch {
    return '0.0.0-SNAPSHOT';
  }
}

function buildInfo() {
  const info = {
    shortSha: '',
    fullSha: '',
    date: new Date().toISOString().slice(0, 10),
    branch: process.env.GITHUB_REF_NAME || '',
  };
  try {
    info.shortSha = git('rev-parse --short HEAD');
    info.fullSha = git('rev-parse HEAD');
    info.date = git('show -s --format=%cs HEAD'); // committer date, YYYY-MM-DD
    if (!info.branch) info.branch = git('rev-parse --abbrev-ref HEAD');
  } catch {
    // leave defaults
  }
  return info;
}

let cached;

/** Resolve once per build. Returns { versions: {cimxml, cimvocabcheck, cimnotebook}, build }. */
export default function versionInfo() {
  if (cached) return cached;
  cached = {
    versions: {
      cimxml: latestReleaseVersion('cimxml'),
      cimvocabcheck: latestReleaseVersion('cimvocabcheck'),
      cimnotebook: latestReleaseVersion('cimnotebook'),
    },
    build: buildInfo(),
  };
  return cached;
}
