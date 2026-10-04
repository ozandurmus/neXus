import re
with open("ui2/frontend/src/shell/TopAppBar.tsx", "r") as f:
    code = f.read()

# Add Tooltip to imports
if "import Tooltip" not in code:
    code = code.replace("import Typography from \"@mui/material/Typography\";", "import Typography from \"@mui/material/Typography\";\nimport Tooltip from \"@mui/material/Tooltip\";")

# Find the role line
search = '<Typography variant="body2">{session.roleTokens.join(", ")}</Typography>'

replacement = '''<Tooltip title={session.roleTokens.join(", ")}>
                <Typography variant="body2" sx={{ cursor: "default" }}>
                  {session.roleTokens.length > 2
                    ? `${session.roleTokens[0]} (+${session.roleTokens.length - 1} roles)`
                    : session.roleTokens.join(", ")}
                </Typography>
              </Tooltip>'''

code = code.replace(search, replacement)

with open("ui2/frontend/src/shell/TopAppBar.tsx", "w") as f:
    f.write(code)
print("patched")
