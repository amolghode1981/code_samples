package MediaOperations;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP3;
import static org.bytedeco.ffmpeg.global.avcodec.av_init_packet;
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

import java.nio.file.Paths;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVOutputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.javacpp.PointerPointer;

import FileUtils.MediaFileUtils;

/* This class is responsible for converting input flv to 
 * mp4. 
 * Transcoding is not supported. mp4 does not support all codecs
 * which are supported by flv. e.g vp6 and mp3 is not supported by mp4
 * So this class expects that input file is muxed with the codecs
 * needed for mp4 file. If we want to support generic flv, we will have to
 * support transcoding.
 * 
 * Following is used as a reference :
 * https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg
 * http://bytedeco.org/javacpp-presets/ffmpeg/apidocs/
 * https://stackoverflow.com/questions/48788630/how-can-i-copy-file-frame-by-frame-to-get-exactly-the-same-file-ffmpeg
 */
public class FormatConverter {
    String mInputFileName = null;
    String mOutputFileName = null;
    String mOutputDirectoryPath = null;
    String mOutputFormatName = null;
    AVFormatContext mInputFmtCtx =  null;
    AVFormatContext mOutputFmtCtx =  null;
    AVOutputFormat mOutputFormat = null;

    AVStream mAudioStream = null;
    AVStream mVideoStream = null;
    int mStreamMapping[] = {0,0};
    /* constuctor :
     * Input :
     *  Input file to be converted.
     *  Directory path where the output will be written.
     *  Output Mux. (Currently only mp4 is supported)
     */
    public FormatConverter(String inputFile,
                           String outputDirectoryPath,
                           String outputFormat) {
        mInputFileName = inputFile;
        mOutputDirectoryPath = outputDirectoryPath;
        mOutputFormatName = outputFormat;
    }
    /* copyFrames :
     * Input : None.
     * Output : True whe all packets are successfully copied.
     *          else false.
     * Desc : This function reads one packet from input format context
     * and writes it to the corresponding output stream by adjusting the
     * timestamps.
     */
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
            /* Find out which stream it belongs to
             * Audio or video based on the stream index.
             */
            if (mInputFmtCtx.streams(currentStreamId).
                    codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                mCurrentOutputStream = mAudioStream;
            } else if (mInputFmtCtx.streams(p.stream_index()).
                    codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                mCurrentOutputStream = mVideoStream;
            }
            p.stream_index(currentStreamId);
            // Modify the packet presentation timestamp as per the output formats time stamp
            // 
            p.pts( av_rescale_q_rnd(p.pts() - pts,
                                       mInputFmtCtx.streams(currentStreamId).time_base(),
                                       mCurrentOutputStream.time_base(),
                                       AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX));
            // Modify the packet decoding timestamp as per the output formats time stamp
            //             
            p.dts(av_rescale_q_rnd(p.dts() - dts,
                                    mInputFmtCtx.streams(currentStreamId).time_base(),
                                    mCurrentOutputStream.time_base(),
                                    AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX));
            // Adjust the duration as per the output format's timebase.
            //                         
            p.duration(av_rescale_q(p.duration(),
                       mInputFmtCtx.streams(currentStreamId).time_base(),
                       mCurrentOutputStream.time_base()));
            p.pos(-1);
            // Packets are added interleaved. Sequence of audio and video packets.
            // Instead of all audio together and all video together.
            // Write the packet to the output format.                      
            ret = av_interleaved_write_frame(mOutputFmtCtx, p);
        }
        // Write mp4 trailer and close the input format, output format
        // and io context.
        av_write_trailer(mOutputFmtCtx);
        avformat_close_input(mInputFmtCtx);
        MediaFileUtils.closeMediaFile(mOutputFmtCtx.pb());
        return true;
    }
    
    /* initStreamsAndCodecs :
     * Input : none.
     * Output : True.
     *               
     * Desc : This function demuxes the input context.
     * Reads each stream. This currently handles only one audio and one video stream.
     * Does not support subtitle or any other stream. 
     * 
     * It also creates the output stream for the output format 
     * and copies the necessary parameters of the input codec.
     * Note : Output streams may not be needed as we are copying the packets.
     * If we remember only input streams, we dont even need to change the packet
     * timestamp while writing the packet. Though this has not been tested.
     */
    private boolean initStreamsAndCodecs() {
        // Copy audio video codec parameters from input stream

        for (int i = 0; i < mInputFmtCtx.nb_streams(); i++) {
            AVStream inputStream = mInputFmtCtx.streams(i);
            AVStream outputStream = null;
            // Skip anything other than audio and video
            if (inputStream.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                // This is audio stream of input. Create output's audio stream
                // and copy the codec params.  
                mAudioStream = avformat_new_stream(mOutputFmtCtx,
                        mInputFmtCtx.audio_codec());

                avcodec_parameters_copy(mAudioStream.codecpar(),
                                        inputStream.codecpar());
                outputStream = mAudioStream;
                mAudioStream.codecpar().codec_tag(0);
            } else if (inputStream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                // This is video stream of input. Create output's video stream
                // and copy the codec params.                 
                mVideoStream = avformat_new_stream(mOutputFmtCtx,
                        mInputFmtCtx.video_codec());
                avcodec_parameters_copy(mVideoStream.codecpar(),
                                        inputStream.codecpar());
                outputStream = mVideoStream;
                mVideoStream.codecpar().codec_tag(0);
            }
            
            // Note(Amol) : Not sure if this part of the code is needed.
            if ((mOutputFmtCtx.oformat().flags() & AVFMT_GLOBALHEADER) == 1) {
                outputStream.codec().flags(outputStream.codec().flags ()|
                        AV_CODEC_FLAG_GLOBAL_HEADER);
            }
        }
        av_dump_format(mOutputFmtCtx, 0, mOutputFileName, 1);
        return true;
    }
    /* initInput :
     * Input : none.
     * Output : True when Input mux is opened and initialised properly else false.
     *               
     * Desc : Initializes input format and reads the stream info. If the input 
     * containes any of the codecs other than mp3, aac for audio and h264 for 
     * video, it throws an error.
     */    
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
        
        for (int i = 0; i < mInputFmtCtx.nb_streams(); i++) {
            AVStream inputStream = mInputFmtCtx.streams(i);
            // Skip anything other than audio and video
            if (inputStream.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO &&
                inputStream.codecpar().codec_id() != AV_CODEC_ID_AAC &&
                inputStream.codecpar().codec_id() != AV_CODEC_ID_MP3) {
                System.out.println("initInput() Unsupported Audio codecodec = " + 
                                    inputStream.codecpar().codec_id() + 
                                    " For file " + mInputFileName);
                return false;
            }
            
            if (inputStream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO &&
                    inputStream.codecpar().codec_id() != AV_CODEC_ID_H264) {
                    System.out.println("initInput() Unsupported Video codecodec = " + 
                                        inputStream.codecpar().codec_id()  + 
                                        " For file " + mInputFileName);
                    return false;
            }            
        }
        return true;
    }
    /* initOutput :
     * Input : none.
     * Output : True when output context is confitured properly, false otherwise.
     *               
     * Desc : This class initialises the output context and the io context
     *        for this particular output context.
     *        It also creates a output file name based on the output 
     *        directory and input file.
     */    
    private boolean initOutput() {
        mOutputFmtCtx = new AVFormatContext();
        // Allocate and set the IO context for the output context.
        AVIOContext ioContext = new AVIOContext();
        mOutputFmtCtx.pb(ioContext);
        // Get the just the filename from input file name.
        String file = Paths.get(mInputFileName).getFileName().toString();
        // Create full output file path based on output directory and
        // replace the file extension of the file name found above by
        // output format name. i.e mp4 in our case.
        mOutputFileName = mOutputDirectoryPath + "\\" +
        file.substring(0, file.lastIndexOf('.')) + "." + mOutputFormatName;

        // Create the output context for the output format.
        int ret = avformat_alloc_output_context2(mOutputFmtCtx,
                                             null,
                                             null,
                                             mOutputFileName);
        if (ret < 0) {
            System.out.printf("initOutput()::Unable to open output format");
            return false;
        }
        // As we are doing just conversion without transcoding,
        // Almost all the inpput paramters will remain the same.
        mOutputFormat = mOutputFmtCtx.oformat();
        mOutputFmtCtx.duration(mInputFmtCtx.duration());
        mOutputFmtCtx.bit_rate(mInputFmtCtx.bit_rate());
        mOutputFmtCtx.start_time(mInputFmtCtx.start_time());
        mOutputFmtCtx.metadata(mInputFmtCtx.metadata());
        return initStreamsAndCodecs();
    }
    /* init :
     * Input : none.
     * Output : True when input context, output context, streams are initialised correctly.
     * Else false.
     *               
     * Desc : This function calls all the initializations needed
     * for copying the data.
     */    
    public boolean init() {
        if (initInput() && initOutput()) {
           return true;
        }
        return false;
    }

    /* convert :
     * Input : none.
     * Output : True when if the conversion is successful, else false.
     *               
     * Desc : This function calls copy frames to copy the data.
     */    
    public boolean convert() {
        return copyFrames();
    }
}
