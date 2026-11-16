use jni::objects::{JByteBuffer, JClass, JFloatArray, JObject, ReleaseMode};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

const INVALID: jint = -1;
const NONFINITE_INPUT: jint = -2;
const CANCELLED: jint = -3;
const NONFINITE_OUTPUT: jint = -4;

fn cancellation_callback(
    env: &JNIEnv,
    cancelled: &JObject,
    method: jni::objects::JMethodID,
) -> impl FnMut() -> bool {
    let native_env = env.get_native_interface();
    let cancelled_raw = cancelled.as_raw();
    let method_raw = method.into_raw();
    let call = unsafe { (**native_env).CallBooleanMethodA };
    let exception_check = unsafe { (**native_env).ExceptionCheck };
    let exception_clear = unsafe { (**native_env).ExceptionClear };
    move || match call {
        Some(call) => unsafe {
            let result = call(native_env, cancelled_raw, method_raw, std::ptr::null());
            if exception_check
                .map(|check| check(native_env) != 0)
                .unwrap_or(true)
            {
                if let Some(clear) = exception_clear {
                    clear(native_env);
                }
                true
            } else {
                result != 0
            }
        },
        None => true,
    }
}

struct BigEndianSamples<'a> {
    bytes: &'a [u8],
}

impl BigEndianSamples<'_> {
    fn get(&self, index: usize) -> Option<f32> {
        let offset = index.checked_mul(4)?;
        let end = offset.checked_add(4)?;
        let bytes = self.bytes.get(offset..end)?;
        Some(f32::from_bits(u32::from_be_bytes([
            bytes[0], bytes[1], bytes[2], bytes[3],
        ])))
    }
}

#[allow(clippy::too_many_arguments)]
fn calibrate(
    src: &[f32],
    dst: &mut [f32],
    width: usize,
    height: usize,
    channels: usize,
    start_offset: usize,
    master_row_pitch: usize,
    subtraction: Option<&BigEndianSamples<'_>>,
    flat: Option<&BigEndianSamples<'_>>,
    mut cancel: impl FnMut() -> bool,
) -> jint {
    if width == 0 || height == 0 || (channels != 1 && channels != 3) {
        return INVALID;
    }
    let Some(row_samples) = width.checked_mul(channels) else {
        return INVALID;
    };
    let Some(samples) = row_samples.checked_mul(height) else {
        return INVALID;
    };
    if src.len() < samples || dst.len() < samples {
        return INVALID;
    }
    if height > 1 && master_row_pitch < row_samples {
        return INVALID;
    }
    let Some(last_row_offset) = height
        .checked_sub(1)
        .and_then(|rows| rows.checked_mul(master_row_pitch))
    else {
        return INVALID;
    };
    let Some(last_index) = start_offset
        .checked_add(last_row_offset)
        .and_then(|offset| offset.checked_add(row_samples))
    else {
        return INVALID;
    };
    let required_bytes = match last_index.checked_mul(4) {
        Some(value) => value,
        None => return INVALID,
    };
    if subtraction.is_some_and(|samples| samples.bytes.len() < required_bytes)
        || flat.is_some_and(|samples| samples.bytes.len() < required_bytes)
    {
        return INVALID;
    }

    for y in 0..height {
        if cancel() {
            return CANCELLED;
        }
        let input_start = y * row_samples;
        let master_row = match y.checked_mul(master_row_pitch) {
            Some(value) => value,
            None => return INVALID,
        };
        for x in 0..row_samples {
            let input_index = input_start + x;
            if input_index % 16384 == 0 && input_index != input_start && cancel() {
                return CANCELLED;
            }
            let input = src[input_index];
            if !input.is_finite() {
                return NONFINITE_INPUT;
            }
            let master_index = start_offset + master_row + x;
            let subtraction_value = match subtraction {
                Some(samples) => match samples.get(master_index) {
                    Some(value) => value,
                    None => return INVALID,
                },
                None => 0.0,
            };
            let flat_value = match flat {
                Some(samples) => match samples.get(master_index) {
                    Some(value) => value,
                    None => return INVALID,
                },
                None => 1.0,
            };
            let value = ((input as f64 - subtraction_value as f64) / flat_value as f64) as f32;
            if !value.is_finite() {
                return NONFINITE_OUTPUT;
            }
            dst[input_index] = value;
        }
    }
    0
}

fn direct_samples<'a>(
    env: &JNIEnv<'a>,
    buffer: &'a JByteBuffer<'a>,
) -> Option<BigEndianSamples<'a>> {
    if buffer.is_null() {
        return None;
    }
    let address = env.get_direct_buffer_address(buffer).ok()?;
    let capacity = env.get_direct_buffer_capacity(buffer).ok()?;
    if address.is_null() {
        return None;
    }
    Some(BigEndianSamples {
        bytes: unsafe { std::slice::from_raw_parts(address, capacity) },
    })
}

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativeCalibrationBridge_apply(
    mut env: JNIEnv,
    _class: JClass,
    src_array: JFloatArray,
    dst_array: JFloatArray,
    width: jint,
    height: jint,
    channels: jint,
    start_offset: jlong,
    master_row_pitch: jlong,
    subtraction_buffer: JByteBuffer,
    flat_buffer: JByteBuffer,
    cancelled: JObject,
) -> jint {
    if width <= 0
        || height <= 0
        || (channels != 1 && channels != 3)
        || start_offset < 0
        || master_row_pitch < 0
        || cancelled.is_null()
    {
        return INVALID;
    }
    let Ok(width) = usize::try_from(width) else {
        return INVALID;
    };
    let Ok(height) = usize::try_from(height) else {
        return INVALID;
    };
    let Ok(channels) = usize::try_from(channels) else {
        return INVALID;
    };
    let Ok(start_offset) = usize::try_from(start_offset) else {
        return INVALID;
    };
    let Ok(master_row_pitch) = usize::try_from(master_row_pitch) else {
        return INVALID;
    };
    let src = match unsafe { env.get_array_elements(&src_array, ReleaseMode::NoCopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let mut dst = match unsafe { env.get_array_elements(&dst_array, ReleaseMode::CopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let subtraction = if subtraction_buffer.is_null() {
        None
    } else {
        match direct_samples(&env, &subtraction_buffer) {
            Some(samples) => Some(samples),
            None => return INVALID,
        }
    };
    let flat = if flat_buffer.is_null() {
        None
    } else {
        match direct_samples(&env, &flat_buffer) {
            Some(samples) => Some(samples),
            None => return INVALID,
        }
    };
    let cancel_class = match env.get_object_class(&cancelled) {
        Ok(class) => class,
        Err(_) => return INVALID,
    };
    let method = match env.get_method_id(&cancel_class, "isCancelled", "()Z") {
        Ok(method) => method,
        Err(_) => return INVALID,
    };
    let mut cancel = cancellation_callback(&env, &cancelled, method);
    calibrate(
        &src,
        &mut dst,
        width,
        height,
        channels,
        start_offset,
        master_row_pitch,
        subtraction.as_ref(),
        flat.as_ref(),
        &mut cancel,
    )
}

#[cfg(test)]
mod tests {
    use super::{calibrate, BigEndianSamples};

    fn biased_samples(values: &[f32]) -> Vec<u8> {
        values
            .iter()
            .flat_map(|value| value.to_bits().to_be_bytes())
            .collect()
    }

    #[test]
    fn applies_bias_and_flat_with_region_pitch() {
        let src = [0.5_f32, 0.7, 0.9, 1.1];
        let mut dst = [0.0_f32; 4];
        let subtraction = biased_samples(&[9.0, 9.0, 9.0, 0.1, 0.2, 9.0, 0.1, 0.1, 9.0]);
        let flat = biased_samples(&[9.0, 9.0, 9.0, 0.5, 0.4, 9.0, 2.0, 2.0, 9.0]);
        let subtraction = BigEndianSamples {
            bytes: &subtraction,
        };
        let flat = BigEndianSamples { bytes: &flat };
        assert_eq!(
            calibrate(
                &src,
                &mut dst,
                2,
                2,
                1,
                3,
                3,
                Some(&subtraction),
                Some(&flat),
                || false
            ),
            0
        );
        for (actual, expected) in dst.into_iter().zip([0.8, 1.25, 0.4, 0.5]) {
            assert!((actual - expected).abs() < f32::EPSILON);
        }
    }

    #[test]
    fn rejects_nonfinite_input_output_and_cancellation() {
        let mut dst = [0.0_f32; 4];
        assert_eq!(
            calibrate(
                &[0.0, f32::NAN, 0.0, 0.0],
                &mut dst,
                2,
                2,
                1,
                0,
                2,
                None,
                None,
                || false
            ),
            -2
        );
        let zero_flat = biased_samples(&[0.0; 4]);
        let zero_flat = BigEndianSamples { bytes: &zero_flat };
        assert_eq!(
            calibrate(
                &[0.0; 4],
                &mut dst,
                2,
                2,
                1,
                0,
                2,
                None,
                Some(&zero_flat),
                || false
            ),
            -4
        );
        assert_eq!(
            calibrate(&[0.0; 4], &mut dst, 2, 2, 1, 0, 2, None, None, || true),
            -3
        );
    }

    #[test]
    fn rejects_rows_that_overlap_or_exceed_master_buffer() {
        let mut dst = [0.0_f32; 4];
        let subtraction = biased_samples(&[0.0; 6]);
        let subtraction = BigEndianSamples {
            bytes: &subtraction,
        };
        assert_eq!(
            calibrate(
                &[0.0; 4],
                &mut dst,
                2,
                2,
                1,
                0,
                1,
                Some(&subtraction),
                None,
                || false
            ),
            -1
        );
        assert_eq!(
            calibrate(
                &[0.0; 4],
                &mut dst,
                2,
                2,
                1,
                3,
                2,
                Some(&subtraction),
                None,
                || false
            ),
            -1
        );
    }

    #[test]
    fn cancels_within_large_rows_and_at_each_row_start() {
        let mut dst = [0.0_f32; 16_385];
        let mut calls = 0;
        assert_eq!(
            calibrate(
                &[0.0; 16_385],
                &mut dst,
                16_385,
                1,
                1,
                0,
                16_385,
                None,
                None,
                || {
                    calls += 1;
                    calls == 2
                }
            ),
            -3
        );
        assert_eq!(calls, 2);
    }
}
