#!/usr/bin/env bash
# Everything that has to be true before a version is tagged.
#
#   ./scripts/release-preflight.sh 0.1.3
#
# This exists because a tag is the one thing that cannot be taken back. A
# repository, a module proxy or somebody's lock file will have the bad version
# within minutes, and the only remedy is to burn a number and publish another.
# The Go library burned three that way; every check below is one of those
# mistakes, written down.
#
# Nothing here is pushed, tagged or published. It answers one question: would
# tagging this commit produce a release that works?
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: $0 <version>   e.g. $0 0.1.3" >&2
  exit 2
fi
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "a version is three numbers: $VERSION" >&2
  exit 2
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

FAILED=0
ok()   { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILED=1; }

echo "==> the working tree"

if [ -n "$(git status --porcelain)" ]; then
  fail "there are uncommitted changes; a tag points at a commit, not at a desk"
else
  ok "clean"
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "main" ]; then
  fail "on $BRANCH rather than main"
else
  ok "on main"
fi

git fetch --quiet origin main
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
  fail "HEAD is not origin/main; push first, so the tag names a commit others have"
else
  ok "up to date with origin/main"
fi

echo "==> the version"

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  fail "the tag v$VERSION already exists here"
elif git ls-remote --exit-code --tags origin "v$VERSION" >/dev/null 2>&1; then
  # Checked against the remote rather than only locally: a shallow or stale
  # clone knows nothing about a tag somebody else pushed, and this is exactly
  # how a version gets published twice.
  fail "the tag v$VERSION already exists on origin"
else
  ok "v$VERSION is unused"
fi

# The changelog is the release notes and the only record of why a version
# exists. "Unreleased" in the heading means nobody wrote them.
if grep -q "^## $VERSION — " CHANGELOG.md; then
  ok "CHANGELOG.md has a section for $VERSION"
else
  fail "CHANGELOG.md has no '## $VERSION — <date>' section"
fi

echo "==> continuous integration on this commit"

if command -v gh >/dev/null 2>&1; then
  CONCLUSION="$(gh run list --branch main --limit 20 --json headSha,name,conclusion \
      --jq "[.[] | select(.headSha == \"$(git rev-parse HEAD)\" and .name == \"ci\")][0].conclusion" \
      2>/dev/null || echo "")"
  case "$CONCLUSION" in
    success) ok "ci is green on this commit" ;;
    "" | null) fail "no finished ci run for this commit yet" ;;
    *) fail "ci concluded '$CONCLUSION' on this commit" ;;
  esac
else
  fail "gh is not installed, so ci cannot be checked"
fi

echo "==> the build, at the version being released"

# A copy with the version set, exactly as the publish script does it. The
# release version belongs in the tag rather than in a commit that has to be
# reverted afterwards -- and the jars carry it in their manifest, so a jar built
# from an unmodified tree would report 0.1.0-SNAPSHOT to anybody who asked.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
tar -c --exclude='target' --exclude='.git' --exclude='node_modules' -C "$REPO_ROOT" . \
  | tar -x -C "$WORK_DIR"

(
  cd "$WORK_DIR"
  mvn -q -B versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false
  mvn -B -DskipTests -DskipITs package
) > "$WORK_DIR/build.log" 2>&1 || {
  fail "the build failed at $VERSION"
  tail -20 "$WORK_DIR/build.log" >&2
}

CLI="$WORK_DIR/library/target/acemq-workload.jar"
STUDIO="$WORK_DIR/studio/target/acemq-workloads-studio.jar"

if [ -f "$CLI" ]; then
  ok "the command line jar was built"
  REPORTED="$(java -jar "$CLI" --version 2>/dev/null | tr -d '\r')"
  if [ "$REPORTED" = "acemq-workload $VERSION" ]; then
    ok "it reports '$REPORTED'"
  else
    # The Go library shipped a version that called itself "dev". Whatever the
    # jar says is what somebody will quote in a bug report.
    fail "it reports '$REPORTED' rather than 'acemq-workload $VERSION'"
  fi
else
  fail "no command line jar"
fi

if [ -f "$STUDIO" ]; then
  ok "the studio jar was built"

  PORT=8751
  java -jar "$STUDIO" --server.port="$PORT" \
      --acemq.studio.database="$WORK_DIR/preflight.db" > "$WORK_DIR/studio.log" 2>&1 &
  STUDIO_PID=$!
  SERVED=0
  for _ in $(seq 1 40); do
    if curl -fsS "http://127.0.0.1:$PORT/" -o "$WORK_DIR/index.html" 2>/dev/null; then
      SERVED=1
      break
    fi
    sleep 1
  done
  kill "$STUDIO_PID" 2>/dev/null || true
  wait "$STUDIO_PID" 2>/dev/null || true

  if [ "$SERVED" = 1 ] && grep -q "<div id=\"root\"" "$WORK_DIR/index.html"; then
    # The interface is built by a separate toolchain and copied in. A jar that
    # starts and serves no interface is the failure this catches.
    ok "it starts and serves the interface"
  else
    fail "the studio jar did not serve its interface"
    tail -20 "$WORK_DIR/studio.log" >&2
  fi
else
  fail "no studio jar"
fi

echo
if [ "$FAILED" != 0 ]; then
  echo "not ready to tag $VERSION" >&2
  exit 1
fi

cat <<DONE
ready to tag $VERSION

  git tag -a v$VERSION -m "$VERSION"
  git push origin v$VERSION
  ../scripts/publish-maven-repo.sh $VERSION acemq-java-amqp-workloads

The release's jars must be built with the version set, the way this script just
did it, or they report a snapshot version to whoever runs them.
DONE
