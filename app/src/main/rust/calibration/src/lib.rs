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

#[inline(always)]
unsafe fn read_big_endian_f32(pointer: *const u8, index: usize) -> f32 {
    let bits = pointer.add(index * 4).cast::<u32>().read_unaligned();
    f32::from_bits(u32::from_be(bits))
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

    let subtraction = subtraction.map(|samples| samples.bytes.as_ptr());
    let flat = flat.map(|samples| samples.bytes.as_ptr());
    match channels {
        1 => dispatch_calibration::<1>(
            src,
            dst,
            width,
            height,
            start_offset,
            master_row_pitch,
            subtraction,
            flat,
            &mut cancel,
        ),
        3 => dispatch_calibration::<3>(
            src,
            dst,
            width,
            height,
            start_offset,
            master_row_pitch,
            subtraction,
            flat,
            &mut cancel,
        ),
        _ => INVALID,
    }
}

#[allow(clippy::too_many_arguments)]
fn dispatch_calibration<const CHANNELS: usize>(
    src: &[f32],
    dst: &mut [f32],
    width: usize,
    height: usize,
    start_offset: usize,
    master_row_pitch: usize,
    subtraction: Option<*const u8>,
    flat: Option<*const u8>,
    cancel: &mut impl FnMut() -> bool,
) -> jint {
    match (subtraction, flat) {
        (Some(subtraction), Some(flat)) => calibrate_channels::<CHANNELS, true, true>(
            src,
            dst,
            width,
            height,
            start_offset,
            master_row_pitch,
            subtraction,
            flat,
            cancel,
        ),
        (Some(subtraction), None) => calibrate_channels::<CHANNELS, true, false>(
            src,
            dst,
            width,
            height,
            start_offset,
            master_row_pitch,
            subtraction,
            std::ptr::null(),
            cancel,
        ),
        (None, Some(flat)) => calibrate_channels::<CHANNELS, false, true>(
            src,
            dst,
            width,
            height,
            start_offset,
            master_row_pitch,
            std::ptr::null(),
            flat,
            cancel,
        ),
        (None, None) => calibrate_channels::<CHANNELS, false, false>(
            src,
            dst,
            width,
            height,
            start_offset,
            master_row_pitch,
            std::ptr::null(),
            std::ptr::null(),
            cancel,
        ),
    }
}

#[allow(clippy::too_many_arguments)]
fn calibrate_channels<const CHANNELS: usize, const HAS_SUBTRACTION: bool, const HAS_FLAT: bool>(
    src: &[f32],
    dst: &mut [f32],
    width: usize,
    height: usize,
    start_offset: usize,
    master_row_pitch: usize,
    subtraction: *const u8,
    flat: *const u8,
    cancel: &mut impl FnMut() -> bool,
) -> jint {
    let row_samples = width * CHANNELS;
    let samples = row_samples * height;
    debug_assert!(src.len() >= samples);
    debug_assert!(dst.len() >= samples);
    let src = src.as_ptr();
    let dst = dst.as_mut_ptr();

    // SAFETY: `calibrate` validated lengths, master ranges and checked arithmetic. Every
    // master index is below the validated byte range, and all derived offsets are bounded.
    unsafe {
        for y in 0..height {
            if cancel() {
                return CANCELLED;
            }
            let input_start = y * row_samples;
            let master_row = y * master_row_pitch;
            let master_byte_base = (start_offset + master_row) * 4;
            let subtraction_row = if HAS_SUBTRACTION {
                subtraction.add(master_byte_base)
            } else {
                std::ptr::null()
            };
            let flat_row = if HAS_FLAT {
                flat.add(master_byte_base)
            } else {
                std::ptr::null()
            };
            for x in 0..row_samples {
                let input_index = input_start + x;
                if input_index.is_multiple_of(16384) && input_index != input_start && cancel() {
                    return CANCELLED;
                }
                let input = *src.add(input_index);
                if !input.is_finite() {
                    return NONFINITE_INPUT;
                }
                let subtraction_value = if HAS_SUBTRACTION {
                    read_big_endian_f32(subtraction_row, x)
                } else {
                    0.0
                };
                let flat_value = if HAS_FLAT {
                    read_big_endian_f32(flat_row, x)
                } else {
                    1.0
                };
                let value = ((input as f64 - subtraction_value as f64) / flat_value as f64) as f32;
                if !value.is_finite() {
                    return NONFINITE_OUTPUT;
                }
                *dst.add(input_index) = value;
            }
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
    match env.is_same_object(&src_array, &dst_array) {
        Ok(false) => {}
        _ => return INVALID,
    }
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
    fn applies_rgb_bias_and_flat_with_compile_time_specialization() {
        let src = [
            0.5_f32, 0.7, 0.9, 1.1, 1.3, 1.5, 1.7, 1.9, 2.1, 2.3, 2.5, 2.7,
        ];
        let mut dst = [0.0_f32; 12];
        let subtraction = biased_samples(&[0.0; 12]);
        let flat = biased_samples(&[0.5, 1.0, 2.0, 0.5, 1.0, 2.0, 0.5, 1.0, 2.0, 0.5, 1.0, 2.0]);
        let subtraction = BigEndianSamples {
            bytes: &subtraction,
        };
        let flat = BigEndianSamples { bytes: &flat };
        assert_eq!(
            calibrate(
                &src,
                &mut dst,
                4,
                1,
                3,
                0,
                12,
                Some(&subtraction),
                Some(&flat),
                || false
            ),
            0
        );
        for (index, value) in dst.iter().enumerate() {
            let expected = src[index] / [0.5_f32, 1.0, 2.0][index % 3];
            assert!((*value - expected).abs() < f32::EPSILON);
        }
    }

    #[test]
    fn specializes_subtraction_only_flat_only_and_copy_paths() {
        let src = [0.5_f32, 0.75];
        let subtraction_bytes = biased_samples(&[0.1, 0.25]);
        let flat_bytes = biased_samples(&[0.5, 1.5]);
        let subtraction = BigEndianSamples {
            bytes: &subtraction_bytes,
        };
        let flat = BigEndianSamples { bytes: &flat_bytes };
        for (has_subtraction, has_flat) in [(true, false), (false, true), (false, false)] {
            let mut dst = [0.0_f32; 2];
            assert_eq!(
                calibrate(
                    &src,
                    &mut dst,
                    2,
                    1,
                    1,
                    0,
                    2,
                    has_subtraction.then_some(&subtraction),
                    has_flat.then_some(&flat),
                    || false
                ),
                0
            );
            for (index, value) in dst.iter().enumerate() {
                let subtract = if has_subtraction {
                    subtraction_bytes[..]
                        .chunks_exact(4)
                        .nth(index)
                        .map(|bytes| {
                            f32::from_bits(u32::from_be_bytes([
                                bytes[0], bytes[1], bytes[2], bytes[3],
                            ]))
                        })
                        .unwrap()
                } else {
                    0.0
                };
                let divide = if has_flat {
                    flat_bytes[..]
                        .chunks_exact(4)
                        .nth(index)
                        .map(|bytes| {
                            f32::from_bits(u32::from_be_bytes([
                                bytes[0], bytes[1], bytes[2], bytes[3],
                            ]))
                        })
                        .unwrap()
                } else {
                    1.0
                };
                let expected = ((src[index] as f64 - subtract as f64) / divide as f64) as f32;
                assert!((*value - expected).abs() < f32::EPSILON);
            }
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
