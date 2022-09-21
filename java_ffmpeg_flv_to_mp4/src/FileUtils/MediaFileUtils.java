package FileUtils;

import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;

public class MediaFileUtils {
    public static boolean openMediaFile(AVFormatContext ctx,
                                        String outputFileName) {
        // We have to allocate the context as
        // the file is is not present.
        AVIOContext  ioContext = new AVIOContext();
        int ret = avio_open(ioContext,
                    outputFileName,
                    AVIO_FLAG_WRITE);
        if (ret < 0) {
            return false;
        }
        ctx.pb(ioContext);
        return true;
    }
    public static void closeMediaFile(AVIOContext ioContext) {
        avio_closep(ioContext);
    }
}
