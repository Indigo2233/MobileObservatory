#![deny(unsafe_op_in_unsafe_fn)]

use std::cell::Cell;
use std::fs::File;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use jni::JNIEnv;
use jni::objects::{
    JByteArray, JClass, JDoubleArray, JMethodID, JObject, JObjectArray, JString, JValue,
};
use jni::sys::{jfloat, jobject, jvalue};
use mjsolex::pipeline::{
    CorrectionWarnings, Export, Images, ProcessingMemory, ProcessingOptions, ReconstructionOptions,
    View,
};
use mjsolex::{
    BandingAxis, DisplaySettings, Framing, PhysicalFlat, Product, ScanSettings, SerReader,
    Spectrum, Stretch, Trace,
};

const ANALYSIS_CLASS: &str = "com/indigo/mobileobservatory/solar/NativeSolarAnalysis";
const PREVIEW_CLASS: &str = "com/indigo/mobileobservatory/solar/NativeSolarPreview";
const RECONSTRUCTION_CLASS: &str = "com/indigo/mobileobservatory/solar/NativeSolarReconstruction";

struct AnalysisData {
    width: usize,
    height: usize,
    trace_rows: Vec<f64>,
    coefficients: [f64; 3],
    preview_pixels: Vec<u8>,
    confidence: f64,
    line_depth: f64,
    valid_columns: usize,
    residual_pixels: f64,
    illuminated_first: usize,
    illuminated_last: usize,
    roi_top: usize,
    roi_height: usize,
}

struct PreviewData {
    width: usize,
    height: usize,
    pixels: Vec<u8>,
}

struct ReconstructionData {
    display_png: Option<String>,
    linear_png: Option<String>,
    linear_fits: Option<String>,
    width: usize,
    height: usize,
    frame_stride: u32,
    source_frames: u32,
    warnings: Vec<String>,
    geometry_applied: bool,
    banding_applied: BandingAxis,
    banding_width: Option<u8>,
    scan_rows_aligned: bool,
    oscillation_period: Option<f64>,
    oscillation_amplitude: Option<f64>,
    synthetic_flat_strength: Option<u8>,
    physical_flat_applied: bool,
    additional_paths: Vec<Option<String>>,
}

const EXTRA_PRODUCTS: [(i32, Product, bool, &str); 7] = [
    (8, Product::Gray, true, "enhanced"),
    (16, Product::Negative, false, "negative"),
    (32, Product::Eclipse, false, "eclipse"),
    (64, Product::Hydrogen, false, "halpha-color"),
    (128, Product::Calcium, false, "calcium-color"),
    (256, Product::Helium, false, "helium-color"),
    (512, Product::Doppler, false, "doppler"),
];

fn line_seed(column: i32, row: i32) -> Option<(usize, usize)> {
    (column >= 0 && row >= 0).then_some((column as usize, row as usize))
}

fn analysis(
    path: &Path,
    seed: Option<(usize, usize)>,
    mut progress: impl FnMut(f32) -> bool,
) -> Result<AnalysisData, mjsolex::Error> {
    let mut reader = SerReader::open(File::open(path)?)?;
    let header = reader.header();
    let spectrum = Spectrum::average(&mut reader, |value| progress(value * 0.7))?;
    let trace = spectrum.fit_line(seed)?;
    if !progress(0.8) {
        return Err(mjsolex::Error::Cancelled);
    }
    let trace_rows = (0..header.width).map(|x| trace.row(x)).collect::<Vec<_>>();
    let coefficients = fit_trace(&trace_rows);
    let profile = spectrum.line_profile(trace);
    let (illuminated_first, illuminated_last) =
        illuminated_columns(&spectrum, header.width, header.height);
    if illuminated_last < illuminated_first {
        return Err(mjsolex::Error::NoLine);
    }
    let (line_depth, valid_columns, residual_pixels, trace_top, trace_bottom) = trace_quality(
        &spectrum,
        &trace,
        header.width,
        header.height,
        illuminated_first,
        illuminated_last,
    );
    let line_depth = profile.fit.map_or(line_depth, |fit| {
        let continuum = fit.continuum_at(fit.center);
        if continuum > 0.0 {
            ((continuum - fit.intensity_at(fit.center)) / continuum).clamp(0.0, 1.0)
        } else {
            line_depth
        }
    });
    let available_columns = illuminated_last - illuminated_first + 1;
    let confidence = if available_columns == 0 {
        0.0
    } else {
        (valid_columns as f64 / available_columns as f64)
            * (-residual_pixels / 2.0).exp()
            * (line_depth / 0.05).clamp(0.0, 1.0)
    }
    .clamp(0.0, 1.0);
    let margin = 8.0;
    let roi_top = (trace_top.floor() - margin).max(0.0) as usize;
    let roi_bottom = (trace_bottom.ceil() + margin).min((header.height - 1) as f64) as usize;
    let preview_pixels = preview_from_spectrum(&spectrum, header.width, header.height);
    if !progress(1.0) {
        return Err(mjsolex::Error::Cancelled);
    }
    Ok(AnalysisData {
        width: header.width,
        height: header.height,
        trace_rows,
        coefficients,
        preview_pixels,
        confidence,
        line_depth,
        valid_columns,
        residual_pixels,
        illuminated_first,
        illuminated_last,
        roi_top,
        roi_height: roi_bottom.saturating_sub(roi_top) + 1,
    })
}

fn preview(
    path: &Path,
    mut progress: impl FnMut(f32) -> bool,
) -> Result<PreviewData, mjsolex::Error> {
    let mut reader = SerReader::open(File::open(path)?)?;
    let header = reader.header();
    let spectrum = Spectrum::average(&mut reader, &mut progress)?;
    if !progress(1.0) {
        return Err(mjsolex::Error::Cancelled);
    }
    Ok(PreviewData {
        width: header.width,
        height: header.height,
        pixels: preview_from_spectrum(&spectrum, header.width, header.height),
    })
}

fn illuminated_columns(spectrum: &Spectrum, width: usize, height: usize) -> (usize, usize) {
    if width == 0 {
        return (0, 0);
    }
    let mut columns = vec![0.0_f64; width];
    for row in spectrum.pixels().chunks_exact(width).take(height) {
        for (column, value) in columns.iter_mut().zip(row) {
            *column += f64::from(*value);
        }
    }
    let peak = columns.iter().copied().fold(0.0, f64::max);
    let threshold = peak * 0.12;
    let first = columns
        .iter()
        .position(|value| *value >= threshold)
        .unwrap_or(0);
    let last = columns
        .iter()
        .rposition(|value| *value >= threshold)
        .unwrap_or(width - 1);
    (first, last.max(first))
}

fn trial_coverage(
    path: &Path,
    progress: impl FnMut(f32) -> bool,
) -> Result<[i32; 6], mjsolex::Error> {
    measure_sweep(SerReader::open(File::open(path)?)?, progress)
}

fn measure_sweep<R: std::io::Read + std::io::Seek>(
    mut reader: SerReader<R>,
    mut progress: impl FnMut(f32) -> bool,
) -> Result<[i32; 6], mjsolex::Error> {
    let header = reader.header();
    let count = header.frames.min(64);
    if count < 16 || header.trimmed || header.color != mjsolex::ColorMode::Mono {
        return Err(mjsolex::Error::InvalidSer(
            "sweep calibration requires a complete monochrome SER with at least 16 frames",
        ));
    }
    let mut windows = Vec::with_capacity(count as usize);
    for index in 0..count {
        let start = (u64::from(index) * u64::from(header.frames) / u64::from(count)) as u32;
        let end = (u64::from(index + 1) * u64::from(header.frames) / u64::from(count)) as u32;
        let spectrum = Spectrum::average_range(&mut reader, start..end, |value| {
            progress((index as f32 + value) / (2 * count) as f32)
        })?;
        let signal = spectrum
            .pixels()
            .iter()
            .copied()
            .map(f64::from)
            .sum::<f64>();
        windows.push((start, end, signal));
    }
    let peak = windows.iter().map(|window| window.2).fold(0., f64::max);
    let background = windows[0].2.min(windows[windows.len() - 1].2);
    let contrast = peak - background;
    if contrast <= (peak * 0.25).max(header.pixel_count() as f64) {
        return Err(mjsolex::Error::InvalidSer(
            "sweep signal has insufficient contrast",
        ));
    }
    let on_disk = |window: &(u32, u32, f64)| window.2 > background + 0.06 * contrast;
    let first = windows
        .iter()
        .position(on_disk)
        .ok_or(mjsolex::Error::NoLine)?;
    let last = windows
        .iter()
        .rposition(on_disk)
        .ok_or(mjsolex::Error::NoLine)?;
    if first < 2
        || last + 2 >= windows.len()
        || last - first < 3
        || !windows[first..=last].iter().all(on_disk)
    {
        return Err(mjsolex::Error::InvalidSer(
            "sweep needs continuous disk coverage and blank sky at both ends",
        ));
    }
    let mut left = header.width - 1;
    let mut right = 0;
    let mut first_disk = None;
    let mut end_disk = 0;
    let blank = Spectrum::average_range(&mut reader, 0..windows[first - 1].1, |_| progress(0.5))?;
    let mut background_columns = vec![0.0_f64; header.width];
    for row in blank.pixels().chunks_exact(header.width) {
        for (sum, value) in background_columns.iter_mut().zip(row) {
            *sum += f64::from(*value);
        }
    }
    drop(blank);
    let mut columns = vec![0.0_f64; header.width];
    for frame in 0..header.frames {
        if !progress(0.5 + 0.5 * frame as f32 / header.frames as f32) {
            return Err(mjsolex::Error::Cancelled);
        }
        reader.mono_column_sums(frame, &mut columns)?;
        for (column, background) in columns.iter_mut().zip(&background_columns) {
            *column -= background;
        }
        let threshold = (0.12 * columns.iter().copied().fold(0.0_f64, f64::max))
            .max(header.height as f64 * 256.0);
        // The first and last bright windows can straddle a limb. Their interiors
        // are resolved below by checking continuity between actual bright frames.
        if let Some(a) = columns.iter().position(|&value| value > threshold) {
            if first_disk.is_some() && frame != end_disk {
                return Err(mjsolex::Error::InvalidSer(
                    "sweep lost usable signal inside the disk",
                ));
            }
            first_disk.get_or_insert(frame);
            end_disk = frame + 1;
            left = left.min(a);
            right = right.max(
                columns
                    .iter()
                    .rposition(|&value| value > threshold)
                    .unwrap(),
            );
        }
    }
    if !progress(1.0) {
        return Err(mjsolex::Error::Cancelled);
    }
    let first_disk = first_disk.ok_or(mjsolex::Error::NoLine)?;
    if right <= left || first_disk > windows[first].1 || end_disk < windows[last].0 {
        return Err(mjsolex::Error::NoLine);
    }
    Ok([
        header.frames as i32,
        header.width as i32,
        first_disk as i32,
        end_disk as i32,
        left as i32,
        right as i32,
    ])
}

fn trace_quality(
    spectrum: &Spectrum,
    trace: &Trace,
    width: usize,
    height: usize,
    first: usize,
    last: usize,
) -> (f64, usize, f64, f64, f64) {
    let mut valid = 0usize;
    let mut residual_sum = 0.0;
    let mut depths = Vec::new();
    let mut trace_top = f64::INFINITY;
    let mut trace_bottom = f64::NEG_INFINITY;
    for x in first..=last {
        let predicted = trace.row(x);
        trace_top = trace_top.min(predicted);
        trace_bottom = trace_bottom.max(predicted);
        let center = predicted.round() as isize;
        let mut best: Option<(f64, f64, f64)> = None;
        for y in (center - 4).max(1)..=(center + 4).min(height as isize - 2) {
            let y = y as usize;
            let value = f64::from(spectrum.pixels()[y * width + x]);
            let before = f64::from(spectrum.pixels()[(y - 1) * width + x]);
            let after = f64::from(spectrum.pixels()[(y + 1) * width + x]);
            if value > before || value > after {
                continue;
            }
            let mut continuum = value;
            for offset in 4..=8 {
                if y >= offset {
                    continuum =
                        continuum.max(f64::from(spectrum.pixels()[(y - offset) * width + x]));
                }
                if y + offset < height {
                    continuum =
                        continuum.max(f64::from(spectrum.pixels()[(y + offset) * width + x]));
                }
            }
            let depth = continuum - value;
            let score = depth / (1.0 + (y as f64 - predicted).abs());
            if best.is_none_or(|(_, _, old)| score > old) {
                best = Some((depth / continuum.max(1.0), y as f64, score));
            }
        }
        if let Some((relative_depth, observed, _)) =
            best.filter(|(relative_depth, _, _)| *relative_depth >= 0.003)
        {
            valid += 1;
            residual_sum += (observed - predicted).powi(2);
            depths.push(relative_depth);
        }
    }
    depths.sort_by(f64::total_cmp);
    let depth = depths.get(depths.len() / 2).copied().unwrap_or(0.0);
    let residual = if valid > 0 {
        (residual_sum / valid as f64).sqrt()
    } else {
        0.0
    };
    if !trace_top.is_finite() {
        trace_top = predicted_row(trace, first);
    }
    if !trace_bottom.is_finite() {
        trace_bottom = predicted_row(trace, last);
    }
    (depth, valid, residual, trace_top, trace_bottom)
}

fn predicted_row(trace: &Trace, column: usize) -> f64 {
    trace.row(column.min(usize::MAX - 1))
}

fn preview_from_spectrum(spectrum: &Spectrum, width: usize, height: usize) -> Vec<u8> {
    let pixels = spectrum.pixels();
    let stretch = Stretch::estimate(width, height, |index| pixels[index].round() as u16);
    pixels
        .iter()
        .take(width * height)
        .map(|value| stretch.map(value.round() as u16))
        .collect()
}

fn fit_trace(rows: &[f64]) -> [f64; 3] {
    if rows.is_empty() {
        return [0.0, 0.0, 0.0];
    }
    let mut matrix = [[0.0_f64; 4]; 3];
    let denominator = (rows.len() - 1).max(1) as f64;
    for (column, row) in rows.iter().enumerate() {
        let x = if rows.len() == 1 {
            0.0
        } else {
            2.0 * column as f64 / denominator - 1.0
        };
        let basis = [1.0, x, x * x];
        for (i, basis_value) in basis.iter().enumerate() {
            for (j, other_basis) in basis.iter().enumerate() {
                matrix[i][j] += basis_value * other_basis;
            }
            matrix[i][3] += basis_value * row;
        }
    }
    for pivot in 0..3 {
        let mut best = pivot;
        for row in pivot + 1..3 {
            if matrix[row][pivot].abs() > matrix[best][pivot].abs() {
                best = row;
            }
        }
        if matrix[best][pivot].abs() < 1e-10 {
            let mean = rows.iter().sum::<f64>() / rows.len() as f64;
            return [mean, 0.0, 0.0];
        }
        matrix.swap(pivot, best);
        let divisor = matrix[pivot][pivot];
        for value in &mut matrix[pivot][pivot..] {
            *value /= divisor;
        }
        let pivot_row = matrix[pivot];
        for (row, values) in matrix.iter_mut().enumerate() {
            if row == pivot {
                continue;
            }
            let factor = values[pivot];
            for (value, pivot_value) in values[pivot..].iter_mut().zip(&pivot_row[pivot..]) {
                *value -= factor * pivot_value;
            }
        }
    }
    [matrix[0][3], matrix[1][3], matrix[2][3]]
}

#[allow(clippy::too_many_arguments)]
fn reconstruction(
    source: &Path,
    output: &Path,
    shift: f64,
    stride: u32,
    flip_h: bool,
    flip_v: bool,
    rotation: f64,
    products: i32,
    memory_headroom: Option<u64>,
    scan_banding: i32,
    scan_banding_width: i32,
    scan_oscillation: bool,
    scan_jagged: bool,
    synthetic_flat_strength: i32,
    flat_source: Option<&Path>,
    correct_disk: bool,
    invert_prominences: bool,
    prominence_strength: i32,
    wavelength: f64,
    dispersion: f64,
    wing_offset: f64,
    crop_factor: f64,
    seed: Option<(usize, usize)>,
    chinese: bool,
    mut progress: impl FnMut(f32) -> bool,
) -> Result<ReconstructionData, mjsolex::Error> {
    if stride == 0 || !shift.is_finite() || !rotation.is_finite() {
        return Err(mjsolex::Error::InvalidStride);
    }
    let banding = match scan_banding {
        0 => BandingAxis::Off,
        1 => BandingAxis::Slit,
        2 => BandingAxis::Scan,
        3 => BandingAxis::Both,
        _ => {
            return Err(mjsolex::Error::InvalidSer(
                "invalid scan-banding correction axis",
            ));
        }
    };
    let banding_width = match scan_banding_width {
        0 => None,
        width @ 7..=51 if width % 2 == 1 => Some(width as u8),
        _ => {
            return Err(mjsolex::Error::InvalidSer(
                "banding reference width must be odd and from 7 to 51 pixels",
            ));
        }
    };
    let synthetic_flat = match synthetic_flat_strength {
        0 => None,
        strength @ 1..=100 => Some(strength as u8),
        _ => {
            return Err(mjsolex::Error::InvalidSer(
                "synthetic flat strength must be from 1 to 100",
            ));
        }
    };
    if synthetic_flat.is_some() && flat_source.is_some() {
        return Err(mjsolex::Error::InvalidSer(
            "synthetic and physical flat correction are mutually exclusive",
        ));
    }
    let mut analysis_reader = SerReader::open(File::open(source)?)?;
    let header = analysis_reader.header();
    let spectrum = Spectrum::average(&mut analysis_reader, |value| progress(value * 0.12))?;
    let trace = spectrum.fit_line(seed)?;
    let fwhm = spectrum.line_profile(trace).fit.map(|fit| fit.fwhm);
    let physical_flat = flat_source
        .map(|path| {
            let mut reader = SerReader::open(File::open(path)?)?;
            let flat = PhysicalFlat::from_ser(&mut reader, &spectrum, |value| {
                progress(0.12 + value * 0.08)
            })?;
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("flat.ser")
                .to_owned();
            Ok::<_, mjsolex::Error>((Arc::new(flat), name))
        })
        .transpose()?;
    let reconstruction_start = if physical_flat.is_some() { 0.20 } else { 0.12 };
    let options = ReconstructionOptions {
        wavelength,
        dispersion: (dispersion != 0.0).then_some(dispersion),
        shift,
        stride,
        memory: memory_headroom
            .map_or_else(ProcessingMemory::default, ProcessingMemory::with_headroom),
        doppler: products & 512 != 0,
        wing_offset,
        palettes: [products & 64 != 0, products & 128 != 0, products & 256 != 0],
        processing: ProcessingOptions {
            scan: ScanSettings {
                banding,
                banding_width,
                oscillation: scan_oscillation,
                jagged: scan_jagged,
            },
            correct_disk,
            synthetic_strength: synthetic_flat.unwrap_or(100),
            framing: Framing {
                flip_x: flip_h,
                flip_y: flip_v,
                rotation_deg: rotation,
                crop_factor: (crop_factor != 0.0).then_some(crop_factor),
            },
            enhance: products & 8 != 0,
            negative: products & 16 != 0,
            eclipse: products & 32 != 0,
            invert_prominences,
            prominence_strength: prominence_strength.clamp(1, 100) as u8,
        },
        synthetic_flat: synthetic_flat.is_some(),
        physical_flat,
    };
    let wing_shift = options.wing_shift(&spectrum, trace);
    let reader = SerReader::open(File::open(source)?)?;
    let source_name = source
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("scan.ser")
        .to_owned();
    let (images, correction_warnings) =
        options.build(reader, source_name, trace, fwhm, wing_shift, |value| {
            progress(reconstruction_start + value * (0.86 - reconstruction_start))
        })?;
    if !progress(0.88) {
        return Err(mjsolex::Error::Cancelled);
    }
    std::fs::create_dir_all(output)?;
    let stem = source
        .file_stem()
        .and_then(|name| name.to_str())
        .unwrap_or("solar-scan")
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
                character
            } else {
                '-'
            }
        })
        .take(80)
        .collect::<String>();
    if !progress(0.89) {
        return Err(mjsolex::Error::Cancelled);
    }
    let display = (products & 1 != 0)
        .then(|| {
            write_export(
                &images,
                output,
                Export::Display(View::GRAY, DisplaySettings::default()),
                &format!("{stem}-display.png"),
            )
        })
        .transpose()?;
    if !progress(0.92) {
        return Err(mjsolex::Error::Cancelled);
    }
    let linear = (products & 2 != 0)
        .then(|| {
            write_export(
                &images,
                output,
                Export::RawPng,
                &format!("{stem}-linear.png"),
            )
        })
        .transpose()?;
    if !progress(0.96) {
        return Err(mjsolex::Error::Cancelled);
    }
    let fits = (products & 4 != 0)
        .then(|| write_export(&images, output, Export::Fits, &format!("{stem}-linear.fit")))
        .transpose()?;
    let mut additional_paths = Vec::with_capacity(EXTRA_PRODUCTS.len());
    for (bit, product, enhanced, suffix) in EXTRA_PRODUCTS {
        if !progress(0.98) {
            return Err(mjsolex::Error::Cancelled);
        }
        let view = View { product, enhanced };
        let path = (products & bit != 0 && images.views().any(|available| available == view))
            .then(|| {
                write_export(
                    &images,
                    output,
                    Export::Display(view, DisplaySettings::default()),
                    &format!("{stem}-{suffix}.png"),
                )
            })
            .transpose()?;
        additional_paths.push(path);
    }
    if !progress(1.0) {
        return Err(mjsolex::Error::Cancelled);
    }
    let metadata = images.metadata();
    let scan = metadata.scan_corrections;
    let (oscillation_period, oscillation_amplitude) = scan
        .oscillation
        .map_or((None, None), |(period, amplitude)| {
            (Some(period), Some(amplitude))
        });
    Ok(ReconstructionData {
        display_png: display,
        linear_png: linear,
        linear_fits: fits,
        width: images.center().width(),
        height: images.center().height(),
        frame_stride: images.metadata().frame_step.unwrap_or(stride),
        source_frames: header.frames,
        warnings: warning_messages(&correction_warnings, chinese),
        geometry_applied: metadata.geometry.is_some(),
        banding_applied: scan.banding,
        banding_width: scan.banding_width,
        scan_rows_aligned: scan.jagged,
        oscillation_period,
        oscillation_amplitude,
        synthetic_flat_strength: metadata.synthetic_flat_strength,
        physical_flat_applied: metadata.flat_file.is_some(),
        additional_paths,
    })
}

fn write_export(
    images: &Images,
    output: &Path,
    export: Export,
    file_name: &str,
) -> Result<String, mjsolex::Error> {
    let path = output.join(file_name);
    let mut file = File::create(&path)?;
    images.write(export, &mut file)?;
    file.sync_all()?;
    Ok(path.to_string_lossy().into_owned())
}

fn warning_messages(warnings: &CorrectionWarnings, chinese: bool) -> Vec<String> {
    warnings
        .entries()
        .into_iter()
        .filter_map(|(error, english, chinese_text)| {
            error
                .as_ref()
                .map(|value| format!("{}{value}", if chinese { chinese_text } else { english }))
        })
        .collect()
}

fn observer_callback(
    env: &JNIEnv,
    observer: &JObject,
    cancel_method: JMethodID,
    progress_method: JMethodID,
) -> impl FnMut(f32) -> bool {
    let native_env = env.get_native_interface();
    let observer_raw = observer.as_raw();
    let cancel_raw = cancel_method.into_raw();
    let progress_raw = progress_method.into_raw();
    let call_boolean = unsafe { (**native_env).CallBooleanMethodA };
    let call_void = unsafe { (**native_env).CallVoidMethodA };
    let exception_check = unsafe { (**native_env).ExceptionCheck };
    let exception_clear = unsafe { (**native_env).ExceptionClear };
    let last_progress = Cell::new(i32::MIN);
    move |value: f32| {
        let exception = exception_check
            .map(|check| unsafe { check(native_env) != 0 })
            .unwrap_or(true);
        if exception {
            if let Some(clear) = exception_clear {
                unsafe { clear(native_env) };
            }
            return false;
        }
        let cancelled = match call_boolean {
            Some(call) => unsafe {
                call(native_env, observer_raw, cancel_raw, std::ptr::null()) != 0
            },
            None => true,
        };
        let exception = exception_check
            .map(|check| unsafe { check(native_env) != 0 })
            .unwrap_or(true);
        if exception {
            if let Some(clear) = exception_clear {
                unsafe { clear(native_env) };
            }
            return false;
        }
        if cancelled {
            return false;
        }
        let percent = (value.clamp(0.0, 1.0) * 100.0).round() as i32;
        if last_progress.get() != percent {
            last_progress.set(percent);
            if let Some(call) = call_void {
                let args = [jvalue { f: value as jfloat }];
                unsafe {
                    call(native_env, observer_raw, progress_raw, args.as_ptr());
                }
            }
        }
        let exception = exception_check
            .map(|check| unsafe { check(native_env) != 0 })
            .unwrap_or(true);
        if exception {
            if let Some(clear) = exception_clear {
                unsafe { clear(native_env) };
            }
            false
        } else {
            true
        }
    }
}

fn observer_methods(
    env: &mut JNIEnv,
    observer: &JObject,
) -> Result<(JMethodID, JMethodID), String> {
    let class = env
        .get_object_class(observer)
        .map_err(|error| error.to_string())?;
    let cancel = env
        .get_method_id(&class, "isCancelled", "()Z")
        .map_err(|error| error.to_string())?;
    let progress = env
        .get_method_id(&class, "onProgress", "(F)V")
        .map_err(|error| error.to_string())?;
    Ok((cancel, progress))
}

fn throw_java(env: &mut JNIEnv, message: impl Into<String>) -> jobject {
    let _ = env.throw_new("java/lang/IllegalStateException", message.into());
    JObject::null().into_raw()
}

fn analysis_object<'local>(
    env: &mut JNIEnv<'local>,
    data: AnalysisData,
) -> Result<JObject<'local>, jni::errors::Error> {
    let trace: JDoubleArray<'_> = env.new_double_array(data.trace_rows.len() as i32)?;
    env.set_double_array_region(&trace, 0, &data.trace_rows)?;
    let preview_i8 = data
        .preview_pixels
        .iter()
        .map(|value| *value as i8)
        .collect::<Vec<_>>();
    let preview: JByteArray<'_> = env.new_byte_array(preview_i8.len() as i32)?;
    env.set_byte_array_region(&preview, 0, &preview_i8)?;
    let class = env.find_class(ANALYSIS_CLASS)?;
    env.new_object(
        class,
        "(II[D[BDDDDDIDIIII)V",
        &[
            JValue::Int(data.width as i32),
            JValue::Int(data.height as i32),
            JValue::Object(&trace),
            JValue::Object(&preview),
            JValue::Double(data.coefficients[0]),
            JValue::Double(data.coefficients[1]),
            JValue::Double(data.coefficients[2]),
            JValue::Double(data.confidence),
            JValue::Double(data.line_depth),
            JValue::Int(data.valid_columns as i32),
            JValue::Double(data.residual_pixels),
            JValue::Int(data.illuminated_first as i32),
            JValue::Int(data.illuminated_last as i32),
            JValue::Int(data.roi_top as i32),
            JValue::Int(data.roi_height as i32),
        ],
    )
}

fn preview_object<'local>(
    env: &mut JNIEnv<'local>,
    data: PreviewData,
) -> Result<JObject<'local>, jni::errors::Error> {
    let pixels_i8 = data
        .pixels
        .iter()
        .map(|value| *value as i8)
        .collect::<Vec<_>>();
    let pixels: JByteArray<'_> = env.new_byte_array(pixels_i8.len() as i32)?;
    env.set_byte_array_region(&pixels, 0, &pixels_i8)?;
    let class = env.find_class(PREVIEW_CLASS)?;
    env.new_object(
        class,
        "(II[B)V",
        &[
            JValue::Int(data.width as i32),
            JValue::Int(data.height as i32),
            JValue::Object(&pixels),
        ],
    )
}

fn optional_string<'local>(
    env: &mut JNIEnv<'local>,
    value: Option<String>,
) -> Result<JObject<'local>, jni::errors::Error> {
    value.map_or_else(
        || Ok(JObject::null()),
        |value| env.new_string(value).map(|value| value.into()),
    )
}

fn reconstruction_object<'local>(
    env: &mut JNIEnv<'local>,
    data: ReconstructionData,
) -> Result<JObject<'local>, jni::errors::Error> {
    let display = optional_string(env, data.display_png)?;
    let linear = optional_string(env, data.linear_png)?;
    let fits = optional_string(env, data.linear_fits)?;
    let paths = env.new_object_array(
        data.additional_paths.len() as i32,
        "java/lang/String",
        JObject::null(),
    )?;
    for (index, path) in data.additional_paths.into_iter().enumerate() {
        if let Some(path) = path {
            let value = env.new_string(path)?;
            env.set_object_array_element(&paths, index as i32, &value)?;
        }
    }
    let string_class = env.find_class("java/lang/String")?;
    let warnings: JObjectArray<'_> =
        env.new_object_array(data.warnings.len() as i32, string_class, JObject::null())?;
    for (index, warning) in data.warnings.into_iter().enumerate() {
        let value = env.new_string(warning)?;
        env.set_object_array_element(&warnings, index as i32, &value)?;
    }
    let class = env.find_class(RECONSTRUCTION_CLASS)?;
    let banding_applied = match data.banding_applied {
        BandingAxis::Off => 0,
        BandingAxis::Slit => 1,
        BandingAxis::Scan => 2,
        BandingAxis::Both => 3,
    };
    env.new_object(
        class,
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIII[Ljava/lang/String;ZIIZZDDIZ[Ljava/lang/String;)V",
        &[
            JValue::Object(&display),
            JValue::Object(&linear),
            JValue::Object(&fits),
            JValue::Int(data.width as i32),
            JValue::Int(data.height as i32),
            JValue::Int(data.frame_stride as i32),
            JValue::Int(data.source_frames as i32),
            JValue::Object(&warnings),
            JValue::Bool(u8::from(data.geometry_applied)),
            JValue::Int(banding_applied),
            JValue::Int(data.banding_width.map_or(0, i32::from)),
            JValue::Bool(u8::from(data.scan_rows_aligned)),
            JValue::Bool(u8::from(data.oscillation_period.is_some())),
            JValue::Double(data.oscillation_period.unwrap_or(0.0)),
            JValue::Double(data.oscillation_amplitude.unwrap_or(0.0)),
            JValue::Int(data.synthetic_flat_strength.map_or(0, i32::from)),
            JValue::Bool(u8::from(data.physical_flat_applied)),
            JValue::Object(&paths),
        ],
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_indigo_mobileobservatory_solar_NativeSolarBridge_preview(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    observer: JObject,
) -> jobject {
    let path = match env.get_string(&source) {
        Ok(value) => PathBuf::from(String::from(value)),
        Err(error) => return throw_java(&mut env, error.to_string()),
    };
    let (cancel, progress) = match observer_methods(&mut env, &observer) {
        Ok(value) => value,
        Err(error) => return throw_java(&mut env, error),
    };
    let result = {
        let mut callback = observer_callback(&env, &observer, cancel, progress);
        preview(&path, &mut callback)
    };
    match result.and_then(|data| {
        preview_object(&mut env, data)
            .map_err(|error| mjsolex::Error::Io(std::io::Error::other(error.to_string())))
    }) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_java(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_indigo_mobileobservatory_solar_NativeSolarBridge_analyze(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    column: i32,
    row: i32,
    observer: JObject,
) -> jobject {
    let path = match env.get_string(&source) {
        Ok(value) => PathBuf::from(String::from(value)),
        Err(error) => return throw_java(&mut env, error.to_string()),
    };
    let (cancel, progress) = match observer_methods(&mut env, &observer) {
        Ok(value) => value,
        Err(error) => return throw_java(&mut env, error),
    };
    let result = {
        let mut callback = observer_callback(&env, &observer, cancel, progress);
        analysis(&path, line_seed(column, row), &mut callback)
    };
    match result.and_then(|data| {
        analysis_object(&mut env, data)
            .map_err(|error| mjsolex::Error::Io(std::io::Error::other(error.to_string())))
    }) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_java(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_indigo_mobileobservatory_solar_NativeSolarBridge_trialCoverage(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    observer: JObject,
) -> jobject {
    let path = match env.get_string(&source) {
        Ok(value) => PathBuf::from(String::from(value)),
        Err(error) => return throw_java(&mut env, error.to_string()),
    };
    let (cancel, progress) = match observer_methods(&mut env, &observer) {
        Ok(value) => value,
        Err(error) => return throw_java(&mut env, error),
    };
    let result = {
        let mut callback = observer_callback(&env, &observer, cancel, progress);
        trial_coverage(&path, &mut callback)
    };
    match result {
        Ok(values) => match env.new_int_array(values.len() as i32).and_then(|array| {
            env.set_int_array_region(&array, 0, &values)?;
            Ok(array)
        }) {
            Ok(array) => array.into_raw(),
            Err(error) => throw_java(&mut env, error.to_string()),
        },
        Err(error) => throw_java(&mut env, error.to_string()),
    }
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_indigo_mobileobservatory_solar_NativeSolarBridge_reconstruct(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    output: JString,
    line_shift: f64,
    frame_stride: i32,
    flip_horizontal: bool,
    flip_vertical: bool,
    rotation_degrees: f64,
    products: i32,
    memory_headroom: i64,
    scan_banding: i32,
    scan_banding_width: i32,
    scan_oscillation: bool,
    scan_jagged: bool,
    synthetic_flat_strength: i32,
    flat_source: JString,
    correct_disk: bool,
    invert_prominences: bool,
    prominence_strength: i32,
    wavelength: f64,
    dispersion: f64,
    wing_offset: f64,
    crop_factor: f64,
    seed_column: i32,
    seed_row: i32,
    chinese: bool,
    observer: JObject,
) -> jobject {
    let source = match env.get_string(&source) {
        Ok(value) => PathBuf::from(String::from(value)),
        Err(error) => return throw_java(&mut env, error.to_string()),
    };
    let output = match env.get_string(&output) {
        Ok(value) => PathBuf::from(String::from(value)),
        Err(error) => return throw_java(&mut env, error.to_string()),
    };
    let flat_source = match env.get_string(&flat_source) {
        Ok(value) => {
            let value = String::from(value);
            (!value.is_empty()).then(|| PathBuf::from(value))
        }
        Err(error) => return throw_java(&mut env, error.to_string()),
    };
    if frame_stride <= 0 {
        return throw_java(&mut env, "frame stride must be positive");
    }
    if memory_headroom < -1 {
        return throw_java(&mut env, "memory headroom must be non-negative or -1");
    }
    let (cancel, progress) = match observer_methods(&mut env, &observer) {
        Ok(value) => value,
        Err(error) => return throw_java(&mut env, error),
    };
    let result = {
        let mut callback = observer_callback(&env, &observer, cancel, progress);
        reconstruction(
            &source,
            &output,
            line_shift,
            frame_stride as u32,
            flip_horizontal,
            flip_vertical,
            rotation_degrees,
            products,
            (memory_headroom >= 0).then_some(memory_headroom as u64),
            scan_banding,
            scan_banding_width,
            scan_oscillation,
            scan_jagged,
            synthetic_flat_strength,
            flat_source.as_deref(),
            correct_disk,
            invert_prominences,
            prominence_strength,
            wavelength,
            dispersion,
            wing_offset,
            crop_factor,
            line_seed(seed_column, seed_row),
            chinese,
            &mut callback,
        )
    };
    match result.and_then(|data| {
        reconstruction_object(&mut env, data)
            .map_err(|error| mjsolex::Error::Io(std::io::Error::other(error.to_string())))
    }) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_java(&mut env, error.to_string()),
    }
}

#[cfg(test)]
mod sweep_tests {
    use super::*;
    use std::io::Cursor;

    fn sweep_ser(
        first: usize,
        end: usize,
        clipped: bool,
        dim_frame: Option<u8>,
    ) -> SerReader<Cursor<Vec<u8>>> {
        let mut bytes = vec![0; 178];
        bytes[..14].copy_from_slice(b"LUCAM-RECORDER");
        for (offset, value) in [(22, 1_u32), (26, 40), (30, 8), (34, 8), (38, 640)] {
            bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for frame in 0..640 {
            let active = (first..end).contains(&frame);
            let columns = if clipped && frame == 300 {
                0..24
            } else {
                8..32
            };
            for _ in 0..8 {
                bytes.extend((0..40).map(|x| {
                    if active && columns.contains(&x) {
                        if frame == 300 {
                            dim_frame.unwrap_or(90)
                        } else {
                            90
                        }
                    } else {
                        10
                    }
                }));
            }
        }
        SerReader::open(Cursor::new(bytes)).unwrap()
    }

    #[test]
    fn measures_slit_margins_with_camera_black_level() {
        assert_eq!(
            measure_sweep(sweep_ser(80, 560, false, None), |_| true).unwrap(),
            [640, 40, 80, 560, 8, 31],
        );
        let clipped = measure_sweep(sweep_ser(80, 560, true, None), |_| true).unwrap();
        assert_eq!(clipped[4], 0);
    }

    #[test]
    fn rejects_missing_blank_sky_and_checks_cancel() {
        assert!(measure_sweep(sweep_ser(0, 560, false, None), |_| true).is_err());
        assert!(measure_sweep(sweep_ser(80, 640, false, None), |_| true).is_err());
        assert!(matches!(
            measure_sweep(sweep_ser(80, 560, false, None), |_| false),
            Err(mjsolex::Error::Cancelled)
        ));
    }

    #[test]
    fn rejects_dark_or_ambiguous_frames_inside_the_disk() {
        for intensity in [10, 11] {
            assert!(measure_sweep(sweep_ser(80, 560, true, Some(intensity)), |_| true).is_err());
        }
    }
}
