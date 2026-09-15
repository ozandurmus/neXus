# A source file using a NUL delimiter is binary to git and cannot be reviewed by diff

status: planned · target: ui2 service api ConfigurationController

ConfigurationController.java joins a context and a category with a literal NUL character as a delimiter, twice. Java accepts it and the tests pass, but git classifies the file as binary, so every diff of it renders as 'Bin 9767 -> 11059 bytes' and no review of a change to that file is possible -- on a pull request, in a terminal, or by an agent. Found on 2026-09-15 while reviewing NXS-LOCAL-0190, whose change to that file had to be accepted without reading it. Pre-existing on main, not introduced by that movement. Any delimiter that cannot appear in a context or category name and is not a control character -- a unit separator written as an escape, or a two-character sequence -- restores reviewability without changing behaviour. Check for other source files with the same pattern before assuming this is the only one.
