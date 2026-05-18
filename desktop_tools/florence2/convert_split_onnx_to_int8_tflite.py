from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from collections.abc import Iterator
from pathlib import Path

import numpy as np
import tensorflow as tf


IMAGE_SIZE = 384
DEFAULT_BATCH_SIZE = 1
DEFAULT_PROMPT_LENGTH = 8
DEFAULT_DECODER_LENGTH = 1
DEFAULT_IMAGE_TOKEN_LENGTH = 145
DEFAULT_HIDDEN_SIZE = 1024
DEFAULT_REPRESENTATIVE_SAMPLES = 100


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def remove_existing_dir(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)


def convert_onnx_to_saved_model(onnx_path: Path, saved_model_dir: Path) -> None:
    if not onnx_path.exists():
        raise FileNotFoundError(
            f"ONNX file not found: {onnx_path}. Run "
            "`python desktop_tools/florence2/export_florence2_split_onnx.py` first."
        )

    remove_existing_dir(saved_model_dir)
    saved_model_dir.parent.mkdir(parents=True, exist_ok=True)

    command = [
        sys.executable,
        "-m",
        "onnx2tf",
        "-i",
        onnx_path.as_posix(),
        "-o",
        saved_model_dir.as_posix(),
    ]
    try:
        subprocess.run(command, check=True)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(
            "onnx2tf conversion failed. If the error mentions "
            "`float32_to_bfloat16`, reinstall the pinned dependencies with "
            "`pip install --force-reinstall -r desktop_tools/florence2/requirements.txt`."
        ) from error


def default_dim_for_name(name: str) -> int:
    lowered = name.lower()
    if "batch" in lowered:
        return DEFAULT_BATCH_SIZE
    if "height" in lowered or lowered == "h":
        return IMAGE_SIZE
    if "width" in lowered or lowered == "w":
        return IMAGE_SIZE
    if "channel" in lowered or lowered == "c":
        return 3
    if "prompt" in lowered:
        return DEFAULT_PROMPT_LENGTH
    if "decoder" in lowered:
        return DEFAULT_DECODER_LENGTH
    if "image_token" in lowered:
        return DEFAULT_IMAGE_TOKEN_LENGTH
    if "hidden" in lowered or "embed" in lowered:
        return DEFAULT_HIDDEN_SIZE
    return 1


def concrete_shape(tensor_spec: tf.TensorSpec) -> tuple[int, ...]:
    shape: list[int] = []
    for index, dim in enumerate(tensor_spec.shape.as_list()):
        if dim is not None:
            shape.append(dim)
            continue

        if index == 0:
            shape.append(DEFAULT_BATCH_SIZE)
        elif "pixel_values" in tensor_spec.name and index in (2, 3):
            shape.append(IMAGE_SIZE)
        elif "pixel_values" in tensor_spec.name and index == 1:
            shape.append(3)
        else:
            shape.append(default_dim_for_name(tensor_spec.name))
    return tuple(shape)


def random_float_tensor(shape: tuple[int, ...]) -> np.ndarray:
    return np.random.uniform(-1.0, 1.0, size=shape).astype(np.float32)


def random_integer_tensor(shape: tuple[int, ...], dtype: tf.dtypes.DType) -> np.ndarray:
    np_dtype = dtype.as_numpy_dtype
    if np.issubdtype(np_dtype, np.unsignedinteger):
        return np.random.randint(0, 256, size=shape, dtype=np_dtype)
    return np.random.randint(0, 1000, size=shape).astype(np_dtype)


def random_representative_value(tensor_spec: tf.TensorSpec) -> np.ndarray:
    shape = concrete_shape(tensor_spec)
    dtype = tensor_spec.dtype

    if dtype.is_floating:
        return random_float_tensor(shape).astype(dtype.as_numpy_dtype)
    if dtype.is_integer:
        return random_integer_tensor(shape, dtype)
    if dtype == tf.bool:
        return np.random.choice([False, True], size=shape)

    raise TypeError(f"Unsupported representative dtype: {tensor_spec.name} {dtype}")


def serving_input_specs(saved_model_dir: Path) -> dict[str, tf.TensorSpec]:
    loaded = tf.saved_model.load(saved_model_dir.as_posix())
    signature = loaded.signatures["serving_default"]
    _, keyword_specs = signature.structured_input_signature
    if keyword_specs:
        return dict(keyword_specs)

    input_specs = {}
    for tensor in signature.inputs:
        if tensor.dtype == tf.resource:
            continue
        name = tensor.name.split(":")[0]
        input_specs[name] = tf.TensorSpec(
            shape=tensor.shape,
            dtype=tensor.dtype,
            name=name,
        )
    return input_specs


def representative_dataset(
    input_specs: dict[str, tf.TensorSpec],
    sample_count: int,
) -> Iterator[dict[str, np.ndarray]]:
    for _ in range(sample_count):
        yield {
            name: random_representative_value(tensor_spec)
            for name, tensor_spec in input_specs.items()
        }


def convert_saved_model_to_int8_tflite(
    saved_model_dir: Path,
    tflite_path: Path,
    representative_sample_count: int,
    allow_select_tf_ops: bool,
) -> None:
    input_specs = serving_input_specs(saved_model_dir)
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir.as_posix())
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: representative_dataset(
        input_specs,
        representative_sample_count,
    )

    supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    if allow_select_tf_ops:
        supported_ops.append(tf.lite.OpsSet.SELECT_TF_OPS)
    converter.target_spec.supported_ops = supported_ops

    if all(tensor_spec.dtype.is_floating for tensor_spec in input_specs.values()):
        converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8

    tflite_model = converter.convert()
    tflite_path.parent.mkdir(parents=True, exist_ok=True)
    tflite_path.write_bytes(tflite_model)


def convert_one_model(
    onnx_path: Path,
    saved_model_dir: Path,
    tflite_path: Path,
    representative_sample_count: int,
    allow_select_tf_ops: bool,
) -> None:
    print(f"ONNX -> SavedModel: {onnx_path} -> {saved_model_dir}")
    convert_onnx_to_saved_model(onnx_path, saved_model_dir)

    print(f"SavedModel -> INT8 TFLite: {saved_model_dir} -> {tflite_path}")
    convert_saved_model_to_int8_tflite(
        saved_model_dir,
        tflite_path,
        representative_sample_count,
        allow_select_tf_ops,
    )


def parse_args() -> argparse.Namespace:
    florence_dir = repo_root() / "desktop_tools" / "florence2"
    parser = argparse.ArgumentParser(
        description="Convert split Florence-2 ONNX models to INT8 PTQ TFLite."
    )
    parser.add_argument(
        "--onnx-dir",
        type=Path,
        default=florence_dir / "onnx",
        help="Directory containing the split ONNX models.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=florence_dir / "saved_model",
        help="Temporary TensorFlow SavedModel output directory.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=florence_dir / "tflite",
        help="Directory where INT8 TFLite files will be written.",
    )
    parser.add_argument(
        "--representative-samples",
        type=int,
        default=DEFAULT_REPRESENTATIVE_SAMPLES,
        help="Number of random samples to feed into PTQ calibration.",
    )
    parser.add_argument(
        "--allow-select-tf-ops",
        action="store_true",
        help="Allow SELECT_TF_OPS if pure TFLite INT8 conversion fails.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    vision_onnx = args.onnx_dir / "florence2_base_vision_encoder_384.onnx"
    decoder_onnx = args.onnx_dir / "florence2_base_text_decoder.onnx"

    convert_one_model(
        vision_onnx,
        args.work_dir / "vision_encoder",
        args.output_dir / "florence2_base_vision_encoder_384_int8.tflite",
        args.representative_samples,
        args.allow_select_tf_ops,
    )
    convert_one_model(
        decoder_onnx,
        args.work_dir / "text_decoder",
        args.output_dir / "florence2_base_text_decoder_int8.tflite",
        args.representative_samples,
        args.allow_select_tf_ops,
    )

    print("TFLite INT8 conversion complete.")


if __name__ == "__main__":
    main()
