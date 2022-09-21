
#include "MP4Handler.h"

#include <iostream>
using namespace std;
/* Ref :
1. https://rodic.fr/blog/libavcodec-tutorial-decode-audio-file/
2. https://github.com/vigata/y4mcreator/blob/master/y4mcreator.c
*/
/* Amol G : This program reads the input file,
 * decodes it frame by frame and dumps the frames
 * in YUV file.
 * TODO(Amol) : Audio processing is not done. Nevertheless,
 * Internet information says that it is not possible to have y4m containing
 * audio stream. Neverthelesss, I do not personally see any to be able to
 * copy the audio as is.

 * Note : The problem statement says that frames to be dumped in coded picture order,
 * instead of presentation order, to a Y4M file.
 * // http://dranger.com/ffmpeg/tutorial05.html
 * My understanding is as follows : Presentation time (PTS) could hold non incremental values
 * as the bitstream may be laid for the speeding up the decoder.
 * e.g if the stream contains I P B B, B frames can not be decoded unless P is decoded.
 * So the dts of IPBB will be say (1,2,3,4). But which also means that the P is displayed
 * after B. So PTS could be (1,4,2,3). i.e P frame must be displayed after B.

 * As this problem decodes each and every frame, it automatically happens that
 * the data is decoded based on DTS and written in the sequence of DTS.
 * Further more, while dumping the data in y4m file, the question of pts/dts would not
 * come and it would be the same as the data in y4m is I420/raw image. So there is no
 * codec optimizations happening on the data. So we can assume that the data
 * in the output too is in DTS order.
 */
/* Dumps data as raw image */
void saveFrame(AVFrame *pFrame, int iFrame) {
    // Ref https://stackoverflow.com/questions/41513984/convert-video-into-ppm-files
    FILE *pFile;
    char szFilename[32];
    int  y;

    // Open file
    sprintf(szFilename, "/tmp/output_jpg/frame%d.ppm", iFrame);
    pFile=fopen(szFilename, "wb");
    if(pFile==NULL)
        return;

    // Write header
    fprintf(pFile, "P6\n%d %d\n255\n", pFrame->width, pFrame->height);

    // Write pixel data
    for(y=0; y<pFrame->height; y++)
        fwrite(pFrame->data[0]+y*pFrame->linesize[0], 1, pFrame->width*3, pFile);

    // Close file
    fclose(pFile);
}

MP4Handler::MP4Handler() {
    av_register_all();
    mInputFormatContext = avformat_alloc_context();

}
MP4Handler::~MP4Handler() {
  cleanup();
}
/* initInputVideoData: Initializes data structures such as
 * input video codec, input video stream. This also opens
 * the necessary codecs.
 */
int MP4Handler::initInputVideoData() {
    cout << "MP4Handler::initInputVideoData() " << endl;
    mInputVideoCodec = NULL;
    int ret = av_find_best_stream(mInputFormatContext, AVMEDIA_TYPE_VIDEO, -1, -1, &mInputVideoCodec, 0);
    if (ret < 0) {
        cout << "MP4Handler::initInputVideoData() error in guessing stream" << endl;
        return ret;
    }
    mInputVideoStream =  mInputFormatContext->streams[ret];
    mInputVideoStreamIndex = ret;
    ret = avcodec_open2(mInputVideoStream->codec, mInputVideoCodec, nullptr);
    if (ret < 0) {
        cout << "MP4Handler::initInputVideoData() error in opening codec" << endl;
        return ret;
    }
    mVidDecContext = avcodec_alloc_context3(mInputVideoCodec);
    ret = avcodec_parameters_to_context(mVidDecContext, mInputVideoStream->codecpar);
    if(ret<0) {
        cout << "MP4Handler::initInputVideoData() error in parameter copy" << endl;
        return ret;
    }
    mVidDecContext->framerate = av_guess_frame_rate( mInputFormatContext, mInputVideoStream, NULL );
}
/* writeFrame: Writes decoded picture to the output format
 */
int MP4Handler::writeFrame(AVFrame *frame) {
    cout <<"Writing frame " << endl;
    AVPacket enc_packet;
    int got_frame;
    int ret;

    enc_packet.data = NULL;
    enc_packet.size = 0;
    av_init_packet(&enc_packet);
    ret = avcodec_encode_video2(mVidEncContext, &enc_packet, frame,  &got_frame);
    if(ret<0)
        return ret;

    // and mux
    int stream_index = 0; // we only have one output stream by design
    enc_packet.stream_index = stream_index;
    av_packet_rescale_ts(&enc_packet, mVidEncContext->time_base, mOutputFormatContext->streams[mInputVideoStreamIndex]->time_base );
    ret = av_interleaved_write_frame(mOutputFormatContext, &enc_packet);
    cout <<"Writing written" << endl;
    return ret;
}
/* openFile : Opens the input file for reading.
 * This function also looks in to the stream and
 * finds out the streams in the file.
 */
int MP4Handler::openFile(const string& fileName) {
	 //
    if (avformat_open_input(&mInputFormatContext, fileName.c_str(), NULL, NULL) != 0) {
        cout << "MP4Handler::openFile () Error in opening file " << fileName << endl;
        return -1;
    } else {
        cout << "File " << fileName << " is Opened" << endl;
    }
    if (avformat_find_stream_info(mInputFormatContext, NULL) < 0) {
        cout << "MP4Handler::openFile () reading stream info " << endl;
        return -1;
    }
    for (int i = 0; i < mInputFormatContext->nb_streams; i++) {
        AVStream* inputStream =  mInputFormatContext->streams[i];
        cout << "Codec type = " << inputStream->codec->codec_type << endl;
        cout << "Codec Name = " << inputStream->codec->codec_id << endl;
    }
    av_dump_format(mInputFormatContext, 0, fileName.c_str(),0);
    av_dump_format(mInputFormatContext, 1, fileName.c_str(),0);
    // Initialize streams and other data structures:
    initInputVideoData();
}

/* allocateOutputFormat : Allocates necessary output formats
 * for converting the mp4 to required output format
 */
int MP4Handler::allocateOutputFormat(const string& outputFileName) {
    int ret = avformat_alloc_output_context2(&mOutputFormatContext, NULL, NULL, outputFileName.c_str());
    if (ret < 0) {
        cout << "MP4Handler::allocateOutputFormat () output format allocation error" << outputFileName.c_str() << endl;
        return ret;
    }
    cout << "Opened output format " << endl;
    /* Initialise the output format as per the
     * input
     */
    mOutputFormat = mOutputFormatContext->oformat;
    mOutputFormatContext->duration = mInputFormatContext->duration;
    mOutputFormatContext->bit_rate = mInputFormatContext->bit_rate;
    mOutputFormatContext->start_time = mInputFormatContext->start_time;

    mOutputVideoStream = avformat_new_stream(mOutputFormatContext, NULL);
    if (mOutputVideoStream == NULL) {
        cout  << "Error in stream " << endl;
        return -1;
    }
    mOutputVideoCodec = avcodec_find_encoder(AV_CODEC_ID_WRAPPED_AVFRAME);
    if (mOutputVideoCodec == NULL) {
        cout  << "Error in stream " << endl;
        return -1;
    }
    mVidEncContext = avcodec_alloc_context3(mOutputVideoCodec);
    if (mVidEncContext == NULL) {
        cout  << "Error in codec " << endl;
        return -1;
    }
    mVidEncContext->height = mVidDecContext->height;
    mVidEncContext->width  = mVidDecContext->width;
    mVidEncContext->sample_aspect_ratio = mVidDecContext->sample_aspect_ratio;
    mVidEncContext->pix_fmt = mOutputVideoCodec->pix_fmts ? mOutputVideoCodec->pix_fmts[0] : mVidDecContext->pix_fmt;
    mVidEncContext->time_base = av_inv_q(mVidDecContext->framerate);
    cout << "Height =  " << mVidEncContext->height << endl;
    // open the actual codec
    ret = avcodec_open2(mVidEncContext, mOutputVideoCodec, NULL);
    if (ret < 0) {
        cout << "MP4Handler::allocateOutputFormat Error in avcodec_open2" << endl;
        return ret;
    }


    ret = avcodec_parameters_from_context(mOutputVideoStream->codecpar, mVidEncContext);
    if(ret<0) {
        cout << "MP4Handler::allocateOutputFormat Error in avcodec_parameters_from_context" << endl;
        return ret;
    }

    mOutputVideoStream->time_base = mVidEncContext->time_base;
    av_dump_format(mOutputFormatContext, 0, outputFileName.c_str(), 1);

    // open output file
    ret = avio_open(&mOutputFormatContext->pb, outputFileName.c_str(), AVIO_FLAG_WRITE );
    if(ret<0) {
        cout << "MP4Handler::allocateOutputFormat Error in avio_open" << endl;
        return ret;
    }

    // write header
    ret = avformat_write_header(mOutputFormatContext, NULL);
    if( ret<0 ) {
        cout << "MP4Handler::allocateOutputFormat Error in avio_open avformat_write_header" << endl;
        return ret;
    }
    return 0;
}

/* transcodePackets : Reads media  packets, decodes them. And writes the
 * decoded frame to output.
 */
int MP4Handler::transcodePackets() {
    AVFrame* decframe = av_frame_alloc();
    unsigned nb_frames = 0;
    bool end_of_stream = false;
    int got_pic = 0;

    while(1) {
        AVPacket packet;
        av_init_packet(&packet);
        int ret = av_read_frame(mInputFormatContext, &packet);
        if (ret < 0) {
            cout << "MP4Handler::readInputPackets () Failed to read frame" << endl;
            break;
        }
        cout << "mInputVideoStreamIndex = " << mInputVideoStreamIndex << endl;
        cout << "packet.stream_index = " << packet.stream_index << endl;
        if ((int) packet.stream_index == mInputVideoStreamIndex) {
            avcodec_decode_video2(mInputVideoStream->codec, decframe, &got_pic, &packet);
            if (got_pic) {
                //saveFrame(decframe, nb_frames);
                writeFrame(decframe);
                nb_frames++;
                got_pic = 0;
                if (nb_frames == 100) {
                    break;
                }
            }
        } else {
            cout << "Packlet not read" << endl;
        }

    }

}

int MP4Handler::cleanup() {
    if (mOutputFormatContext != NULL) {
        av_write_trailer(mOutputFormatContext);
        mOutputFormatContext = NULL;
    }
    if (mVidDecContext != NULL) {
        avcodec_free_context(&mVidDecContext);
        mVidDecContext = NULL;
    }
    if (mVidEncContext != NULL) {
        avcodec_free_context(&mVidEncContext);
        mVidEncContext = NULL;
    }
    if (mInputFormatContext != NULL) {
        avformat_close_input(&mInputFormatContext);
        mInputFormatContext = NULL;
    }
    if (mOutputFormatContext != NULL) {
        avformat_free_context(mOutputFormatContext);
        mOutputFormatContext = NULL;
    }
}

