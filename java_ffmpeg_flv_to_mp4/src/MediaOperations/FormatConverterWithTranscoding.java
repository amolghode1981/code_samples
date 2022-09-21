package MediaOperations;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER;
import static org.bytedeco.ffmpeg.global.avcodec.av_init_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER;
import static org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE;
import static org.bytedeco.ffmpeg.global.avformat.av_dump_format;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_ROUND_NEAR_INF;
import static org.bytedeco.ffmpeg.global.avutil.AV_ROUND_PASS_MINMAX;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q_rnd;

import java.io.IOException;
import java.nio.file.Paths;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.javacpp.PointerPointer;

import FileUtils.MediaFileUtils;
import codec.PacketDecoder;
@SuppressWarnings("unused")
//http://bytedeco.org/javacpp-presets/ffmpeg/apidocs/
// https://stackoverflow.com/questions/48788630/how-can-i-copy-file-frame-by-frame-to-get-exactly-the-same-file-ffmpeg
public class FormatConverterWithTranscoding {
    String mInputFileName = null;
    String mOutputFileName = null;
    String mOutputDirectoryPath = null;
    String mOutputFormatName = null;
    AVFormatContext mInputFmtCtx =  null;
    AVFormatContext mOutputFmtCtx =  null;
    AVOutputFormat mOutputFormat = null;

    AVStream mAudioStream = null;
    AVStream mVideoStream = null;
    PacketDecoder mPacketDecoder = null;
    int mStreamMapping[] = {0,0};
    public FormatConverterWithTranscoding(String inputFile,
                           String outputDirectoryPath,
                           String outputFormat) {
        mInputFileName = inputFile;
        mOutputDirectoryPath = outputDirectoryPath;
        mOutputFormatName = outputFormat;
    }
    private boolean copyFrames() {
        // Check if file exists. If not, create one
        if ((mOutputFmtCtx.oformat().flags() & AVFMT_NOFILE)  == 0) {
            if (!MediaFileUtils.openMediaFile(mOutputFmtCtx,
                    mOutputFileName)) {
                System.out.println("Unable to open output file");
                return false;
            }
            int ret = avformat_write_header(mOutputFmtCtx, (PointerPointer)null);
            if (ret < 0) {
                System.out.println("Error in writing header");
                return false;
            }
        }
        boolean firstPacket = true;
        long pts = 0, dts = 0;
        while(true) {
            AVPacket p = new AVPacket();
            av_init_packet(p);
            int ret = av_read_frame(mInputFmtCtx, p);
            if (ret < 0) {
                System.out.println("Failure in reading frame");
                break;
            }
            /* This had to be done, it was observed that
             * the start time was becoming non-zero.
             * in all the examples of avformat, it is seen that
             * there is no special need for handling the pts and dts
             * for the first packet and adust them later.
             * But according to
             * https://stackoverflow.com/questions/59059320/the-start-timestamp-is-non-zero-when-remuxing-using-libavformat,
             * the start time was seen non-zero. It also spoilt the seek
             * To avoid this, added the handling for pts and dts.
             */
            if (firstPacket) {
                firstPacket = false;
                pts = p.pts();
                dts = p.dts();
            }
            int currentStreamId = p.stream_index();
            mInputFmtCtx.streams(currentStreamId);
            AVStream  mCurrentOutputStream = null;
            if (mInputFmtCtx.streams(currentStreamId).
                    codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                mCurrentOutputStream = mAudioStream;
            } else if (mInputFmtCtx.streams(p.stream_index()).
                    codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                mCurrentOutputStream = mVideoStream;
                try {
                    mPacketDecoder.decodeAndDumpPacket(p);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            p.stream_index(currentStreamId);
            System.out.println("Before = " + p.pts());
            p.pts( av_rescale_q_rnd(p.pts() - pts,
                                       mInputFmtCtx.streams(currentStreamId).time_base(),
                                       mCurrentOutputStream.time_base(),
                                       AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX));
            System.out.println("After = " + p.pts());
            p.dts(av_rescale_q_rnd(p.dts() - dts,
                                    mInputFmtCtx.streams(currentStreamId).time_base(),
                                    mCurrentOutputStream.time_base(),
                                    AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX));
            p.duration(av_rescale_q(p.duration(),
                       mInputFmtCtx.streams(currentStreamId).time_base(),
                       mCurrentOutputStream.time_base()));
            p.pos(-1);

            ret = av_interleaved_write_frame(mOutputFmtCtx, p);
            av_packet_unref(p);
        }
        av_write_trailer(mOutputFmtCtx);
        avformat_close_input(mInputFmtCtx);

        MediaFileUtils.closeMediaFile(mOutputFmtCtx.pb());
        return true;
    }
    private boolean initStreamsAndCodecs() {
        // Copy audio video codec parameters from input stream

        for (int i = 0; i < mInputFmtCtx.nb_streams(); i++) {
            AVStream inputStream = mInputFmtCtx.streams(i);
            AVStream outputStream = null;
            // Skip anything other than audio and video
            if (inputStream.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                mAudioStream = avformat_new_stream(mOutputFmtCtx,
                        mInputFmtCtx.audio_codec());

                avcodec_parameters_copy(mAudioStream.codecpar(),
                                        inputStream.codecpar());
                outputStream = mAudioStream;
                mAudioStream.codecpar().codec_tag(0);
            } else if (inputStream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                mVideoStream = avformat_new_stream(mOutputFmtCtx,
                        mInputFmtCtx.video_codec());
                avcodec_parameters_copy(mVideoStream.codecpar(),
                                        inputStream.codecpar());
                outputStream = mVideoStream;
                mVideoStream.codecpar().codec_tag(0);
            }
            if ((mOutputFmtCtx.oformat().flags() & AVFMT_GLOBALHEADER) == 1) {
                outputStream.codec().flags(outputStream.codec().flags ()|
                        AV_CODEC_FLAG_GLOBAL_HEADER);
            }
        }
        mPacketDecoder = new PacketDecoder(mAudioStream.codecpar(), mVideoStream.codecpar());
        mPacketDecoder.initDecoders();
        av_dump_format(mOutputFmtCtx, 0, mOutputFileName, 1);
        return true;
    }
    private boolean initInput() {
        mInputFmtCtx = new AVFormatContext(null);
        int ret = avformat_open_input(mInputFmtCtx, mInputFileName, null, null);
        if (ret < 0) {
            System.out.printf("Open video file %s failed \n", mInputFileName);
            return false;
        }
        if (avformat_find_stream_info(mInputFmtCtx, (PointerPointer)null) < 0) {
            return false;
        }
        av_dump_format(mInputFmtCtx, 1, mInputFileName, 0);
        return true;
    }

    private boolean initOutput() {
        mOutputFmtCtx = new AVFormatContext();
        AVIOContext ioContext = new AVIOContext(10 *1024 * 1024);
        mOutputFmtCtx.pb(ioContext);
        String file = Paths.get(mInputFileName).getFileName().toString();
        mOutputFileName = mOutputDirectoryPath + "\\" +
        file.substring(0, file.lastIndexOf('.')) + "." + mOutputFormatName;


        int ret = avformat_alloc_output_context2(mOutputFmtCtx,
                                             null,
                                             null,
                                             mOutputFileName);
        if (ret < 0) {
            System.out.printf("initOutput()::Unable to open output format");
            return false;
        }
        mOutputFormat = mOutputFmtCtx.oformat();
        mOutputFmtCtx.duration(mInputFmtCtx.duration());
        mOutputFmtCtx.bit_rate(mInputFmtCtx.bit_rate());
        mOutputFmtCtx.start_time(mInputFmtCtx.start_time());
        mOutputFmtCtx.metadata(mInputFmtCtx.metadata());
        return initStreamsAndCodecs();
    }
    public boolean init() {
        if (initInput() && initOutput()) {
           return true;
        }
        return false;
    }

    public boolean convert() {
        return copyFrames();
    }
}
