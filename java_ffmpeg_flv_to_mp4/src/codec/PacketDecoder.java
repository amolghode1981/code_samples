package codec;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays;
import static org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size;
import static org.bytedeco.ffmpeg.global.avutil.av_malloc;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;

public class PacketDecoder {
    AVCodecContext mAudioCodecContext = null;
    AVCodec mAudioDecoder = null;
    AVCodecContext mVideoCodecContext = null;
    AVCodec mVideoDecoder = null;
    SwsContext mSoftwareScalingContext = null;
    AVFrame mFrame = null;
    AVFrame mRGBFrame  = null;
    public PacketDecoder(AVCodecParameters audioCodecParams,
                  AVCodecParameters videoCodecParams) {
      mAudioCodecContext = avcodec_alloc_context3(null);
      avcodec_parameters_to_context(mAudioCodecContext, audioCodecParams);
      mAudioDecoder = avcodec_find_decoder(audioCodecParams.codec_id());
      mVideoCodecContext = avcodec_alloc_context3(null);
      avcodec_parameters_to_context(mVideoCodecContext, videoCodecParams);
      mVideoDecoder = avcodec_find_decoder(videoCodecParams.codec_id());
    }
    private boolean initMemory() {
        mFrame = av_frame_alloc();

        // Allocate an AVFrame structure
        mRGBFrame = av_frame_alloc();
        if (mRGBFrame == null) {
            return false;
        }
        // Determine required buffer size and allocate buffer
        int numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGB24, 
                mVideoCodecContext.width(),
                mVideoCodecContext.height(), 1);
        BytePointer buffer = new BytePointer(av_malloc(numBytes));
        av_image_fill_arrays(mRGBFrame.data(),
                             mRGBFrame.linesize(),
                             buffer,
                             AV_PIX_FMT_RGB24,
                             mVideoCodecContext.width(),
                             mVideoCodecContext.height(),
                             1); 
        return true;
    }
    private void save_frame(AVFrame pFrame, int width, int height, long f_idx) throws IOException {
        // Open file
        String szFilename = String.format("frame%d_.ppm", f_idx);
        OutputStream pFile = new FileOutputStream(szFilename);

        // Write header
        pFile.write(String.format("P6\n%d %d\n255\n", width, height).getBytes());

        // Write pixel data
        BytePointer data = pFrame.data(0);
        byte[] bytes = new byte[width * 3];
        int l = pFrame.linesize(0);
        for(int y = 0; y < height; y++) {
            data.position(y * l).get(bytes);
            pFile.write(bytes);
        }

        // Close file
        pFile.close();
    }    
    public boolean initDecoders() {
        int ret = avcodec_open2(mAudioCodecContext,
                                mAudioDecoder,
                                (PointerPointer)null);
        if (ret < 0) {
            return false;
        }
        ret = avcodec_open2(mVideoCodecContext,
                mVideoDecoder,
                (PointerPointer)null);
        if (ret < 0) {
            return false;
        }
        mSoftwareScalingContext = sws_getContext(
                mVideoCodecContext.width(),
                mVideoCodecContext.height(),
                mVideoCodecContext.pix_fmt(),
                mVideoCodecContext.width(),
                mVideoCodecContext.height(),
                AV_PIX_FMT_RGB24,
                SWS_BILINEAR,
                null,
                null,
                (DoublePointer)null
            );
        if (mSoftwareScalingContext == null) {
            System.out.println("Can not use sws");
            return false;
        }

        return initMemory();
    }
    
    

    public void decodeAndDumpPacket(AVPacket packet) throws IOException  {
        int ret1 = -1, ret2 = -1;        
        ret1 = avcodec_send_packet(mVideoCodecContext, packet);
        ret2 = avcodec_receive_frame(mVideoCodecContext, mFrame);
        System.out.printf("ret1 %d ret2 %d\n", ret1, ret2);
        sws_scale(
                mSoftwareScalingContext,
                mFrame.data(),
                mFrame.linesize(),
                0,
                mVideoCodecContext.height(),
                mRGBFrame.data(),
                mRGBFrame.linesize()
            );
        save_frame(mRGBFrame, mVideoCodecContext.width(), mVideoCodecContext.height(),  java.lang.System.currentTimeMillis());
    }
}
