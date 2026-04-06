use std::sync::{Mutex, OnceLock};

use image::load_from_memory;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use ocr_rs::{Backend, OcrEngine, OcrEngineConfig};

#[derive(PartialEq, Eq)]
struct EngineCacheKey {
    det_model_path: String,
    rec_model_path: String,
    charset_path: String,
    backend: Backend,
}

impl EngineCacheKey {
    fn new(det_model_path: &str, rec_model_path: &str, charset_path: &str, backend: Backend) -> Self {
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

fn available_threads() -> i32 {
    std::thread::available_parallelism()
        .map(|value| value.get())
        .unwrap_or(4)
        .min(4) as i32
}

fn engine_cache() -> &'static Mutex<Option<CachedEngine>> {
    ENGINE_CACHE.get_or_init(|| Mutex::new(None))
}

fn backend_from_jni(backend: jint) -> Result<Backend, String> {
    match backend {
        0 => Ok(Backend::CPU),
        1 => Ok(Backend::Vulkan),
        value => Err(format!("Unsupported OCR backend id: {value}")),
    }
}

fn engine_config(backend: Backend) -> OcrEngineConfig {
    OcrEngineConfig::new()
        .with_backend(backend)
        .with_threads(available_threads())
        .with_parallel(false)
}

fn recognize(
    image_bytes: Vec<u8>,
    det_model_path: &str,
    rec_model_path: &str,
    charset_path: &str,
    backend: Backend,
) -> Result<String, String> {
    let image = load_from_memory(&image_bytes)
        .map_err(|error| format!("Failed to decode image bytes: {error}"))?;

    let cache_key = EngineCacheKey::new(det_model_path, rec_model_path, charset_path, backend);
    let mut cache = engine_cache()
        .lock()
        .map_err(|_| "Failed to lock OCR engine cache".to_string())?;

    let should_reload = cache
        .as_ref()
        .map(|cached| cached.key != cache_key)
        .unwrap_or(true);

    if should_reload {
        let engine = OcrEngine::new(
            det_model_path,
            rec_model_path,
            charset_path,
            Some(engine_config(backend)),
        )
        .map_err(|error| format!("Failed to initialize OCR engine: {error}"))?;

        *cache = Some(CachedEngine {
            key: cache_key,
            engine,
        });
    }

    let results = cache
        .as_ref()
        .expect("OCR engine cache should be initialized")
        .engine
        .recognize(&image)
        .map_err(|error| format!("OCR failed: {error}"))?;

    Ok(results
        .into_iter()
        .filter_map(|result| {
            let text = result.text.trim().to_string();
            if text.is_empty() {
                return None;
            }

            let min_confidence = if text.chars().count() == 1 { 0.85 } else { 0.75 };
            if result.confidence < min_confidence {
                return None;
            }

            Some(text)
        })
        .collect::<Vec<_>>()
        .join("\n"))
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
