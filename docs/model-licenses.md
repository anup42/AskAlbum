# On-device model licenses

Model binaries are not committed to this repository. The application accepts an explicit local import, or the `consumer` build performs a user-started download from a pinned immutable revision. Every pack is activated only after its declared size and SHA-256 digest pass.

## OpenCV SFace

| Field | Pinned value |
| --- | --- |
| Purpose | Local face embeddings for opt-in familiar-person grouping |
| Upstream | [opencv/face_recognition_sface](https://huggingface.co/opencv/face_recognition_sface) |
| Revision | `c140188d35b7d0050f2dcfdfb8fe3e98d516744f` |
| File | `face_recognition_sface_2021dec.onnx` |
| Size | `38,696,353` bytes |
| SHA-256 | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |
| Declared model license | Apache-2.0 |
| Runtime contract | ONNX `data` `[1,3,112,112]` to `fc1` `[1,128]` |
| Match threshold | cosine similarity `0.363`, the OpenCV reference threshold for same-identity verification |

The implementation follows OpenCV's five-landmark similarity alignment and produces an L2-normalized 128-dimensional vector. The app is not intended for commercialization, but the declared Apache-2.0 model license permits both non-commercial and commercial use. This does not remove privacy obligations: people indexing is disabled by default, embeddings remain app-private, users must explicitly opt in, and reset deletes the derived face index and labels without deleting gallery media.

The model is used only for familiar-person grouping. The application does not infer ethnicity, religion, health, sexuality, emotion, or other sensitive traits.

References: [OpenCV SFace model card](https://huggingface.co/opencv/face_recognition_sface), [OpenCV DNN face tutorial](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html), and [SFace paper](https://arxiv.org/abs/2205.12010).
