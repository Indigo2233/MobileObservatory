use jni::objects::{JClass, JFloatArray, JObject, ReleaseMode};
use jni::sys::jint;
use jni::JNIEnv;

const INVALID: jint = -1;
const NONFINITE_INPUT: jint = -2;
const CANCELLED: jint = -3;
const NONFINITE_OUTPUT: jint = -4;

fn colour(x: usize, y: usize, red_x: usize, red_y: usize) -> usize {
    if x & 1 == red_x && y & 1 == red_y {
        0
    } else if x & 1 != red_x && y & 1 != red_y {
        2
    } else {
        1
    }
}

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

fn bilinear(
    src: &[f32],
    dst: &mut [f32],
    width: usize,
    height: usize,
    red_x: usize,
    red_y: usize,
    mut cancel: impl FnMut() -> bool,
) -> jint {
    if width < 2 || height < 2 || red_x > 1 || red_y > 1 {
        return INVALID;
    }
    let Some(samples) = width.checked_mul(height) else {
        return INVALID;
    };
    let Some(output_len) = samples.checked_mul(3) else {
        return INVALID;
    };
    if src.len() < samples || dst.len() < output_len {
        return INVALID;
    }
    for y in 0..height {
        if cancel() {
            return CANCELLED;
        }
        for x in 0..width {
            let p = y * width + x;
            let own = colour(x, y, red_x, red_y);
            for channel in 0..3 {
                if channel == own {
                    dst[p * 3 + channel] = src[p];
                    continue;
                }
                let mut sum = 0.0_f32;
                let mut count = 0_u32;
                let from_y = y.saturating_sub(1);
                let to_y = (y + 1).min(height - 1);
                let from_x = x.saturating_sub(1);
                let to_x = (x + 1).min(width - 1);
                for yy in from_y..=to_y {
                    for xx in from_x..=to_x {
                        if colour(xx, yy, red_x, red_y) == channel {
                            sum += src[yy * width + xx];
                            count += 1;
                        }
                    }
                }
                dst[p * 3 + channel] = sum / count.max(1) as f32;
            }
        }
    }
    0
}

fn finite_float(value: f64) -> Option<f32> {
    let value = value as f32;
    value.is_finite().then_some(value)
}

fn vng_gradient_row(
    gradients: &mut [f64],
    src: &[f32],
    width: usize,
    row_stride: usize,
    y: usize,
    cancel: &mut dyn FnMut() -> bool,
) -> bool {
    let base = (y % 3) * row_stride;
    for x in 1..width - 1 {
        if x % 1024 == 0 && cancel() {
            return false;
        }
        let p = y * width + x;
        let nw = src[p - width - 1] as f64;
        let n = src[p - width] as f64;
        let ne = src[p - width + 1] as f64;
        let w = src[p - 1] as f64;
        let e = src[p + 1] as f64;
        let sw = src[p + width - 1] as f64;
        let s = src[p + width] as f64;
        let se = src[p + width + 1] as f64;
        let g = base + x;
        gradients[g] = (nw - sw).abs() + 2.0 * (n - s).abs() + (ne - se).abs();
        gradients[g + width] = (nw - ne).abs() + 2.0 * (w - e).abs() + (sw - se).abs();
        gradients[g + width * 2] = 2.0 * (ne - sw).abs();
        gradients[g + width * 3] = 2.0 * (nw - se).abs();
        gradients[g + width * 4] = gradients[g + width * 2] + (n - w).abs() + (s - e).abs();
        gradients[g + width * 5] = gradients[g + width * 3] + (n - e).abs() + (s - w).abs();
        gradients[g + width * 6] = (n + w + e + s) * 0.5;
    }
    true
}

fn vng(
    src: &[f32],
    dst: &mut [f32],
    width: usize,
    height: usize,
    red_x: usize,
    red_y: usize,
    mut cancel: impl FnMut() -> bool,
) -> jint {
    if width < 2 || height < 2 || red_x > 1 || red_y > 1 {
        return INVALID;
    }
    let Some(samples) = width.checked_mul(height) else {
        return INVALID;
    };
    let Some(output_len) = samples.checked_mul(3) else {
        return INVALID;
    };
    if src.len() < samples || dst.len() < output_len {
        return INVALID;
    }
    if cancel() {
        return CANCELLED;
    }
    for (index, value) in src.iter().enumerate() {
        if index % 16384 == 0 && cancel() {
            return CANCELLED;
        }
        if !value.is_finite() {
            return NONFINITE_INPUT;
        }
    }

    for y in 0..height {
        if cancel() {
            return CANCELLED;
        }
        for x in 0..width {
            if x % 1024 == 0 && cancel() {
                return CANCELLED;
            }
            if x >= 2 && x < width - 2 && y >= 2 && y < height - 2 {
                continue;
            }
            let p = y * width + x;
            let own = colour(x, y, red_x, red_y);
            for channel in 0..3 {
                if channel == own {
                    dst[p * 3 + channel] = src[p];
                    continue;
                }
                let mut sum = 0.0;
                let mut count = 0_u32;
                let from_y = y.saturating_sub(1);
                let to_y = (y + 1).min(height - 1);
                let from_x = x.saturating_sub(1);
                let to_x = (x + 1).min(width - 1);
                for yy in from_y..=to_y {
                    for xx in from_x..=to_x {
                        if colour(xx, yy, red_x, red_y) == channel {
                            sum += src[yy * width + xx] as f64;
                            count += 1;
                        }
                    }
                }
                if count == 0 {
                    return INVALID;
                }
                let Some(value) = finite_float(sum / count as f64) else {
                    return NONFINITE_OUTPUT;
                };
                dst[p * 3 + channel] = value;
            }
        }
    }
    if width < 5 || height < 5 {
        return 0;
    }

    let row_stride = match width.checked_mul(7) {
        Some(value) => value,
        None => return INVALID,
    };
    let Some(gradient_len) = row_stride.checked_mul(3) else {
        return INVALID;
    };
    let mut gradients = Vec::new();
    if gradients.try_reserve_exact(gradient_len).is_err() {
        return INVALID;
    }
    gradients.resize(gradient_len, 0.0_f64);
    let row = |index: usize| (index % 3) * row_stride;
    if !vng_gradient_row(&mut gradients, src, width, row_stride, 1, &mut cancel)
        || !vng_gradient_row(&mut gradients, src, width, row_stride, 2, &mut cancel)
    {
        return CANCELLED;
    }

    let w2 = width * 2;
    let mut direction = [0.0_f64; 8];
    for y in 2..height - 2 {
        if cancel() {
            return CANCELLED;
        }
        if !vng_gradient_row(&mut gradients, src, width, row_stride, y + 1, &mut cancel) {
            return CANCELLED;
        }
        for x in 2..width - 2 {
            if x % 1024 == 0 && cancel() {
                return CANCELLED;
            }
            let p = y * width + x;
            let b0 = row(y - 1) + x;
            let b1 = row(y) + x;
            let b2 = row(y + 1) + x;
            let own = colour(x, y, red_x, red_y);
            direction[0] = gradients[b0] + gradients[b1];
            direction[1] = gradients[b1] + gradients[b2];
            direction[2] = gradients[b1 + width - 1] + gradients[b1 + width];
            direction[3] = gradients[b1 + width] + gradients[b1 + width + 1];
            if own != 1 {
                direction[4] = gradients[b0 + width * 4 + 1] + gradients[b1 + width * 4];
                direction[5] = gradients[b1 + width * 4] + gradients[b2 + width * 4 - 1];
                direction[6] = gradients[b0 + width * 5 - 1] + gradients[b1 + width * 5];
                direction[7] = gradients[b1 + width * 5] + gradients[b2 + width * 5 + 1];
            } else {
                direction[4] = gradients[b0 + w2]
                    + gradients[b0 + w2 + 1]
                    + gradients[b1 + w2]
                    + gradients[b1 + w2 + 1];
                direction[5] = gradients[b1 + w2]
                    + gradients[b1 + w2 - 1]
                    + gradients[b2 + w2]
                    + gradients[b2 + w2 - 1];
                direction[6] = gradients[b0 + width * 3]
                    + gradients[b0 + width * 3 - 1]
                    + gradients[b1 + width * 3]
                    + gradients[b1 + width * 3 - 1];
                direction[7] = gradients[b1 + width * 3]
                    + gradients[b1 + width * 3 + 1]
                    + gradients[b2 + width * 3]
                    + gradients[b2 + width * 3 + 1];
            }
            let mut smallest = direction[0];
            let mut largest = direction[0];
            for value in direction.iter().skip(1) {
                smallest = smallest.min(*value);
                largest = largest.max(*value);
            }
            let threshold = smallest + largest * 0.5;
            let mut rs = 0.0;
            let mut gs = 0.0;
            let mut bs = 0.0;
            let mut used = 0_u32;
            let center = src[p] as f64;
            let s = |offset: isize| src[(p as isize + offset) as usize] as f64;
            for (d, cost) in direction.iter().enumerate() {
                if largest != 0.0 && *cost >= threshold {
                    continue;
                }
                used += 1;
                if own != 1 {
                    match d {
                        0 => {
                            rs += s(-(w2 as isize)) + center;
                            gs += s(-(width as isize)) * 2.0;
                            bs += s(-(width as isize) - 1) + s(-(width as isize) + 1);
                        }
                        1 => {
                            rs += s(w2 as isize) + center;
                            gs += s(width as isize) * 2.0;
                            bs += s(width as isize - 1) + s(width as isize + 1);
                        }
                        2 => {
                            rs += s(-2) + center;
                            gs += s(-1) * 2.0;
                            bs += s(-(width as isize) - 1) + s(width as isize - 1);
                        }
                        3 => {
                            rs += s(2) + center;
                            gs += s(1) * 2.0;
                            bs += s(-(width as isize) + 1) + s(width as isize + 1);
                        }
                        4 => {
                            rs += s(-(w2 as isize) + 2) + center;
                            gs += gradients[b0 + width * 6 + 1];
                            bs += s(-(width as isize) + 1) * 2.0;
                        }
                        5 => {
                            rs += s(w2 as isize - 2) + center;
                            gs += gradients[b2 + width * 6 - 1];
                            bs += s(width as isize - 1) * 2.0;
                        }
                        6 => {
                            rs += s(-(w2 as isize) - 2) + center;
                            gs += gradients[b0 + width * 6 - 1];
                            bs += s(-(width as isize) - 1) * 2.0;
                        }
                        7 => {
                            rs += s(w2 as isize + 2) + center;
                            gs += gradients[b2 + width * 6 + 1];
                            bs += s(width as isize + 1) * 2.0;
                        }
                        _ => return INVALID,
                    }
                } else {
                    match d {
                        0 => {
                            rs += s(-(w2 as isize) - 1) + s(-(w2 as isize) + 1);
                            gs += s(-(w2 as isize)) + center;
                            bs += s(-(width as isize)) * 2.0;
                        }
                        1 => {
                            rs += s(w2 as isize - 1) + s(w2 as isize + 1);
                            gs += s(w2 as isize) + center;
                            bs += s(width as isize) * 2.0;
                        }
                        2 => {
                            rs += s(-1) * 2.0;
                            gs += s(-2) + center;
                            bs += s(-(width as isize) - 2) + s(width as isize - 2);
                        }
                        3 => {
                            rs += s(1) * 2.0;
                            gs += s(2) + center;
                            bs += s(-(width as isize) + 2) + s(width as isize + 2);
                        }
                        4 => {
                            rs += s(-(w2 as isize) + 1) + s(1);
                            gs += s(-(width as isize) + 1) * 2.0;
                            bs += s(-(width as isize)) + s(-(width as isize) + 2);
                        }
                        5 => {
                            rs += s(w2 as isize - 1) + s(-1);
                            gs += s(width as isize - 1) * 2.0;
                            bs += s(width as isize) + s(width as isize - 2);
                        }
                        6 => {
                            rs += s(-(w2 as isize) - 1) + s(-1);
                            gs += s(-(width as isize) - 1) * 2.0;
                            bs += s(-(width as isize) - 2) + s(-(width as isize));
                        }
                        7 => {
                            rs += s(w2 as isize + 1) + s(1);
                            gs += s(width as isize + 1) * 2.0;
                            bs += s(width as isize + 2) + s(width as isize);
                        }
                        _ => return INVALID,
                    }
                }
            }
            if used == 0 {
                return INVALID;
            }
            let scale = 0.5 / used as f64;
            if own != 1 {
                let Some(green) = finite_float(center + (gs - rs) * scale) else {
                    return NONFINITE_OUTPUT;
                };
                let Some(opposite) = finite_float(center + (bs - rs) * scale) else {
                    return NONFINITE_OUTPUT;
                };
                dst[p * 3 + own] = src[p];
                dst[p * 3 + 1] = green;
                dst[p * 3 + 2 - own] = opposite;
            } else {
                let horizontal = if y & 1 == red_y { 0 } else { 2 };
                let Some(horizontal_value) = finite_float(center + (rs - gs) * scale) else {
                    return NONFINITE_OUTPUT;
                };
                let Some(vertical_value) = finite_float(center + (bs - gs) * scale) else {
                    return NONFINITE_OUTPUT;
                };
                dst[p * 3 + 1] = src[p];
                dst[p * 3 + horizontal] = horizontal_value;
                dst[p * 3 + (2 - horizontal)] = vertical_value;
            }
        }
    }
    if cancel() {
        CANCELLED
    } else {
        0
    }
}

#[allow(clippy::too_many_arguments)]
fn invoke_demosaic(
    env: &mut JNIEnv,
    src_array: JFloatArray,
    dst_array: JFloatArray,
    width: jint,
    height: jint,
    red_x: jint,
    red_y: jint,
    cancelled: JObject,
    use_vng: bool,
) -> jint {
    if width < 2 || height < 2 || !(0..=1).contains(&red_x) || !(0..=1).contains(&red_y) {
        return INVALID;
    }
    let src = match unsafe { env.get_array_elements(&src_array, ReleaseMode::NoCopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let mut dst = match unsafe { env.get_array_elements(&dst_array, ReleaseMode::CopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let cancel_class = match env.get_object_class(&cancelled) {
        Ok(class) => class,
        Err(_) => return INVALID,
    };
    let method = match env.get_method_id(&cancel_class, "isCancelled", "()Z") {
        Ok(method) => method,
        Err(_) => return INVALID,
    };
    let mut cancel = cancellation_callback(env, &cancelled, method);
    if use_vng {
        vng(
            &src,
            &mut dst,
            width as usize,
            height as usize,
            red_x as usize,
            red_y as usize,
            &mut cancel,
        )
    } else {
        bilinear(
            &src,
            &mut dst,
            width as usize,
            height as usize,
            red_x as usize,
            red_y as usize,
            &mut cancel,
        )
    }
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativeDemosaicBridge_bilinear(
    mut env: JNIEnv,
    _class: JClass,
    src: JFloatArray,
    dst: JFloatArray,
    width: jint,
    height: jint,
    red_x: jint,
    red_y: jint,
    cancelled: JObject,
) -> jint {
    invoke_demosaic(
        &mut env, src, dst, width, height, red_x, red_y, cancelled, false,
    )
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativeDemosaicBridge_vng(
    mut env: JNIEnv,
    _class: JClass,
    src: JFloatArray,
    dst: JFloatArray,
    width: jint,
    height: jint,
    red_x: jint,
    red_y: jint,
    cancelled: JObject,
) -> jint {
    invoke_demosaic(
        &mut env, src, dst, width, height, red_x, red_y, cancelled, true,
    )
}

#[cfg(test)]
mod tests {
    use super::{bilinear, vng};

    #[test]
    fn bilinear_preserves_native_samples_and_constant_colour() {
        let levels = [-0.125_f32, 0.25, 1.5];
        for width in [2_usize, 3, 5] {
            for height in [2_usize, 4] {
                for red_x in 0..=1 {
                    for red_y in 0..=1 {
                        let src = (0..width * height)
                            .map(|p| {
                                let x = p % width;
                                let y = p / width;
                                let own = if x & 1 == red_x && y & 1 == red_y {
                                    0
                                } else if x & 1 != red_x && y & 1 != red_y {
                                    2
                                } else {
                                    1
                                };
                                levels[own]
                            })
                            .collect::<Vec<_>>();
                        let mut dst = vec![0.0; src.len() * 3];
                        assert_eq!(
                            bilinear(&src, &mut dst, width, height, red_x, red_y, || false),
                            0
                        );
                        for p in 0..src.len() {
                            for channel in 0..3 {
                                assert_eq!(
                                    dst[p * 3 + channel].to_bits(),
                                    levels[channel].to_bits()
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn bilinear_checks_cancellation_on_each_row() {
        let src = vec![0.25_f32; 100 * 2];
        let mut dst = vec![0.0; src.len() * 3];
        let mut checks = 0;
        assert_eq!(
            bilinear(&src, &mut dst, 100, 2, 0, 0, || {
                checks += 1;
                checks == 2
            }),
            -3
        );
        assert_eq!(checks, 2);
    }

    #[test]
    fn vng_rejects_nonfinite_and_cancels() {
        let mut dst = vec![0.0; 12];
        assert_eq!(
            vng(&[0.0, 1.0, f32::NAN, 0.0], &mut dst, 2, 2, 0, 0, || false),
            -2
        );
        assert_eq!(
            vng(&[0.0, 1.0, 0.5, 0.0], &mut dst, 2, 2, 0, 0, || true),
            -3
        );
    }

    #[test]
    fn vng_reconstructs_constant_colour_on_small_border() {
        let levels = [-0.125_f32, 0.234567, 1.25];
        for width in [2_usize, 3, 4, 5] {
            for height in [2_usize, 3, 5] {
                let src = (0..width * height)
                    .map(|p| {
                        let x = p % width;
                        let y = p / width;
                        let own = if x & 1 == 0 && y & 1 == 0 {
                            0
                        } else if x & 1 == 1 && y & 1 == 1 {
                            2
                        } else {
                            1
                        };
                        levels[own]
                    })
                    .collect::<Vec<_>>();
                let mut dst = vec![0.0; src.len() * 3];
                assert_eq!(vng(&src, &mut dst, width, height, 0, 0, || false), 0);
                for p in 0..src.len() {
                    for channel in 0..3 {
                        assert!((dst[p * 3 + channel] - levels[channel]).abs() < 1e-7);
                    }
                }
            }
        }
    }
}
