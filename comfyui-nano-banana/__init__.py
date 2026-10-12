"""
ComfyUI Nano Banana custom node - calls Google Gemini API for image generation.
Supports gemini-2.5-flash-image (Nano Banana) with text-to-image and image-to-image.
Requires a Gemini API key from https://aistudio.google.com/apikey
"""

import base64
import io
import json
import urllib.request
import urllib.error

import numpy as np
import torch
from PIL import Image


class NanoBananaGenerate:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "api_key": ("STRING", {"multiline": False, "placeholder": "AIza..."}),
                "prompt": ("STRING", {"multiline": True, "placeholder": "Describe the image..."}),
                "model": (["gemini-2.5-flash-image", "gemini-3-pro-image-preview"], {"default": "gemini-2.5-flash-image"}),
                "aspect_ratio": (["1:1", "16:9", "9:16", "4:3", "3:4"], {"default": "1:1"}),
            },
            "optional": {
                "input_image": ("IMAGE",),
                "seed": ("INT", {"default": 0, "min": 0, "max": 0xffffffffffffffff}),
            },
        }

    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "generate"
    CATEGORY = "Google AI/Nano Banana"

    def generate(self, api_key, prompt, model, aspect_ratio, input_image=None, seed=0):
        if not api_key or not api_key.strip():
            raise ValueError("Gemini API key is required. Get one at https://aistudio.google.com/apikey")

        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key.strip()}"

        parts = [{"text": prompt}]
        if input_image is not None:
            # Convert ComfyUI IMAGE tensor (B,H,W,C float 0-1) to base64 PNG
            img_array = (input_image[0].cpu().numpy() * 255).astype(np.uint8)
            pil_img = Image.fromarray(img_array)
            buf = io.BytesIO()
            pil_img.save(buf, format="PNG")
            b64 = base64.b64encode(buf.getvalue()).decode("utf-8")
            parts.append({"inline_data": {"mime_type": "image/png", "data": b64}})

        payload = {
            "contents": [{"parts": parts}],
            "generationConfig": {
                "responseModalities": ["TEXT", "IMAGE"],
            },
        }
        if seed:
            payload["generationConfig"]["seed"] = seed

        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                result = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")[:500]
            raise RuntimeError(f"Gemini API error {e.code}: {err_body}")

        # Extract the image from the response
        for candidate in result.get("candidates", []):
            for part in candidate.get("content", {}).get("parts", []):
                inline = part.get("inlineData") or part.get("inline_data")
                if inline and inline.get("data"):
                    img_data = base64.b64decode(inline["data"])
                    pil_img = Image.open(io.BytesIO(img_data)).convert("RGB")
                    img_array = np.array(pil_img).astype(np.float32) / 255.0
                    tensor = torch.from_numpy(img_array).unsqueeze(0)
                    return (tensor,)

        raise RuntimeError("No image in Gemini API response. Raw: " + json.dumps(result)[:500])


NODE_CLASS_MAPPINGS = {
    "NanoBananaGenerate": NanoBananaGenerate,
}

NODE_DISPLAY_NAME_MAPPINGS = {
    "NanoBananaGenerate": "Nano Banana Generate",
}
