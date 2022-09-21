#ifndef __MP4HANDLER__
#define __MP4Handler__
#include <string>
using namespace std;
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
}
class MP4Handler {
    private:
      AVFormatContext* mInputFormatContext;
      AVFormatContext* mOutputFormatContext;
      AVCodecContext* mVidEncContext;
      AVCodecContext* mVidDecContext;
      AVOutputFormat* mOutputFormat;

      AVStream* mInputAudioStream;
      AVStream* mInputVideoStream;
      AVCodec* mInputVideoCodec;
      AVCodec* mInputAudioCodec;

      AVCodec* mOutputVideoCodec;
      AVCodec* mOutputAudioCodec;
      AVStream* mOutputAudioStream;
      AVStream* mOutputVideoStream;
      int mInputVideoStreamIndex;
      int initInputVideoData();
      int writeFrame(AVFrame* ptr);
    public:
      MP4Handler();
      virtual ~MP4Handler();
      int openFile(const string& fileName);
      int allocateOutputFormat(const string& outputFileName);
      int transcodePackets();
      int cleanup();
};
#endif
