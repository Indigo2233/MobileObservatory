import com.indigo.mobileobservatory.camera.*;
import com.indigo.mobileobservatory.guide.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Desktop microbenchmark of production classes; does not estimate Android device latency. */
public class GuideReconstructionBenchmark {
    private static volatile long sink;

    private static double center(int index, int length, int count) {
        return 40 + (index + .4) * (length - 80) / count;
    }

    private static FrameData frame(int width, int height) {
        byte[] data = new byte[width * height * 2];
        Random random = new Random(585);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int phase = (y & 1) * 2 + (x & 1);
            int value = 300 + random.nextInt(31) + (phase == 0 ? 10 : phase == 3 ? -10 : 0);
            int offset = (y * width + x) * 2;
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >> 8);
        }
        for (int star = 0; star < 20; star++) {
            double cx = center(star % 5, width, 5), cy = center(star / 5, height, 4);
            for (int y = (int) cy - 10; y <= (int) cy + 10; y++)
                for (int x = (int) cx - 10; x <= (int) cx + 10; x++) {
                    int offset = (y * width + x) * 2;
                    int phase = (y & 1) * 2 + (x & 1);
                    double response = phase == 0 ? 1.4 : phase == 3 ? .5 : 1;
                    int value = (data[offset] & 255) + ((data[offset + 1] & 255) << 8) +
                        (int) (3500 * response * Math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / 6.48));
                    data[offset] = (byte) value;
                    data[offset + 1] = (byte) (value >> 8);
                }
        }
        return new FrameData(data, width, height, PixelFormat.BAYER_RG14, 1, 0, null);
    }

    private static long localMeasurements(FrameData frame) {
        GuideImage image = new GuideImage(frame);
        long mass = 0;
        for (int star = 0; star < 20; star++) {
            int x = (int) Math.round(center(star % 5, frame.getWidth(), 5) - .5);
            int y = (int) Math.round(center(star / 5, frame.getHeight(), 4) - .5);
            mass += (long) Phd2StarMeasurement.INSTANCE.find(image, x, y, 3, 16383, 1.5, 20).getMass();
        }
        return mass;
    }

    public static void main(String[] args) {
        String operation = args[0];
        int width = Integer.parseInt(args[1]), height = Integer.parseInt(args[2]);
        FrameData frame = frame(width, height);
        GuideModule module = new GuideModule();
        LongSupplier action;
        if (operation.equals("detect")) action = () -> module.detectStars(frame, 16, 16383, null, true).size();
        else if (operation.equals("local")) action = () -> localMeasurements(frame);
        else throw new IllegalArgumentException(operation);
        for (int i = 0; i < 30; i++) sink = action.getAsLong();
        double[] times = new double[21];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            sink = action.getAsLong();
            times[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(times);
        System.out.println("operation,width,height,minMs,medianMs,p95Ms,checksumOrStars");
        System.out.printf(Locale.ROOT, "%s,%d,%d,%.3f,%.3f,%.3f,%d%n",
            operation, width, height, times[0], times[10], times[19], sink);
    }
}
