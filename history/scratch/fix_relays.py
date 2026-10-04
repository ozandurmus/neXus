import json
import glob

for file in glob.glob("relay/NXS-LOCAL-028*.json"):
    with open(file, "r") as f:
        data = json.load(f)
        
    if "model_requested" in data["entries"][0]["report"]["recommended_reasoning"]:
        del data["entries"][0]["report"]["recommended_reasoning"]["model_requested"]
    if "provider" in data["entries"][0]["report"]["recommended_reasoning"]:
        del data["entries"][0]["report"]["recommended_reasoning"]["provider"]
    
    with open(file, "w") as f:
        json.dump(data, f, indent=2)

