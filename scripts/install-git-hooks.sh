#!/bin/sh
# Points git at the tracked .husky/ scripts through a relative core.hooksPath, so every worktree runs the hooks of
# its own branch. Run only through the Gradle task: ./gradlew installGitHooks
set -eu
root="$1"
if ! git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "installGitHooks: $root is not a git work tree, so no hooks were installed." >&2
    exit 0
fi
for hook in commit-msg pre-commit; do
    [ -x "$root/.husky/$hook" ] || { echo "installGitHooks: .husky/$hook is missing or not executable." >&2; exit 1; }
done
current="$(git -C "$root" config --local --get core.hooksPath || true)"
case "$current" in
    "" | .husky | */.husky) ;;
    *) echo "installGitHooks: core.hooksPath is '$current'. Unset it or set it to .husky, then rerun." >&2; exit 1 ;;
esac
git -C "$root" config --local core.hooksPath .husky
echo "Git hooks: core.hooksPath is .husky; every worktree runs its own .husky/ scripts."
