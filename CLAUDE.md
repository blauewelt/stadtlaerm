# Stadtlärm — standing instructions for Claude sessions and agents

Read before changing anything. These rules apply to the main session and to every
subagent it launches; the main session copies the agent rules into each agent prompt.

## For every session

1. **Never present choice dialogs** (AskUserQuestion or any multiple-choice widget): they
   break on the phone. Lay options out in prose, name the pick, proceed, keep it reversible.
2. **Links are clickable markdown, one per line**, and verified by opening them before they
   are posted. Never construct a URL from a pattern.
3. **Spell out every acronym or code name** the first time it appears, in one plain sentence.
4. **Privacy is the product.** No change to the public app, the server or the website may
   weaken what `PRIVACY.md` promises without changing `PRIVACY.md`, `README.md` and the
   website in the same commit. `server/DESIGN.md` §2 is the privacy contract of the map.

## For subagents (and for the main session when it does the work itself)

5. **Keep a `WORKLOG.md` in the worktree root.** One line per decision or deviation, with
   the reason, as you go: what was ambiguous, what you chose, what you could not verify,
   anything you tried that failed. The harness does not persist an agent's reasoning, so
   this file is the only place a reviewer can read *why*; commit it with the work. Format:
   `- HH:MM — <what> — <why>`.
6. **No web access from agents.** Do not use WebFetch, WebSearch, curl, wget, or any
   script that fetches a URL, except package registries (npm, pip) and the project's own
   git remote. If you need a fact from the web, write it in `WORKLOG.md` as an open
   question and continue with a clearly marked placeholder; the main session will fetch it
   with the user's approval. A fetch that the harness declined or that timed out waiting
   for approval is **never** retried by other means (curl, a different URL, a mirror).
7. **Stay in your worktree.** Read other clones (e.g. `/home/claude/blauewelt/earth`) only
   when the prompt points you there; write only inside your worktree.
8. **Report facts, not reassurance:** exact test commands with pass/fail counts, what was
   not verified and why, every deviation from the spec you were given.
9. Commit messages end with the attribution lines the session provides. Push your branch;
   do not open pull requests unless asked.
