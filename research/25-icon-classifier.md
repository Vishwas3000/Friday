# 25 — Naming unlabelled icons on the phone: a tiny icon classifier

_2026-10-18 · Trust tags: [primary] read at source · [secondary] blog, forum or search snippet · [measured] read or run here · [derived] computed from cited numbers · [UNCERTAIN] couldn't confirm_

Friday shows an icon with no text, content description or useful resource id as `- (ImageView)`, so Laya can't choose it (docs/SYSTEM.md §3.2). D-052 shipped icon steps 1–3: resource-id words, the words the next screen gained after a tap, and a screenshot test. Steps 4–5 need a classifier that names an icon from a screenshot crop. Research/24 §4 called the UIST'18 "semantic icon classifier" "the only open ≤ 50 MB one". This file checks that at source and finds the smallest offline path. The budget is ≤ 20 MB on disk and ≤ ~30 ms per icon on the Pixel 8 CPU, inside the app (which ships onnxruntime-android 1.31). Labels are cached per app element, and money apps block screenshots, so it runs rarely and only in other apps.

**How we looked:** we read the classifier's repo and paper, and read its released files by byte range only: the weights' first 256 KB, `data.zip`'s file list, its 36 KB class list and its label arrays. No code from them ran. Two parallel web passes covered released alternatives and training our own, reading big files the same way. We read the screenshot path in AOSP source. Nothing ran on the phone.

## Answer in one screen
1. **The UIST'18 weights still download, but the model isn't small** [measured]. It is a 294 MB Keras 2.0.8 HDF5 file on Google Drive. It has 38.5M parameters and needs 1.49 G multiply-adds per 32×32 grey icon: 147 MB in fp32, ~37 MB in int8. Every layer converts to ONNX, but its size rules it out. Research/24's "≤ 50 MB" was wrong.
2. **No released model fits** [primary]. The small ones were never released: Google's IconNet (8.5 MB), Apple's Screen Recognition (20 MB), Apple's 98-type labeller and TalkBack's model. What can be downloaded is too big or GPL: COALA's ResNet-18 (45 MB, GPL-3.0), a 70 MB MaxViT, and captioners of 0.7–1.9 GB.
3. **Training our own is small and quick** [derived]. MobileNetV3-Small with a 100-class head has 1.62M parameters. That is 6.5 MB in fp32 and ~5M multiply-adds per icon at 64×64, a few ms per icon. ONNX Runtime 1.31 runs it unchanged, and training takes minutes on the GPU box.
4. **Labelled data exists** [measured; primary]. UIST'18's `data.zip` (135 MB, live) holds 49,136 hand-labelled 32×32 Rico icons in 99 classes. They cover search, menu, settings, back, share, cart, profile, more, close and filter, but not QR code. Rico Semantics adds 353k human-drawn icon boxes, and rendered icon sets such as Material Symbols (Apache-2.0) fill the gaps.
5. **Licences are the weak spot** [primary]. Rico grants no licence to its screenshots, makes the downloader indemnify the University of Illinois, and binds a for-profit employer. ImageNet's terms may carry over to pretrained weights. Rendered icon sets, trained from scratch, avoid both problems.
6. **Cropping is cheap and half-built** [primary, AOSP]. Take one `takeScreenshot` per screen (at most once per 333 ms), copy it once to a software bitmap, and crop each icon by `getBoundsInScreen()`. A window shot of a FLAG_SECURE window fails with error 6; the comment in `AgentService.screenshotTest()` says 5.
7. **Recommendation:** train MobileNetV3-Small at 64×64 on the UIST'18 crops plus rendered icons for the missing classes. Ship it as a ~6.5 MB fp32 ONNX behind a confidence threshold, and run it only after the free text sources. About 1.5 days of work.

## 1. The UIST'18 classifier, checked at source
| What | Finding | Tag |
|---|---|---|
| Code | `datadrivendesign/semantic-icon-classifier`: MIT, 9 files, Python 2.7 + Keras 2.0.8 + TF 1.8. 8 commits, all 2020-02-26/27, then only Dependabot PRs. The one human issue (#12, 2021, asking for the model JSON) has no reply. | [primary] |
| Weights | `small_cnn_weights_100_512.h5`, Google Drive id `1Kq5agoiLSuv5_CVBlkf5F7iENtpyKyz8`, 308,272,512 bytes. Live on 2026-10-18 behind Drive's virus-scan page; byte-range reads work. No mirror found. | [measured] |
| Format | One Keras 2.0.8 HDF5 "full model" (TF backend): the architecture JSON, the weights and the RMSprop optimizer state, which doubles the file. Not a SavedModel. | [measured] |
| Other files | `data.zip` (135 MB): float64 .npy crops (train 113,854 × 32×32×1, test 4,809) and `validation_metadata.json` with the class names. `saved_models.zip` (70 MB): `datagen.pkl` and two scikit-learn anomaly pickles. `icons.zip` (3.3 GB): the raw crops, "over 100k" files. | [measured] |
| Network | From the file's own config: 32×32×1 input; 17 conv layers in 6 stacks of 384 → 1,152 filters (1×1, 2×2 and 3×3, "same", ELU); 5 max-pools; Dense 512 (`embedding`); Dense 99 softmax. **38,521,059 parameters, 1.49 G multiply-adds per icon.** The repo's real small variant ("simple", 1.97M parameters) was never released. | [measured] |
| Input | PIL `convert('L')` (grey), antialiased resize to 32×32, ÷ 255. Then dataset-wide centring, std scaling and ZCA whitening, a 1,024×1,024 matrix "to boost edges". The evaluation code also shifts test images randomly by up to 10%. | [primary] |
| Data | Rico, 2017 apps. The candidates were clickable images under 5% of the screen with aspect ratio > 0.75: 73,449, hand-coded into 135 classes. Keeping classes with > 40 examples left 99 classes and 49,136 icons. The split is random per class, not per app. Training rows are repeated up to ≥ 1,000 per class (87 classes sit at exactly 1,000); test classes hold 4–627 icons. | [primary; measured] |
| Accuracy | Test 94.68%, macro precision 0.875, macro recall 0.855 (README). With the anomaly filter: 91% accuracy, 79% recall, 91% precision, and 2% of real icons rejected (paper). Resource-id text "did not increase performance". | [primary] |
| Licence | Code MIT. The weights and crops have no licence of their own; they derive from Rico (§3). | [primary] |

**The 99 classes** [measured, `validation_metadata.json`, in index order]: explore, add, lock, follow, minus, flash, email, search, arrow_downward, pause, book, date_range, filter_list, arrow_upward, expand_less, av_rewind, help, close, arrow_backward, navigation, dashboard, arrow_forward, weather, info, redo, folder, more, emoji, chat, volume, visibility, thumbs_up, file_download, playlist, stop, repeat, expand_more, shop, flight, reply, location, undo, photo, call, star, list, delete, share, warning, avatar, build, play, dialpad, settings, layers, time, videocam, filter, send, power, gift, cart, music, av_forward, refresh, save, copy, launch, group, font, skip_next, history, location_crosshair, edit, swap, attach_file, skip_previous, facebook, globe, thumbs_down, description, notifications, wallpaper, bluetooth, network_wifi, compare, home, fullscreen, check, twitter, national_flag, microphone, sliders, favorite, menu, zoom_out, label, bookmark, switcher.
- **Against Friday's list** [measured]: search, menu, settings, share, cart, more, close and filter (also `filter_list`, `sliders`) are present; back is `arrow_backward`, and profile is `avatar`. **QR code is missing**, as are scan, camera, wallet, rupee, translate, apps grid and sort. The brand classes are 2017's (`facebook`, `twitter`).
- **94.7% is an upper bound for 2026 apps** [derived]. The test icons are a random 10% of the same 2017 pool, not unseen apps, and back, more, close and menu alone are 36% of the test set.

## 2. Running it on the phone
- **Every layer converts** [derived from the config]. Conv, Elu, MaxPool, Flatten, Gemm and Softmax exist in ONNX and TFLite, and Dropout vanishes at inference. The 2×2 "same" kernels pad bottom/right, which is ONNX `SAME_UPPER`. TFLite even has an int8 Elu, as a lookup table [primary].
- **Conversion paths** [primary]: `tf2onnx` (v1.17.0, 2026-03; `keras2onnx` is archived) or `TFLiteConverter.from_keras_model`. Or rebuild the network (17 convolutions, 5 pools, 2 dense layers) in PyTorch, copy in the HDF5 arrays, and export. Whether today's Keras loads a 2.0.8 file is untested [UNCERTAIN].
- **The preprocessing has to move into the graph** [derived]: subtract, divide, and a 1,024×1,024 ZCA MatMul (+4 MB). Recompute the statistics from `training_x.npy`, because unpickling `datagen.pkl` runs code.
- **Size fails** [derived]: 147 MB in fp32, ~74 MB in fp16, ~37 MB in int8.
- **int8 helps less than usual** [primary, `registry.py`]: ORT's quantizer has no entry for Elu, so every Elu runs in float between Dequantize/Quantize pairs.
- **Speed is borderline** [derived, UNCERTAIN]. The model needs ~3.0 GFLOP per icon. Friday's Pixel 8 bench (Laya fp32, ORT 1.31, 4 threads: 636 tokens in 2.53–3.32 s, research/18 §6b) implies ~50–70 GFLOP/s, so ~45–60 ms per icon in fp32.
- **The anomaly filter doesn't port.** It is two pickled scikit-learn Gaussian mixtures; a softmax-confidence threshold does the same job.
- **Verdict:** don't ship it. Its value is the labelled crops and the class list.

## 3. Alternatives
**Released models** [primary unless tagged]
| Model | What | Size | Labels | Accuracy | Weights today | Licence |
|---|---|---|---|---|---|---|
| Google IconNet (2021) | int8 CenterNet detector in Voice Access | 8.5 MB; 107 ms on a Pixel 3a | 31 types | 94.2% mAP (internal) | not released | – |
| Apple Screen Recognition (CHI'21) | element detector with one generic "icon" type | 20 MB; ~10 ms on an iPhone 11 | 13 element types | 71.3% mAP | not released | – |
| Apple icon labeller (CHI'22) | ResNet-50 classifier, 256 px | ~98 MB [secondary] | 98 + long tail | 96.3%; long tail 78.6% | not released | – |
| TalkBack icon descriptions | on-device model fetched at run time | – | – | – | proprietary; stubbed out in the open-source build | code Apache-2.0 |
| COALA (FSE'21) | context-aware captioner with a ResNet-18 icon classifier | 45 MB fp32 pickle; ~2 G multiply-adds at 244 px [derived] | 98 Rico classes | classifier not reported; caption exact match 0.38 vs 0.14 for LabelDroid | GitHub release v1.0.1 | GPL-3.0 |
| LabelDroid (ICSE'20) | ResNet-101 + Transformer captioner | encoder alone ~170 MB [secondary] | free text | exact match 60.7%; 0.17 on non-default labels | Zenodo (timed out) | GPL-3.0 |
| `Lancelot53/icon_classifier_maxvit` | MaxViT-T classifier, 224 px | 70 MB pickle | 43, incl. QR code | not documented | Hugging Face | CC BY 4.0 (card) |
| DeepIntent (CCS'19) | icon + text → the permissions an action uses | – | 8 permission groups | – | Dropbox | none |
| OmniParser v2; BLIP / Florence icon captioners | captioners | 0.7–1.9 GB | free text | – | Hugging Face | MIT / BSD / Apache |

**Datasets** [primary unless tagged]
| Dataset | Labels | Size | Licence | Pixels |
|---|---|---|---|---|
| UIST'18 `data.zip` | hand-coded, 99 classes | 49,136 crops, 32×32 grey | none; Rico-derived | included [measured] |
| COALA `icon-image-classes.zip` | 98 classes, by folder | 36,321 PNGs, 250 MB | none; Rico-derived | included |
| Rico `semantic_annotations.zip` (158 MB, live) | `iconClass` from the UIST'18 network on 66,261 screens; ~10% verified, ~32% of icons missed | ~179k icons [UNCERTAIN, sampled] | Rico's terms | needs `unique_uis.tar.gz` (6.47 GB) |
| Rico Semantics (Google, 2022) | human boxes: 77 icon shapes (73 in val/test), 353k boxes; 37 shape:function classes, 77k boxes | annotations only | CC BY-SA 4.0 | Rico's screenshots |
| Screen Annotation (ScreenAI); Widget Captioning | pictogram shape names; 61k widget captions | 22k screens | CC BY 4.0 | Rico's screenshots |

- **Rico's terms** [primary, copyright.txt, 2026-09-22]: the screenshots "may contain copyrighted work" and no licence to them is given. The downloader indemnifies the University of Illinois, "including … copies of copyrighted images". "If Researcher is employed by a for-profit, commercial entity, Researcher's employer shall also be bound." There is no non-commercial clause, so research/24's "research-only" overstates it. Whether the terms reach copies hosted by others (`data.zip`, COALA) is unclear [UNCERTAIN].
- **Missing labels:** neither Rico Semantics nor `iconClass` has QR code, and Rico Semantics has no filter [primary].

**Training our own** [derived from published 1000-class counts, timm's weight headers and published file sizes; checked against the MobileNetV3 paper's 96 px figures]
| Backbone, 100-class head | Parameters | fp32 file | int8 file | Multiply-adds at 64 px | Note |
|---|---|---|---|---|---|
| MobileNetV3-Small 1.0 | 1.62M | 6.5 MB | 1.6–1.9 MB | 5.1M | hard-swish and squeeze-excite stay float under ORT int8 |
| MobileNetV3-Small 0.75 | 1.12M | 4.5 MB | 1.1–1.3 MB | 4.0M | |
| MobileNetV2 0.5 | 0.82M | 3.3 MB | 0.8–1.0 MB | 7.9M | |
| MobileNetV4-Conv-S | 2.62M | 10.5 MB | 2.6–3.1 MB | 16.7M | ReLU, no squeeze-excite: friendlier to int8 |
| EfficientNet-Lite0 | 3.50M | 14.0 MB | 3.5–4.2 MB | 33.2M | built for int8 (ImageNet 75.1 → 74.4) |
| _UIST'18, for scale_ | _38.5M_ | _147 MB_ | _~37 MB_ | _1,490M at 32 px_ | |

- **Speed** [primary; derived]. Published at 224 px: MobileNetV3-Small fp32 14.4 ms on one Pixel 3 core; EfficientNet-Lite0 int8 6.5 ms on a Pixel 4 (4 threads); MobileNetV4-Conv-S int8 2.4 ms on one Pixel 6 core. Time falls more slowly than work: 224 → 96 px is ×0.21 work but ×0.28 time for MobileNetV3-Small. Expect a few ms per icon at 64–96 px, in one batched run per screen. No Pixel 8 or ORT number exists [UNCERTAIN].
- **ORT 1.31 runs MobileNetV3 on the CPU** [primary]: it has no HardSwish kernel, but its partitioner expands HardSwish into HardSigmoid × x.
- **Ship fp32; it already fits.** int8 is a risk, since hard-swish and squeeze-excite stay float: one fine-tuned 3-class MobileNetV3-Small fell from 98.4% to 58.9% under ORT static int8 [secondary]. If int8 is ever needed, use MobileNetV4-Conv-S or EfficientNet-Lite0. LiteRT would add 3.4–5.6 MB of runtime for no gain [primary].
- **Data, readiest first** [measured; primary]: UIST'18's `data.zip`; the larger originals in `icons.zip` (3.3 GB) and COALA's PNGs; then Rico Semantics crops, cut on the box from the 6.47 GB of screenshots.
- **Rendered icon sets** add the missing classes under clean licences [primary]. Material Symbols (Apache-2.0, 3,929 icons, 35 categories) has every class on Friday's list by name, `qr_code` included. MDI (7,447), Tabler (6,238), Lucide, Phosphor, Bootstrap, Fluent, Ionicons and Feather are Apache-2.0, MIT or ISC. Font Awesome Free needs attribution (CC BY 4.0). Skip Remix Icon, whose licence changed on 2026-01-25. Map icons to classes by name, not by tag.
- **Renders help** [primary]. A 2022 Stuttgart thesis (Knecht) trained MobileNetV2 at 96×96 grey and tested on icons from 19 real websites. Rico icons alone scored 0.83; adding rendered icon sets and Noun Project icons raised that to 0.89. Nobody has tested renders alone on app screenshots [UNCERTAIN].
- **Reference accuracies:** an image-only EfficientNet-B0 reached F1 82.1 on 77 Rico icon shapes (Sunkara et al. 2022) [primary]. Apple's labeller reached 96.3% with ~3,000 examples per type [secondary].
- **Weight licences** [primary]. timm's cards say Apache-2.0, but timm warns that ImageNet's non-commercial terms may carry over to the weights. In Knecht's runs, training from scratch cost ~3 points.
- **Training cost** [derived]. MobileNetV3-Small trains at ~5,750 images/s at 224 px on an RTX 3090 (timm's benchmark), so 100k crops × 30 epochs take ~9 minutes. Render the glyphs once, up front.
- **Zero-shot CLIP-style models are a dead end for now** [primary]. MobileCLIP-S0's image tower (11.4M parameters) has a research-only licence; TinyCLIP (MIT) is weak (41.1% on ImageNet) and 94 MB; no study tests any of them on UI icons.

## 4. Cropping icons from an AccessibilityService screenshot
- **Two calls** [primary, AOSP]. `takeScreenshot(displayId, …)` (API 30) captures the display; `takeScreenshotOfWindow(windowId, …)` (API 34) captures one window, for when it "might be visually underneath an accessibility overlay", such as Friday's banner. Both return a `ScreenshotResult` (a `HardwareBuffer` plus a colour space). Both need `android:canTakeScreenshot="true"`, which Friday already has.
- **Rate limit** [primary]: 333 ms between display shots per service (1,000 ms on Android 11), and 333 ms per window id for window shots. A call sooner fails with error 3. One shot serves every icon on a screen, so the limit never bites.
- **Secure content** [primary]. A window shot of a FLAG_SECURE window fails with **error 6**; error 5 means an invalid window id. The comment in `AgentService.screenshotTest()` says 5. Display shots use default capture arguments, which leave secure layers out. Research/03 reports they come out black; the pending GPay test will show it [UNCERTAIN].
- **Pixels** [primary, `Bitmap.java`]. `wrapHardwareBuffer` gives a HARDWARE bitmap: `getPixel(s)` throws, and `createBitmap(hw, x, y, w, h)` copies the whole screen only to return another HARDWARE bitmap. So copy once per screen to ARGB_8888, as `screenshotTest()` already does, crop every icon from that copy, then close the buffer.
- **Per icon:** `Bitmap.createBitmap(copy, r.left, r.top, r.width(), r.height())`, then `createScaledBitmap(…, filter = true)` to the model's input; read the pixels as floats and run the session. The crop keeps the button's padding, as Rico's crops did.
- **Coordinates** [UNCERTAIN until run]. A display shot should share `getBoundsInScreen()`'s screen pixels; check its size once. A window shot starts at the window's corner, so subtract the window's bounds. Clamp every box to the bitmap.
- **Cost and privacy** [derived]: one 1080×2400 copy (10.4 MB) per new screen. Crops stay in memory and are never saved (D-004); only the label is kept, next to step 2's meanings.

## 5. Recommendation: the smallest working path (ranked)
1. **Free text first** (D-052 steps 1–2): resource-id words, then the words the next screen gained. Classify only icons that are still unnamed, once per (app, resource id or position), and cache the label [derived].
2. **Train MobileNetV3-Small 1.0 on the box and ship fp32 ONNX** [derived]: ~6.5 MB, a few ms per icon, no new runtime and no quantization risk.
   - **Input:** 64×64, upsampled from `data.zip` or taken from `icons.zip`. Try grey (as UIST'18 did) and RGB.
   - **Classes:** the 99 UIST'18 classes in Friday's words (`arrow_backward` → back, `avatar` → profile). Add QR code, scan, camera, wallet, rupee, translate, apps and sort, rendered from Material Symbols and MDI. Add an "other" class, cut from Rico screens or rendered, since `data.zip` has no non-icon crops.
   - **Output:** the top label only if its probability clears a threshold set on held-out data. Otherwise the icon stays `- (ImageView)`. Laya sees the label where a content description goes, e.g. `[4] search (ImageButton)`.
   - **Effort:** ~1 day on the box (data, training, export, a PyTorch-vs-ORT parity check) plus ~0.5 day of Kotlin (crop, preprocess, session, cache), reusing `screenshotTest()`.
3. **Gate it before Laya sees its labels** [derived]. First on held-out Rico icons and renders. Then on ~100–200 icons from the owner's apps, with labels suggested by step 2's meanings and confirmed by the owner. Then on the task suite (D-049).
4. **If licences must be clean** (Rico's terms, ImageNet's), train from scratch on renders only. It is the same size; its accuracy on real apps is unknown [UNCERTAIN].
5. **Don't** port the UIST'18 network (147 MB, 1.49 G multiply-adds). Don't ship COALA's GPL classifier or any captioner, wait for IconNet or Screen Recognition, add LiteRT, or ship MobileCLIP (research-only licence).

## Dead ends
- **UIST'18's "small CNN" has 38.5M parameters** [measured]. Its small variant was never released, and its anomaly filter is pickled scikit-learn.
- **`data.zip` has no non-icon crops** [measured]. It holds only network outputs for them, arrays of 23,906 × 99 and 13,230 × 512.
- **Never released:** IconNet, Screen Recognition, Apple's labeller, TalkBack's icon model and IconIntent. The Rico Semantics paper says its benchmark models are released, but the repo holds only a converter.
- **Dead links:** Google's MobileNetV3 checkpoint tarballs return 403, and the TF Hub TFLite links return 404.
- **Never published:** any small CNN's latency on a Pixel 8 or under ORT; any CLIP-style result on UI icons; renders-only training tested on app screenshots.
- **Unreachable:** LabelDroid's Zenodo record timed out.

## Not checked
- **No runs:** no conversion, training or phone run. Of the HDF5 file, only its architecture JSON was read, not the weights.
- **Files:** whether today's Keras loads the 2.0.8 file; whether `icons.zip` has non-icon crops, and at what sizes. The 54.7 MB train split of Rico Semantics wasn't counted.
- **Phone:** display-shot coordinates against `getBoundsInScreen()`; whether GPay comes back black (the pending step-3 test); Pixel 8 latency for any backbone.

## Sources
- **UIST'18:** https://github.com/datadrivendesign/semantic-icon-classifier (`cnn_pretrain.py`, `dl_inference_service.py`, `settings.py`, issue #12) · https://ranjithakumar.net/resources/mobile-semantics.pdf
  - Drive ids via https://drive.usercontent.google.com/download?id=…: `1Kq5agoiLSuv5_CVBlkf5F7iENtpyKyz8` (weights), `1SiD_U5ifjX1poJZzLB-MwvoUQBhutYzH` (data), `16hUHUzxkGHHBRsgvfeLV_4gTD3-KFYIy`, `1D0CFmDP0xNSyfSkK7kUHnfP0HnpcKZc1`
- **Released models:**
  - Google: https://research.google/blog/improving-mobile-app-accessibility-with-icon-detection/ · https://github.com/google/talkback
  - Apple: https://arxiv.org/abs/2101.04893 · https://machinelearning.apple.com/research/icon-labelling
  - Research code: https://github.com/fmehralian/COALA · https://seal.ics.uci.edu/publications/2021_FSE_COALA.pdf · https://github.com/chenjshnn/LabelDroid · https://github.com/deepintent-ccs/DeepIntent
  - Hugging Face: https://huggingface.co/Lancelot53/icon_classifier_maxvit · https://huggingface.co/microsoft/OmniParser-v2.0
- **Datasets:**
  - Rico: https://storage.googleapis.com/crowdstf-rico-uiuc-4540/rico_dataset_v0.1/semantic_annotations.zip · https://www.interactionmining.org/archive/rico/copyright.txt
  - Google: https://github.com/google-research-datasets/rico_semantics · https://arxiv.org/abs/2210.02663 · https://github.com/google-research-datasets/screen_annotation · https://github.com/google-research-datasets/widget-caption
- **Training our own:**
  - Backbones: https://arxiv.org/abs/1905.02244 · https://github.com/tensorflow/tpu/blob/master/models/official/efficientnet/lite/README.md · https://arxiv.org/html/2404.10518
  - timm: https://github.com/huggingface/pytorch-image-models (results CSVs, README "Licenses") · https://huggingface.co/timm/mobilenetv3_small_100.lamb_in1k
  - int8 results: https://huggingface.co/Arm/mobilenet-v3-small-int8-litert · https://huggingface.co/vivekkaushal/DocRex
- **ONNX, ORT and packages:** https://github.com/onnx/onnx/blob/main/docs/Operators.md#HardSwish · ORT `docs/OperatorKernels.md`, `core/framework/graph_partitioner.cc`, `python/tools/quantization/registry.py` · https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html · Maven `onnxruntime-android` 1.31.0, `litert` 2.3.0
- **Icon sets, Knecht and CLIP-style models:**
  - Icon sets: https://github.com/google/material-design-icons · https://github.com/Templarian/MaterialDesign · https://github.com/Remix-Design/RemixIcon
  - Knecht: https://elib.uni-stuttgart.de/server/api/core/bitstreams/dfadd9e7-8ba7-483c-b5ed-119f136e65ed/content
  - CLIP-style: https://github.com/apple/ml-mobileclip · https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M
- **Android:** AOSP https://android.googlesource.com/platform/frameworks/base/ `AccessibilityService.java` (android-11/12/14/16 tags and main), `AbstractAccessibilityServiceConnection.java`, `AccessibilityInteractionController.java`, `WindowManagerService.java`, `ScreenCapture.java`, `Bitmap.java`
- **Converters:** TFLite `lite/kernels/activations.cc` · https://github.com/onnx/tensorflow-onnx · https://github.com/onnx/keras-onnx (archived)
