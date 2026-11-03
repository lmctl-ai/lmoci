"""Resolve the locally installed lmctl compiler for Python test drivers."""
from pathlib import Path
import os
import shutil


def command(executable: str) -> list[str]:
    """Return an argv prefix for the compiler on POSIX and Windows."""
    path = Path(executable).resolve()
    if os.name == "nt":
        node_modules = next((parent for parent in path.parents if parent.name.lower() == "node_modules"), None)
        if node_modules is not None:
            entry = node_modules / "@lmctl-ai" / "lmctl" / "dist" / "cli" / "lmscript.js"
            if entry.is_file():
                node = shutil.which("node")
                if node is None:
                    raise RuntimeError("Node.js is required to run the local lmscript package")
                return [node, str(entry)]
        if path.suffix.lower() in (".cmd", ".bat"):
            raise RuntimeError(f"cannot locate the local lmctl package for {path}")
    return [str(path)]
