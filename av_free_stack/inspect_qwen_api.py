#!/usr/bin/env python3
"""Inspect the Qwen/Qwen-Image HF Space API signature via gradio_client."""
from gradio_client import Client

client = Client("Qwen/Qwen-Image")
print(client.view_api())
