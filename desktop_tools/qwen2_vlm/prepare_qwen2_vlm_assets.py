from pathlib import Path
import os
import shutil

import onnx
from huggingface_hub import hf_hub_download
from onnx import TensorProto, helper


REPO_ID = "llava-hf/llava-onevision-qwen2-0.5b-si-hf"
ASSET_FILES = {
    "onnx/decoder_model_merged_q4.onnx": "decoder_model_merged_q4.onnx",
    "onnx/embed_tokens_int8.onnx": "embed_tokens_int8.onnx",
    "onnx/vision_encoder_q4.onnx": "vision_encoder_q4.onnx",
    "tokenizer.json": "tokenizer.json",
}


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def patch_decoder_to_last_token_logits(decoder_path: Path) -> None:
    model = onnx.load(decoder_path, load_external_data=False)
    graph = model.graph
    if graph.output and graph.output[0].name == "logits_last_token":
        return

    graph.initializer.append(
        helper.make_tensor("last_token_gather_index", TensorProto.INT64, [1], [-1])
    )
    graph.node.append(
        helper.make_node(
            "Gather",
            inputs=["logits", "last_token_gather_index"],
            outputs=["logits_last_token"],
            axis=1,
            name="GatherLastTokenLogitsForAndroid",
        )
    )
    old_output = graph.output[0]
    graph.output.remove(old_output)
    graph.output.insert(
        0,
        helper.make_tensor_value_info(
            "logits_last_token",
            TensorProto.FLOAT,
            ["batch_size", 1, 152000],
        ),
    )
    onnx.checker.check_model(model)
    temp_path = decoder_path.with_suffix(decoder_path.suffix + ".tmp")
    onnx.save(model, temp_path)
    os.replace(temp_path, decoder_path)


def main() -> None:
    assets_dir = repo_root() / "app" / "src" / "main" / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)

    for remote_path, asset_name in ASSET_FILES.items():
        source = Path(hf_hub_download(REPO_ID, remote_path))
        destination = assets_dir / asset_name
        shutil.copy2(source, destination)
        print(f"copied {remote_path} -> {destination}")

    decoder_path = assets_dir / "decoder_model_merged_q4.onnx"
    patch_decoder_to_last_token_logits(decoder_path)
    print(f"patched decoder output for Android heap safety: {decoder_path}")


if __name__ == "__main__":
    main()
