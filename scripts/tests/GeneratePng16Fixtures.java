import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.File;
import javax.imageio.*;

/** Run with a desktop JDK: java scripts/tests/GeneratePng16Fixtures.java OUTPUT_DIRECTORY */
class GeneratePng16Fixtures {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]); directory.mkdirs();
        for (boolean interlaced : new boolean[]{false, true}) for (int channels : new int[]{1, 2, 3, 4}) {
            WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 19, 13, channels, null);
            for (int y=0; y<13; y++) for (int x=0; x<19; x++) for (int c=0; c<channels; c++)
                raster.setSample(x,y,c,(x*137+y*277+c*10009)&65535);
            ColorSpace space = ColorSpace.getInstance(channels<=2?ColorSpace.CS_GRAY:ColorSpace.CS_sRGB);
            boolean alpha = channels==2 || channels==4;
            ComponentColorModel model = new ComponentColorModel(space,alpha,false,alpha?Transparency.TRANSLUCENT:Transparency.OPAQUE,DataBuffer.TYPE_USHORT);
            BufferedImage image = new BufferedImage(model,raster,false,null);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
            try (var output = ImageIO.createImageOutputStream(new File(directory,"external-"+interlaced+"-"+channels+".png"))) {
                writer.setOutput(output);
                var parameters = writer.getDefaultWriteParam();
                parameters.setProgressiveMode(interlaced?ImageWriteParam.MODE_DEFAULT:ImageWriteParam.MODE_DISABLED);
                writer.write(null,new IIOImage(image,null,null),parameters);
            } finally { writer.dispose(); }
        }
    }
}
