use std::sync::{Mutex, OnceLock};
use std::time::Instant;

use image::{DynamicImage, GenericImageView, load_from_memory};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use log::info;
use ocr_rs::postprocess::extract_boxes_with_unclip;
use ocr_rs::preprocess::{NormalizeParams, preprocess_for_det, resize_to_max_side};
use ocr_rs::{
    Backend, DetModel, DetOptions, DetResizeMode, OcrEngine, OcrEngineConfig, RecOptions, TextBox,
};

struct SerializableOcrBlock {
    line_index: usize,
    text: String,
    confidence: f32,
    left: i32,
    top: i32,
    width: u32,
    height: u32,
}

struct RecognitionCrop {
    image: DynamicImage,
    bbox: TextBox,
    line_index: usize,
}

#[derive(PartialEq, Eq)]
struct EngineCacheKey {
    det_model_path: String,
    rec_model_path: String,
    charset_path: String,
    backend: Backend,
}

impl EngineCacheKey {
    fn new(
        det_model_path: &str,
        rec_model_path: &str,
        charset_path: &str,
        backend: Backend,
    ) -> Self {
        Self {
            det_model_path: det_model_path.to_string(),
            rec_model_path: rec_model_path.to_string(),
            charset_path: charset_path.to_string(),
            backend,
        }
    }
}

struct CachedEngine {
    key: EngineCacheKey,
    engine: OcrEngine,
}

static ENGINE_CACHE: OnceLock<Mutex<Option<CachedEngine>>> = OnceLock::new();

#[derive(Default)]
struct DetectionTimings {
    scaled_width: u32,
    scaled_height: u32,
    tensor_width: usize,
    tensor_height: usize,
    resize_ms: f64,
    tensor_ms: f64,
    preprocess_ms: f64,
    inference_ms: f64,
    postprocess_ms: f64,
    crop_ms: f64,
    detect_ms: f64,
}

fn available_threads() -> i32 {
    std::thread::available_parallelism()
        .map(|value| value.get())
        .unwrap_or(4)
        .min(4) as i32
}

fn engine_cache() -> &'static Mutex<Option<CachedEngine>> {
    ENGINE_CACHE.get_or_init(|| Mutex::new(None))
}

fn init_native_logging() {
    #[cfg(target_os = "android")]
    {
        static LOGGER_INIT: OnceLock<()> = OnceLock::new();
        LOGGER_INIT.get_or_init(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("OcrBindings"),
            );
        });
    }
}

fn backend_from_jni(backend: jint) -> Result<Backend, String> {
    match backend {
        0 => Ok(Backend::CPU),
        1 => Ok(Backend::Vulkan),
        2 => Ok(Backend::OpenCL),
        value => Err(format!("Unsupported OCR backend id: {value}")),
    }
}

fn engine_config(backend: Backend) -> OcrEngineConfig {
    OcrEngineConfig::new()
        .with_backend(backend)
        .with_threads(available_threads())
        .with_det_options(
            DetOptions::new()
                .with_max_side_len(1280)
                .with_resize_mode(DetResizeMode::Fast),
        )
        // bigger batch = slower on my phone on CPU?
        .with_rec_options(RecOptions::new().with_batch_size(1))
        .with_parallel(false)
}

fn recognize(
    image_bytes: Vec<u8>,
    det_model_path: &str,
    rec_model_path: &str,
    charset_path: &str,
    backend: Backend,
) -> Result<String, String> {
    init_native_logging();
    let native_started = Instant::now();

    let image_decode_started = Instant::now();
    let image = load_from_memory(&image_bytes)
        .map_err(|error| format!("Failed to decode image bytes: {error}"))?;
    let image_decode_ms = image_decode_started.elapsed().as_secs_f64() * 1000.0;
    let image_width = image.width();
    let image_height = image.height();

    let cache_key = EngineCacheKey::new(det_model_path, rec_model_path, charset_path, backend);
    let mut cache = engine_cache()
        .lock()
        .map_err(|_| "Failed to lock OCR engine cache".to_string())?;

    let should_reload = cache
        .as_ref()
        .map(|cached| cached.key != cache_key)
        .unwrap_or(true);

    let mut engine_init_ms = 0.0;
    if should_reload {
        let engine_init_started = Instant::now();
        let engine = OcrEngine::new(
            det_model_path,
            rec_model_path,
            charset_path,
            Some(engine_config(backend)),
        )
        .map_err(|error| format!("Failed to initialize OCR engine: {error}"))?;
        engine_init_ms = engine_init_started.elapsed().as_secs_f64() * 1000.0;

        *cache = Some(CachedEngine {
            key: cache_key,
            engine,
        });
    }

    let engine = &cache
        .as_ref()
        .expect("OCR engine cache should be initialized")
        .engine;

    let (recognition_crops, det_timing) = detect_and_crop_timed(engine.det_model(), &image)
        .map_err(|error| format!("OCR failed: {error}"))?;

    let recognition_started = Instant::now();
    let cropped_images: Vec<DynamicImage> = recognition_crops
        .iter()
        .map(|crop| crop.image.clone())
        .collect();
    let rec_results = engine
        .recognize_batch(&cropped_images)
        .map_err(|error| format!("OCR failed: {error}"))?;
    let recognition_ms = recognition_started.elapsed().as_secs_f64() * 1000.0;

    let blocks = rec_results
        .into_iter()
        .zip(recognition_crops.into_iter())
        .filter_map(|(rec, crop)| {
            let text = rec.text.trim().to_string();
            if text.is_empty() {
                return None;
            }

            let min_confidence = if text.chars().count() == 1 {
                0.85
            } else {
                0.75
            };
            if rec.confidence < min_confidence
                || rec.confidence < engine.config().min_result_confidence
            {
                return None;
            }

            Some(SerializableOcrBlock {
                line_index: crop.line_index,
                text,
                confidence: rec.confidence,
                left: crop.bbox.rect.left(),
                top: crop.bbox.rect.top(),
                width: crop.bbox.rect.width(),
                height: crop.bbox.rect.height(),
            })
        })
        .collect::<Vec<_>>();

    let serialize_started = Instant::now();

    let payload = serialize_recognition_result(image_width, image_height, &blocks);
    let serialize_ms = serialize_started.elapsed().as_secs_f64() * 1000.0;
    let native_total_ms = native_started.elapsed().as_secs_f64() * 1000.0;

    info!(
        "backend={:?} image={}x{} bytes={} engineReloaded={} imageDecodeMs={:.3} engineInitMs={:.3} detScaled={}x{} detTensor={}x{} detResizeMs={:.3} detTensorMs={:.3} detPreprocessMs={:.3} detInferMs={:.3} detPostMs={:.3} detCropMs={:.3} detTotalMs={:.3} recInputs={} recTotalMs={:.3} outBlocks={} serializeMs={:.3} nativeTotalMs={:.3}",
        backend,
        image_width,
        image_height,
        image_bytes.len(),
        should_reload,
        image_decode_ms,
        engine_init_ms,
        det_timing.scaled_width,
        det_timing.scaled_height,
        det_timing.tensor_width,
        det_timing.tensor_height,
        det_timing.resize_ms,
        det_timing.tensor_ms,
        det_timing.preprocess_ms,
        det_timing.inference_ms,
        det_timing.postprocess_ms,
        det_timing.crop_ms,
        det_timing.detect_ms,
        cropped_images.len(),
        recognition_ms,
        blocks.len(),
        serialize_ms,
        native_total_ms,
    );

    Ok(payload)
}

fn detect_and_crop_timed(
    detector: &DetModel,
    image: &DynamicImage,
) -> Result<(Vec<RecognitionCrop>, DetectionTimings), String> {
    let detect_started = Instant::now();
    let options = detector.options();
    let (original_width, original_height) = image.dimensions();

    let resize_started = Instant::now();
    let scaled = scale_image_for_detection(image, options)
        .map_err(|error| format!("Failed to resize image for detection: {error}"))?;
    let resize_ms = resize_started.elapsed().as_secs_f64() * 1000.0;
    let (scaled_width, scaled_height) = scaled.dimensions();

    let tensor_started = Instant::now();
    let input = preprocess_for_det(&scaled, &NormalizeParams::paddle_det())
        .map_err(|error| format!("Failed to preprocess detection input: {error}"))?;
    let tensor_ms = tensor_started.elapsed().as_secs_f64() * 1000.0;
    let input_shape = input.shape().to_vec();
    if input_shape.len() != 4 {
        return Err(format!(
            "Unexpected detector input shape after preprocess: {:?}",
            input_shape
        ));
    }

    let inference_started = Instant::now();
    let output = detector
        .run_raw(input.view().into_dyn())
        .map_err(|error| format!("Detection inference failed: {error}"))?;
    let inference_ms = inference_started.elapsed().as_secs_f64() * 1000.0;

    let postprocess_started = Instant::now();
    let output_shape = output.shape();
    if output_shape.len() < 4 {
        return Err(format!(
            "Unexpected detector output shape: {:?}",
            output_shape
        ));
    }
    let out_w = output_shape[3] as u32;
    let out_h = output_shape[2] as u32;
    let mask_data: Vec<f32> = output.iter().copied().collect();
    let binary_mask: Vec<u8> = mask_data
        .iter()
        .map(|&value| {
            if value > options.score_threshold {
                255
            } else {
                0
            }
        })
        .collect();
    let boxes = extract_boxes_with_unclip(
        &binary_mask,
        out_w,
        out_h,
        scaled_width,
        scaled_height,
        original_width,
        original_height,
        options.min_area,
        options.unclip_ratio,
    );
    let postprocess_ms = postprocess_started.elapsed().as_secs_f64() * 1000.0;

    let crop_started = Instant::now();
    let mut recognition_crops = Vec::with_capacity(boxes.len());
    for (line_index, text_box) in boxes.into_iter().enumerate() {
        let expanded = text_box.expand(options.box_border, original_width, original_height);
        let cropped = image.crop_imm(
            expanded.rect.left().max(0) as u32,
            expanded.rect.top().max(0) as u32,
            expanded.rect.width(),
            expanded.rect.height(),
        );
        recognition_crops.push(RecognitionCrop {
            image: cropped,
            bbox: expanded,
            line_index,
        });
    }
    let crop_ms = crop_started.elapsed().as_secs_f64() * 1000.0;

    Ok((
        recognition_crops,
        DetectionTimings {
            scaled_width,
            scaled_height,
            tensor_width: input_shape[3],
            tensor_height: input_shape[2],
            resize_ms,
            tensor_ms,
            preprocess_ms: resize_ms + tensor_ms,
            inference_ms,
            postprocess_ms,
            crop_ms,
            detect_ms: detect_started.elapsed().as_secs_f64() * 1000.0,
        },
    ))
}

fn scale_image_for_detection(
    image: &DynamicImage,
    options: &DetOptions,
) -> Result<DynamicImage, String> {
    let (width, height) = image.dimensions();
    let max_dim = width.max(height);
    if max_dim <= options.max_side_len {
        return Ok(image.clone());
    }

    match options.resize_mode {
        DetResizeMode::Fast => resize_to_max_side(image, options.max_side_len)
            .map_err(|error| format!("Fast resize failed: {error}")),
        DetResizeMode::Lanczos3 => {
            let scale = options.max_side_len as f64 / max_dim as f64;
            let scaled_width = (width as f64 * scale).round() as u32;
            let scaled_height = (height as f64 * scale).round() as u32;
            Ok(image.resize_exact(
                scaled_width,
                scaled_height,
                image::imageops::FilterType::Lanczos3,
            ))
        }
    }
}

fn serialize_recognition_result(
    image_width: u32,
    image_height: u32,
    blocks: &[SerializableOcrBlock],
) -> String {
    let mut payload = String::from("{\"imageWidth\":");
    payload.push_str(&image_width.to_string());
    payload.push_str(",\"imageHeight\":");
    payload.push_str(&image_height.to_string());
    payload.push_str(",\"blocks\":[");

    for (index, block) in blocks.iter().enumerate() {
        if index > 0 {
            payload.push(',');
        }

        payload.push_str("{\"text\":");
        append_json_string(&mut payload, &block.text);
        payload.push_str(",\"lineIndex\":");
        payload.push_str(&block.line_index.to_string());
        payload.push_str(",\"confidence\":");
        payload.push_str(&block.confidence.to_string());
        payload.push_str(",\"left\":");
        payload.push_str(&block.left.to_string());
        payload.push_str(",\"top\":");
        payload.push_str(&block.top.to_string());
        payload.push_str(",\"width\":");
        payload.push_str(&block.width.to_string());
        payload.push_str(",\"height\":");
        payload.push_str(&block.height.to_string());
        payload.push('}');
    }

    payload.push_str("]}");
    payload
}

fn append_json_string(buffer: &mut String, value: &str) {
    buffer.push('"');
    for ch in value.chars() {
        match ch {
            '"' => buffer.push_str("\\\""),
            '\\' => buffer.push_str("\\\\"),
            '\n' => buffer.push_str("\\n"),
            '\r' => buffer.push_str("\\r"),
            '\t' => buffer.push_str("\\t"),
            '\u{08}' => buffer.push_str("\\b"),
            '\u{0C}' => buffer.push_str("\\f"),
            ch if ch.is_control() => {
                buffer.push_str(&format!("\\u{:04x}", ch as u32));
            }
            _ => buffer.push(ch),
        }
    }
    buffer.push('"');
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_dev_davidv_ocr_OcrNativeBridge_nativeRecognize(
    mut env: JNIEnv,
    _: JClass,
    image_bytes: JByteArray,
    det_model_path: JString,
    rec_model_path: JString,
    charset_path: JString,
    backend: jint,
) -> jstring {
    let image_bytes = match env.convert_byte_array(&image_bytes) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Failed to read image bytes from JNI: {error}"),
            );
            return std::ptr::null_mut();
        }
    };

    let det_model_path: String = match env.get_string(&det_model_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Failed to read detection model path: {error}"),
            );
            return std::ptr::null_mut();
        }
    };

    let rec_model_path: String = match env.get_string(&rec_model_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Failed to read recognizer model path: {error}"),
            );
            return std::ptr::null_mut();
        }
    };

    let charset_path: String = match env.get_string(&charset_path) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                format!("Failed to read charset path: {error}"),
            );
            return std::ptr::null_mut();
        }
    };

    let backend = match backend_from_jni(backend) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/RuntimeException", error);
            return std::ptr::null_mut();
        }
    };

    match recognize(
        image_bytes,
        &det_model_path,
        &rec_model_path,
        &charset_path,
        backend,
    ) {
        Ok(result) => match env.new_string(result) {
            Ok(value) => value.into_raw(),
            Err(error) => {
                let _ = env.throw_new(
                    "java/lang/RuntimeException",
                    format!("Failed to create OCR result string: {error}"),
                );
                std::ptr::null_mut()
            }
        },
        Err(error) => {
            let _ = env.throw_new("java/lang/RuntimeException", error);
            std::ptr::null_mut()
        }
    }
}
