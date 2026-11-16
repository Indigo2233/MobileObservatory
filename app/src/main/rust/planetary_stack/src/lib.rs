use jni::objects::{JClass, JDoubleArray, JFloatArray, JObject, JShortArray, ReleaseMode};
use jni::sys::{jint, jlong, jshort};
use jni::JNIEnv;

const INVALID: jint = -1;
const COVERAGE_LIMIT: jint = -2;
const CANCELLED: jint = -3;

struct OffsetTable {
    step: usize,
    columns: usize,
    rows: usize,
    column_index: Vec<usize>,
    column_next: Vec<usize>,
    column_fraction: Vec<f64>,
    row_base: Vec<usize>,
    row_next_base: Vec<usize>,
    row_fraction: Vec<f64>,
}

impl OffsetTable {
    fn new(width: usize, height: usize, step: usize) -> Option<Self> {
        if width == 0 || height == 0 || step == 0 {
            return None;
        }
        let columns = width.checked_add(step - 1)? / step + 1;
        let rows = height.checked_add(step - 1)? / step + 1;
        let mut table = Self {
            step,
            columns,
            rows,
            column_index: Vec::new(),
            column_next: Vec::new(),
            column_fraction: Vec::new(),
            row_base: Vec::new(),
            row_next_base: Vec::new(),
            row_fraction: Vec::new(),
        };
        table.column_index.try_reserve_exact(width).ok()?;
        table.column_next.try_reserve_exact(width).ok()?;
        table.column_fraction.try_reserve_exact(width).ok()?;
        table.row_base.try_reserve_exact(height).ok()?;
        table.row_next_base.try_reserve_exact(height).ok()?;
        table.row_fraction.try_reserve_exact(height).ok()?;

        for x in 0..width {
            let grid = x as f64 / step as f64;
            let index = grid as usize;
            table.column_index.push(index);
            table.column_next.push((index + 1).min(columns - 1));
            table.column_fraction.push(grid - index as f64);
        }
        for y in 0..height {
            let grid = y as f64 / step as f64;
            let index = grid as usize;
            table.row_base.push(index * columns);
            table
                .row_next_base
                .push((index + 1).min(rows - 1) * columns);
            table.row_fraction.push(grid - index as f64);
        }
        Some(table)
    }

    fn mesh_size(&self) -> Option<usize> {
        self.columns.checked_mul(self.rows)
    }
}

struct Accumulator {
    width: usize,
    height: usize,
    channels: usize,
    sums: Vec<f64>,
    coverage: Vec<u16>,
    table: Option<OffsetTable>,
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
        let mut sums = Vec::new();
        let mut coverage = Vec::new();
        sums.try_reserve_exact(samples).ok()?;
        coverage.try_reserve_exact(pixels).ok()?;
        sums.resize(samples, 0.0);
        coverage.resize(pixels, 0);
        Some(Self {
            width,
            height,
            channels,
            sums,
            coverage,
            table: None,
        })
    }

    fn prepare_table(&mut self, step: usize) -> bool {
        let rebuild = self
            .table
            .as_ref()
            .map(|table| table.step != step)
            .unwrap_or(true);
        if rebuild {
            self.table = OffsetTable::new(self.width, self.height, step);
        }
        self.table.is_some()
    }

    fn accumulate(
        &mut self,
        frame: &[f32],
        dx_mesh: &[f32],
        dy_mesh: &[f32],
        step: usize,
        mut cancelled: impl FnMut() -> bool,
    ) -> jint {
        let Some(samples) = self
            .width
            .checked_mul(self.height)
            .and_then(|pixels| pixels.checked_mul(self.channels))
        else {
            return INVALID;
        };
        if frame.len() < samples {
            return INVALID;
        }
        let width = self.width;
        let height = self.height;
        let channels = self.channels;
        if !self.prepare_table(step) {
            return INVALID;
        }
        let Some(table) = self.table.as_ref() else {
            return INVALID;
        };
        let Some(mesh_size) = table.mesh_size() else {
            return INVALID;
        };
        if dx_mesh.len() < mesh_size || dy_mesh.len() < mesh_size {
            return INVALID;
        }

        for y in 0..height {
            if y % 32 == 0 && cancelled() {
                return CANCELLED;
            }
            let row_top = table.row_base[y];
            let row_bottom = table.row_next_base[y];
            let mesh_y = table.row_fraction[y];
            let not_mesh_y = 1.0 - mesh_y;
            for x in 0..width {
                let column = table.column_index[x];
                let next_column = table.column_next[x];
                let mesh_x = table.column_fraction[x];
                let not_mesh_x = 1.0 - mesh_x;
                let dx_top = dx_mesh[row_top + column] as f64 * not_mesh_x
                    + dx_mesh[row_top + next_column] as f64 * mesh_x;
                let dx_bottom = dx_mesh[row_bottom + column] as f64 * not_mesh_x
                    + dx_mesh[row_bottom + next_column] as f64 * mesh_x;
                let dy_top = dy_mesh[row_top + column] as f64 * not_mesh_x
                    + dy_mesh[row_top + next_column] as f64 * mesh_x;
                let dy_bottom = dy_mesh[row_bottom + column] as f64 * not_mesh_x
                    + dy_mesh[row_bottom + next_column] as f64 * mesh_x;
                let sx = x as f64 + dx_top * not_mesh_y + dx_bottom * mesh_y;
                let sy = y as f64 + dy_top * not_mesh_y + dy_bottom * mesh_y;
                if sx < 0.0 || sy < 0.0 || sx > width as f64 - 1.0 || sy > height as f64 - 1.0 {
                    continue;
                }
                let ix = sx as usize;
                let iy = sy as usize;
                let nx = (ix + 1).min(width - 1);
                let ny = (iy + 1).min(height - 1);
                let fx = sx - ix as f64;
                let fy = sy - iy as f64;
                let a = (iy * width + ix) * channels;
                let b = (iy * width + nx) * channels;
                let c = (ny * width + ix) * channels;
                let d = (ny * width + nx) * channels;
                let p = y * width + x;
                let one_minus_fx = 1.0 - fx;
                let one_minus_fy = 1.0 - fy;

                let mut values = [0.0_f64; 3];
                let mut finite = true;
                for (channel, value) in values.iter_mut().enumerate().take(channels) {
                    *value = (frame[a + channel] as f64 * one_minus_fx
                        + frame[b + channel] as f64 * fx)
                        * one_minus_fy
                        + (frame[c + channel] as f64 * one_minus_fx
                            + frame[d + channel] as f64 * fx)
                            * fy;
                    if !value.is_finite() {
                        finite = false;
                        break;
                    }
                }
                if !finite {
                    continue;
                }
                let weight = self.coverage[p];
                if weight == u16::MAX {
                    return COVERAGE_LIMIT;
                }
                for (channel, value) in values.iter().enumerate().take(channels) {
                    self.sums[p * channels + channel] += *value;
                }
                self.coverage[p] = weight + 1;
            }
        }
        0
    }
}

fn accumulator_from_handle(handle: jlong) -> Option<&'static mut Accumulator> {
    if handle == 0 {
        return None;
    }
    // Kotlin holds each handle from one science worker.
    unsafe { (handle as *mut Accumulator).as_mut() }
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

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativePlanetaryAccumulatorBridge_create(
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
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativePlanetaryAccumulatorBridge_accumulate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    frame_array: JFloatArray,
    dx_array: JFloatArray,
    dy_array: JFloatArray,
    step: jint,
    cancelled: JObject,
) -> jint {
    let Some(accumulator) = accumulator_from_handle(handle) else {
        return INVALID;
    };
    if step <= 0 {
        return INVALID;
    }
    let expected = match accumulator
        .width
        .checked_mul(accumulator.height)
        .and_then(|pixels| pixels.checked_mul(accumulator.channels))
    {
        Some(value) => value,
        None => return INVALID,
    };
    let frame = match unsafe { env.get_array_elements(&frame_array, ReleaseMode::NoCopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    if frame.len() < expected {
        return INVALID;
    }
    let dx = match unsafe { env.get_array_elements(&dx_array, ReleaseMode::NoCopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let dy = match unsafe { env.get_array_elements(&dy_array, ReleaseMode::NoCopyBack) } {
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
    let mut cancel = cancellation_callback(&env, &cancelled, method);
    accumulator.accumulate(&frame, &dx, &dy, step as usize, &mut cancel)
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativePlanetaryAccumulatorBridge_finish(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    output_array: JFloatArray,
    coverage_array: JShortArray,
    cancelled: JObject,
) -> jlong {
    let Some(accumulator) = accumulator_from_handle(handle) else {
        return INVALID as jlong;
    };
    let samples = match accumulator
        .width
        .checked_mul(accumulator.height)
        .and_then(|pixels| pixels.checked_mul(accumulator.channels))
    {
        Some(value) => value,
        None => return INVALID as jlong,
    };
    let pixels = accumulator.width * accumulator.height;
    let mut output = match unsafe { env.get_array_elements(&output_array, ReleaseMode::CopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID as jlong,
    };
    let mut coverage =
        match unsafe { env.get_array_elements(&coverage_array, ReleaseMode::CopyBack) } {
            Ok(values) => values,
            Err(_) => return INVALID as jlong,
        };
    if output.len() < samples || coverage.len() < pixels {
        return INVALID as jlong;
    }
    let cancel_class = match env.get_object_class(&cancelled) {
        Ok(class) => class,
        Err(_) => return INVALID as jlong,
    };
    let method = match env.get_method_id(&cancel_class, "isCancelled", "()Z") {
        Ok(method) => method,
        Err(_) => return INVALID as jlong,
    };
    let mut cancel = cancellation_callback(&env, &cancelled, method);
    for y in 0..accumulator.height {
        if y % 32 == 0 && cancel() {
            return CANCELLED as jlong;
        }
        for x in 0..accumulator.width {
            let pixel = y * accumulator.width + x;
            let weight = accumulator.coverage[pixel];
            coverage[pixel] = weight as jshort;
            for channel in 0..accumulator.channels {
                let index = pixel * accumulator.channels + channel;
                output[index] = if weight > 0 {
                    (accumulator.sums[index] / weight as f64) as f32
                } else {
                    0.0
                };
            }
        }
    }
    samples as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativePlanetaryWarpBuilderBridge_build(
    mut env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    step: jint,
    global_dx: f64,
    global_dy: f64,
    points_array: JDoubleArray,
    dx_array: JFloatArray,
    dy_array: JFloatArray,
    cancelled: JObject,
) -> jint {
    if width <= 0 || height <= 0 || step <= 0 || !global_dx.is_finite() || !global_dy.is_finite() {
        return INVALID;
    }
    let Some(table) = OffsetTable::new(width as usize, height as usize, step as usize) else {
        return INVALID;
    };
    let Some(mesh_size) = table.mesh_size() else {
        return INVALID;
    };
    let points = match unsafe { env.get_array_elements(&points_array, ReleaseMode::NoCopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let mut dx = match unsafe { env.get_array_elements(&dx_array, ReleaseMode::CopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    let mut dy = match unsafe { env.get_array_elements(&dy_array, ReleaseMode::CopyBack) } {
        Ok(values) => values,
        Err(_) => return INVALID,
    };
    if points.len() % 6 != 0 || dx.len() < mesh_size || dy.len() < mesh_size {
        return INVALID;
    }
    for value in points.iter() {
        if !value.is_finite() {
            return INVALID;
        }
    }
    let cancel_class = match env.get_object_class(&cancelled) {
        Ok(class) => class,
        Err(_) => return INVALID,
    };
    let method = match env.get_method_id(&cancel_class, "isCancelled", "()Z") {
        Ok(method) => method,
        Err(_) => return INVALID,
    };
    let mut cancel = cancellation_callback(&env, &cancelled, method);
    let point_count = points.len() / 6;

    for y in 0..table.rows {
        if y % 16 == 0 && cancel() {
            return CANCELLED;
        }
        let node_y = (y * table.step) as f64;
        for x in 0..table.columns {
            let node_x = (x * table.step) as f64;
            let mut sx = 0.0;
            let mut sy = 0.0;
            let mut weight = 0.0;
            for point in 0..point_count {
                let offset = point * 6;
                let distance = ((node_x - points[offset]).powi(2)
                    + (node_y - points[offset + 1]).powi(2))
                .sqrt()
                    / points[offset + 5];
                if distance < 1.0 {
                    let point_weight = (1.0 - distance * distance).powi(3) * points[offset + 4];
                    sx += points[offset + 2] * point_weight;
                    sy += points[offset + 3] * point_weight;
                    weight += point_weight;
                }
            }
            let index = y * table.columns + x;
            dx[index] = (global_dx + sx / (weight + 0.002)) as f32;
            dy[index] = (global_dy + sy / (weight + 0.002)) as f32;
        }
    }

    for y in 0..table.rows - 1 {
        if y % 16 == 0 && cancel() {
            return CANCELLED;
        }
        for x in 0..table.columns - 1 {
            let index = y * table.columns + x;
            let ax = 1.0 + (dx[index + 1] - dx[index]) as f64 / table.step as f64;
            let ay = (dy[index + 1] - dy[index]) as f64 / table.step as f64;
            let bx = (dx[index + table.columns] - dx[index]) as f64 / table.step as f64;
            let by = 1.0 + (dy[index + table.columns] - dy[index]) as f64 / table.step as f64;
            if ax * by - ay * bx < 0.35 {
                return 1;
            }
        }
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_indigo_mobileobservatory_stacking_NativePlanetaryAccumulatorBridge_release(
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
    use super::{Accumulator, OffsetTable};

    #[test]
    fn offset_table_resolves_edges() {
        let table = OffsetTable::new(9, 8, 4).expect("table");
        assert_eq!((table.columns, table.rows), (4, 3));
        assert_eq!(table.column_index[8], 2);
        assert_eq!(table.column_next[8], 3);
        assert_eq!(table.row_base[7], 4);
        assert_eq!(table.row_next_base[7], 8);
    }

    #[test]
    fn identity_warp_accumulates_exact_values() {
        let mut accumulator = Accumulator::new(3, 2, 1).expect("accumulator");
        let frame = [1.0_f32, 2.0, 3.0, 4.0, 5.0, 6.0];
        let dx = [0.0_f32; 4];
        let dy = [0.0_f32; 4];
        assert_eq!(accumulator.accumulate(&frame, &dx, &dy, 4, || false), 0);
        assert_eq!(accumulator.accumulate(&frame, &dx, &dy, 4, || false), 0);
        assert_eq!(accumulator.coverage, vec![2; 6]);
        for (index, value) in accumulator.sums.iter().enumerate() {
            assert_eq!(*value, frame[index] as f64 * 2.0);
        }
    }

    #[test]
    fn cancellation_leaves_data_unchanged_at_first_row() {
        let mut accumulator = Accumulator::new(2, 2, 1).expect("accumulator");
        assert_eq!(
            accumulator.accumulate(&[1.0; 4], &[0.0; 4], &[0.0; 4], 4, || true),
            -3
        );
        assert_eq!(accumulator.coverage, vec![0; 4]);
        assert_eq!(accumulator.sums, vec![0.0; 4]);
    }

    #[test]
    fn out_of_bounds_warp_does_not_add_coverage() {
        let mut accumulator = Accumulator::new(2, 2, 1).expect("accumulator");
        assert_eq!(
            accumulator.accumulate(&[1.0; 4], &[-2.0; 4], &[0.0; 4], 4, || false),
            0
        );
        assert_eq!(accumulator.coverage, vec![0; 4]);
    }
}
