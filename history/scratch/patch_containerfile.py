import re

file_path = "ui2/Containerfile"
with open(file_path, "r") as f:
    content = f.read()

old_str = "COPY ui2/frontend/ ./\nRUN npm run build"
new_str = "COPY ui2/frontend/ ./\nCOPY docs/ /build/docs/\nRUN npm run build"

content = content.replace(old_str, new_str)

with open(file_path, "w") as f:
    f.write(content)

