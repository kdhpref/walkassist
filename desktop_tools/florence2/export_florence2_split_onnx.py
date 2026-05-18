from __future__ import annotations

import argparse
from pathlib import Path

import torch
from torch import nn
from transformers import AutoProcessor

try:
    from transformers import AutoModelForImageTextToText
except ImportError:  # Older Transformers releases used this loader for Florence-2.
    from transformers import AutoModelForCausalLM as AutoModelForImageTextToText


MODEL_ID = "microsoft/Florence-2-base"
IMAGE_SIZE = 384
DEFAULT_PROMPT = "<CAPTION>"


class FlorenceVisionEncoder(nn.Module):
    """Vision tower plus Florence-2 image projection."""

    def __init__(self, florence_model: nn.Module) -> None:
        super().__init__()
        self.florence_model = florence_model

    def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
        return self.florence_model._encode_image(pixel_values)


class FlorenceTextDecoder(nn.Module):
    """Text generation module that starts after image features are available."""

    def __init__(self, florence_model: nn.Module) -> None:
        super().__init__()
        self.florence_model = florence_model

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        image_features: torch.Tensor,
        decoder_input_ids: torch.Tensor,
    ) -> torch.Tensor:
        text_embeds = self.florence_model.get_input_embeddings()(input_ids)
        image_attention_mask = torch.ones(
            image_features.shape[:2],
            dtype=attention_mask.dtype,
            device=image_features.device,
        )
        inputs_embeds = torch.cat([image_features, text_embeds], dim=1)
        merged_attention_mask = torch.cat([image_attention_mask, attention_mask], dim=1)

        outputs = self.florence_model.language_model(
            inputs_embeds=inputs_embeds,
            attention_mask=merged_attention_mask,
            decoder_input_ids=decoder_input_ids,
            use_cache=False,
            return_dict=True,
        )
        return outputs.logits.float()


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def load_model(device: torch.device) -> tuple[nn.Module, AutoProcessor]:
    processor = AutoProcessor.from_pretrained(MODEL_ID, trust_remote_code=True)
    model = AutoModelForImageTextToText.from_pretrained(
        MODEL_ID,
        trust_remote_code=True,
        torch_dtype=torch.float32,
    )
    model.to(device)
    model.eval()
    return model, processor


def build_dummy_inputs(
    processor: AutoProcessor,
    device: torch.device,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    dummy_image = torch.zeros(1, 3, IMAGE_SIZE, IMAGE_SIZE, dtype=torch.float32)
    text_inputs = processor(text=DEFAULT_PROMPT, return_tensors="pt")

    input_ids = text_inputs["input_ids"].to(torch.long)
    attention_mask = text_inputs["attention_mask"].to(torch.long)
    decoder_start_token_id = processor.tokenizer.bos_token_id
    if decoder_start_token_id is None:
        decoder_start_token_id = processor.tokenizer.eos_token_id
    if decoder_start_token_id is None:
        decoder_start_token_id = processor.tokenizer.pad_token_id
    if decoder_start_token_id is None:
        raise ValueError("Could not find a decoder start token from the processor.")

    decoder_input_ids = torch.tensor([[decoder_start_token_id]], dtype=torch.long)
    return (
        dummy_image.to(device),
        input_ids.to(device),
        attention_mask.to(device),
        decoder_input_ids.to(device),
    )


def export_vision_encoder(
    vision_encoder: nn.Module,
    pixel_values: torch.Tensor,
    output_path: Path,
) -> torch.Tensor:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        image_features = vision_encoder(pixel_values)

    torch.onnx.export(
        vision_encoder,
        (pixel_values,),
        output_path.as_posix(),
        input_names=["pixel_values"],
        output_names=["image_features"],
        dynamic_axes={
            "pixel_values": {0: "batch_size"},
            "image_features": {0: "batch_size", 1: "image_token_length"},
        },
        opset_version=17,
        do_constant_folding=True,
    )
    return image_features


def export_text_decoder(
    text_decoder: nn.Module,
    input_ids: torch.Tensor,
    attention_mask: torch.Tensor,
    image_features: torch.Tensor,
    decoder_input_ids: torch.Tensor,
    output_path: Path,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        text_decoder,
        (input_ids, attention_mask, image_features, decoder_input_ids),
        output_path.as_posix(),
        input_names=[
            "input_ids",
            "attention_mask",
            "image_features",
            "decoder_input_ids",
        ],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "prompt_length"},
            "attention_mask": {0: "batch_size", 1: "prompt_length"},
            "image_features": {0: "batch_size", 1: "image_token_length"},
            "decoder_input_ids": {0: "batch_size", 1: "decoder_length"},
            "logits": {0: "batch_size", 1: "decoder_length"},
        },
        opset_version=17,
        do_constant_folding=True,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export microsoft/Florence-2-base as split ONNX models."
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=repo_root() / "desktop_tools" / "florence2" / "onnx",
        help="Directory where ONNX files will be written.",
    )
    parser.add_argument(
        "--device",
        default="cpu",
        help="Torch device for export, for example cpu or cuda.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    device = torch.device(args.device)
    model, processor = load_model(device)

    pixel_values, input_ids, attention_mask, decoder_input_ids = build_dummy_inputs(
        processor,
        device,
    )

    vision_encoder = FlorenceVisionEncoder(model).eval()
    text_decoder = FlorenceTextDecoder(model).eval()

    vision_path = args.output_dir / "florence2_base_vision_encoder_384.onnx"
    decoder_path = args.output_dir / "florence2_base_text_decoder.onnx"

    image_features = export_vision_encoder(vision_encoder, pixel_values, vision_path)
    export_text_decoder(
        text_decoder,
        input_ids,
        attention_mask,
        image_features,
        decoder_input_ids,
        decoder_path,
    )

    print(f"vision encoder ONNX: {vision_path}")
    print(f"text decoder ONNX: {decoder_path}")
    print(f"fixed image input: 1 x 3 x {IMAGE_SIZE} x {IMAGE_SIZE}")
    print(f"image feature shape from dummy export: {tuple(image_features.shape)}")


if __name__ == "__main__":
    main()
