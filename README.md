# BabyCryClassifier

Android app for baby cry classification using the Dunstan Baby Language (DBL) dataset.

## Note on Model File
The TFLite model file `final_vgg16_dynamic_quant.tflite` is not included in this repository as it exceeds GitHub's file size limits.

To run the app, place the model file in:
`app/src/main/assets/final_vgg16_dynamic_quant.tflite`

## Project Structure
- `AudioPreprocessor.java` — Mel spectrogram preprocessing pipeline
- `CryClassifier.java` — On-device TFLite inference
- `CryDetectionService.java` — Background cry detection service
- `MainActivity.java` — Main UI

## Built with
- TensorFlow Lite
- VGG16 fine-tuned on Dunstan Baby Language dataset
- Heidelberg University BSc Thesis 2026
