# Workflow

## Git / PR
- Wants the agent to carry git work end-to-end on a short instruction ("커밋 & PR"): create a feature branch, commit, push, and open the PR autonomously rather than stopping for confirmation. Confidence: 0.6
- Works on a personal fork and opens PRs against the upstream repository; when direct upstream push is denied, pushes to the fork remote and opens a cross-repo PR from the fork branch. Confidence: 0.7
- Keeps each commit/PR scoped to only the files touched for the current task; leaves unrelated pre-existing working-tree changes unstaged. Confidence: 0.6
- When syncing/merging upstream, favors adopting the upstream (origin) implementation wholesale over keeping their own parallel branch implementation, even when it means dropping their local design; expects conflicts resolved to the origin side rather than hand-merging both. Confidence: 0.55
- Tracks whether the fork feature branch has actually been synced with upstream (fetch vs. merge/pull), and expects the agent to answer such questions with a definitive verdict backed by real repo state (branch tracking, reflog/FETCH_HEAD, divergence counts) rather than speculation. Confidence: 0.5

## Scope
- Expects the agent to stay within the elements/targets explicitly requested rather than broadening the change to other parts of the screen; disengages/outputs feel like a miss when scope is over-extended. Confidence: 0.5

## Security Review
- Distinguishes theoretical/hardening weaknesses from actually exploitable vulnerabilities: when reviewing for security, expects a direct verdict on whether an attacker can really exploit it (e.g. forcibly acquire admin privileges) rather than a list of best-practice concerns. Confidence: 0.5

## Verification
- The user's deployed app (Firebase Hosting) can be newer than the local checkout; when local code analysis contradicts observed live behavior, expects the agent to reconcile against the actual deployed artifact (live URL, deployed bundle, or remote branch such as origin/main) instead of trusting a possibly-stale local working tree. Confidence: 0.55
