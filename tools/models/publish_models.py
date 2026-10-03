# /// script
# requires-python = ">=3.11"
# dependencies = ["huggingface_hub>=0.34"]
# ///
"""Publish the on-device models the app downloads, and write the model catalog.

    uv run tools/models/publish_models.py            # verify, upload what changed, write catalogs
    uv run tools/models/publish_models.py --dry-run  # verify and write the bundled catalog only

The app downloads three models from Hugging Face (Settings → Text recognition / Item extraction):

- GLM-OCR GGUF pair (llama.cpp) and Granite 4.0 350M (LiteRT-LM) straight from their upstream repos,
  pinned to a commit. Nothing is re-hosted.
- GLM-OCR LiteRT (experimental) from our own repo, because those files are our conversion
  (model-assets/huggingface/convert-glm-ocr-litert.sh). They are uploaded from build/glm-ocr-litert/
  after checking them against the conversion manifest.

catalog.json lists every file with a URL pinned to a commit SHA, its sha256 and size, and where the
app stores it. It is written to the repo's main branch (the app refreshes it at most once a day) and
to app/src/main/assets/model_catalog.json (the copy the app ships with). Republishing unchanged files
uploads nothing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from huggingface_hub import CommitOperationAdd, HfApi, hf_hub_download

ROOT = Path(__file__).resolve().parents[2]
REPO_ID = "paulbauriegel/digital-fridge-models"
LITERT_DIR = ROOT / "build" / "glm-ocr-litert"
LITERT_MANIFEST = LITERT_DIR / "glm_ocr_litert_conversion_manifest.json"
CONVERT_SCRIPT = ROOT / "model-assets" / "huggingface" / "convert-glm-ocr-litert.sh"
ASSET_CATALOG = ROOT / "app" / "src" / "main" / "assets" / "model_catalog.json"
LITERT_PREFIX = "glm-ocr-litert"
CATALOG_FORMAT = 1

# Upstream sources, pinned. Bump a revision deliberately and republish.
GGUF_REPO, GGUF_REVISION = "ggml-org/GLM-OCR-GGUF", "65a42de1148dbed2297e922b5dbc7d9b70c36578"
GRANITE_REPO, GRANITE_REVISION = "litert-community/granite-4.0-350m-litert-lm", "6f1e9bce89b174930a79de82d0dcdede708f8c34"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def resolve_url(repo: str, revision: str, path: str) -> str:
    return f"https://huggingface.co/{repo}/resolve/{revision}/{path}"


def upstream_file(api: HfApi, repo: str, revision: str, path: str, dest_dir: str, dest_name: str) -> dict:
    """sha256 and size come from the Hub's LFS metadata for that exact revision."""
    (info,) = api.get_paths_info(repo, [path], revision=revision, expand=True)
    if info.lfs is None:
        raise SystemExit(f"{repo}@{revision}:{path} is not an LFS file")
    return {
        "url": resolve_url(repo, revision, path),
        "sha256": info.lfs.sha256,
        "bytes": info.lfs.size,
        "destDir": dest_dir,
        "destName": dest_name,
    }


def litert_artifacts() -> tuple[dict, list[dict]]:
    manifest = json.loads(LITERT_MANIFEST.read_text())
    files = []
    for key in ("vision_encoder", "combined_decoder"):
        artifact = manifest["artifacts"][key]
        path = LITERT_DIR / artifact["file"]
        if not path.exists() or path.stat().st_size != artifact["bytes"]:
            raise SystemExit(f"{path} is missing or has the wrong size; rerun the conversion")
        print(f"hashing {path.name} …", flush=True)
        if sha256(path) != artifact["sha256"]:
            raise SystemExit(f"{path} does not match the conversion manifest")
        files.append({"local": path, "name": artifact["file"], "sha256": artifact["sha256"], "bytes": artifact["bytes"]})
    return manifest, files


def license_text(manifest: dict) -> str:
    """The upstream weights declare MIT in their model card but ship no LICENSE file; record that."""
    model = manifest["model"]
    return f"""The GLM-OCR LiteRT files in this repository are converted from {model['repo']} (revision
{model['revision']}), whose model card declares the MIT licence:
https://huggingface.co/{model['repo']}

MIT License

Copyright (c) the GLM-OCR authors (Z.ai)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
"""


def model_card(manifest: dict) -> str:
    model = manifest["model"]
    conv = manifest["converter"]
    return f"""---
license: mit
base_model: {model['repo']}
tags:
- litert
- tflite
- ocr
- on-device
---

# Digital Fridge models

Models downloaded by the Digital Fridge Android app
([paulbauriegel/digital-fride-app](https://github.com/paulbauriegel/digital-fride-app)).
`catalog.json` on `main` lists every file the app can download, each pinned to a commit with its
sha256. The GLM-OCR GGUF pair and Granite 4.0 350M are downloaded from their upstream repos; only the
LiteRT conversion below is hosted here.

## `{LITERT_PREFIX}/`: GLM-OCR for LiteRT (experimental)

A conversion of [{model['repo']}](https://huggingface.co/{model['repo']}) at revision
`{model['revision']}` for LiteRT {conv.get('ai_edge_litert')} on Android: a fixed 644 × 952 vision
encoder (`glm_ocr_vision_68x46_fp32.tflite`) and a cached multimodal decoder
(`glm_ocr_multimodal_decoder_combined_fp16.tflite`, float16 weights). Converted with litert-torch
{conv.get('litert_torch')} and torch {conv.get('torch')} by `convert-glm-ocr-litert.sh` (included);
`glm_ocr_litert_conversion_manifest.json` records shapes, hashes and validation results.

Licence: MIT, as declared by the original GLM-OCR model card (see `LICENSE`).
"""


def catalog_json(api: HfApi, litert_revision: str, litert_files: list[dict]) -> dict:
    return {
        "formatVersion": CATALOG_FORMAT,
        "models": [
            {
                "id": "glm_ocr",
                "name": "GLM-OCR (llama.cpp)",
                "license": "MIT",
                "source": f"https://huggingface.co/{GGUF_REPO}",
                "files": [
                    upstream_file(api, GGUF_REPO, GGUF_REVISION, "GLM-OCR-Q8_0.gguf", "models/glm-ocr", "GLM-OCR.gguf"),
                    upstream_file(api, GGUF_REPO, GGUF_REVISION, "mmproj-GLM-OCR-Q8_0.gguf", "models/glm-ocr", "mmproj-GLM-OCR.gguf"),
                ],
            },
            {
                "id": "glm_ocr_litert",
                "name": "GLM-OCR LiteRT (experimental)",
                "license": "MIT",
                "source": f"https://huggingface.co/{REPO_ID}",
                "files": [
                    {
                        "url": resolve_url(REPO_ID, litert_revision, f"{LITERT_PREFIX}/{f['name']}"),
                        "sha256": f["sha256"],
                        "bytes": f["bytes"],
                        "destDir": "models/glm-ocr-litert",
                        "destName": f["name"],
                    }
                    for f in litert_files
                ],
            },
            {
                "id": "granite",
                "name": "Granite 4.0 350M (LiteRT-LM)",
                "license": "Apache-2.0",
                "source": f"https://huggingface.co/{GRANITE_REPO}",
                "files": [
                    upstream_file(api, GRANITE_REPO, GRANITE_REVISION, "granite-4.0-350m_q8_ekv1280.litertlm",
                                  "models/granite", "granite-4.0-350m_q8_ekv1280.litertlm"),
                ],
            },
        ],
    }


def published_litert_revision(api: HfApi, files: list[dict]) -> str | None:
    """The main-branch commit if every LiteRT file there already matches, else None."""
    if not api.repo_exists(REPO_ID):
        return None
    paths = [f"{LITERT_PREFIX}/{f['name']}" for f in files]
    infos = {i.path: i for i in api.get_paths_info(REPO_ID, paths, expand=True)}
    for f, p in zip(files, paths):
        info = infos.get(p)
        if info is None or info.lfs is None or info.lfs.sha256 != f["sha256"]:
            return None
    # The commit that last touched the LiteRT files; catalog commits on top don't change them.
    commits = api.list_repo_commits(REPO_ID)
    for commit in commits:
        if commit.title.startswith("GLM-OCR LiteRT"):
            return commit.commit_id
    return commits[-1].commit_id


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="verify and write the bundled catalog, upload nothing")
    args = parser.parse_args()

    api = HfApi()
    manifest, litert_files = litert_artifacts()
    revision = published_litert_revision(api, litert_files)
    if args.dry_run:
        revision = revision or "UNPUBLISHED"
    elif revision is None:
        api.create_repo(REPO_ID, repo_type="model", private=False, exist_ok=True)
        ops = [CommitOperationAdd(f"{LITERT_PREFIX}/{f['name']}", str(f["local"])) for f in litert_files]
        ops += [
            CommitOperationAdd(f"{LITERT_PREFIX}/{LITERT_MANIFEST.name}", str(LITERT_MANIFEST)),
            CommitOperationAdd(f"{LITERT_PREFIX}/{CONVERT_SCRIPT.name}", str(CONVERT_SCRIPT)),
            CommitOperationAdd("LICENSE", license_text(manifest).encode()),
            CommitOperationAdd("README.md", model_card(manifest).encode()),
        ]
        info = api.create_commit(REPO_ID, operations=ops, commit_message="GLM-OCR LiteRT conversion")
        revision = info.oid
        print(f"uploaded LiteRT files as {revision}")
    else:
        print(f"LiteRT files already published at {revision}")

    catalog = catalog_json(api, revision, litert_files)
    text = json.dumps(catalog, indent=2) + "\n"
    ASSET_CATALOG.parent.mkdir(parents=True, exist_ok=True)
    ASSET_CATALOG.write_text(text)
    print(f"wrote {ASSET_CATALOG.relative_to(ROOT)}")
    if args.dry_run:
        return

    current = None
    try:
        current = Path(hf_hub_download(REPO_ID, "catalog.json")).read_text()
    except Exception:  # first publish
        pass
    if current == text:
        print("catalog.json already up to date")
        return
    api.create_commit(
        REPO_ID,
        operations=[CommitOperationAdd("catalog.json", text.encode()), CommitOperationAdd("README.md", model_card(manifest).encode())],
        commit_message="Update catalog.json",
    )
    print(f"catalog: https://huggingface.co/{REPO_ID}/blob/main/catalog.json")


if __name__ == "__main__":
    sys.exit(main())
