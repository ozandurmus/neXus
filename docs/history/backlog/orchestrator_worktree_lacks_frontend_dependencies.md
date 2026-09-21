# Orchestrator worktrees carry no frontend node_modules, so every UI dispatch reports BLOCKED

status: planned · target: 

NXS-LOCAL-0357 returned BLOCKED with the implementation complete but unvalidated, because npm --prefix ui2/frontend test could not start: vitest is not installed in a freshly created worktree. The PO session linked the main checkout's node_modules into the worktree, ran the same plan, and found two failing assertions the worker could not have seen (stale filter-count assertions, and a fetch stub on the wrong job path). Any UI movement whose validation plan calls npm test will report BLOCKED the same way, which silently moves validation from the worker to the PO session. Worktree creation should link or install ui2/frontend dependencies.
