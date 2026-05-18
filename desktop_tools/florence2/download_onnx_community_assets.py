from __future__ import annotations

import argparse
import urllib.request
from pathlib import Path


REPO_ID = "onnx-community/Florence-2-base-ft"
BASE_URL = f"https://huggingface.co/{REPO_ID}/resolve/main"

COMMON_FILES = [
    "config.json",
    "generation_config.json",
    "preprocessor_config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "vocab.json",
    "merges.txt",
    "added_tokens.json",
    "special_tokens_map.json",
]

VARIANTS = {
    "int4": [
        "onnx/encoder_model_q4.onnx",
        "onnx/decoder_model_merged_q4.onnx",
        "onnx/embed_tokens_q4.onnx",
        "onnx/vision_encoder_q4.onnx",
    ],
    "int8": [
        "onnx/encoder_model_int8.onnx",
        "onnx/decoder_model_merged_int8.onnx",
        "onnx/embed_tokens_int8.onnx",
        "onnx/vision_encoder_int8.onnx",
    ],
}


def download_file(relative_path: str, output_root: Path) -> None:
    target = output_root / relative_path
    if target.exists() and target.stat().st_size > 0:
        print(f"skip {target}")
        return

    target.parent.mkdir(parents=True, exist_ok=True)
    temp_target = target.with_suffix(target.suffix + ".download")
    url = f"{BASE_URL}/{relative_path}"
    print(f"download {url}")
    urllib.request.urlretrieve(url, temp_target)
    temp_target.replace(target)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Download Florence-2-base-ft INT4 and INT8 ONNX assets from Hugging Face.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("florence-2-base-ft"),
        help="Directory where variant folders will be created.",
    )
    parser.add_argument(
        "--variant",
        choices=["int4", "int8", "all"],
        default="all",
        help="Which quantized asset set to download.",
    )
    args = parser.parse_args()

    selected_variants = VARIANTS.keys() if args.variant == "all" else [args.variant]
    for variant in selected_variants:
        variant_root = args.output_dir / variant
        for relative_path in COMMON_FILES + VARIANTS[variant]:
            download_file(relative_path, variant_root)


if __name__ == "__main__":
    main()
