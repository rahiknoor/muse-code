import time, shutil, sys
sys.path.insert(0, __import__("os").path.dirname(__file__))
from hf_auth import space_client

PROMPT = "3D animated family film character portrait, character-sheet detail. CHARACTER — Humpty Dumpty, a warm capable middle-aged family-man egg. HEAD: large smooth egg-shaped head, ivory-white eggshell with a delicate matte finish, soft subsurface scattering glow, a few faint natural speckles near the crown. FACE: big warm brown glossy eyes, large and expressive with upper lids slightly heavy in calm confidence, sparkling white catchlights in each eye; thick dark eyebrows, gently arched; a DARK, STRONG, THICK bushy mustache — deep espresso-brown, bold and wide across the upper lip, neatly shaped with natural tapered ends, no curled handlebar tips; full rounded rosy cheeks with a soft peachy blush; kind confident closed-lip smile with gentle laugh lines at the eyes and mouth corners. BODY: plump sturdy egg body, slightly bottom-heavy, small stubby arms with simple rounded three-finger hands. CLOTHING: cozy hand-knitted forest-green cardigan vest, visible chunky knit texture and ribbed hem, three small light-wooden buttons down the front, fitting snugly over the shell. POSE: sitting comfortably on top of a weathered red brick wall, body angled three-quarters toward the viewer, head tilted slightly with warm direct eye contact, one arm resting on a raised knee, calm welcoming posture of a dad at ease. SETTING: sunny storybook village — weathered red brick wall with aged mortar and a touch of moss at the base; cobblestone lane winding past cozy cottages with slate roofs and window flower boxes overflowing with red geraniums; a leafy green tree to one side, soft white clouds in a clear blue sky, two distant birds in flight, warm morning haze. LIGHTING: golden-hour sunlight streaming from the upper left, warm key light sculpting the shell, soft luminous rim light tracing the right edge of the egg, gentle fill keeping shadows open, long soft shadows on the bricks. CAMERA: eye-level medium portrait shot, 85mm lens feel, head-and-torso framing with breathing room above, shallow depth of field, creamy bokeh on the village. STYLE: Pixar-style 3D animated film render, ray-traced soft shadows, physically-based materials, ultra-detailed knit and eggshell micro-texture, vibrant cinematic color grade, warm wholesome mood, 8k masterpiece."
OUT = "/home/hatch/workspace/av_free_stack/bench_qwen-image.png"

for attempt in range(1, 11):
    try:
        print(f"Attempt {attempt}: generating...", flush=True)
        c = space_client("Qwen/Qwen-Image")
        result, used_seed = c.predict(
            PROMPT, 7, False, "1:1", 4.0, 50, True, api_name="/infer"
        )
        path = result["path"] if isinstance(result, dict) else result
        shutil.copy(path, OUT)
        print(f"DONE seed={used_seed} -> {OUT}", flush=True)
        sys.exit(0)
    except Exception as e:
        msg = str(e)
        print(f"Attempt {attempt} failed: {msg[:200]}", flush=True)
        if attempt < 10:
            time.sleep(75)
print("ALL ATTEMPTS FAILED", flush=True)
sys.exit(1)
