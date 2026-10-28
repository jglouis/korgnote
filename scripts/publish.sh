#!/usr/bin/env bash
# Publishes a release: raises the version, runs the tests, builds the signed APK with release.sh,
# commits and tags the new version, pushes them, and puts the APK on a GitHub release.
#
#   scripts/publish.sh [options] patch|minor|major|X.Y.Z|current [DATABASE.kdbx]
#
#   patch, minor, major   the part of the current version to raise; or the new version itself
#   current               release the version the build files already say, raised by hand
#   DATABASE.kdbx         the KeePassXC database release.sh takes the key from, or KORGNOTE_KDBX
#
#   -n, --dry-run     say what would happen, and change nothing
#   --no-push         stop after the commit and the tag, and say how to push and publish them
#   --draft           make the GitHub release a draft, to look over before it goes out
#   -e, --edit-notes  edit the release notes before anything changes
#   --skip-tests      build without running the tests first
#
#   KORGNOTE_CERT_SHA256  the release key's certificate digest; the 1.0.0 key's by default
#
# Nothing changes until the plan is shown and agreed to. If the tests or the build fail, the version
# goes back to what it was; once the tag is made, a step that fails says how to carry on by hand.
set -euo pipefail

usage() { sed -n '2,/^set -e/{/^set -e/d;s/^# \{0,1\}//;p;}' "$0"; }
die() {
  echo "publish.sh: $*" >&2
  exit 1
}

dry_run=false push=true draft=false edit_notes=false tests=true
bump='' database=''
while (($#)); do
  case $1 in
    -h | --help) usage; exit 0 ;;
    -n | --dry-run) dry_run=true ;;
    --no-push) push=false ;;
    --draft) draft=true ;;
    -e | --edit-notes) edit_notes=true ;;
    --skip-tests) tests=false ;;
    -*) die "unknown option $1; see --help" ;;
    *)
      if [[ -z $bump ]]; then bump=$1
      elif [[ -z $database ]]; then database=$1
      else die "too many arguments; see --help"
      fi
      ;;
  esac
  shift
done
[[ -n $bump ]] || { usage >&2; exit 2; }
database=${database:-${KORGNOTE_KDBX:-}}
expected_cert=${KORGNOTE_CERT_SHA256:-2bb8626e0a758e5fc3284f833bfa7b81995ecb3315612489c21805ea243d0720}

cd "$(dirname "$0")/.."
android=androidApp/build.gradle.kts
desktop=composeApp/build.gradle.kts
server=server/build.gradle.kts

# --- Is this a state to release from? ----------------------------------------------------------

[[ $(git symbolic-ref --short HEAD 2>/dev/null) == master ]] || die "release from master."
git diff --quiet && git diff --cached --quiet || die "commit or set aside the changes first."
[[ -n $database ]] || $dry_run || die "which KeePassXC database holds the release key? Pass it, or set KORGNOTE_KDBX."
if $push; then
  command -v gh >/dev/null || die "the GitHub CLI, gh, is not installed; or use --no-push."
  gh auth status -h github.com >/dev/null 2>&1 || die "gh is not logged in; run 'gh auth login'."
fi
git fetch -q --tags origin
behind=$(git rev-list --count HEAD..origin/master)
((behind == 0)) || die "master is $behind commit(s) behind origin/master; pull first."

# --- The new version ---------------------------------------------------------------------------

current=$(sed -n 's/^ *versionName = "\([0-9.]*\)"$/\1/p' "$android")
code=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$android")
[[ $current =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "cannot read versionName in $android."
[[ -n $code ]] || die "cannot read versionCode in $android."
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}

raise=true
case $bump in
  current) next=$current raise=false ;;
  patch) next="$major.$minor.$((patch + 1))" ;;
  minor) next="$major.$((minor + 1)).0" ;;
  major) next="$((major + 1)).0.0" ;;
  *)
    [[ $bump =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "'$bump' is neither patch, minor, major nor X.Y.Z."
    # Numerically, part by part: 1.10.0 comes after 1.9.0.
    if ((BASH_REMATCH[1] != major ? BASH_REMATCH[1] < major :
      BASH_REMATCH[2] != minor ? BASH_REMATCH[2] < minor : BASH_REMATCH[3] <= patch)); then
      die "$bump is not after the current $current."
    fi
    next=$bump
    ;;
esac
$raise && next_code=$((code + 1)) || next_code=$code
tag=v$next
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "there is already a tag $tag."

# --- What goes in the release notes ------------------------------------------------------------

previous=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
notes=$work/notes.md
changes=$(git log --no-merges --format='- %s' ${previous:+"$previous"..}HEAD | grep -v '^- Bump the version to ' || true)
[[ -n $changes ]] || die "nothing has changed since $previous."
{
  echo "Changes since ${previous#v}:"
  echo
  echo "$changes"
} >"$notes"
if $edit_notes; then
  "${VISUAL:-${EDITOR:-vi}}" "$notes"
fi

ahead=$(git rev-list --count origin/master..HEAD)
echo "Release KorgNote $next"
if $raise; then
  echo "  version   $current -> $next, versionCode $code -> $next_code"
  echo "  tag       $tag, on a new commit that raises it"
  ((ahead += 1))
else
  echo "  version   $next, versionCode $code, as the build files already say"
  echo "  tag       $tag, on $(git log -1 --format='%h "%s"')"
fi
if $push; then
  echo "  push      master ($ahead new commit(s)) and $tag to origin"
  echo "  GitHub    $($draft && echo "draft release" || echo "release") \"KorgNote $next\" with korgnote-$next.apk"
else
  echo "  push      nothing (--no-push)"
fi
$tests || echo "  tests     skipped"
echo
sed 's/^/  | /' "$notes"
echo

if $dry_run; then
  echo "Dry run: nothing changed."
  exit 0
fi
read -rp "Go ahead? [y/N] " answer
[[ $answer == [yY]* ]] || die "stopped; nothing changed."

# --- Raise the version, test and build ---------------------------------------------------------

# Until the commit, a failure puts the files back as they were.
restore() {
  if $raise; then
    git checkout -q -- "$android" "$desktop" "$server"
    echo "publish.sh: stopped; the version is back to $current." >&2
  fi
}
trap 'restore' ERR

if $raise; then
  perl -pi -e "s/^(\s*versionCode = )$code\$/\${1}$next_code/; s/^(\s*versionName = )\"$current\"/\${1}\"$next\"/" "$android"
  perl -pi -e "s/^(\s*packageVersion = )\"$current\"/\${1}\"$next\"/" "$desktop"
  perl -pi -e "s/^(version = )\"$current\"/\${1}\"$next\"/" "$server"
  [[ $(git diff --numstat -- "$android" "$desktop" "$server" | wc -l) -eq 3 ]] ||
    { restore; die "the version is not $current in all of $android, $desktop and $server."; }
fi

if $tests; then
  ./gradlew :shared:jvmTest :composeApp:jvmTest :server:test
fi

scripts/release.sh "$database"

mkdir -p build/releases
apk=build/releases/korgnote-$next.apk
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$apk"

# An APK signed with another key cannot update the app already installed, so it must not go out.
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)
[[ -n $apksigner ]] || { restore; die "apksigner not found under $sdk/build-tools, to check the APK's key."; }
cert=$("$apksigner" verify --print-certs "$apk" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
if [[ $cert != "$expected_cert" ]]; then
  restore
  die "the APK is signed with ${cert:-no} key, not the release key $expected_cert."
fi
{
  echo
  echo "Signer #1 certificate SHA-256 digest: $cert"
} >>"$notes"

# --- Commit and tag ----------------------------------------------------------------------------

$raise && git commit -q -m "Bump the version to $next" -- "$android" "$desktop" "$server"
git tag -a "$tag" -m "KorgNote $next"
trap - ERR
cp "$notes" "build/releases/korgnote-$next-notes.md"
notes=build/releases/korgnote-$next-notes.md
echo "Tagged $tag. APK: $apk"

release=(gh release create "$tag" "$apk" --verify-tag --title "KorgNote $next" --notes-file "$notes")
$draft && release+=(--draft)

if ! $push; then
  echo
  echo "Not pushed. To publish:"
  echo "  git push --atomic origin master $tag"
  echo "  ${release[*]}"
  echo "To take it back instead:"
  if $raise; then
    echo "  git tag -d $tag && git reset --hard HEAD~1"
  else
    echo "  git tag -d $tag"
  fi
  exit 0
fi

# --- Push and publish --------------------------------------------------------------------------

# Both or neither, so the tag never names a commit origin does not have.
git push --atomic origin master "$tag" ||
  die "the push failed; the commit and $tag are only here. Retry: git push --atomic origin master $tag"
"${release[@]}" ||
  die "pushed, but the GitHub release failed. Retry: ${release[*]}"
echo "Published: $(gh release view "$tag" --json url --jq .url)"
