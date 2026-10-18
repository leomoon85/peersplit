"""Generate the offline in-app policy from the root Markdown policy.
Run with --check in release checks to detect stale packaged policy text.
"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
text = (root / "PRIVACY_POLICY.md").read_text(encoding="utf-8")
text = re.sub(r"^#+ ", "", text, flags=re.MULTILINE)
text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1 (\2)", text)
text = text.replace("**", "").replace("`", "")
target = root / "app/src/main/assets/privacy_policy.txt"
if "--check" in sys.argv:
    if not target.exists() or target.read_text(encoding="utf-8") != text:
        raise SystemExit("Packaged privacy policy is stale. Run scripts/sync_privacy_policy.py")
    print("Packaged privacy policy matches PRIVACY_POLICY.md")
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")
    print("Updated packaged privacy policy")
