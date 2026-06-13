# BabyCryClassifier

Android app for real-time baby cry classification using the Dunstan Baby Language (DBL) framework.  
Bachelor's Thesis — Heidelberg University, ECLECTX Research Group, 2026.
 
---
 
## Overview
 
BabyCryClassifier listens to infant cries through the phone microphone and classifies them into one of five Dunstan Baby Language categories:
 
| Class | Code |
|-------|------|
| Belly pain | eairh |
| Need to burp | eh |
| Discomfort | heh |
| Hunger | neh |
| Tiredness | owh |
 
The app uses a fine-tuned VGG16 model deployed as a quantised TFLite model for on-device inference. No internet connection is required.
 
---

## Note on Model File
The TFLite model files are not included in this repository as they exceed GitHub's file size limits.
 
To run the app, place the following file in `app/src/main/assets/`:
 
```
final_vgg16_mic_finetuned_quant.tflite
```
 
The model is a VGG16 classification head fine-tuned on a combination of the original Dunstan dataset (196 samples) and microphone-recorded audio from the deployment device (90 samples).
 
---
 ## Features
 
- **Auto-listen mode** — continuous background monitoring via `CryDetectionService`, detects cries using a binary CNN gate and classifies the type (work in progress)
- **Manual record mode** — tap the red button to record 3 seconds and classify immediately
- **Live voting** — 5 classification rounds with majority vote for more robust results
- **Flash result screen** — full-screen colour-coded result display after detection
- **History tab** — all detections and feedback stored in a local Room database
 
---
## Built with
- TensorFlow Lite
- VGG16 fine-tuned on Dunstan Baby Language dataset
- Heidelberg University BSc Thesis 2026

---
 
## Reference
 
Khooyooz, S., Ricard, L., Liu, J., Haghi, M., & TaheriNejad, N. (2026).  
*CryHop: Baby Cry Classification on Dunstan Dataset using Transfer Learning and Ensembling.*  
IEEE International Symposium on Circuits and Systems (ISCAS).
