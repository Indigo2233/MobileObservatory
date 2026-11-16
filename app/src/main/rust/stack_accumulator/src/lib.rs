use jni::objects::{JClass, JFloatArray, JObject, JShortArray, ReleaseMode};
use jni::sys::{jdouble, jint, jlong, jshort};
use jni::JNIEnv;

const COVERAGE_LIMIT: jint = -2;
const CANCELLED: jint = -3;
const INVALID: jint = -1;

fn checked_row_range(first_row: jint, row_count: jint, height: usize) -> Option<(usize, usize)> {
    let first_row = usize::try_from(first_row).ok()?;
    let row_count = usize::try_from(row_count).ok()?;
    if row_count == 0 {
        return None;
    }
    let end = first_row.checked_add(row_count)?;
    (end <= height).then_some((first_row, row_count))
}

struct Accumulator {
    width: usize,
    height: usize,
    channels: usize,
    mean: Vec<f32>,
    m2: Vec<f32>,
    coverage: Vec<u16>,
}

impl Accumulator {
    fn new(width: usize, height: usize, channels: usize) -> Option<Self> {
        if width == 0 || height == 0 || (channels != 1 && channels != 3) {
            return None;
        }
        let pixels = width.checked_mul(height)?;
        let samples = pixels.checked_mul(channels)?;
        if pixels > jint::MAX as usize || samples > jint::MAX as usize {
            return None;
        }

        let mut mean = Vec::new();
        let mut m2 = Vec::new();
        let mut coverage = Vec::new();
        mean.try_reserve_exact(samples).ok()?;
        m2.try_reserve_exact(samples).ok()?;
        coverage.try_reserve_exact(pixels).ok()?;
        mean.resize(samples, 0.0);
        m2.resize(samples, 0.0);
        coverage.resize(pixels, 0);

        Some(Self {
            width,
            height,
            channels,
            mean,
            m2,
            coverage,
        })
    }

    #[allow(clippy::too_many_arguments)]
    fn accumulate(
        &mut self,
        frame: &[f32],
        cosine: f64,
        sine: f64,
        dx: f64,
        dy: f64,
        sigma_threshold: f64,
        noise_floor: f64,
        warmup_samples: i32,
        mut cancelled: impl FnMut() -> bool,
    ) -> jint {
        let width = self.width;
        let height = self.height;
        let channels = self.channels;
        let Some(expected) = self
            .width
            .checked_mul(self.height)
            .and_then(|pixels| pixels.checked_mul(self.channels))
        else {
            return INVALID;
        };
        let Some(pixels) = width.checked_mul(height) else {
            return INVALID;
        };
        if frame.len() < expected
            || self.mean.len() < expected
            || self.m2.len() < expected
            || self.coverage.len() < pixels
        {
            return INVALID;
        }
        let frame = &frame[..expected];
        let mean = &mut self.mean[..expected];
        let m2 = &mut self.m2[..expected];
        let coverage = &mut self.coverage[..pixels];
        match channels {
            1 => accumulate_channels::<1>(
                width,
                height,
                frame,
                mean,
                m2,
                coverage,
                cosine,
                sine,
                dx,
                dy,
                sigma_threshold,
                noise_floor,
                warmup_samples,
                &mut cancelled,
            ),
            3 => accumulate_channels::<3>(
                width,
                height,
                frame,
                mean,
                m2,
                coverage,
                cosine,
                sine,
                dx,
                dy,
                sigma_threshold,
                noise_floor,
                warmup_samples,
                &mut cancelled,
            ),
            _ => INVALID,
        }
    }
}

#[allow(clippy::too_many_arguments)]
#[inline(never)]
fn accumulate_channels<const CHANNELS: usize>(
    width: usize,
    height: usize,
    frame: &[f32],
    mean: &mut [f32],
    m2: &mut [f32],
    coverage: &mut [u16],
    cosine: f64,
    sine: f64,
    dx: f64,
    dy: f64,
    sigma_threshold: f64,
    noise_floor: f64,
    warmup_samples: i32,
    cancelled: &mut impl FnMut() -> bool,
) -> jint {
    let samples = width * height * CHANNELS;
    let pixels = width * height;
    debug_assert!(frame.len() >= samples);
    debug_assert!(mean.len() >= samples);
    debug_assert!(m2.len() >= samples);
    debug_assert!(coverage.len() >= pixels);

    let frame_ptr = frame.as_ptr();
    let mean_ptr = mean.as_mut_ptr();
    let m2_ptr = m2.as_mut_ptr();
    let coverage_ptr = coverage.as_mut_ptr();
    let mut rejected_pixels: jint = 0;
    let mut sampled = [0.0_f32; CHANNELS];
    let max_x = width as f64 - 1.0;
    let max_y = height as f64 - 1.0;

    // SAFETY: `new` and `accumulate` validated the backing lengths. Coordinates are clamped
    // before conversion to pixel indices, every channel is compile-time checked against
    // CHANNELS, and the three mutable buffers are disjoint fields of one owned accumulator.
    unsafe {
        for y in 0..height {
            if y % 128 == 0 && cancelled() {
                return CANCELLED;
            }
            let upper_row = y.saturating_sub(1) * width;
            let middle_row = y * width;
            let lower_row = (y + 1).min(height - 1) * width;
            for x in 0..width {
                let sx = cosine * (x as f64 - dx) + sine * (y as f64 - dy);
                let sy = -sine * (x as f64 - dx) + cosine * (y as f64 - dy);
                if sx < -1e-7 || sy < -1e-7 || sx > max_x + 1e-7 || sy > max_y + 1e-7 {
                    continue;
                }

                let fx = if sx < 0.0 {
                    0.0
                } else if sx > max_x {
                    max_x
                } else {
                    sx
                };
                let fy = if sy < 0.0 {
                    0.0
                } else if sy > max_y {
                    max_y
                } else {
                    sy
                };
                let ix = fx as usize;
                let iy = fy as usize;
                let ix1 = (ix + 1).min(width - 1);
                let iy1 = (iy + 1).min(height - 1);
                let ax = fx - ix as f64;
                let ay = fy - iy as f64;
                let p = y * width + x;
                let weight = *coverage_ptr.add(p) as i32;
                if weight >= u16::MAX as i32 {
                    return COVERAGE_LIMIT;
                }

                let needs_temporal_check = weight >= warmup_samples;
                let left_column = x.saturating_sub(1);
                let right_column = (x + 1).min(width - 1);
                let mut reject = false;
                for (ch, sampled_value) in sampled.iter_mut().enumerate() {
                    let a = (iy * width + ix) * CHANNELS + ch;
                    let b = (iy * width + ix1) * CHANNELS + ch;
                    let c = (iy1 * width + ix) * CHANNELS + ch;
                    let d = (iy1 * width + ix1) * CHANNELS + ch;
                    *sampled_value = ((*frame_ptr.add(a) as f64 * (1.0 - ax)
                        + *frame_ptr.add(b) as f64 * ax)
                        * (1.0 - ay)
                        + (*frame_ptr.add(c) as f64 * (1.0 - ax) + *frame_ptr.add(d) as f64 * ax)
                            * ay) as f32;

                    if needs_temporal_check {
                        let i = p * CHANNELS + ch;
                        let upper_left = *mean_ptr.add((upper_row + left_column) * CHANNELS + ch);
                        let upper = *mean_ptr.add((upper_row + x) * CHANNELS + ch);
                        let upper_right = *mean_ptr.add((upper_row + right_column) * CHANNELS + ch);
                        let left = *mean_ptr.add((middle_row + left_column) * CHANNELS + ch);
                        let center = *mean_ptr.add(i);
                        let right = *mean_ptr.add((middle_row + right_column) * CHANNELS + ch);
                        let lower_left = *mean_ptr.add((lower_row + left_column) * CHANNELS + ch);
                        let lower = *mean_ptr.add((lower_row + x) * CHANNELS + ch);
                        let lower_right = *mean_ptr.add((lower_row + right_column) * CHANNELS + ch);
                        let local_low = upper_left
                            .min(upper)
                            .min(upper_right)
                            .min(left)
                            .min(center)
                            .min(right)
                            .min(lower_left)
                            .min(lower)
                            .min(lower_right);
                        let local_high = upper_left
                            .max(upper)
                            .max(upper_right)
                            .max(left)
                            .max(center)
                            .max(right)
                            .max(lower_left)
                            .max(lower)
                            .max(lower_right);
                        let gradient_allowance = (local_high - local_low) as f64 * 0.35;
                        let measured_variance = if weight > 1 {
                            *m2_ptr.add(i) as f64 / (weight - 1) as f64
                        } else {
                            0.0
                        };
                        let sigma = noise_floor.max(measured_variance.max(0.0).sqrt());
                        if (*sampled_value as f64 - center as f64).abs()
                            > sigma_threshold * sigma + gradient_allowance + 1e-5
                        {
                            reject = true;
                        }
                    }
                }

                if reject {
                    rejected_pixels += 1;
                    continue;
                }

                let next = (weight + 1) as f64;
                for (ch, sampled_value) in sampled.iter().enumerate() {
                    let i = p * CHANNELS + ch;
                    let previous = *mean_ptr.add(i);
                    let delta = *sampled_value - previous;
                    let updated = previous + (delta as f64 / next) as f32;
                    *mean_ptr.add(i) = updated;
                    *m2_ptr.add(i) += delta * (*sampled_value - updated);
                }
                *coverage_ptr.add(p) = (weight + 1) as u16;
            }
        }
    }
    rejected_pixels
}

fn accumulator_from_handle(handle: jlong) -> Option<&'static mut Accumulator> {
    if handle == 0 {
        return None;
    }
    // The Kotlin owner serializes all calls for one handle.
    unsafe { (handle as *mut Accumulator).as_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_create(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    channels: jint,
) -> jlong {
    if width <= 0 || height <= 0 {
        return 0;
    }
    let Some(accumulator) = Accumulator::new(width as usize, height as usize, channels as usize)
    else {
        return 0;
    };
    Box::into_raw(Box::new(accumulator)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_accumulate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    frame_array: JFloatArray,
    width: jint,
    height: jint,
    channels: jint,
    cosine: jdouble,
    sine: jdouble,
    dx: jdouble,
    dy: jdouble,
    sigma_threshold: jdouble,
    noise_floor: jdouble,
    warmup_samples: jint,
    cancelled: JObject,
) -> jint {
    let Some(accumulator) = accumulator_from_handle(handle) else {
        return INVALID;
    };
    if width <= 0
        || height <= 0
        || (channels != 1 && channels != 3)
        || accumulator.width != width as usize
        || accumulator.height != height as usize
        || accumulator.channels != channels as usize
    {
        return INVALID;
    }

    let frame_len = match env.get_array_length(&frame_array) {
        Ok(len) if len >= 0 => len as usize,
        _ => return INVALID,
    };
    let expected = (width as usize)
        .checked_mul(height as usize)
        .and_then(|pixels| pixels.checked_mul(channels as usize));
    let Some(expected) = expected else {
        return INVALID;
    };
    if frame_len < expected {
        return INVALID;
    }

    let frame = match unsafe { env.get_array_elements(&frame_array, ReleaseMode::NoCopyBack) } {
        Ok(frame) => frame,
        Err(_) => return INVALID,
    };
    let frame_slice = unsafe { std::slice::from_raw_parts(frame.as_ptr(), expected) };

    let cancel_class = match env.get_object_class(&cancelled) {
        Ok(class) => class,
        Err(_) => return INVALID,
    };
    let is_cancelled = match env.get_method_id(&cancel_class, "isCancelled", "()Z") {
        Ok(method) => method,
        Err(_) => return INVALID,
    };
    let native_env = env.get_native_interface();
    let cancelled_raw = cancelled.as_raw();
    let is_cancelled_raw = is_cancelled.into_raw();
    let call_boolean_method = unsafe { (**native_env).CallBooleanMethodA };
    let exception_check = unsafe { (**native_env).ExceptionCheck };
    let exception_clear = unsafe { (**native_env).ExceptionClear };
    let mut cancel = || match call_boolean_method {
        Some(call) => unsafe {
            let result = call(
                native_env,
                cancelled_raw,
                is_cancelled_raw,
                std::ptr::null(),
            );
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
    };

    accumulator.accumulate(
        frame_slice,
        cosine,
        sine,
        dx,
        dy,
        sigma_threshold,
        noise_floor,
        warmup_samples,
        &mut cancel,
    )
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_readRows(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first_row: jint,
    row_count: jint,
    mean_array: JFloatArray,
    m2_array: JFloatArray,
    coverage_array: JShortArray,
) -> jlong {
    let Some(accumulator) = accumulator_from_handle(handle) else {
        return INVALID as jlong;
    };
    let Some((first_row, row_count)) = checked_row_range(first_row, row_count, accumulator.height)
    else {
        return INVALID as jlong;
    };

    let Some(row_samples) = accumulator.width.checked_mul(accumulator.channels) else {
        return INVALID as jlong;
    };
    let Some(samples) = row_samples.checked_mul(row_count) else {
        return INVALID as jlong;
    };
    let Some(pixels) = accumulator.width.checked_mul(row_count) else {
        return INVALID as jlong;
    };
    let mean_len = match env.get_array_length(&mean_array) {
        Ok(len) if len >= 0 => len as usize,
        _ => return INVALID as jlong,
    };
    let coverage_len = match env.get_array_length(&coverage_array) {
        Ok(len) if len >= 0 => len as usize,
        _ => return INVALID as jlong,
    };
    if mean_len < samples || coverage_len < pixels {
        return INVALID as jlong;
    }

    let mut mean = match unsafe { env.get_array_elements(&mean_array, ReleaseMode::CopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID as jlong,
    };
    let Some(offset) = first_row.checked_mul(row_samples) else {
        return INVALID as jlong;
    };
    let Some(sample_end) = offset.checked_add(samples) else {
        return INVALID as jlong;
    };
    if sample_end > accumulator.mean.len() {
        return INVALID as jlong;
    }
    mean[..samples].copy_from_slice(&accumulator.mean[offset..offset + samples]);
    drop(mean);

    if !m2_array.as_raw().is_null() {
        let m2_len = match env.get_array_length(&m2_array) {
            Ok(len) if len >= 0 => len as usize,
            _ => return INVALID as jlong,
        };
        if m2_len < samples {
            return INVALID as jlong;
        }
        if sample_end > accumulator.m2.len() {
            return INVALID as jlong;
        }
        let mut m2 = match unsafe { env.get_array_elements(&m2_array, ReleaseMode::CopyBack) } {
            Ok(values) => values,
            Err(_) => return INVALID as jlong,
        };
        m2[..samples].copy_from_slice(&accumulator.m2[offset..offset + samples]);
        drop(m2);
    }

    let mut coverage =
        match unsafe { env.get_array_elements(&coverage_array, ReleaseMode::CopyBack) } {
            Ok(values) => values,
            Err(_) => return INVALID as jlong,
        };
    let Some(pixel_offset) = first_row.checked_mul(accumulator.width) else {
        return INVALID as jlong;
    };
    let Some(pixel_end) = pixel_offset.checked_add(pixels) else {
        return INVALID as jlong;
    };
    if pixel_end > accumulator.coverage.len() {
        return INVALID as jlong;
    }
    for i in 0..pixels {
        coverage[i] = accumulator.coverage[pixel_offset + i] as jshort;
    }
    drop(coverage);
    samples as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_releaseM2(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(accumulator) = accumulator_from_handle(handle) {
        accumulator.m2 = Vec::new();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_StackAccumulatorNative_release(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Accumulator));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{checked_row_range, Accumulator};

    #[test]
    fn accumulates_identity_warp_and_tracks_coverage() {
        let mut accumulator = Accumulator::new(2, 2, 1).expect("accumulator");
        let frame = [1.0_f32, 2.0, 3.0, 4.0];

        assert_eq!(
            accumulator.accumulate(&frame, 1.0, 0.0, 0.0, 0.0, 5.0, 0.0, 5, || false),
            0
        );
        assert_eq!(accumulator.mean, frame);
        assert_eq!(accumulator.coverage, vec![1, 1, 1, 1]);
        assert_eq!(accumulator.m2, vec![0.0; 4]);

        assert_eq!(
            accumulator.accumulate(&frame, 1.0, 0.0, 0.0, 0.0, 5.0, 0.0, 5, || false),
            0
        );
        assert_eq!(accumulator.mean, frame);
        assert_eq!(accumulator.coverage, vec![2, 2, 2, 2]);
    }

    #[test]
    fn accumulates_rgb_with_compile_time_channel_specialization() {
        let mut accumulator = Accumulator::new(2, 1, 3).expect("accumulator");
        let frame = [0.1_f32, 0.2, 0.3, 0.4, 0.5, 0.6];

        for _ in 0..2 {
            assert_eq!(
                accumulator.accumulate(&frame, 1.0, 0.0, 0.0, 0.0, 5.0, 0.0, 5, || false),
                0
            );
        }
        assert_eq!(accumulator.mean, frame);
        assert_eq!(accumulator.coverage, vec![2, 2]);
        assert_eq!(accumulator.m2, vec![0.0; 6]);
    }

    #[test]
    fn cancellation_returns_without_mutating_accumulation() {
        let mut accumulator = Accumulator::new(2, 2, 1).expect("accumulator");
        let frame = [1.0_f32, 2.0, 3.0, 4.0];

        assert_eq!(
            accumulator.accumulate(&frame, 1.0, 0.0, 0.0, 0.0, 5.0, 0.0, 5, || true),
            -3
        );
        assert_eq!(accumulator.mean, vec![0.0; 4]);
        assert_eq!(accumulator.coverage, vec![0; 4]);
    }

    #[test]
    fn rejects_invalid_frame_length() {
        let mut accumulator = Accumulator::new(2, 2, 1).expect("accumulator");
        assert_eq!(
            accumulator.accumulate(&[1.0_f32], 1.0, 0.0, 0.0, 0.0, 5.0, 0.0, 5, || false),
            -1
        );
    }

    #[test]
    fn row_range_rejects_negative_empty_and_out_of_bounds_values() {
        assert_eq!(checked_row_range(-1, 1, 10), None);
        assert_eq!(checked_row_range(0, 0, 10), None);
        assert_eq!(checked_row_range(9, 2, 10), None);
        assert_eq!(checked_row_range(i32::MAX, i32::MAX, 10), None);
        assert_eq!(checked_row_range(2, 3, 10), Some((2, 3)));
    }
}
